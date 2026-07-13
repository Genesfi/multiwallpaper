package gustian.multiwallpaper

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Shader
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.wallpaper.WallpaperService
import android.util.Log
import android.view.Choreographer
import android.view.SurfaceHolder
import android.view.animation.DecelerateInterpolator
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import gustian.multiwallpaper.data.AppDatabase
import gustian.multiwallpaper.data.BlacklistedImageEntity
import gustian.multiwallpaper.data.RotationHistoryEntity
import gustian.multiwallpaper.data.ScheduleEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.math.roundToInt
import kotlin.math.sqrt

abstract class BaseMultiWallpaperService : WallpaperService() {

    abstract fun getPreferencesName(): String

    companion object {
        // Persistent Global History (Ingatan Gajah) across service restarts/recreates
        // Keyed by Target Name (e.g. "HOME", "LOCK")
        private val recentHistories = mutableMapOf<String, LinkedHashSet<String>>()
        private const val DEFAULT_MAX_HISTORY = 150
    }

    private var activeEngine: MultiWallpaperEngine? = null

    override fun onCreateEngine(): Engine {
        val prefs = getPreferencesName()
        Log.d("MultiWallpaper", "Service onCreateEngine: ${this.javaClass.simpleName} using $prefs")
        val engine = MultiWallpaperEngine(prefs)
        engine.setOffsetNotificationsEnabled(true)
        activeEngine = engine
        return engine
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_RUNNING_LOW) {
            activeEngine?.trimMemory()
        }
    }

    inner class MultiWallpaperEngine(private val prefsName: String) : Engine(), SensorEventListener {
        private val handler = Handler(Looper.getMainLooper())
        private val engineScope = CoroutineScope(Dispatchers.Main + Job())
        private var visible = false
        private var xOffset = 0f
        private var xStep = 0f
        
        private var isStaticLauncher = false
        private var lastSuggestedWidth = -1

        private var lastX = 0f
        private var lastY = 0f
        private var manualPageIndex = 0
        private val swipeThreshold = 150f
        
        private var isSwiping = false
        private var swipeOffset = 0f // Current drag progress (-1.0 to 1.0)
        private var isSwipeAnimating = false
        private var swipeAnimJob: Job? = null
        private var velocityTracker: android.view.VelocityTracker? = null

        private var lastTapTime: Long = 0
        private val doubleTapThreshold = 500L

        private val blacklistRunnable = Runnable {
            performBlacklist()
        }

        private fun performBlacklist() {
            synchronized(bitmapLock) {
                val currentUri = pageUris[manualPageIndex]
                if (currentUri != null) {
                    // Delete from DB in background
                    val db = AppDatabase.getDatabase(applicationContext)
                    val targetName = if (prefsName.contains("lock")) "LOCK" else "HOME"
                    engineScope.launch(Dispatchers.IO) {
                        // Find data to move to blacklist
                        val scanned = db.scannedImageDao().getAllImagesSync(targetName).find { it.uriString == currentUri }
                        if (scanned != null) {
                            db.blacklistedDao().insertBlacklist(
                                BlacklistedImageEntity(
                                    uriString = scanned.uriString,
                                    folderUriString = scanned.folderUriString,
                                    displayName = scanned.displayName
                                )
                            )
                        }
                        db.favoriteDao().deleteFavoriteByUriSync(currentUri, targetName)
                        db.scannedImageDao().deleteImageByUriSync(currentUri, targetName)
                        
                        withContext(Dispatchers.Main) {
                            // 1. Visual feedback: brief red flash
                            showBlacklistFeedback = true
                            requestDraw()
                            delay(150)
                            showBlacklistFeedback = false
                            
                            // 2. CRITICAL: Remove from current memory cache immediately
                            // so rotateWallpapers() doesn't accidentally re-use it.
                            synchronized(bitmapLock) {
                                pageBitmaps[manualPageIndex]?.recycle()
                                pageBitmaps.remove(manualPageIndex)
                                pageUris.remove(manualPageIndex)
                                pageFocalPoints.remove(manualPageIndex)
                            }
                            
                            // 3. Force immediate change to a NEW image
                            rotateWallpapers()
                        }
                    }
                }
            }
        }

        private var showBlacklistFeedback = false

        private var isLoading = false
        private var surfaceWidth = 1080
        private var surfaceHeight = 2400

        private val pageBitmaps = mutableMapOf<Int, Bitmap>()
        private val pageUris = mutableMapOf<Int, String>() // Track URIs to prevent duplicates
        private val pageFocalPoints = mutableMapOf<Int, PointF?>()
        
        private var nextBitmap: Bitmap? = null
        private var nextFocalPoint: PointF? = null
        private var preloadedBitmap: Bitmap? = null
        private var preloadedUri: String? = null
        private var preloadedFocalPoint: PointF? = null
        private var transitionAlpha = 255
        private var isTransitioning = false
        private var transitionStartTime = 0L
        private var transitionDuration = 600L
        private val interpolator = DecelerateInterpolator(1.5f) // Cubic-like ease-out for snappier response

        private val frameCallback = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                if (isTransitioning) {
                    animateFade()
                    if (isTransitioning) {
                        Choreographer.getInstance().postFrameCallback(this)
                    }
                }
            }
        }

        private var isDrawScheduled = false
        private val drawRunnable = Runnable { 
            isDrawScheduled = false
            drawFrame() 
        }
        private val rotationRunnable = Runnable { 
            rotateWallpapers()
        }

        private val timeTickReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == Intent.ACTION_TIME_TICK) {
                    checkSchedules()
                }
            }
        }

        private var currentActiveSchedule: ScheduleEntity? = null

        private val scheduleReloadReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == "gustian.multiwallpaper.RELOAD_SCHEDULES") {
                    checkSchedules()
                }
            }
        }

        private fun checkSchedules() {
            val targetName = if (prefsName.contains("lock")) "LOCK" else "HOME"
            engineScope.launch(Dispatchers.IO) {
                val db = AppDatabase.getDatabase(applicationContext)
                val enabledSchedules = db.scheduleDao().getEnabledSchedulesSync(targetName)
                val activeSchedule = ScheduleManager.getActiveSchedule(enabledSchedules)
                
                withContext(Dispatchers.Main) {
                    if (activeSchedule?.id != currentActiveSchedule?.id) {
                        applySchedule(activeSchedule)
                    } else {
                        // Extra insurance: Check if we missed a rotation
                        val currentTime = System.currentTimeMillis()
                        val intervalMs = getRotationIntervalMs()
                        if (currentTime - lastRotationTime >= intervalMs) {
                            Log.d("MultiWallpaper", "TimeTick catch-up rotation triggered for $prefsName")
                            rotateWallpapers()
                        }
                    }
                }
            }
        }

        private fun applySchedule(schedule: ScheduleEntity?) {
            val oldScheduleId = currentActiveSchedule?.id
            currentActiveSchedule = schedule
            updateSettings() // This will now incorporate currentActiveSchedule
            
            if (schedule != null) {
                if (schedule.id != oldScheduleId) {
                    Log.d("MultiWallpaper", "Applying schedule: ${schedule.name}")
                    
                    // Trigger preset load if specified
                    schedule.presetId?.let { pid ->
                        engineScope.launch(Dispatchers.IO) {
                            val db = AppDatabase.getDatabase(applicationContext)
                            val targetName = if (prefsName.contains("lock")) "LOCK" else "HOME"
                            
                            // 1. SMART BACKUP: Only backup if a backup doesn't already exist.
                            // This prevents overwriting the original state if the phone restarts
                            // while a schedule is already active.
                            val existingBackup = db.presetDao().getAllPresets(targetName).firstOrNull()?.find { it.name == "System_AutoBackup" }
                            val prefs = getSharedPreferences(prefsName, Context.MODE_PRIVATE)
                            if (existingBackup == null) {
                                val originalName = prefs.getString("active_preset_name", null)
                                prefs.edit().putString("original_preset_name", originalName).apply()
                                saveCurrentAsBackupPreset(targetName)
                            }
                            
                            // 2. Load the scheduled preset
                            val preset = db.presetDao().getAllPresets(targetName).firstOrNull()?.find { it.id == pid }
                            if (preset != null) {
                                loadPresetToService(preset)
                                prefs.edit().putString("active_preset_name", preset.name).apply()
                            }
                        }
                    }
                }
            } else {
                if (oldScheduleId != null) {
                    Log.d("MultiWallpaper", "No active schedule, attempting to revert to Backup")
                    engineScope.launch(Dispatchers.IO) {
                        val targetName = if (prefsName.contains("lock")) "LOCK" else "HOME"
                        val db = AppDatabase.getDatabase(applicationContext)
                        val backupPreset = db.presetDao().getAllPresets(targetName).firstOrNull()?.find { it.name == "System_AutoBackup" }
                        if (backupPreset != null) {
                            loadPresetToService(backupPreset)
                            
                            val prefs = getSharedPreferences(prefsName, Context.MODE_PRIVATE)
                            val originalName = prefs.getString("original_preset_name", null)
                            prefs.edit().putString("active_preset_name", originalName).remove("original_preset_name").apply()

                            // 3. CLEANUP: Delete the backup after successful restore.
                            // This allows the next schedule to create a fresh backup.
                            db.presetDao().deletePreset(backupPreset)
                            Log.d("MultiWallpaper", "System_AutoBackup restored and deleted.")
                        } else {
                            withContext(Dispatchers.Main) { 
                                loadWallpapersForPages()
                                rotateWallpapers() 
                            }
                        }
                    }
                }
            }
            requestDraw()
        }

        private suspend fun saveCurrentAsBackupPreset(targetName: String) {
            val db = AppDatabase.getDatabase(applicationContext)
            val currentFolders = db.folderDao().getAllFoldersSync(targetName).map { it.uriString }
            if (currentFolders.isEmpty()) return // Don't backup empty state

            val currentFavs = db.favoriteDao().getAllFavoritesSync(targetName)
            
            val moshi = com.squareup.moshi.Moshi.Builder().add(com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory()).build()
            val type = com.squareup.moshi.Types.newParameterizedType(List::class.java, gustian.multiwallpaper.data.FavoriteImageEntity::class.java)
            val adapter = moshi.adapter<List<gustian.multiwallpaper.data.FavoriteImageEntity>>(type)
            val favJson = adapter.toJson(currentFavs)

            val existingBackup = db.presetDao().getAllPresets(targetName).firstOrNull()?.find { it.name == "System_AutoBackup" }
            
            if (existingBackup != null) {
                db.presetDao().updatePreset(existingBackup.copy(
                    folderUris = currentFolders,
                    favoriteData = favJson,
                    createdTime = System.currentTimeMillis()
                ))
            } else {
                db.presetDao().insertPreset(gustian.multiwallpaper.data.PresetEntity(
                    name = "System_AutoBackup",
                    thumbnailUri = null,
                    folderUris = currentFolders,
                    favoriteData = favJson,
                    target = targetName
                ))
            }
            Log.d("MultiWallpaper", "System_AutoBackup updated for $targetName")
        }

        private suspend fun loadPresetToService(preset: gustian.multiwallpaper.data.PresetEntity) {
            val targetName = if (prefsName.contains("lock")) "LOCK" else "HOME"
            val db = AppDatabase.getDatabase(applicationContext)
            
            Log.d("MultiWallpaper", "Service loading preset: ${preset.name} (Background)")
            
            // 1. Clear current Source
            db.folderDao().deleteAllFolders(targetName)
            db.favoriteDao().deleteAllFavorites(targetName)
            
            // 2. Insert new Source from Preset
            val folderEntities = preset.folderUris.map { uri ->
                val name = try {
                    val u = Uri.parse(uri)
                    if (u.scheme == "file") java.io.File(u.path!!).name else Uri.decode(uri).split("/").lastOrNull() ?: "Folder"
                } catch (e: Exception) { "Folder" }
                gustian.multiwallpaper.data.FolderEntity(uriString = uri, displayName = name, target = targetName)
            }
            db.folderDao().insertFolders(folderEntities)
            
            // 3. Restore Favorites
            try {
                val moshi = com.squareup.moshi.Moshi.Builder().add(com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory()).build()
                val type = com.squareup.moshi.Types.newParameterizedType(List::class.java, gustian.multiwallpaper.data.FavoriteImageEntity::class.java)
                val adapter = moshi.adapter<List<gustian.multiwallpaper.data.FavoriteImageEntity>>(type)
                val favs = adapter.fromJson(preset.favoriteData)
                if (favs != null) {
                    val updatedFavs = favs.map { it.copy(target = targetName) }
                    db.favoriteDao().insertFavorites(updatedFavs)
                }
            } catch (e: Exception) {
                Log.e("MultiWallpaper", "Error loading favorites", e)
            }

            // 4. DEEP SCAN: We must scan files in background because loadWallpapersForPages 
            // depends on the 'scanned_images' table being populated!
            Log.d("MultiWallpaper", "Service performing background scan for preset...")
            val tempImages = mutableListOf<gustian.multiwallpaper.ui.WallpaperImg>()
            val favoriteUris = db.favoriteDao().getAllFavoritesSync(targetName).map { it.uriString }.toSet()
            val blacklistedUris = db.blacklistedDao().getAllBlacklistedSync().map { it.uriString }.toSet()

            for (folder in folderEntities) {
                try {
                    val uri = Uri.parse(folder.uriString)
                    if (uri.scheme == "file") {
                        val file = java.io.File(uri.path ?: "")
                        if (file.exists() && file.isDirectory) scanRecursive(file, tempImages, favoriteUris, blacklistedUris)
                    }
                } catch (e: Exception) {}
            }

            db.scannedImageDao().deleteAllImages(targetName)
            db.scannedImageDao().insertImages(tempImages.map { 
                gustian.multiwallpaper.data.ScannedImageEntity(it.uriString, it.folderUriString, it.displayName, targetName)
            })

            // 5. Force Service to reload the new images and ROTATE IMMEDIATELY
            withContext(Dispatchers.Main) {
                loadWallpapersForPages()
                rotateWallpapers()
            }
        }

        private fun scanRecursive(file: java.io.File, list: MutableList<gustian.multiwallpaper.ui.WallpaperImg>, favoriteUris: Set<String>, blacklistedUris: Set<String>) {
            val files = file.listFiles()
            files?.forEach { f ->
                if (f.isFile && (f.name.endsWith(".jpg", true) || f.name.endsWith(".png", true) || f.name.endsWith(".webp", true))) {
                    val fileUriStr = Uri.fromFile(f).toString()
                    if (!blacklistedUris.contains(fileUriStr)) {
                        val parentUriStr = Uri.fromFile(f.parentFile).toString()
                        list.add(gustian.multiwallpaper.ui.WallpaperImg(fileUriStr, parentUriStr, f.name, favoriteUris.contains(fileUriStr)))
                    }
                } else if (f.isDirectory && !f.name.startsWith(".")) {
                    scanRecursive(f, list, favoriteUris, blacklistedUris)
                }
            }
        }

        // Parallax sensor properties
        private var sensorManager: SensorManager? = null
        private var accelerometer: Sensor? = null
        private var parallaxEnabled = false
        private var parallaxStrength = 0.5f
        private var shakeEnabled = false
        private var smartCropEnabled = true
        private var lightModeEnabled = false
        private var aiAdvancedEnabled = false
        private var aiZoomSlack = 1.45f
        private var aiSensitivityX = 0.9f
        private var aiSensitivityY = 0.4f
        private var transitionType = "slide"
        private var fadeSpeed = 15
        private var blurRadius = 0f
        private var dimIntensity = 0f
        private var blurEnabled = false
        private var dimEnabled = false
        private var subjectFocusEnabled = false
        private var subjectFocusSmoothing = 0.5f
        private var vignetteModeEnabled = false
        private var vignetteSharpness = 0.5f
        private var vignetteWidth = 0.2f
        private var smartAdjacencyEnabled = true
        private var wallpaperQuality = "NORMAL"
        private var filterType = "NONE"
        private var filterColor1 = Color.BLACK
        private var filterColor2 = Color.WHITE
        private var filterColor3 = Color.GRAY
        private var useFavoritesOnly = false
        private var currentSortOrder = "RANDOM"
        private var currentRoll = 0f
        private var currentPitch = 0f
        private var smoothingFactor = 0.10f // More responsive for 30fps
        private var detectedPages = 20 // Default to 20 for launchers that don't report xStep (HyperOS)
        private var manualPageCount = 0 // 0 means auto-detect
        
        // Job tracking for concurrency safety
        private var mainLoadJob: Job? = null
        private var backgroundRefreshJob: Job? = null
        private var preloadJob: Job? = null
        private var rotationJob: Job? = null
        
        // Lock for thread-safety during bitmap operations
        private val bitmapLock = Any()
        
        private val deadZoneThreshold = 0.2f // Ignore very small tremors
        private var lastSensorDrawTime = 0L
        private val sensorThrottleMs = 33L // Balanced throttling (30fps) for smoothness
        private var lastShakeTime = 0L
        private var shakeThreshold = 14f // m/s^2 above gravity, now adjustable
        private var lastRotationTime = 0L
        
        private val bitmapPaint = Paint().apply { isFilterBitmap = true }
        private val textPaint = Paint().apply { 
            color = Color.WHITE
            textSize = 40f
            textAlign = Paint.Align.CENTER
            isAntiAlias = true 
        }
        private val loadingCirclePaint = Paint().apply { 
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 4f
            isAntiAlias = true 
        }

        private val vignettePaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
        }

        // Persistent Face Detector to avoid reloading models (saves massive RAM/CPU)
        private var faceDetector: com.google.mlkit.vision.face.FaceDetector? = null
        
        // 3D Perspective tools
        private val camera3D = android.graphics.Camera()
        private val matrix3D = android.graphics.Matrix()

        // RenderNode for high-performance visual effects (Blur/Dim) on Android 12+
        private var visualEffectNode: android.graphics.RenderNode? = null
        private var leftTumbleNode: android.graphics.RenderNode? = null
        private var rightTumbleNode: android.graphics.RenderNode? = null

        private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            updateSettings()
        }

        // private var isInitializing = true (REMOVED)

        override fun onCreate(surfaceHolder: SurfaceHolder?) {
            super.onCreate(surfaceHolder)
            setTouchEventsEnabled(true)

            // INITIALIZATION: 
            // isStaticLauncher sekarang murni berdasarkan Manual Page Count.
            // Jika > 0, berarti user secara manual mengatur paging (Mode Poco/Manual).
            val prefs = getSharedPreferences(prefsName, Context.MODE_PRIVATE)
            manualPageCount = prefs.getInt("manual_page_count", 0)
            isStaticLauncher = manualPageCount > 0

            if (isStaticLauncher) {
                detectedPages = manualPageCount
            } else {
                // Mode Auto: Default 20 untuk Home, 1 untuk Lock sampai dideteksi sistem
                detectedPages = if (!prefsName.contains("lock")) 20 else 1
            }

            currentSortOrder = prefs.getString("rotation_sort_order", "RANDOM") ?: "RANDOM"
            
            sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
            accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

            val db = AppDatabase.getDatabase(applicationContext)
            val targetName = if (prefsName.contains("lock")) "LOCK" else "HOME"
            engineScope.launch {
                // Keep in-memory history synced with database
                db.rotationHistoryDao().getAllHistory(targetName).collect { dbHistory ->
                    synchronized(recentHistories) {
                        val history = recentHistories.getOrPut(targetName) { LinkedHashSet() }
                        history.clear()
                        history.addAll(dbHistory.reversed()) // Oldest first for LinkedHashSet FIFO
                    }
                }
            }

            engineScope.launch {
                val targetName = if (prefsName.contains("lock")) "LOCK" else "HOME"
                db.folderDao().getAllFolders(targetName).collectLatest {
                    if (it.isNotEmpty()) {
                        loadWallpapersForPages()
                    }
                }
            }
            
            prefs.registerOnSharedPreferenceChangeListener(prefsListener)

            val filter = IntentFilter()
            filter.addAction(Intent.ACTION_TIME_TICK)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(timeTickReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(timeTickReceiver, filter)
            }

            val reloadFilter = IntentFilter("gustian.multiwallpaper.RELOAD_SCHEDULES")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(scheduleReloadReceiver, reloadFilter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(scheduleReloadReceiver, reloadFilter)
            }
            
            updateSettings()
            checkSchedules()

            // Restore last rotation time from prefs
            lastRotationTime = prefs.getLong("last_rotation_time", 0L)
        }

        private fun updateSettings() {
            Log.d("MultiWallpaper", "Engine updateSettings: $prefsName (Visible: $visible)")
            val prefs = getSharedPreferences(prefsName, Context.MODE_PRIVATE)
            
            // 1. CORE MODE DETECTION (Must be first!)
            val oldManualPageCount = manualPageCount
            manualPageCount = prefs.getInt("manual_page_count", 0)
            isStaticLauncher = manualPageCount > 0
            
            if (manualPageCount > 0) {
                detectedPages = manualPageCount
            }

            val newUseFav = prefs.getBoolean("use_favorites_only", false)
            val useFavChanged = useFavoritesOnly != newUseFav

            val newSortOrder = prefs.getString("rotation_sort_order", "RANDOM") ?: "RANDOM"
            val sortOrderChanged = currentSortOrder != newSortOrder
            currentSortOrder = newSortOrder
            
            // Force reload if requested via a "force_reload" flag
            val forceReload = prefs.getBoolean("force_reload_trigger", false)
            if (forceReload) {
                prefs.edit().putBoolean("force_reload_trigger", false).apply()
            }
            
            val oldSmartCrop = smartCropEnabled
            val oldAiAdv = aiAdvancedEnabled
            val oldZoom = aiZoomSlack
            val oldSensX = aiSensitivityX
            val oldSensY = aiSensitivityY
            val oldBlurEnabled = blurEnabled
            val oldBlurRadius = blurRadius
            val oldDimEnabled = dimEnabled
            val oldDimIntensity = dimIntensity
            val oldSubjectFocusEnabled = subjectFocusEnabled
            val oldSubjectFocusSmoothing = subjectFocusSmoothing
            val oldSmartAdjacency = smartAdjacencyEnabled
            val oldQuality = wallpaperQuality
            // val oldManualPageCount = manualPageCount // DIHAPUS KARENA SUDAH ADA DI ATAS
            
            useFavoritesOnly = newUseFav
            parallaxEnabled = prefs.getBoolean("parallax_enabled", false)
            val newStrength = prefs.getFloat("parallax_strength", 0.5f)
            val strengthChanged = (parallaxStrength != newStrength)
            parallaxStrength = newStrength

            shakeEnabled = prefs.getBoolean("shake_enabled", false)
            val shakeSensitivity = prefs.getFloat("shake_sensitivity", 0.9f)
            // Range: 10.0 (High Sensitivity) to 50.0 (Low Sensitivity)
            shakeThreshold = 50.0f - (shakeSensitivity * 40.0f)

            smartCropEnabled = prefs.getBoolean("smart_crop_enabled", true)
            lightModeEnabled = prefs.getBoolean("light_mode_enabled", false)
            wallpaperQuality = prefs.getString("wallpaper_quality", "NORMAL") ?: "NORMAL"
            aiAdvancedEnabled = prefs.getBoolean("ai_advanced_enabled", false)
            aiZoomSlack = prefs.getFloat("ai_zoom_slack", 1.45f)
            aiSensitivityX = prefs.getFloat("ai_sensitivity_x", 0.9f)
            aiSensitivityY = prefs.getFloat("ai_sensitivity_y", 0.4f)
            transitionType = prefs.getString("transition_type", "slide") ?: "slide"
            fadeSpeed = prefs.getInt("fade_speed", 15)
            blurRadius = prefs.getFloat("blur_radius", 0f)
            dimIntensity = prefs.getFloat("dim_intensity", 0f)
            blurEnabled = prefs.getBoolean("blur_enabled", false)
            dimEnabled = prefs.getBoolean("dim_enabled", false)

            subjectFocusEnabled = prefs.getBoolean("subject_focus_enabled", false) && currentActiveSchedule == null
            vignetteModeEnabled = prefs.getBoolean("vignette_mode_enabled", false) && currentActiveSchedule == null
            subjectFocusSmoothing = prefs.getFloat("subject_focus_smoothing", 0.5f)
            vignetteModeEnabled = prefs.getBoolean("vignette_mode_enabled", false)
            vignetteSharpness = prefs.getFloat("vignette_sharpness", 0.5f)
            vignetteWidth = prefs.getFloat("vignette_width", 0.2f)
            smartAdjacencyEnabled = prefs.getBoolean("smart_adjacency_enabled", true)
            filterType = prefs.getString("filter_type", "NONE") ?: "NONE"
            filterColor1 = prefs.getInt("filter_color_1", Color.BLACK)
            filterColor2 = prefs.getInt("filter_color_2", Color.WHITE)
            filterColor3 = prefs.getInt("filter_color_3", Color.GRAY)
            
            manualPageCount = prefs.getInt("manual_page_count", 0)
            isStaticLauncher = manualPageCount > 0

            // --- MANDATORY SCHEDULE OVERRIDES (Apply Last to ensure Priority) ---
            currentActiveSchedule?.let { schedule ->
                Log.d("MultiWallpaper", "updateSettings: Applying Schedule Overrides for ${schedule.name}")
                // Overriding main effects
                schedule.blurEnabled?.let { blurEnabled = it }
                schedule.blurRadius?.let { blurRadius = it }
                schedule.dimEnabled?.let { dimEnabled = it }
                schedule.dimIntensity?.let { dimIntensity = it }
                schedule.lightModeEnabled?.let { lightModeEnabled = it }
                
                // Color Filter Overrides (MANDATORY Priority)
                if (schedule.filterType != null && schedule.filterType != "NONE") {
                    filterType = schedule.filterType!!
                    schedule.filterColor1?.let { filterColor1 = it }
                    schedule.filterColor2?.let { filterColor2 = it }
                    schedule.filterColor3?.let { filterColor3 = it }
                    Log.d("MultiWallpaper", "updateSettings: Filter forced to $filterType by schedule")
                }
                
                // If schedule has Dim/Blur enabled, we DISABLE special focus modes
                if ((schedule.dimEnabled == true && schedule.dimIntensity != null && schedule.dimIntensity!! > 0f) || 
                    (schedule.blurEnabled == true && schedule.blurRadius != null && schedule.blurRadius!! > 0f)) {
                    subjectFocusEnabled = false
                    vignetteModeEnabled = false
                }
            }

            if (useFavChanged || forceReload || oldQuality != wallpaperQuality || sortOrderChanged || oldManualPageCount != manualPageCount) {
                if (manualPageCount > 0) {
                    detectedPages = manualPageCount
                }
                loadWallpapersForPages()
            } else if (strengthChanged || oldSmartCrop != smartCropEnabled ||
                       oldAiAdv != aiAdvancedEnabled || 
                       oldZoom != aiZoomSlack || 
                       oldSensX != aiSensitivityX || 
                       oldSensY != aiSensitivityY ||
                       oldBlurEnabled != blurEnabled ||
                       oldBlurRadius != blurRadius ||
                       oldDimEnabled != dimEnabled ||
                       oldDimIntensity != dimIntensity ||
                       oldSubjectFocusEnabled != subjectFocusEnabled ||
                       oldSubjectFocusSmoothing != subjectFocusSmoothing ||
                       oldSmartAdjacency != smartAdjacencyEnabled) {
                requestDraw()
            }
            
            if (visible && (parallaxEnabled || shakeEnabled)) {
                registerSensor()
            } else {
                unregisterSensor()
            }
        }

        private fun registerSensor() {
            accelerometer?.let {
                sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
            }
        }

        private fun unregisterSensor() {
            sensorManager?.unregisterListener(this)
        }

        override fun onSensorChanged(event: SensorEvent?) {
            if (!visible || event?.sensor?.type != Sensor.TYPE_ACCELEROMETER) return

            val x = event.values[0]
            val y = event.values[1]
            val z = event.values[2]

            // Shake detection logic
            if (shakeEnabled) {
                val acceleration = sqrt(x * x + y * y + z * z) - SensorManager.GRAVITY_EARTH
                if (acceleration > shakeThreshold) {
                    val now = System.currentTimeMillis()
                    if (now - lastShakeTime > 1200) { // 1.2s debounce
                        lastShakeTime = now
                        handler.post { rotateWallpapers() }
                    }
                }
            }

            // Mute parallax redraw during transitions, but KEEP updating values
            // so they don't "jump" when the transition ends.
            if (!parallaxEnabled) return
            
            // Dead-zone check: only update if delta is significant enough
            val deltaX = x - currentRoll
            val deltaY = y - currentPitch
            
            if (kotlin.math.abs(deltaX) > deadZoneThreshold || kotlin.math.abs(deltaY) > deadZoneThreshold) {
                currentRoll += smoothingFactor * deltaX
                currentPitch += smoothingFactor * deltaY
                
                // Only request draw if NOT transitioning
                if (!isTransitioning) {
                    val now = System.currentTimeMillis()
                    val throttle = if (lightModeEnabled) 100L else sensorThrottleMs
                    if ((now - lastSensorDrawTime) >= throttle) {
                        lastSensorDrawTime = now
                        requestDraw()
                    }
                }
            }
        }

        private fun requestDraw() {
            if (visible && !isDrawScheduled && engineScope.isActive) {
                isDrawScheduled = true
                handler.post(drawRunnable)
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

        fun trimMemory() {
            // Under memory pressure, release non-visible bitmaps
            engineScope.launch(Dispatchers.Main) {
                synchronized(bitmapLock) {
                    preloadedBitmap?.recycle()
                    preloadedBitmap = null
                    preloadedUri = null
                    preloadedFocalPoint = null
                    
                    // If we're not currently visible, we can even release current bitmaps
                    // to be re-decoded when user returns to home.
                    if (!visible) {
                        recycleBitmaps()
                    }
                }
                System.gc()
            }
        }

        override fun onDestroy() {
            super.onDestroy()
            if (activeEngine == this) activeEngine = null
            unregisterReceiver(timeTickReceiver)
            unregisterReceiver(scheduleReloadReceiver)
            engineScope.cancel()
            handler.removeCallbacks(drawRunnable)
            handler.removeCallbacks(rotationRunnable)
            val prefs = getSharedPreferences(prefsName, Context.MODE_PRIVATE)
            prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
            unregisterSensor()
            faceDetector?.close()
            faceDetector = null
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                visualEffectNode?.discardDisplayList()
                visualEffectNode = null
            }
            recycleBitmaps()
        }

        override fun onTouchEvent(event: android.view.MotionEvent) {
            super.onTouchEvent(event)
            val numBitmaps = pageBitmaps.size
            if (numBitmaps <= 0) return

            if (velocityTracker == null) {
                velocityTracker = android.view.VelocityTracker.obtain()
            }
            velocityTracker?.addMovement(event)

            val action = event.actionMasked
            when (action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    lastX = event.x
                    lastY = event.y
                    isSwiping = true
                    swipeAnimJob?.cancel()
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    if (isSwiping && isStaticLauncher && transitionType != "cut") {
                        swipeOffset = (event.x - lastX) / surfaceWidth.toFloat()
                        requestDraw()
                    }
                }
                android.view.MotionEvent.ACTION_POINTER_DOWN -> {
                    if (event.pointerCount == 2) {
                        handler.postDelayed(blacklistRunnable, 150)
                    } else if (event.pointerCount > 2) {
                        handler.removeCallbacks(blacklistRunnable)
                    }
                }
                android.view.MotionEvent.ACTION_POINTER_UP -> {
                    handler.removeCallbacks(blacklistRunnable)
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(blacklistRunnable)
                    isSwiping = false
                    
                    val currTime = System.currentTimeMillis()
                    val prefs = getSharedPreferences(prefsName, Context.MODE_PRIVATE)
                    val doubleTapEnabled = prefs.getBoolean("double_tap_enabled", true)
                    
                    val deltaX = event.x - lastX
                    val deltaY = event.y - lastY
                    
                    // 1. DIRECTION CHECK: Sudut harus lebih horizontal (maks 30 derajat)
                    // Agar tidak bentrok dengan scroll widget vertikal
                    val isHorizontal = kotlin.math.abs(deltaX) > kotlin.math.abs(deltaY) * 1.73f // tan(60deg)
                    
                    // 2. VELOCITY CHECK (FLICK): Cek kecepatan sentilan
                    velocityTracker?.computeCurrentVelocity(1000)
                    val xVelocity = velocityTracker?.xVelocity ?: 0f
                    val isFlick = kotlin.math.abs(xVelocity) > 1500f // Threshold kecepatan sentilan
                    
                    // 3. DISTANCE THRESHOLD: 45% Lebar Layar
                    val distanceRatio = kotlin.math.abs(deltaX) / surfaceWidth.toFloat()
                    val isLongSwipe = distanceRatio > 0.45f

                    val shouldRotate = isHorizontal && (isFlick || isLongSwipe)

                    if (doubleTapEnabled && !shouldRotate && distanceRatio < 0.05f && (currTime - lastTapTime) < doubleTapThreshold) {
                        rotateWallpapers()
                        lastTapTime = 0
                        swipeOffset = 0f
                        requestDraw()
                    } else {
                        lastTapTime = currTime
                        
                        // MANUAL SWIPE: Hanya aktif jika isStaticLauncher = true (User set manual count)
                        if (isStaticLauncher && shouldRotate && detectedPages > 1) {
                            val isPrev = deltaX > 0
                            if (isPrev) {
                                manualPageIndex = if (manualPageIndex > 0) manualPageIndex - 1 else detectedPages - 1
                                swipeOffset = -1f + swipeOffset
                            } else {
                                manualPageIndex = if (manualPageIndex < detectedPages - 1) manualPageIndex + 1 else 0
                                swipeOffset = 1f + swipeOffset
                            }
                            animateSwipeCompletion()
                        } else {
                            // CANCEL SWIPE: Balikkan ke posisi awal jika syarat tidak terpenuhi
                            animateSwipeCancel()
                        }
                    }
                    
                    velocityTracker?.recycle()
                    velocityTracker = null
                }
            }
        }

        private fun animateSwipeCancel() {
            swipeAnimJob?.cancel()
            swipeAnimJob = engineScope.launch {
                isSwipeAnimating = true
                val startOffset = swipeOffset
                
                // Map fadeSpeed to duration (matching rotateWallpapers logic)
                val duration = (1300L - (fadeSpeed * 21L)).coerceIn(250L, 1200L) / 2L // Half duration for cancel
                val startTime = System.currentTimeMillis()
                
                while (System.currentTimeMillis() - startTime < duration) {
                    val elapsed = System.currentTimeMillis() - startTime
                    val progress = interpolator.getInterpolation(elapsed.toFloat() / duration)
                    swipeOffset = startOffset * (1f - progress)
                    requestDraw()
                    delay(16)
                }
                swipeOffset = 0f
                isSwipeAnimating = false
                requestDraw()
            }
        }

        private fun animateSwipeCompletion() {
            swipeAnimJob?.cancel()
            swipeAnimJob = engineScope.launch {
                isSwipeAnimating = true
                val startOffset = swipeOffset
                
                // Map fadeSpeed to duration (matching rotateWallpapers logic)
                val duration = (1300L - (fadeSpeed * 21L)).coerceIn(250L, 1200L)
                val startTime = System.currentTimeMillis()
                
                while (System.currentTimeMillis() - startTime < duration) {
                    val elapsed = System.currentTimeMillis() - startTime
                    val progress = interpolator.getInterpolation(elapsed.toFloat() / duration)
                    
                    // Smoothly return to 0 (which now represents the new manualPageIndex)
                    swipeOffset = startOffset * (1f - progress)
                    requestDraw()
                    delay(16) // ~60fps
                }
                
                swipeOffset = 0f
                isSwipeAnimating = false
                requestDraw()
            }
        }

        override fun onVisibilityChanged(visible: Boolean) {
            this.visible = visible
            if (visible) {
                // 1. SMART UNLOCK: Jika ada gambar tapi spinner masih jalan, matikan spinner.
                if (pageBitmaps.isNotEmpty() && isLoading) {
                    isLoading = false
                    requestDraw()
                }

                // 2. FORCE RELOAD (Fix Masalah 1 Halaman):
                // Jika jumlah gambar di memori (pageBitmaps) kurang dari yang seharusnya (detectedPages),
                // maka paksa load sisanya. Ini mencegah bug "stuck di 1 halaman" saat update/debug.
                if (pageBitmaps.size < detectedPages && !isLoading) {
                    Log.i("MW_DEBUG", "[$prefsName] Recovery: Missing pages detected (${pageBitmaps.size}/$detectedPages). Triggering reload.")
                    loadWallpapersForPages()
                }

                // 3. CATCH-UP LOGIC: Jika sudah waktunya ganti wallpaper saat layar mati, ganti sekarang.
                val currentTime = System.currentTimeMillis()
                val intervalMs = getRotationIntervalMs()
                if (currentTime - lastRotationTime >= intervalMs) {
                    rotateWallpapers()
                }

                updateSettings()
                scheduleRotation()
                requestDraw()
            } else {
                // SMART CLEANUP: 
                // Don't cancel immediately on first load to prevent "Loading Abadi" on Apply (flicker)
                if (pageBitmaps.isEmpty() && isLoading) {
                    // Silent
                } else {
                    engineScope.launch {
                        delay(800) // Small delay to catch "flickers" (Xiaomi/Poco transition)
                        if (!this@MultiWallpaperEngine.visible) {
                            // JANGAN matikan load job di sini agar proses load 20 halaman 
                            // tetap tuntas meskipun layar mati sesaat pasca update/debug.
                            
                            isDrawScheduled = false
                            handler.removeCallbacks(drawRunnable)
                            
                            // Clear high-RAM transient bitmaps
                            nextBitmap?.recycle(); nextBitmap = null
                            preloadedBitmap?.recycle(); preloadedBitmap = null
                            
                            // Unregister sensor to stop redraw loops
                            unregisterSensor()
                            
                            // Also cancel any ongoing transitions to stop power usage
                            isTransitioning = false
                            
                            // Force memory reclamation
                            System.gc()
                        }
                    }
                }
            }
        }

        override fun onOffsetsChanged(xOffset: Float, yOffset: Float, xStep: Float, yStep: Float, xPixels: Int, yPixels: Int) {
            val validXOffset = if (xOffset.isNaN()) 0f else xOffset
            val validXStep = if (xStep.isNaN()) 0f else xStep
            
            // --- MODE POCO / MANUAL ---
            if (isStaticLauncher || (validXStep <= 0f && !prefsName.contains("lock"))) {
                // Jika xStep 0 (Poco), kita paksa mode Static agar manual swipe aktif
                if (!isStaticLauncher && validXStep <= 0f) {
                    isStaticLauncher = true
                    if (detectedPages <= 1) detectedPages = 20
                }
                
                this.xStep = 0f
                if (kotlin.math.abs(this.xOffset - validXOffset) > 0.0001f) {
                    this.xOffset = validXOffset
                    requestDraw()
                }
                return
            }

            // --- MODE AUTO (HP Normal) ---
            if (!visible && validXOffset == 0f && validXStep == 0f) return

            if (validXStep <= 0f) {
                if (detectedPages != 20) {
                    detectedPages = 20
                    loadWallpapersForPages()
                }
                this.xStep = 0f
            } else {
                val newDetectedPages = (1f / validXStep).roundToInt() + 1
                if (newDetectedPages != detectedPages && newDetectedPages in 1..50) {
                    detectedPages = newDetectedPages
                    handler.removeCallbacks(reloadRunnable)
                    handler.postDelayed(reloadRunnable, 500)
                }
                this.xStep = validXStep
            }

            // Update manualPageIndex berdasarkan offset sistem (Hanya di mode Auto)
            val offsetDelta = kotlin.math.abs(this.xOffset - validXOffset)
            if (visible && (offsetDelta > 0.0001f || this.xStep != validXStep)) {
                this.xOffset = validXOffset
                if (pageBitmaps.isNotEmpty() && this.xStep > 0f) {
                    val targetIndex = (validXOffset / this.xStep).roundToInt()
                    val clampedIndex = targetIndex.coerceIn(0, detectedPages - 1)
                    if (manualPageIndex != clampedIndex) {
                        manualPageIndex = clampedIndex
                        requestDraw()
                    }
                }
            }
        }

        private val reloadRunnable = Runnable { 
            if (manualPageCount > 0) detectedPages = manualPageCount
            loadWallpapersForPages() 
        }

        override fun onSurfaceChanged(holder: SurfaceHolder?, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            this.surfaceWidth = width
            this.surfaceHeight = height
            
            // TRICK SISTEM: 
            // Meskipun kita di mode Manual/Poco, kita tetap minta lebar 5 layar untuk HOME.
            // Ini supaya Launcher tidak pelit membagikan touch event (Horizontal Swipe).
            val targetW = if (prefsName.contains("lock")) width else width * 5
            updateWallpaperDimensions(targetW, height)

            // RECOVERY: If surface changed and we have no bitmaps, force a load
            if (pageBitmaps.isEmpty() && !isLoading) {
                loadWallpapersForPages()
            }
            requestDraw()
        }

        private fun updateWallpaperDimensions(targetWidth: Int, targetHeight: Int) {
            if (targetWidth <= 0 || targetHeight <= 0) return
            if (lastSuggestedWidth == targetWidth) return
            lastSuggestedWidth = targetWidth
            try {
                val wm = getSystemService(Context.WALLPAPER_SERVICE) as android.app.WallpaperManager
                wm.suggestDesiredDimensions(targetWidth, targetHeight)
            } catch (e: Exception) {
                Log.e("MultiWallpaper", "Error suggesting dimensions: ${e.message}")
            }
        }

        // Track recently added URIs to prevent duplicates from concurrent jobs
        // Use a set per target to allow multiple unique images (like 20 pages) to be added at once
        private val recentAddedUris = mutableMapOf<String, MutableSet<String>>()

        private suspend fun addToHistory(uri: String) {
            val targetName = if (prefsName.contains("lock")) "LOCK" else "HOME"
            val now = System.currentTimeMillis()
            
            synchronized(recentAddedUris) {
                val lastSet = recentAddedUris.getOrPut(targetName) { mutableSetOf() }
                if (lastSet.contains(uri)) {
                    return
                }
                lastSet.add(uri)
                // Clear the set periodically (every 5 seconds) to allow rotation to re-add later
                handler.postDelayed({ 
                    synchronized(recentAddedUris) { recentAddedUris[targetName]?.remove(uri) }
                }, 5000)
            }

            withContext(Dispatchers.IO) {
                val db = AppDatabase.getDatabase(applicationContext)
                val entity = RotationHistoryEntity(uriString = uri, target = targetName)
                db.rotationHistoryDao().insertHistory(entity)
                
                // Sync in-memory history for quick lookup/UI
                synchronized(recentHistories) {
                    val history = recentHistories.getOrPut(targetName) { LinkedHashSet() }
                    // Add to the end (newest)
                    history.add(uri)
                    // Trim if needed (matching DEFAULT_MAX_HISTORY)
                    if (history.size > DEFAULT_MAX_HISTORY) {
                        val iterator = history.iterator()
                        if (iterator.hasNext()) {
                            iterator.next()
                            iterator.remove()
                        }
                    }
                }
            }
        }

        private fun scheduleRotation() {
            handler.removeCallbacks(rotationRunnable)
            val intervalMs = getRotationIntervalMs()
            val currentTime = System.currentTimeMillis()
            
            // PERSISTENT TIMER FIX: 
            // Calculate remaining time instead of always resetting to full intervalMs.
            // This prevents frequent screen on/off from indefinitely postponing rotation.
            val elapsed = currentTime - lastRotationTime
            val remainingMs = (intervalMs - elapsed).coerceIn(0L, intervalMs)
            
            Log.d("MultiWallpaper", "Engine scheduleRotation ($prefsName): next in ${remainingMs/1000}s (interval: ${intervalMs/1000}s, elapsed: ${elapsed/1000}s)")
            handler.postDelayed(rotationRunnable, remainingMs)
        }

        private suspend fun getNextWallpaperUriBatch(count: Int = 1): List<String> {
            val db = AppDatabase.getDatabase(applicationContext)
            val prefs = getSharedPreferences(prefsName, Context.MODE_PRIVATE)
            val useFavorites = prefs.getBoolean("use_favorites_only", false)
            val sortOrder = prefs.getString("rotation_sort_order", "RANDOM")
            val targetName = if (prefsName.contains("lock")) "LOCK" else "HOME"

            // 1. Check History Exhaustion
            val totalImages = if (useFavorites) db.favoriteDao().getFavoriteCount(targetName) else db.scannedImageDao().getImageCount(targetName)
            
            // SMART EXHAUSTION PROTECTION:
            // Instead of just comparing counts (which breaks when switching from 12000 to 300 photos),
            // we check if the CURRENT set actually has any images that ARE NOT in history.
            // This allows history to accumulate across different presets without being wiped.
            
            val hasAvailableImages = withContext(Dispatchers.IO) {
                if (useFavorites) {
                    db.favoriteDao().getRandomFavoriteUrisExcludingHistory(targetName, 1).isNotEmpty()
                } else {
                    db.scannedImageDao().getRandomUrisExcludingHistory(targetName, 1).isNotEmpty()
                }
            }
            
            if (totalImages > 0 && !hasAvailableImages) {
                Log.d("MultiWallpaper", "History Exhaustion for $targetName: No unique images left in current preset, clearing history")
                db.rotationHistoryDao().clearHistory(targetName)
                synchronized(recentHistories) { recentHistories[targetName]?.clear() }
            }

            val finalUris = mutableListOf<String>()

            try {
                if (sortOrder == "RANDOM") {
                    // BALANCED RANDOM LOGIC (DIVERSITY ACROSS SUB-FOLDERS)
                    val folders = if (useFavorites) db.favoriteDao().getDistinctFavoriteFolders(targetName) 
                                 else db.scannedImageDao().getDistinctFolders(targetName)
                    
                    if (folders.isNotEmpty()) {
                        // Shuffle the list of sub-folders to keep rotation source random
                        val shuffledFolders = folders.shuffled().toMutableList()
                        var folderIdx = 0
                        
                        // Attempt to pick 1 image from each sub-folder until count is met
                        while (finalUris.size < count) {
                            if (shuffledFolders.isEmpty()) break
                            
                            val targetFolder = shuffledFolders[folderIdx % shuffledFolders.size]
                            val picked = if (useFavorites) 
                                db.favoriteDao().getRandomFavoriteUrisFromFolderExcludingHistory(targetName, targetFolder, 1)
                            else 
                                db.scannedImageDao().getRandomUrisFromFolderExcludingHistory(targetName, targetFolder, 1)
                            
                            if (picked.isNotEmpty()) {
                                finalUris.add(picked[0])
                            } else {
                                // If this sub-folder is exhausted (all in history), remove it from candidates for this batch
                                shuffledFolders.removeAt(folderIdx % shuffledFolders.size)
                                if (shuffledFolders.isEmpty()) break
                                folderIdx-- // Compensate for removal
                            }
                            
                            folderIdx++
                            // Safety break
                            if (folderIdx > 500) break 
                        }
                    }
                    
                    // Fallback: If Balanced picking didn't fill the count (e.g., all folders mostly in history)
                    if (finalUris.size < count) {
                        val remaining = count - finalUris.size
                        val fallback = if (useFavorites) 
                            db.favoriteDao().getRandomFavoriteUrisExcludingHistory(targetName, remaining)
                        else 
                            db.scannedImageDao().getRandomUrisExcludingHistory(targetName, remaining)
                        
                        fallback.forEach { if (!finalUris.contains(it)) finalUris.add(it) }
                    }
                } else {
                    // BY FOLDER (ORDERED) MODE
                    val ordered = if (useFavorites)
                        db.favoriteDao().getOrderedFavoriteUrisExcludingHistory(targetName, count)
                    else
                        db.scannedImageDao().getOrderedUrisExcludingHistory(targetName, count)
                    
                    finalUris.addAll(ordered)
                }
            } catch (e: Exception) {
                Log.e("MultiWallpaper", "Rotation fetch error", e)
            }
            
            return finalUris
        }

        private fun rotateWallpapers() {
            val now = System.currentTimeMillis()
            lastRotationTime = now
            getSharedPreferences(prefsName, Context.MODE_PRIVATE).edit().putLong("last_rotation_time", now).apply()

            synchronized(bitmapLock) {
                if (isTransitioning) return // Avoid overlapping transitions

                if (transitionType == "fade" && pageBitmaps.isNotEmpty()) {
                    // Tighter timing mapping for better feel
                    transitionDuration = (1300L - (fadeSpeed * 21L)).coerceIn(250L, 1200L)
                    
                    if (preloadedBitmap != null) {
                        if (visible) {
                            nextBitmap?.recycle()
                            nextBitmap = preloadedBitmap
                            nextFocalPoint = preloadedFocalPoint
                            if (preloadedUri != null) {
                                pageUris[manualPageIndex] = preloadedUri!!
                            }
                            
                            preloadedBitmap = null
                            preloadedUri = null
                            preloadedFocalPoint = null
                            
                            startFade()
                        } else {
                            // SCREEN OFF: Respect timer, but swap instantly to free memory
                            val old = pageBitmaps[manualPageIndex]
                            pageBitmaps[manualPageIndex] = preloadedBitmap!!
                            pageFocalPoints[manualPageIndex] = preloadedFocalPoint
                            if (preloadedUri != null) {
                                pageUris[manualPageIndex] = preloadedUri!!
                            }
                            
                            if (old != preloadedBitmap) old?.recycle()
                            
                            preloadedBitmap = null
                            preloadedUri = null
                            preloadedFocalPoint = null
                            
                            // CLEANUP: Ensure no hidden indices exist beyond detectedPages
                            val keysToRemove = pageBitmaps.keys.filter { it >= detectedPages }
                            keysToRemove.forEach { k ->
                                pageBitmaps[k]?.recycle()
                                pageBitmaps.remove(k)
                                pageUris.remove(k)
                                pageFocalPoints.remove(k)
                            }
                            
                            scheduleRotation()
                            preloadNextWallpaper()
                            System.gc()
                        }
                    } else {
                        startFadeRotation()
                    }
                    
                    // Silently refresh other pages to keep them fresh
                    refreshOtherPages()
                } else {
                    loadWallpapersForPages()
                }
            }
        }

        private fun refreshOtherPages() {
            backgroundRefreshJob?.cancel()
            backgroundRefreshJob = engineScope.launch(Dispatchers.IO) {
                if (detectedPages <= 1) return@launch
                
                val candidates = getNextWallpaperUriBatch(detectedPages * 2).toMutableList()
                if (candidates.isEmpty()) return@launch
                
                (0 until detectedPages).filter { it != manualPageIndex }.forEach { p ->
                    if (!isActive) return@launch
                    
                    var nextUri: String? = null
                    synchronized(pageUris) {
                        val prevPageFolder = pageUris[p - 1]?.let { Uri.parse(it).path?.substringBeforeLast('/') }
                        val candIdx = candidates.indexOfFirst { cand -> 
                            !smartAdjacencyEnabled || Uri.parse(cand).path?.substringBeforeLast('/') != prevPageFolder
                        }
                        if (candIdx != -1) {
                            nextUri = candidates.removeAt(candIdx)
                        } else if (candidates.isNotEmpty()) {
                            nextUri = candidates.removeAt(0)
                        }
                    }
                    
                    if (nextUri == null) return@forEach
                    
                    var b = decodeSampledBitmapFromUri(Uri.parse(nextUri!!), surfaceWidth, surfaceHeight)
                    if (b == null && candidates.isNotEmpty()) {
                        nextUri = candidates.removeAt(0)
                        b = decodeSampledBitmapFromUri(Uri.parse(nextUri!!), surfaceWidth, surfaceHeight)
                    }

                    if (b != null) {
                        // DETECT AI INSTANTLY FOR NEIGHBORS
                        val focal = if (smartCropEnabled) detectFaceFocalPoint(b!!) else null
                        withContext(Dispatchers.Main) {
                            val old = pageBitmaps[p]
                            pageBitmaps[p] = b!!
                            pageUris[p] = nextUri!!
                            pageFocalPoints[p] = focal
                            old?.recycle()
                            requestDraw()
                        }
                        addToHistory(nextUri!!)
                    }
                    delay(100) // Yield for UI smoothness
                }
            }
        }

        private fun preloadNextWallpaper() {
            preloadJob?.cancel()
            preloadJob = engineScope.launch(Dispatchers.IO) {
                val candidates = getNextWallpaperUriBatch(5).toMutableList()
                if (candidates.isEmpty()) return@launch
                
                var currentUri = candidates.removeAt(0)
                // Rotation target starts as ARGB_8888 for high quality fade
                var rawBmp = decodeSampledBitmapFromUri(Uri.parse(currentUri), surfaceWidth, surfaceHeight, isBackground = false)
                if (rawBmp == null && candidates.isNotEmpty()) {
                    currentUri = candidates.removeAt(0)
                    rawBmp = decodeSampledBitmapFromUri(Uri.parse(currentUri), surfaceWidth, surfaceHeight, isBackground = false)
                }

                if (rawBmp != null) {
                    val focal = if (smartCropEnabled) detectFaceFocalPoint(rawBmp!!) else null
                    withContext(Dispatchers.Main) {
                        if (!isActive) {
                            rawBmp!!.recycle()
                            return@withContext
                        }
                        preloadedBitmap?.recycle()
                        preloadedBitmap = rawBmp
                        preloadedUri = currentUri
                        preloadedFocalPoint = focal
                    }
                }
            }
        }

        private fun startFadeRotation() {
            rotationJob?.cancel()
            rotationJob = engineScope.launch(Dispatchers.IO) {
                val candidates = getNextWallpaperUriBatch(5).toMutableList()
                if (candidates.isEmpty()) return@launch
                
                var currentUri = candidates.removeAt(0)
                // Rotation target starts as ARGB_8888 for high quality fade
                var rawBmp = decodeSampledBitmapFromUri(Uri.parse(currentUri), surfaceWidth, surfaceHeight, isBackground = false)
                if (rawBmp == null && candidates.isNotEmpty()) {
                    currentUri = candidates.removeAt(0)
                    rawBmp = decodeSampledBitmapFromUri(Uri.parse(currentUri), surfaceWidth, surfaceHeight, isBackground = false)
                }

                if (rawBmp != null) {
                    val focal = if (smartCropEnabled) detectFaceFocalPoint(rawBmp!!) else null
                    withContext(Dispatchers.Main) {
                        if (!isActive) {
                            rawBmp!!.recycle()
                            return@withContext
                        }
                        nextBitmap?.recycle()
                        pageUris[manualPageIndex] = currentUri
                        nextBitmap = rawBmp
                        nextFocalPoint = focal
                        
                        if (visible) {
                            startFade()
                            preloadNextWallpaper() 
                        } else {
                            // SCREEN OFF: Instant swap and schedule next
                            val old = pageBitmaps[manualPageIndex]
                            pageBitmaps[manualPageIndex] = nextBitmap!!
                            pageFocalPoints[manualPageIndex] = nextFocalPoint
                            nextBitmap = null
                            nextFocalPoint = null
                            if (old != pageBitmaps[manualPageIndex]) old?.recycle()
                            scheduleRotation()
                            preloadNextWallpaper()
                            System.gc()
                        }
                        
                        // FIX: Ensure isLoading is cleared if manual rotation succeeds
                        if (isLoading && pageBitmaps.isNotEmpty()) {
                            isLoading = false
                            requestDraw()
                        }

                        addToHistory(currentUri)
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        scheduleRotation()
                    }
                }
            }
        }

        private fun startFade() {
            isTransitioning = true
            transitionAlpha = 0
            transitionStartTime = System.currentTimeMillis()
            Choreographer.getInstance().postFrameCallback(frameCallback)
        }

        private fun animateFade() {
            if (!isTransitioning) return
            
            val now = System.currentTimeMillis()
            val elapsed = now - transitionStartTime
            val progress = (elapsed.toFloat() / transitionDuration).coerceIn(0f, 1f)
            
            // Apply AccelerateDecelerate for cinematic feel
            val interpolatedProgress = interpolator.getInterpolation(progress)
            transitionAlpha = (interpolatedProgress * 255).toInt()
            
            if (progress >= 1f) {
                transitionAlpha = 255
                isTransitioning = false
                val old = pageBitmaps[manualPageIndex]
                pageBitmaps[manualPageIndex] = nextBitmap!!
                pageFocalPoints[manualPageIndex] = nextFocalPoint
                nextBitmap = null
                nextFocalPoint = null
                if (old != pageBitmaps[manualPageIndex]) old?.recycle()
                
                // FIX: Also clear isLoading here just in case
                if (isLoading) {
                    isLoading = false
                }

                requestDraw()
                scheduleRotation()
                
                // Hint for GC after rotation to reclaim any overhead
                System.gc()
            } else {
                requestDraw()
            }
        }

        private fun getRotationIntervalMs(): Long {
            val prefs = getSharedPreferences(prefsName, Context.MODE_PRIVATE)
            val seconds = prefs.getFloat("interval_seconds", 60f)
            val interval = (seconds * 1000L).toLong()
            Log.d("MultiWallpaper", "Engine getRotationIntervalMs: $prefsName -> $interval ms ($seconds s)")
            return interval
        }

        // ==========================================
        // FINAL VERIFICATION TRIGGER
        // Comment ini ditambahkan untuk memicu Rebuild Total.
        // Jika kodenya sudah benar, bug 1-page TIDAK AKAN MUNCUL lagi.
        // ==========================================
        private var lastLoadRequestTime = 0L
        private val LOAD_DEBOUNCE_MS = 500L

        private fun loadWallpapersForPages() {
            val now = System.currentTimeMillis()
            
            // RESET isLoading before starting to ensure we don't get stuck in a "true" state from previous failure
            if (isLoading) {
                isLoading = false
            }

            // BYPASS DEBOUNCE if we have no bitmaps (Initial load must proceed!)
            if (pageBitmaps.isNotEmpty() && (now - lastLoadRequestTime < LOAD_DEBOUNCE_MS)) {
                return
            }
            lastLoadRequestTime = now

            mainLoadJob?.cancel()
            backgroundRefreshJob?.cancel()

            isLoading = true
            requestDraw()

            mainLoadJob = engineScope.launch {
                try {
                    // 1. WAIT FOR SURFACE: Wait up to 2 seconds for valid dimensions
                    var waitCount = 0
                    while ((surfaceWidth <= 0 || surfaceHeight <= 0) && waitCount < 20 && isActive) {
                        delay(200)
                        waitCount++
                    }

                    if (surfaceWidth <= 0 || surfaceHeight <= 0) {
                        return@launch
                    }

                    val db = AppDatabase.getDatabase(applicationContext)
                    val prefs = getSharedPreferences(prefsName, Context.MODE_PRIVATE)
                    val useFavorites = prefs.getBoolean("use_favorites_only", false)
                    
                    val targetName = if (prefsName.contains("lock")) "LOCK" else "HOME"
                    var total = if (useFavorites) db.favoriteDao().getFavoriteCount(targetName) else db.scannedImageDao().getImageCount(targetName)
                    
                    if (total <= 0) {
                        delay(1000)
                        total = if (useFavorites) db.favoriteDao().getFavoriteCount(targetName) else db.scannedImageDao().getImageCount(targetName)
                    }

                    if (total <= 0) {
                        synchronized(bitmapLock) { recycleBitmaps() }
                        return@launch
                    }

                    if (!isActive) return@launch

                    val batchSize = 200.coerceAtMost(total)
                    val uriCandidates = getNextWallpaperUriBatch(batchSize).toMutableList()
                    if (uriCandidates.isEmpty()) return@launch

                    // CRITICAL SECTION: Visible Page (Non-Cancellable to prevent Loading Abadi)
                    val visibleUri = uriCandidates.removeAt(0)
                    val (firstBitmap, firstFocal) = withContext(Dispatchers.IO + kotlinx.coroutines.NonCancellable) {
                        val b = decodeSampledBitmapFromUri(Uri.parse(visibleUri), surfaceWidth, surfaceHeight, isBackground = false)
                        val f = if (b != null && smartCropEnabled) detectFaceFocalPoint(b) else null
                        Pair(b, f)
                    }
                    
                    withContext(Dispatchers.Main) {
                        synchronized(bitmapLock) {
                            pageBitmaps.forEach { (idx, b) -> if (idx != manualPageIndex) b.recycle() }
                            val oldMain = pageBitmaps[manualPageIndex]
                            pageBitmaps.clear()
                            pageUris.clear()
                            pageFocalPoints.clear()
                            
                            if (firstBitmap != null) {
                                pageBitmaps[manualPageIndex] = firstBitmap
                                pageUris[manualPageIndex] = visibleUri
                                pageFocalPoints[manualPageIndex] = firstFocal
                                if (oldMain != firstBitmap) oldMain?.recycle()
                            }
                        }
                        if (firstBitmap != null) {
                            addToHistory(visibleUri)
                            val nowTime = System.currentTimeMillis()
                            lastRotationTime = nowTime
                            getSharedPreferences(prefsName, Context.MODE_PRIVATE).edit().putLong("last_rotation_time", nowTime).apply()
                        }
                        // SHOW FIRST IMAGE IMMEDIATELY BUT KEEP isLoading=true FOR NEIGHBORS
                        requestDraw()
                    }

                    if (!isActive) return@launch

                    // Priority 2: Neighbors (Parallel for Speed)
                    val targetPageCount = detectedPages.coerceAtMost(total)
                    val priorityOrder = (0 until targetPageCount).filter { it != manualPageIndex }
                        .sortedBy { p ->
                            // Circular distance priority: Distance from current page in both directions
                            val dist = Math.abs(p - manualPageIndex)
                            val circularDist = Math.abs(targetPageCount - dist)
                            Math.min(dist, circularDist)
                        }

                    // STAGGERED PARALLEL LOADING:
                    // We load in chunks of 3 (as user liked).
                    val chunks = priorityOrder.chunked(3)
                    chunks.forEachIndexed { chunkIndex, chunk ->
                        if (!isActive) return@launch
                        
                        // DELAY FOR REBOOT:
                        // After loading the FIRST chunk (immediate neighbors), if the phone just started (< 3 mins),
                        // wait 10 seconds to let the system finish its startup tasks.
                        if (chunkIndex == 1 && android.os.SystemClock.elapsedRealtime() < 180000) {
                            delay(10000)
                        }

                        if (!isActive) return@launch

                        val jobs = chunk.map { p ->
                            async(Dispatchers.IO) {
                                if (!isActive) return@async
                                
                                var selectedUri: String? = null
                                try {
                                    synchronized(uriCandidates) { // Sync on the candidate list
                                        if (uriCandidates.isNotEmpty()) {
                                            // Try to pick one that respects smart adjacency
                                            var candIdx = -1
                                            val prevPageUri = synchronized(pageUris) { pageUris[p - 1] }
                                            val prevPageFolder = prevPageUri?.let { Uri.parse(it).path?.substringBeforeLast('/') }
                                            
                                            if (smartAdjacencyEnabled && prevPageFolder != null) {
                                                candIdx = uriCandidates.indexOfFirst { 
                                                    Uri.parse(it).path?.substringBeforeLast('/') != prevPageFolder 
                                                }
                                            }
                                            
                                            selectedUri = if (candIdx != -1) {
                                                uriCandidates.removeAt(candIdx)
                                            } else {
                                                uriCandidates.removeAt(0)
                                            }
                                        }
                                    }

                                    val uri = selectedUri ?: return@async
                                    val b = decodeSampledBitmapFromUri(Uri.parse(uri), surfaceWidth, surfaceHeight, isBackground = true)
                                    
                                    if (b != null) {
                                        if (!isActive) { b.recycle(); return@async }
                                        val focal = if (smartCropEnabled) detectFaceFocalPoint(b) else null
                                        withContext(Dispatchers.Main) {
                                            synchronized(bitmapLock) {
                                                val old = pageBitmaps[p]
                                                pageBitmaps[p] = b
                                                synchronized(pageUris) { pageUris[p] = uri }
                                                pageFocalPoints[p] = focal
                                                old?.recycle()
                                            }
                                            requestDraw()
                                        }
                                        addToHistory(uri)
                                    }
                                } catch (e: Exception) {
                                    if (e !is kotlinx.coroutines.CancellationException) {
                                        Log.e("MW_DEBUG", "Page $p load failed: ${e.message}")
                                    }
                                }
                            }
                        }
                        jobs.awaitAll()
                        
                        // LOG PROGRESS BUAT PEMBUKTIAN KERJA BACKGROUND
                        Log.d("MW_DEBUG", "[$prefsName] Progress: ${pageBitmaps.size}/$targetPageCount pages loaded.")
                        
                        // Yield between chunks to keep UI thread responsive
                        delay(100)
                    }
                    
                    System.gc() // Final cleanup after batch load
                    
                    if (isActive) {
                        withContext(Dispatchers.Main) { 
                            scheduleRotation()
                            preloadNextWallpaper()
                        }
                    }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) {
                        // Silent cancellation is normal
                    } else {
                        Log.e("MW_DEBUG", "[$prefsName] Loading failed: ${e.message}")
                    }
                } finally {
                    withContext(Dispatchers.Main) {
                        isLoading = false
                        Log.d("MW_DEBUG", "[$prefsName] mainLoadJob FINISHED. isLoading set to false.")
                        requestDraw()
                    }
                }
            }
        }

        private suspend fun detectFaceFocalPoint(bitmap: Bitmap): PointF? {
            val prefs = getSharedPreferences(prefsName, Context.MODE_PRIVATE)
            val manX = prefs.getFloat("manual_focal_x", 0.5f)
            val manY = prefs.getFloat("manual_focal_y", 0.4f)
            val fallback = PointF(manX, manY)

            // Downscale for faster and more accurate pattern recognition (Industry Standard)
            val targetSize = 480
            val scale = (targetSize.toFloat() / maxOf(bitmap.width, bitmap.height)).coerceAtMost(1f)

            // ALWAYS create a copy for asynchronous ML Kit processing.
            // This prevents the original bitmap from being recycled by other parts of the service
            // while ML Kit is working on it, especially during fast rotations (e.g. 5s interval).
            val detectionBitmap = try {
                if (scale < 1f) {
                    Bitmap.createScaledBitmap(
                        bitmap,
                        (bitmap.width * scale).toInt().coerceAtLeast(1),
                        (bitmap.height * scale).toInt().coerceAtLeast(1),
                        true
                    )
                } else {
                    bitmap.copy(bitmap.config ?: Bitmap.Config.ARGB_8888, false)
                }
            } catch (e: Exception) { null }

            if (detectionBitmap == null) return fallback

            // Capture dimensions BEFORE any potential recycling in background threads
            val dWidth = detectionBitmap.width
            val dHeight = detectionBitmap.height

            // Reuse or initialize the detector ONCE
            if (faceDetector == null) {
                val options = FaceDetectorOptions.Builder()
                    .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                    .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
                    .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
                    .setMinFaceSize(0.1f) // Slightly higher for reliability
                    .build()
                faceDetector = FaceDetection.getClient(options)
            }
            
            val detector = faceDetector ?: run {
                detectionBitmap.recycle()
                return fallback
            }

            return try {
                val image = InputImage.fromBitmap(detectionBitmap, 0)
                val task = detector.process(image)

                // CRITICAL: Only recycle when ML Kit task is completely finished.
                // This survives timeouts/cancellations of the calling coroutine.
                task.addOnCompleteListener {
                    try { if (!detectionBitmap.isRecycled) detectionBitmap.recycle() } catch (e: Exception) {}
                }
                
                // Add timeout to prevent infinite loading if ML Kit is waiting for downloads
                val faces = withTimeoutOrNull(5000L) {
                    suspendCancellableCoroutine<List<com.google.mlkit.vision.face.Face>> { cont ->
                        task.addOnSuccessListener { if (cont.isActive) cont.resume(it) }
                            .addOnFailureListener { if (cont.isActive) cont.resume(emptyList()) }
                    }
                } ?: emptyList()
                
                if (faces.isNotEmpty()) {
                    val mainFace = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }!!
                    val focal = PointF(mainFace.boundingBox.centerX() / dWidth.toFloat(), 
                                       mainFace.boundingBox.centerY() / dHeight.toFloat())
                    Log.d("MultiWallpaper", "Main face detected at: $focal (using ${dWidth}x${dHeight} proxy)")
                    focal
                } else {
                    fallback // Fallback slightly above center
                }
            } catch (e: Exception) { 
                fallback // Fallback slightly above center on error
            }
        }

        private fun decodeSampledBitmapFromUri(uri: Uri, reqWidth: Int, reqHeight: Int, isBackground: Boolean = false): Bitmap? {
            return try {
                // Get Orientation first
                val orientation = contentResolver.openInputStream(uri)?.use { input ->
                    val exif = androidx.exifinterface.media.ExifInterface(input)
                    exif.getAttributeInt(androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION, androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL)
                } ?: androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL

                contentResolver.openInputStream(uri)?.use { input ->
                    val opt = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeStream(input, null, opt)
                    
                    // Quality-based max resolution
                    val baseRes = when(wallpaperQuality) {
                        "HIGH" -> 1920
                        "LOW" -> 1080
                        else -> 1440 // NORMAL
                    }
                    
                    var maxWidth = if (reqWidth > 0) reqWidth.coerceAtMost(baseRes) else baseRes
                    var maxHeight = if (reqHeight > 0) reqHeight.coerceAtMost(baseRes) else baseRes

                    if (lightModeEnabled || isBackground || wallpaperQuality == "LOW") {
                        val factor = if (wallpaperQuality == "LOW") 0.6f else 0.75f
                        val minW = if (wallpaperQuality == "LOW") 480 else 720
                        val minH = if (wallpaperQuality == "LOW") 720 else 1080
                        
                        maxWidth = (maxWidth * factor).toInt().coerceAtLeast(minW)
                        maxHeight = (maxHeight * factor).toInt().coerceAtLeast(minH)
                    }

                    opt.inSampleSize = calculateInSampleSize(opt, maxWidth, maxHeight)
                    opt.inJustDecodeBounds = false
                    
                    // Bitmap configuration selection
                    opt.inPreferredConfig = when {
                        wallpaperQuality == "HIGH" && !isBackground -> Bitmap.Config.ARGB_8888
                        wallpaperQuality == "LOW" -> Bitmap.Config.RGB_565
                        lightModeEnabled || isBackground -> Bitmap.Config.RGB_565
                        else -> Bitmap.Config.ARGB_8888 // NORMAL visible page
                    }
                    
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        opt.inMutable = true
                    }

                    val decoded = contentResolver.openInputStream(uri)?.use { i2 -> BitmapFactory.decodeStream(i2, null, opt) }
                    
                    if (decoded != null) {
                        // LOG PERFORMA UNTUK VERIFIKASI QUALITY SETTING
                        val ramUsage = decoded.byteCount / (1024f * 1024f)
                        Log.d("MW_DEBUG", String.format(
                            "[%s] Decoded: %dx%d | Format: %s | RAM: %.2f MB | Quality: %s",
                            prefsName, decoded.width, decoded.height, decoded.config, ramUsage, wallpaperQuality
                        ))

                        if (orientation != androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL) {
                            val matrix = Matrix()
                            when (orientation) {
                                androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
                                androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                                androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
                                androidx.exifinterface.media.ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
                                androidx.exifinterface.media.ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
                            }
                            val rotated = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
                            decoded.recycle()
                            rotated
                        } else decoded
                    } else null
                }
            } catch (
                e: Exception) { null }
        }

        private fun calculateInSampleSize(opt: BitmapFactory.Options, rw: Int, rh: Int): Int {
            var s = 1; if (opt.outHeight > rh || opt.outWidth > rw) {
                val hh = opt.outHeight / 2; val hw = opt.outWidth / 2
                while (hh / s >= rh && hw / s >= rw) s *= 2
            }
            return s
        }

        private fun recycleBitmaps() {
            pageBitmaps.values.forEach { if (!it.isRecycled) it.recycle() }
            pageBitmaps.clear()
            pageFocalPoints.clear()
            pageUris.clear()
            // recentHistory.clear() // REMOVED: Now persistent in companion object
            nextBitmap?.recycle(); nextBitmap = null
            nextFocalPoint = null
            preloadedBitmap?.recycle(); preloadedBitmap = null
            preloadedFocalPoint = null
            preloadedUri = null
        }

        private fun drawFrame() {
            val holder = surfaceHolder ?: return
            var canvas: Canvas? = null
            try {
                // STRENGTHENED CANVAS LOCKING
                canvas = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    holder.lockHardwareCanvas()
                } else {
                    holder.lockCanvas()
                }
                
                if (canvas != null) {
                    drawCanvas(canvas)
                }
            } catch (e: Exception) {
                Log.e("MultiWallpaper", "Canvas lock error: ${e.message}")
            } finally {
                if (canvas != null) {
                    try {
                        holder.unlockCanvasAndPost(canvas)
                    } catch (e: Exception) {
                        Log.e("MultiWallpaper", "Canvas unlock error: ${e.message}")
                    }
                }
            }
        }

        private val srcRect = Rect()
        private val dstRect = RectF()
        private val nextSrcRect = Rect()
        private val nextDstRect = RectF()
        private val loadingRect = RectF()

        private fun drawLoadingState(canvas: Canvas, w: Int, h: Int) {
            canvas.drawColor(Color.parseColor("#1A1F2C"))
            val centerX = w / 2f
            val centerY = h / 2f
            val radius = 40f
            
            // Calculate rotation based on time (360 degrees per second)
            val now = System.currentTimeMillis()
            val angle = (now % 1000) / 1000f * 360f
            
            loadingRect.set(centerX - radius, centerY - radius, centerX + radius, centerY + radius)
            canvas.drawArc(loadingRect, angle, 270f, false, loadingCirclePaint)
            
            // THROTTELED REDRAW: Limit loading animation to ~30 FPS to save CPU and RAM for cleanup
            handler.removeCallbacks(drawRunnable)
            handler.postDelayed(drawRunnable, 33)
        }

        private fun drawCanvas(canvas: Canvas) {
            val w = canvas.width; val h = canvas.height
            
            // CRITICAL: Always clear background to prevent smearing/ghosting
            canvas.drawColor(Color.parseColor("#1A1F2C"))

            // SMART DRAW: Jika memory kosong baru tampilkan spinner/teks.
            // Jangan nunggu isLoading=false karena loading 20 page butuh waktu di background.
            if (pageBitmaps.isEmpty()) {
                if (isLoading) {
                    drawLoadingState(canvas, w, h)
                } else {
                    canvas.drawText("Select folders in App", w / 2f, h / 2f, textPaint)
                }
                return
            }

            val isFluid = if (xStep > 0f) {
                kotlin.math.abs((xOffset / xStep) - (xOffset / xStep).roundToInt()) > 0.001f
            } else {
                isSwiping || isSwipeAnimating // Stay fluid during swipe animation
            }
            
            val pos = if (xStep > 0f) {
                xOffset / xStep
            } else {
                // Combine manual index with finger drag progress
                (manualPageIndex.toFloat() - swipeOffset).coerceIn(0f, (detectedPages - 1).toFloat())
            }
            
            val maxIdx = (detectedPages - 1).coerceAtLeast(0)
            val idx = pos.roundToInt().coerceIn(0, maxIdx)

            // FOCAL POINT INTERPOLATION (Fixes effect "jolt" during transitions)
            var focal: PointF? = null
            if (smartCropEnabled && subjectFocusEnabled) {
                if (isTransitioning && nextBitmap != null) {
                    val startF = pageFocalPoints[manualPageIndex] ?: PointF(0.5f, 0.4f)
                    val endF = nextFocalPoint ?: PointF(0.5f, 0.4f)
                    val progress = transitionAlpha.toFloat() / 255f
                    focal = PointF(
                        startF.x + (endF.x - startF.x) * progress,
                        startF.y + (endF.y - startF.y) * progress
                    )
                } else if (isFluid && transitionType == "fade") {
                    val l = pos.toInt().coerceIn(0, maxIdx)
                    val r = (l + 1).coerceAtMost(maxIdx)
                    val f = pos - l
                    val startF = pageFocalPoints[l] ?: PointF(0.5f, 0.4f)
                    val endF = pageFocalPoints[r] ?: PointF(0.5f, 0.4f)
                    focal = PointF(
                        startF.x + (endF.x - startF.x) * f,
                        startF.y + (endF.y - startF.y) * f
                    )
                } else {
                    focal = pageFocalPoints[idx]
                }
            }

            // MODERN VISUAL EFFECTS PIPELINE (Android 12+)
            if (android.os.Build.VERSION.SDK_INT >= 31 && canvas.isHardwareAccelerated) {
                try {
                    if (visualEffectNode == null) {
                        visualEffectNode = RenderNode("VisualEffects")
                    }
                    val node = visualEffectNode!!
                    node.setPosition(0, 0, w, h)
                    
                    // Apply Blur to the Node (Global background blur)
                    val actualBlur = if (blurEnabled) blurRadius else 0f
                    if (actualBlur > 0f) {
                        node.setRenderEffect(RenderEffect.createBlurEffect(actualBlur, actualBlur, Shader.TileMode.CLAMP))
                    } else {
                        node.setRenderEffect(null)
                    }

                    // Record drawing into the Node
                    val recordingCanvas = node.beginRecording()
                    // REMOVED solid drawColor here to allow transparency for Tumble Aura
                    drawWallpaperContent(recordingCanvas, w, h, isFluid, pos, idx)
                    node.endRecording()

                    // Draw the Node (Blurred background)
                    canvas.drawRenderNode(node)
                    
                    // Apply Spotlight/Vignette (Drawn OUTSIDE the blurred node to keep edges sharp)
                    if (subjectFocusEnabled && focal != null && dimEnabled && dimIntensity > 0f) {
                        drawSubjectFocus(canvas, w.toFloat(), h.toFloat(), focal)
                    } else if (vignetteModeEnabled && dimEnabled && dimIntensity > 0f) {
                        drawSubjectFocus(canvas, w.toFloat(), h.toFloat(), PointF(0.5f, 0.5f))
                    }
                    
                    // TRUE PORTRAIT MODE: Draw a SHARP subject on top
                    if (subjectFocusEnabled && focal != null && blurEnabled && blurRadius > 0f) {
                        drawSharpSubject(canvas, w.toFloat(), h.toFloat(), focal, isFluid, pos, idx)
                    } else if (vignetteModeEnabled && blurEnabled && blurRadius > 0f) {
                        drawSharpSubject(canvas, w.toFloat(), h.toFloat(), PointF(0.5f, 0.5f), isFluid, pos, idx)
                    }
                } catch (e: Exception) {
                    Log.e("MultiWallpaper", "RenderNode Effects failed: ${e.message}")
                    drawWallpaperContent(canvas, w, h, isFluid, pos, idx)
                }
            } else {
                // FALLBACK for older Android or non-HW canvas
                drawWallpaperContent(canvas, w, h, isFluid, pos, idx)
                if (subjectFocusEnabled && focal != null) {
                    if (dimEnabled && dimIntensity > 0f) drawSubjectFocus(canvas, w.toFloat(), h.toFloat(), focal)
                    if (blurEnabled && blurRadius > 0f) drawSharpSubject(canvas, w.toFloat(), h.toFloat(), focal, isFluid, pos, idx)
                } else if (vignetteModeEnabled) {
                    if (dimEnabled && dimIntensity > 0f) drawSubjectFocus(canvas, w.toFloat(), h.toFloat(), PointF(0.5f, 0.5f))
                    if (blurEnabled && blurRadius > 0f) drawSharpSubject(canvas, w.toFloat(), h.toFloat(), PointF(0.5f, 0.5f), isFluid, pos, idx)
                }
            }

            // Apply Global Dim Overlay (Only if AI Focus and Vignette are OFF)
            if (dimEnabled && dimIntensity > 0f && !subjectFocusEnabled && !vignetteModeEnabled) {
                val alpha = (dimIntensity * 255).toInt().coerceIn(0, 255)
                canvas.drawColor(Color.argb(alpha, 0, 0, 0))
            }

            // Blacklist visual feedback (Red flash)
            if (showBlacklistFeedback) {
                canvas.drawColor(Color.argb(100, 255, 0, 0))
            }
        }

        private fun drawSharpSubject(canvas: Canvas, w: Float, h: Float, focal: PointF, isFluid: Boolean, pos: Float, idx: Int) {
            val isVignette = (focal.x == 0.5f && focal.y == 0.5f)
            val checkpoint = canvas.saveLayer(0f, 0f, w, h, null)
            drawWallpaperContent(canvas, w.toInt(), h.toInt(), isFluid, pos, idx)
            
            if (isVignette) {
                // TRUE RECTANGULAR EDGE SHADOW (STRICTLY LINEAR)
                val edgeW = w * vignetteWidth
                val edgeH = h * vignetteWidth
                val shadowColor = Color.BLACK
                val transparent = Color.TRANSPARENT
                
                val smoothing = 1.0f - vignetteSharpness
                val colorsArr = intArrayOf(shadowColor, transparent)
                val stops = floatArrayOf(
                    (0.5f - (smoothing * 0.45f)).coerceIn(0.01f, 0.49f),
                    (0.5f + (smoothing * 0.45f)).coerceIn(0.51f, 0.99f)
                )

                // ANTI-CROSS (+) LOGIC: 
                // Use default DST_OUT to mask out the sharp layer edges.
                // Overlapping corners will be slightly more transparent, creating a natural vignette shape.

                // Left
                maskPaint.shader = android.graphics.LinearGradient(0f, 0f, edgeW, 0f, colorsArr, stops, Shader.TileMode.CLAMP)
                canvas.drawRect(0f, 0f, edgeW, h, maskPaint)
                // Right
                maskPaint.shader = android.graphics.LinearGradient(w, 0f, w - edgeW, 0f, colorsArr, stops, Shader.TileMode.CLAMP)
                canvas.drawRect(w - edgeW, 0f, w, h, maskPaint)
                // Top
                maskPaint.shader = android.graphics.LinearGradient(0f, 0f, 0f, edgeH, colorsArr, stops, Shader.TileMode.CLAMP)
                canvas.drawRect(0f, 0f, w, edgeH, maskPaint)
                // Bottom
                maskPaint.shader = android.graphics.LinearGradient(0f, h, 0f, h - edgeH, colorsArr, stops, Shader.TileMode.CLAMP)
                canvas.drawRect(0f, h - edgeH, w, h, maskPaint)
            } else {
                // ORIGINAL RADIAL MASK FOR AI SUBJECT FOCUS (ROUND SPOTLIGHT)
                val faceX = focal.x * w
                val faceY = focal.y * h
                val diagonal = sqrt(w * w + h * h) / 2f
                val radius = diagonal * (0.5f + subjectFocusSmoothing * 1.5f)
                val colors = intArrayOf(Color.TRANSPARENT, Color.BLACK)
                
                val smoothing = 1.0f - (vignetteSharpness * 0.9f) 
                val innerStop = (0.5f - (smoothing * 0.45f)).coerceIn(0.01f, 0.49f)
                val outerStop = (0.5f + (smoothing * 0.45f)).coerceIn(0.51f, 0.99f)
                val stops = floatArrayOf(innerStop, outerStop)
                
                val gradient = RadialGradient(faceX, faceY, radius, colors, stops, Shader.TileMode.CLAMP)
                maskPaint.shader = gradient
                canvas.drawRect(0f, 0f, w, h, maskPaint)
            }
            canvas.restoreToCount(checkpoint)
        }

        private fun drawSubjectFocus(canvas: Canvas, w: Float, h: Float, focal: PointF) {
            val isVignette = (focal.x == 0.5f && focal.y == 0.5f)
            val alpha = (dimIntensity * 255).toInt().coerceIn(0, 255)
            val shadowColor = Color.argb(alpha, 0, 0, 0)
            val transparent = Color.TRANSPARENT
            
            if (isVignette) {
                // TRUE RECTANGULAR EDGE DIMMING (STRICTLY LINEAR)
                val edgeW = w * vignetteWidth
                val edgeH = h * vignetteWidth
                
                val smoothing = 1.0f - vignetteSharpness
                val colorsArr = intArrayOf(shadowColor, transparent)
                val stops = floatArrayOf(
                    (0.5f - (smoothing * 0.45f)).coerceIn(0.01f, 0.49f),
                    (0.5f + (smoothing * 0.45f)).coerceIn(0.51f, 0.99f)
                )

                // Use DARKEN xfermode to prevent corner doubling
                val oldXfer = vignettePaint.xfermode
                vignettePaint.xfermode = PorterDuffXfermode(PorterDuff.Mode.DARKEN)
                
                // Left
                vignettePaint.shader = android.graphics.LinearGradient(0f, 0f, edgeW, 0f, colorsArr, stops, Shader.TileMode.CLAMP)
                canvas.drawRect(0f, 0f, edgeW, h, vignettePaint)
                // Right
                vignettePaint.shader = android.graphics.LinearGradient(w, 0f, w - edgeW, 0f, colorsArr, stops, Shader.TileMode.CLAMP)
                canvas.drawRect(w - edgeW, 0f, w, h, vignettePaint)
                // Top
                vignettePaint.shader = android.graphics.LinearGradient(0f, 0f, 0f, edgeH, colorsArr, stops, Shader.TileMode.CLAMP)
                canvas.drawRect(0f, 0f, w, edgeH, vignettePaint)
                // Bottom
                vignettePaint.shader = android.graphics.LinearGradient(0f, h, 0f, h - edgeH, colorsArr, stops, Shader.TileMode.CLAMP)
                canvas.drawRect(0f, h - edgeH, w, h, vignettePaint)
                
                vignettePaint.xfermode = oldXfer
            } else {
                // ORIGINAL RADIAL DIMMING FOR AI SUBJECT FOCUS (ROUND SPOTLIGHT)
                val faceX = focal.x * w
                val faceY = focal.y * h
                val diagonal = sqrt(w * w + h * h) / 2f
                val radius = diagonal * (0.5f + subjectFocusSmoothing * 1.5f)
                
                val smoothing = 1.0f - (vignetteSharpness * 0.9f)
                val radialColors = intArrayOf(transparent, shadowColor)
                val radialStops = floatArrayOf(
                    (0.6f - (smoothing * 0.55f)).coerceIn(0.01f, 0.59f),
                    (0.6f + (smoothing * 0.35f)).coerceIn(0.61f, 0.99f)
                )
                
                val gradient = RadialGradient(faceX, faceY, radius, radialColors, radialStops, Shader.TileMode.CLAMP)
                vignettePaint.shader = gradient
                canvas.drawRect(0f, 0f, w, h, vignettePaint)
            }
        }

        private fun drawWallpaperContent(canvas: Canvas, w: Int, h: Int, isFluid: Boolean, pos: Float, idx: Int) {
            // Apply Visual Filter
            updateFilter()

            val maxIdx = (detectedPages - 1).coerceAtLeast(0)

            // Priority 1: Auto-Rotation Transitions
            if (isTransitioning && nextBitmap != null && !nextBitmap!!.isRecycled) {
                val curr = pageBitmaps[manualPageIndex]
                if (curr != null && !curr.isRecycled) {
                    val currFocal = if (smartCropEnabled) pageFocalPoints[manualPageIndex] else null
                    
                    if (transitionType == "fade") {
                        calculateRects(curr, w, h, srcRect, dstRect, currFocal)
                        canvas.drawBitmap(curr, srcRect, dstRect, bitmapPaint)
                        
                        val oldAlpha = bitmapPaint.alpha
                        bitmapPaint.alpha = transitionAlpha
                        calculateRects(nextBitmap!!, w, h, nextSrcRect, nextDstRect, nextFocalPoint)
                        canvas.drawBitmap(nextBitmap!!, nextSrcRect, nextDstRect, bitmapPaint)
                        bitmapPaint.alpha = oldAlpha
                    } else if (transitionType == "slide") {
                        val progress = transitionAlpha.toFloat() / 255f
                        calculateRects(curr, w, h, srcRect, dstRect, currFocal)
                        dstRect.offset(-progress * w, 0f)
                        canvas.drawBitmap(curr, srcRect, dstRect, bitmapPaint)
                        
                        calculateRects(nextBitmap!!, w, h, nextSrcRect, nextDstRect, nextFocalPoint)
                        nextDstRect.offset((1f - progress) * w, 0f)
                        canvas.drawBitmap(nextBitmap!!, nextSrcRect, nextDstRect, bitmapPaint)
                    } else if (transitionType == "tumble") {
                        val progress = transitionAlpha.toFloat() / 255f
                        drawTumbleTransition(canvas, curr, nextBitmap!!, w, h, currFocal, nextFocalPoint, progress)
                    } else { // "cut" or default
                        drawSingleBitmap(canvas, nextBitmap!!, w, h, -1) // -1 use nextFocalPoint
                    }
                    return
                }
            }

            // Priority 2: Manual Swipe Transitions
            if (isFluid) {
                val l = pos.toInt().coerceIn(0, maxIdx)
                val r = (l + 1).coerceAtMost(maxIdx)
                val f = pos - l
                
                val lb = pageBitmaps[l]
                val rb = pageBitmaps[r]

                if (lb != null && !lb.isRecycled && rb != null && !rb.isRecycled && l != r) {
                    val lf = if (smartCropEnabled) pageFocalPoints[l] else null
                    val rf = if (smartCropEnabled) pageFocalPoints[r] else null
                    calculateRects(lb, w, h, srcRect, dstRect, lf)
                    calculateRects(rb, w, h, nextSrcRect, nextDstRect, rf)

                    if (transitionType == "fade") {
                        val oldAlpha = bitmapPaint.alpha
                        bitmapPaint.alpha = ((1f - f) * 255).toInt()
                        canvas.drawBitmap(lb, srcRect, dstRect, bitmapPaint)
                        
                        bitmapPaint.alpha = (f * 255).toInt()
                        canvas.drawBitmap(rb, nextSrcRect, nextDstRect, bitmapPaint)
                        bitmapPaint.alpha = oldAlpha
                    } else if (transitionType == "slide") {
                        dstRect.offset(-f * w, 0f)
                        canvas.drawBitmap(lb, srcRect, dstRect, bitmapPaint)
                        
                        nextDstRect.offset((1f - f) * w, 0f)
                        canvas.drawBitmap(rb, nextSrcRect, nextDstRect, bitmapPaint)
                    } else if (transitionType == "tumble") {
                        drawTumbleTransition(canvas, lb, rb, w, h, lf, rf, f)
                    } else { // "cut"
                        val clampedIdx = idx.coerceIn(0, maxIdx)
                        val activeB = pageBitmaps[clampedIdx]
                        if (activeB != null) drawSingleBitmap(canvas, activeB, w, h, clampedIdx)
                    }
                    return
                } else if (lb != null && !lb.isRecycled) {
                    drawSingleBitmap(canvas, lb, w, h, l)
                    return
                } else if (rb != null && !rb.isRecycled) {
                    drawSingleBitmap(canvas, rb, w, h, r)
                    return
                }
            }

            // Priority 3: Static Draw
            val curr = pageBitmaps[idx.coerceIn(0, maxIdx)]
            if (curr != null && !curr.isRecycled) {
                drawSingleBitmap(canvas, curr, w, h, idx)
            } else {
                drawLoadingState(canvas, w, h)
            }
        }

        private fun drawTumbleTransition(canvas: Canvas, b1: Bitmap, b2: Bitmap, w: Int, h: Int, f1: PointF?, f2: PointF?, progress: Float) {
            val rotationMax = 25f // Sudut rotasi 2D
            val splitX = (1f - progress) * w

            // 1. Gambar Sisi Kiri (WP 1 + Background Tile Miring)
            canvas.save()
            canvas.clipRect(0f, 0f, splitX, h.toFloat()) // Hard Cut kiri
            
            canvas.rotate(-progress * rotationMax, w / 2f, h.toFloat())
            canvas.translate(-progress * w, 0f)
            
            // A. Draw Blurred Mirror Tile Background
            drawProfessionalTiltedBackground(canvas, b1, w, h, true)
            
            // B. Draw Sharp Card
            calculateRects(b1, w, h, srcRect, dstRect, f1)
            canvas.drawBitmap(b1, srcRect, dstRect, bitmapPaint)
            canvas.restore()

            // 2. Gambar Sisi Kanan (WP 2 + Background Tile Miring)
            canvas.save()
            canvas.clipRect(splitX, 0f, w.toFloat(), h.toFloat()) // Hard Cut kanan
            
            canvas.rotate((1f - progress) * rotationMax, w / 2f, h.toFloat())
            canvas.translate((1f - progress) * w, 0f)
            
            // A. Draw Blurred Mirror Tile Background
            drawProfessionalTiltedBackground(canvas, b2, w, h, false)

            // B. Draw Sharp Card
            calculateRects(b2, w, h, nextSrcRect, nextDstRect, f2)
            canvas.drawBitmap(b2, nextSrcRect, nextDstRect, bitmapPaint)
            canvas.restore()
        }

        private fun drawProfessionalTiltedBackground(canvas: Canvas, b: Bitmap, w: Int, h: Int, isLeft: Boolean) {
            if (android.os.Build.VERSION.SDK_INT >= 31 && canvas.isHardwareAccelerated) {
                val node = if (isLeft) {
                    if (leftTumbleNode == null) leftTumbleNode = RenderNode("LeftTumble")
                    leftTumbleNode!!
                } else {
                    if (rightTumbleNode == null) rightTumbleNode = RenderNode("RightTumble")
                    rightTumbleNode!!
                }
                
                // Area gambar harus luas karena ada perputaran
                node.setPosition(-w, -h, w * 2, h * 2)
                node.setRenderEffect(RenderEffect.createBlurEffect(60f, 60f, Shader.TileMode.MIRROR))
                
                val recordingCanvas = node.beginRecording()
                val paint = Paint(Paint.FILTER_BITMAP_FLAG)
                val shader = android.graphics.BitmapShader(b, Shader.TileMode.MIRROR, Shader.TileMode.MIRROR)
                
                // MIRROR TILE LOGIC: Skala dikecilkan supaya efek "Tile & Flip" kelihatan jelas
                val matrix = Matrix()
                val scale = maxOf(w.toFloat() / b.width, h.toFloat() / b.height) * 0.5f // 50% size for visible tiling
                matrix.setScale(scale, scale, b.width / 2f, b.height / 2f)
                shader.setLocalMatrix(matrix)
                
                paint.shader = shader
                recordingCanvas.drawRect(0f, 0f, w * 3f, h * 3f, paint)
                node.endRecording()
                
                canvas.drawRenderNode(node)
            } else {
                // Fallback untuk Android lama
                drawTiltedMirrorBackground(canvas, b, w, h, 255f)
            }
        }

        private fun drawTiltedMirrorBackground(canvas: Canvas, b: Bitmap, w: Int, h: Int, alpha: Float) {
            val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
                this.alpha = alpha.toInt().coerceIn(0, 255)
                if (android.os.Build.VERSION.SDK_INT >= 31) {
                    maskFilter = android.graphics.BlurMaskFilter(40f, android.graphics.BlurMaskFilter.Blur.NORMAL)
                }
            }
            val shader = android.graphics.BitmapShader(b, Shader.TileMode.MIRROR, Shader.TileMode.MIRROR)
            val matrix = Matrix()
            val scale = maxOf(w.toFloat() / b.width, h.toFloat() / b.height)
            matrix.setScale(scale, scale, b.width / 2f, b.height / 2f)
            shader.setLocalMatrix(matrix)
            paint.shader = shader
            
            // Gambar kotak besar yang miring mengikuti sistem koordinat canvas saat ini
            canvas.drawRect(-w.toFloat(), -h.toFloat(), w * 2f, h * 2f, paint)
        }

        private fun updateFilter() {
            when (filterType) {
                "GRAYSCALE" -> {
                    val cm = ColorMatrix()
                    cm.setSaturation(0f)
                    bitmapPaint.colorFilter = ColorMatrixColorFilter(cm)
                }
                "DUOTONE" -> {
                    // Custom Duotone Matrix: Maps Luminance to a Gradient between Color 1 (Dark) and Color 2 (Light)
                    val r1 = Color.red(filterColor1) / 255f
                    val g1 = Color.green(filterColor1) / 255f
                    val b1 = Color.blue(filterColor1) / 255f
                    
                    val r2 = Color.red(filterColor2) / 255f
                    val g2 = Color.green(filterColor2) / 255f
                    val b2 = Color.blue(filterColor2) / 255f
                    
                    val lr = 0.2126f; val lg = 0.7152f; val lb = 0.0722f
                    
                    val matrix = floatArrayOf(
                        (r2 - r1) * lr, (r2 - r1) * lg, (r2 - r1) * lb, 0f, r1 * 255f,
                        (g2 - g1) * lr, (g2 - g1) * lg, (g2 - g1) * lb, 0f, g1 * 255f,
                        (b2 - b1) * lr, (b2 - b1) * lg, (b2 - b1) * lb, 0f, b1 * 255f,
                        0f, 0f, 0f, 1f, 0f
                    )
                    bitmapPaint.colorFilter = ColorMatrixColorFilter(matrix)
                }
                "TRITONE" -> {
                    // Custom Tritone Matrix: Maps Dark -> Color 1, Mid -> Color 3, Light -> Color 2
                    // This is an approximation using a quadratic-like mapping for better control over midtones.
                    val r1 = Color.red(filterColor1) / 255f
                    val g1 = Color.green(filterColor1) / 255f
                    val b1 = Color.blue(filterColor1) / 255f
                    
                    val r2 = Color.red(filterColor2) / 255f
                    val g2 = Color.green(filterColor2) / 255f
                    val b2 = Color.blue(filterColor2) / 255f

                    val rM = Color.red(filterColor3) / 255f
                    val gM = Color.green(filterColor3) / 255f
                    val bM = Color.blue(filterColor3) / 255f
                    
                    val lr = 0.2126f; val lg = 0.7152f; val lb = 0.0722f

                    // We calculate constants for a curve that passes through C1 at L=0, CM at L=0.5, and C2 at L=1
                    // f(L) = aL^2 + bL + c
                    // L=0 -> c = C1
                    // L=1 -> a + b + C1 = C2 -> a + b = C2 - C1
                    // L=0.5 -> 0.25a + 0.5b + C1 = CM -> 0.25a + 0.5b = CM - C1
                    // Solving for a and b:
                    // 0.5a + b = 2(CM - C1)
                    // (a + b) - (0.5a + b) = (C2 - C1) - 2(CM - C1) -> 0.5a = C2 + C1 - 2CM -> a = 2C2 + 2C1 - 4CM
                    // b = (C2 - C1) - a = C2 - C1 - (2C2 + 2C1 - 4CM) = 4CM - 3C1 - C2
                    
                    // Since ColorMatrix is linear (ax+by+cz+dw+e), we can't do L^2 perfectly in one pass,
                    // but we can simulate a very strong Tritone using a segmented linear approach or weighted luminance.
                    // For best performance and "pop", we use a 3-color weighted blend.
                    val matrix = floatArrayOf(
                        (r2 - r1) * lr, (r2 - r1) * lg, (r2 - r1) * lb, 0f, (r1 + (rM - (r1+r2)/2f) * 0.5f) * 255f,
                        (g2 - g1) * lr, (g2 - g1) * lg, (g2 - g1) * lb, 0f, (g1 + (gM - (g1+g2)/2f) * 0.5f) * 255f,
                        (b2 - b1) * lr, (b2 - b1) * lg, (b2 - b1) * lb, 0f, (b1 + (bM - (b1+b2)/2f) * 0.5f) * 255f,
                        0f, 0f, 0f, 1f, 0f
                    )
                    bitmapPaint.colorFilter = ColorMatrixColorFilter(matrix)
                }
                else -> {
                    bitmapPaint.colorFilter = null
                }
            }
        }

        private fun calculateRects(b: Bitmap, w: Int, h: Int, sR: Rect, dR: RectF, fP: PointF? = null) {
            val bW = b.width.toFloat()
            val bH = b.height.toFloat()
            val sBase = maxOf(w.toFloat() / bW, h.toFloat() / bH)
            
            // AI ZOOM & PARALLAX SLACK INTEGRATION
            // 1. Calculate how much zoom we need for Parallax movement
            // Min slack 1.05f ensures even 16:9 images have room to move
            val minSlack = 1.05f
            val parallaxZoom = if (parallaxEnabled) maxOf(minSlack, 1.0f + (parallaxStrength * 0.15f)) else 1.0f

            // 2. Calculate how much zoom we need for AI Slack if enabled
            val aiZoom = if (smartCropEnabled && fP != null) {
                // Base AI zoom 1.1x, increasing up to zoomSlack (default 1.45x)
                val maxSlack = if (aiAdvancedEnabled) aiZoomSlack else 1.45f
                maxOf(1.1f, maxSlack)
            } else 1.0f
            
            // 3. Final zoom is the maximum of all requirements
            val zoomFactor = maxOf(parallaxZoom, aiZoom)

            val s = sBase * zoomFactor
            
            val ow = bW * s
            val oh = bH * s
            
            val standardCX = (w - ow) / 2f
            val standardCY = (h - oh) / 2f
            
            var cX = standardCX
            var cY = standardCY

            if (smartCropEnabled && fP != null) {
                var aiCX = (w / 2f) - (fP.x * ow)
                var aiCY = (h / 2f) - (fP.y * oh)

                // 3D DEPTH EFFECT:
                // If parallax is enabled, we nudge the AI focus point slightly in the OPPOSITE 
                // direction of the background tilt. This makes the subject feel like they 
                // are in a different layer than the background.
                if (parallaxEnabled) {
                    val subjectTiltX = (currentRoll / 12f) * (w * 0.08f) * parallaxStrength
                    val subjectTiltY = (currentPitch / 12f) * (h * 0.08f) * parallaxStrength
                    aiCX -= subjectTiltX // Opposite nudge for depth illusion
                    aiCY += subjectTiltY
                }
                
                // REFINED SAFE ZONE:
                // Only keep standard center if the face is within the central 10%.
                // Otherwise, start nudging it towards the center to ensure visibility.
                val dx = kotlin.math.abs(fP.x - 0.5f)
                val dy = kotlin.math.abs(fP.y - 0.5f)
                
                val sensitivityX = if (aiAdvancedEnabled) aiSensitivityX else 0.9f
                val sensitivityY = if (aiAdvancedEnabled) aiSensitivityY else 0.4f
                
                // Horizontal shift is more restricted in portrait, so we use a 
                // tight safe zone and highly aggressive weight for X.
                if (dx > 0.01f) {
                    val weight = ((dx - 0.01f) * (sensitivityX * 11f)).coerceIn(0f, 1f)
                    cX = standardCX * (1f - weight) + aiCX * weight
                }
                
                if (dy > 0.05f) {
                    val weightY = ((dy - 0.05f) * (sensitivityY * 10f)).coerceIn(0f, 1f)
                    cY = standardCY * (1f - weightY) + aiCY * weightY
                }
            }
            
            if (parallaxEnabled) {
                // ASPECT-AWARE PARALLAX (BACKGROUND LAYER):
                // We scale the motion intensity based on how much "extra room" (ow - w) 
                // the image actually has, MULTIPLIED by user preference.
                val slackX = (ow - w) / 2f
                val slackY = (oh - h) / 2f
                
                cX += (currentRoll / 10f) * slackX * parallaxStrength
                cY -= (currentPitch / 10f) * slackY * parallaxStrength
            }

            // Clamp to ensure screen is always covered
            cX = cX.coerceIn(w - ow, 0f)
            cY = cY.coerceIn(h - oh, 0f)

            val lOn = maxOf(0f, -cX)
            val tOn = maxOf(0f, -cY)
            val rOn = minOf(ow, w - cX)
            val bOn = minOf(oh, h - cY)

            sR.set((lOn / s).toInt(), (tOn / s).toInt(), (rOn / s).toInt(), (bOn / s).toInt())
            dR.set(maxOf(0f, cX), maxOf(0f, cY), minOf(w.toFloat(), cX + ow), minOf(h.toFloat(), cY + oh))
        }

        private fun drawSingleBitmap(canvas: Canvas, b: Bitmap, w: Int, h: Int, idx: Int) {
            if (b.isRecycled) {
                drawLoadingState(canvas, w, h)
                return
            }
            val focal = if (idx == -1) nextFocalPoint else if (smartCropEnabled) pageFocalPoints[idx] else null
            calculateRects(b, w, h, srcRect, dstRect, focal)
            canvas.drawBitmap(b, srcRect, dstRect, bitmapPaint)
        }
    }
}

class MultiWallpaperHomeService : BaseMultiWallpaperService() {
    override fun getPreferencesName(): String = "multi_wallpaper_prefs"
}

class MultiWallpaperLockService : BaseMultiWallpaperService() {
    override fun getPreferencesName(): String = "multi_wallpaper_prefs_lock"
}
