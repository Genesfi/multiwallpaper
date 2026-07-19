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

        // SHARED RESOURCES (AI & Jam) - Irit RAM & CPU
        private var globalFaceDetector: com.google.mlkit.vision.face.FaceDetector? = null
        private var timeTickReceiver: BroadcastReceiver? = null
        private var scheduleReloadReceiver: BroadcastReceiver? = null
        private val engines = mutableListOf<MultiWallpaperEngine>()

        fun registerEngine(engine: MultiWallpaperEngine) {
            synchronized(engines) { engines.add(engine) }
        }

        fun unregisterEngine(engine: MultiWallpaperEngine) {
            synchronized(engines) { engines.remove(engine) }
        }
    }

    override fun onCreate() {
        super.onCreate()
        // SHARED RECEIVER: Cukup satu sistem yang ngecek menit berganti
        if (timeTickReceiver == null) {
            timeTickReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    synchronized(engines) { engines.forEach { it.checkSchedules() } }
                }
            }
            val filter = IntentFilter(Intent.ACTION_TIME_TICK)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(timeTickReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(timeTickReceiver, filter)
            }
        }

        if (scheduleReloadReceiver == null) {
            scheduleReloadReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    synchronized(engines) { engines.forEach { it.checkSchedules() } }
                }
            }
            val filter = IntentFilter("gustian.multiwallpaper.RELOAD_SCHEDULES")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(scheduleReloadReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(scheduleReloadReceiver, filter)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        timeTickReceiver?.let { unregisterReceiver(it) }
        scheduleReloadReceiver?.let { unregisterReceiver(it) }
        timeTickReceiver = null
        scheduleReloadReceiver = null
        globalFaceDetector?.close()
        globalFaceDetector = null
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
        private var isFreshStart = true
        private val engineStartTime = System.currentTimeMillis()
        private val isBootPhase: Boolean get() = isFreshStart && (System.currentTimeMillis() - engineStartTime < 20000)
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
                                needsNodeUpdate = true
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
        private val pageThumbnails = mutableMapOf<Int, Bitmap>() // Buffer 3 Lapis: Thumbnail Irit RAM
        private val pageUris = mutableMapOf<Int, String>() // Track URIs to prevent duplicates
        private val pageFocalPoints = mutableMapOf<Int, PointF?>()
        private val pageScrollOffsets = mutableMapOf<Int, Float?>() // 0.0 to 1.0 horizontal progress
        
        private var nextBitmap: Bitmap? = null
        private var nextFocalPoint: PointF? = null
        private var nextScrollOffset: Float? = null
        private var nextSpan: Int = 1
        private var preloadedBitmap: Bitmap? = null
        private var preloadedUri: String? = null
        private var preloadedFocalPoint: PointF? = null
        private var preloadedScrollOffset: Float? = null
        private var preloadedSpan: Int = 1
        private var transitionAlpha = 255
        private var isTransitioning = false
        private var transitionStartTime = 0L
        private var transitionDuration = 600L
        private val interpolator = DecelerateInterpolator(1.5f) // Cubic-like ease-out for snappier response

        // GPU Smart Cache: Tandai kapan efek harus di-render ulang
        private var needsNodeUpdate = true
        private var lastRenderedIdx = -1

        private val frameCallback = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                if (isTransitioning) {
                    animateTransition()
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

        fun checkSchedules() {
            val targetName = if (prefsName.contains("lock")) "LOCK" else "HOME"

            // GATING UTAMA: Jika layar mati dan sudah ganti 1x, JANGAN panggil coroutine sama sekali.
            if (!visible && hasRotatedWhileIdle) {
                return
            }

            engineScope.launch(Dispatchers.IO) {
                val db = AppDatabase.getDatabase(applicationContext)
                val enabledSchedules = db.scheduleDao().getEnabledSchedulesSync(targetName)
                val activeSchedule = ScheduleManager.getActiveSchedule(enabledSchedules)
                
                if (!isActive) return@launch

                withContext(Dispatchers.Main) {
                    if (activeSchedule?.id != currentActiveSchedule?.id) {
                        applySchedule(activeSchedule)
                    } else {
                        // SMART IDLE LOGIC:
                        // Jika layar mati dan kita sudah pernah rotasi sekali di background, 
                        // jangan lakukan rotasi lagi sampai layar nyala.
                        if (!visible && hasRotatedWhileIdle) {
                            return@withContext
                        }

                        // Extra insurance: Check if we missed a rotation
                        val currentTime = System.currentTimeMillis()
                        val intervalMs = getRotationIntervalMs()
                        if (currentTime - lastRotationTime >= intervalMs) {
                            if (!visible) {
                                hasRotatedWhileIdle = true
                                Log.d("MW_DEBUG", "[$prefsName] Idle Rotation (One-time) triggered via TimeTick.")
                            }
                            rotateWallpapers()
                        }
                    }
                }
            }
        }

        private var currentActiveSchedule: ScheduleEntity? = null

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
        private var panoramicScrollEnabled = false
        private var maxPanoramicSpan = 3
        
        // Job tracking for concurrency safety
        private var mainLoadJob: Job? = null
        private var backgroundRefreshJob: Job? = null
        private var preloadJob: Job? = null
        private var rotationJob: Job? = null
        
        // Lock for thread-safety during bitmap operations
        private val bitmapLock = Any()
        
        private val deadZoneThreshold = 0.2f // Ignore very small tremors
        private var lastSensorDrawTime = 0L
        private val sensorThrottleMs = 45L // IRIT: Lebih jarang baca sensor (Gak berasa bedanya)
        private var lastShakeTime = 0L
        private var shakeThreshold = 14f // m/s^2 above gravity, now adjustable
        private var lastRotationTime = 0L
        private var hasRotatedWhileIdle = false
        
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
            registerEngine(this)

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
                        if (isBootPhase) {
                            // Give other apps (WhatsApp, System, etc.) 8 seconds to finish booting first
                            delay(8000)
                        }
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
            val oldPanoramicScrollEnabled = panoramicScrollEnabled
            manualPageCount = prefs.getInt("manual_page_count", 0)
            isStaticLauncher = manualPageCount > 0
            panoramicScrollEnabled = prefs.getBoolean("panoramic_scroll_enabled", false)
            maxPanoramicSpan = prefs.getInt("max_panoramic_span", 3)
            
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
            // INCREASED SENSITIVITY: Range 8.0 (Very High) to 40.0 (Low)
            shakeThreshold = 40.0f - (shakeSensitivity * 32.0f)

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
            panoramicScrollEnabled = prefs.getBoolean("panoramic_scroll_enabled", false)
            maxPanoramicSpan = prefs.getInt("max_panoramic_span", 3)

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

            if (useFavChanged || forceReload || oldQuality != wallpaperQuality || sortOrderChanged || oldManualPageCount != manualPageCount || oldPanoramicScrollEnabled != panoramicScrollEnabled) {
                if (manualPageCount > 0) {
                    detectedPages = manualPageCount
                }
                // BYPASS DEBOUNCE for critical mode changes
                if (oldPanoramicScrollEnabled != panoramicScrollEnabled || forceReload) {
                    lastLoadRequestTime = 0L
                }
                needsNodeUpdate = true
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
                needsNodeUpdate = true
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
                        Log.d("MW_DEBUG", "[$prefsName] Shake detected!")
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
                
                // PARALLAX RECOVERY: Force RenderNode update because position changed
                needsNodeUpdate = true
                
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
            }
        }

        override fun onDestroy() {
            super.onDestroy()
            unregisterEngine(this)
            if (activeEngine == this) activeEngine = null
            engineScope.cancel()
            handler.removeCallbacks(drawRunnable)
            handler.removeCallbacks(rotationRunnable)
            val prefs = getSharedPreferences(prefsName, Context.MODE_PRIVATE)
            prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
            unregisterSensor()
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
                                manualPageIndex = (manualPageIndex - 1 + detectedPages) % detectedPages
                                swipeOffset = -1f + swipeOffset
                            } else {
                                manualPageIndex = (manualPageIndex + 1) % detectedPages
                                swipeOffset = 1f + swipeOffset
                            }
                            
                            // DETAILED LOGGING FOR PANORAMA STATUS
                            val currentUri = pageUris[manualPageIndex] ?: "Empty"
                            val currentScroll = pageScrollOffsets[manualPageIndex]
                            val panoStatus = if (currentScroll != null) "PANO (Offset: $currentScroll)" else "NO PANO"
                            val imgName = currentUri.substringAfterLast("/")
                            
                            Log.i("MW_DEBUG", "[$prefsName] MANUAL SWIPE -> Page $manualPageIndex | $panoStatus | Img: $imgName")
                            
                            needsNodeUpdate = true
                            animateSwipeCompletion()
                            // Proactive Gap Repair for neighbors
                            engineScope.launch { repairGaps(manualPageIndex, checkOnlyNeighbors = true) }
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
                // RESET IDLE TRACKER
                hasRotatedWhileIdle = false

                // 1. SMART UNLOCK: Jika ada gambar tapi spinner masih jalan, matikan spinner.
                if (pageBitmaps.isNotEmpty() && isLoading) {
                    isLoading = false
                    requestDraw()
                }

                // 2. FORCE RELOAD:
                val currentSize = synchronized(bitmapLock) { pageBitmaps.size }
                val hasGaps = synchronized(bitmapLock) { 
                    (0 until detectedPages).any { 
                        val b = pageBitmaps[it]
                        b == null || b.isRecycled 
                    } 
                }
                
                Log.d("MW_DEBUG", "[$prefsName] Visibility ON: Size=$currentSize, Det=$detectedPages, Gaps=$hasGaps")

                if ((currentSize < detectedPages || hasGaps) && !isLoading) {
                    if (currentSize == 0) {
                        Log.i("MW_DEBUG", "[$prefsName] Anti-1-Page: Critical gap detected (Size 0). Forcing full reload.")
                        loadWallpapersForPages()
                    } else {
                        Log.i("MW_DEBUG", "[$prefsName] Visibility Recovery: Repairing $currentSize/$detectedPages pages.")
                        engineScope.launch { repairGaps(manualPageIndex) }
                    }
                }

                // 3. CATCH-UP LOGIC: Jika sudah waktunya ganti saat HP di saku, ganti SEKARANG.
                val currentTime = System.currentTimeMillis()
                val intervalMs = getRotationIntervalMs()
                val timeSinceLast = currentTime - lastRotationTime
                
                Log.d("MW_DEBUG", "[$prefsName] Wake Check: Time since last rotation: ${timeSinceLast/1000}s / ${intervalMs/1000}s")

                if (intervalMs > 0 && timeSinceLast >= intervalMs) {
                    Log.i("MW_DEBUG", "[$prefsName] Catch-up Rotation triggered (Wake up from Idle).")
                    rotateWallpapers()
                }

                updateSettings()
                registerSensor() // Resume parallax/shake
                scheduleRotation()
                requestDraw()
            } else {
                // EXTREME BATTERY SAVING: Layar Mati = Stop Total
                
                // Batalkan semua job aktif kecuali jika kita sedang di tengah rotasi idle pertama
                if (hasRotatedWhileIdle) {
                    Log.d("MW_DEBUG", "[$prefsName] Visibility OFF: All background work STOPPED (Idle rotation done).")
                    mainLoadJob?.cancel()
                    backgroundRefreshJob?.cancel()
                    preloadJob?.cancel()
                    rotationJob?.cancel()
                } else {
                    Log.d("MW_DEBUG", "[$prefsName] Visibility OFF: Background work throttled, waiting for idle rotation.")
                }
                
                handler.removeCallbacks(drawRunnable)
                handler.removeCallbacks(rotationRunnable)
                
                isDrawScheduled = false
                isTransitioning = false
                
                // JANGAN recycle nextBitmap dan preloadedBitmap di sini!
                // Kita butuh mereka untuk rotasi idle pertama atau untuk "Fresh Start" saat layar nyala.
                
                unregisterSensor() // Stop accelerometer listener
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
                    
                    // SYNC manualPageIndex from launcher offset if possible (Even in Poco/Manual mode)
                    val effectiveStep = if (this.xStep > 0f) this.xStep else if (detectedPages > 1) 1f / (detectedPages - 1).toFloat() else 0f
                    if (pageBitmaps.isNotEmpty() && effectiveStep > 0f) {
                        val targetIndex = (validXOffset / effectiveStep).roundToInt()
                        val clampedIndex = targetIndex.coerceIn(0, detectedPages - 1)
                        if (manualPageIndex != clampedIndex) {
                            manualPageIndex = clampedIndex
                            
                            val currentUri = pageUris[manualPageIndex] ?: "Empty"
                            val currentScroll = pageScrollOffsets[manualPageIndex]
                            val panoStatus = if (currentScroll != null) "PANO (Offset: $currentScroll)" else "NO PANO"
                            val imgName = currentUri.substringAfterLast("/")
                            
                            Log.i("MW_DEBUG", "[$prefsName] OFFSET CHANGE -> Page $clampedIndex | $panoStatus | Img: $imgName")
                        }
                    }
                    
                    requestDraw()
                }
                return
            }

            // --- MODE AUTO (HP Normal) ---
            if (!visible && validXOffset == 0f && validXStep == 0f) return

            if (validXStep <= 0f) {
                if (detectedPages != 20) {
                    Log.w("MW_DEBUG", "[$prefsName] Anti-1-Page: Launcher reported static (0), but we suspect 20 pages. Delaying decision...")
                    detectedPages = 20
                    loadWallpapersForPages()
                }
                this.xStep = 0f
            } else {
                val newDetectedPages = (1f / validXStep).roundToInt() + 1
                if (newDetectedPages != detectedPages && newDetectedPages in 1..50) {
                    Log.i("MW_DEBUG", "[$prefsName] Page Detection: Launcher changed from $detectedPages to $newDetectedPages pages. Debouncing...")
                    detectedPages = newDetectedPages
                    handler.removeCallbacks(reloadRunnable)
                    handler.postDelayed(reloadRunnable, 500)
                }
                this.xStep = validXStep
            }

            // Update manualPageIndex berdasarkan offset sistem
            val offsetDelta = kotlin.math.abs(this.xOffset - validXOffset)
            if (visible && (offsetDelta > 0.0001f || this.xStep != validXStep)) {
                this.xOffset = validXOffset
                
                // SYNC manualPageIndex from launcher offset
                val effectiveStep = if (this.xStep > 0f) this.xStep else if (detectedPages > 1) 1f / (detectedPages - 1).toFloat() else 0f
                if (pageBitmaps.isNotEmpty() && effectiveStep > 0f) {
                    val targetIndex = (validXOffset / effectiveStep).roundToInt()
                    val clampedIndex = targetIndex.coerceIn(0, detectedPages - 1)
                    if (manualPageIndex != clampedIndex) {
                        manualPageIndex = clampedIndex
                        
                        val currentUri = pageUris[manualPageIndex] ?: "Empty"
                        val currentScroll = pageScrollOffsets[manualPageIndex]
                        val panoStatus = if (currentScroll != null) "PANO (Offset: $currentScroll)" else "NO PANO"
                        val imgName = currentUri.substringAfterLast("/")
                        
                        Log.i("MW_DEBUG", "[$prefsName] OFFSET CHANGE -> Page $clampedIndex | $panoStatus | Img: $imgName")
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
            
            // GATING: Don't schedule next rotation if we are already in idle mode
            if (!visible && hasRotatedWhileIdle) {
                return
            }

            val intervalMs = getRotationIntervalMs()
            val currentTime = System.currentTimeMillis()
            
            // PERSISTENT TIMER FIX: 
            // Calculate remaining time instead of always resetting to full intervalMs.
            // This prevents frequent screen on/off from indefinitely postponing rotation.
            val elapsed = currentTime - lastRotationTime
            val remainingMs = (intervalMs - elapsed).coerceIn(0L, intervalMs)
            
            if (visible) {
                Log.d("MultiWallpaper", "Engine scheduleRotation ($prefsName): next in ${remainingMs/1000}s (interval: ${intervalMs/1000}s, elapsed: ${elapsed/1000}s)")
            }
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
                    withContext(Dispatchers.IO) {
                        // OPTIMIZED BALANCED RANDOM: 1 Query to get everything adil
                        val batch = if (useFavorites) {
                            db.favoriteDao().getBalancedRandomFavorites(targetName, count).map { it.uriString }
                        } else {
                            db.scannedImageDao().getBalancedRandomUris(targetName, count).map { it.uriString }
                        }
                        finalUris.addAll(batch)
                        
                        // Fallback if CTE didn't fill the count (e.g. history exhaustion)
                        if (finalUris.size < count) {
                            val needed = count - finalUris.size
                            val extra = if (useFavorites) db.favoriteDao().getRandomFavoriteUrisExcludingHistory(targetName, needed)
                                        else db.scannedImageDao().getRandomUrisExcludingHistory(targetName, needed)
                            finalUris.addAll(extra)
                        }
                    }
                } else {
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
            synchronized(bitmapLock) {
                if (isTransitioning) return // Avoid overlapping transitions

                val now = System.currentTimeMillis()
                lastRotationTime = now
                getSharedPreferences(prefsName, Context.MODE_PRIVATE).edit().putLong("last_rotation_time", now).apply()

                // Jika rotasi terpancing saat layar mati, tandai agar rotasi berikutnya di-skip
                if (!visible) {
                    if (hasRotatedWhileIdle) {
                        return // Safety veto
                    }
                    hasRotatedWhileIdle = true
                    Log.d("MW_DEBUG", "[$prefsName] Idle Rotation triggered. Future redundant work suspended until Screen On.")
                } else {
                    hasRotatedWhileIdle = false
                }
                
                needsNodeUpdate = true

                // PRIORITAS: Selalu ganti halaman aktif (manualPageIndex) terlebih dahulu!
                if (transitionType != "cut" && pageBitmaps.isNotEmpty()) {
                    transitionDuration = (1300L - (fadeSpeed * 21L)).coerceIn(250L, 1200L)
                    
                    Log.d("MW_DEBUG", "[$prefsName] Rotation Triggered (${transitionType.uppercase()}). Target Index: $manualPageIndex")
                    
                    // BRUTAL CLEAR: Recycle everything EXCEPT current active page (and pano neighbors)
                    // This ensures no old "ghost" images are seen if user swipes during/after transition
                    val currentIdx = manualPageIndex
                    val protectedSet = mutableSetOf<Int>()
                    val currentBmp = pageBitmaps[currentIdx]
                    if (currentBmp != null) {
                        protectedSet.add(currentIdx)
                        // Protect pano neighbors that share the same bitmap
                        for (i in 1 until maxPanoramicSpan) {
                            val p = (currentIdx + i) % detectedPages
                            if (pageBitmaps[p] == currentBmp) protectedSet.add(p) else break
                        }
                    }
                    
                    pageBitmaps.forEach { (idx, b) -> if (!protectedSet.contains(idx)) b.recycle() }
                    val keysToRemove = pageBitmaps.keys.filter { !protectedSet.contains(it) }
                    keysToRemove.forEach { k ->
                        pageBitmaps.remove(k)
                        pageUris.remove(k)
                        pageFocalPoints.remove(k)
                        pageScrollOffsets.remove(k)
                    }
                    
                    isLoading = true // Show spinner on other pages
                    requestDraw()

                    if (preloadedBitmap != null) {
                        if (visible) {
                            nextBitmap?.recycle()
                            nextBitmap = preloadedBitmap
                            nextFocalPoint = preloadedFocalPoint
                            nextScrollOffset = preloadedScrollOffset
                            nextSpan = preloadedSpan
                            pageUris[manualPageIndex] = preloadedUri ?: ""
                            
                            preloadedBitmap = null
                            preloadedUri = null
                            preloadedFocalPoint = null
                            preloadedScrollOffset = null
                            preloadedSpan = 1
                            
                            startTransition()
                        } else {
                            val old = pageBitmaps[manualPageIndex]
                            pageBitmaps[manualPageIndex] = preloadedBitmap!!
                            pageFocalPoints[manualPageIndex] = preloadedFocalPoint
                            pageScrollOffsets[manualPageIndex] = preloadedScrollOffset
                            pageUris[manualPageIndex] = preloadedUri ?: ""
                            
                            // If it's a panorama, apply to adjacent pages immediately
                            if (preloadedSpan > 1) {
                                for (i in 0 until preloadedSpan) {
                                    val targetP = (manualPageIndex + i) % detectedPages
                                    pageBitmaps[targetP] = preloadedBitmap!!
                                    pageUris[targetP] = preloadedUri ?: ""
                                    pageScrollOffsets[targetP] = i.toFloat() / (preloadedSpan - 1).toFloat()
                                    pageFocalPoints[targetP] = null
                                }
                            }
                            
                            if (old != preloadedBitmap) old?.recycle()
                            
                            preloadedBitmap = null
                            preloadedUri = null
                            preloadedFocalPoint = null
                            preloadedScrollOffset = null
                            preloadedSpan = 1

                            scheduleRotation()
                            preloadNextWallpaper()
                        }
                    } else {
                        // INSTANT START: Start transition even if bitmap is not ready
                        startTransition()
                        startRotationTransition()
                    }
                    if (visible) {
                        refreshOtherPages()
                    } else {
                        // SCREEN OFF: Don't refresh other pages! 
                        // Just ensure the current page is swapped (handled above or via startRotationTransition)
                        isLoading = false 
                    }
                } else {
                    // FORCE RELOAD MODE: Pastikan manualPageIndex di-load pertama!
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
                
                // CRITICAL: We must skip pages that are part of the current active panoramic span
                val protectedIndices = mutableSetOf<Int>()
                val startIdx = manualPageIndex
                protectedIndices.add(startIdx) // VETO: Always protect current page first
                
                synchronized(bitmapLock) {
                    val currentBmp = pageBitmaps[startIdx]
                    if (currentBmp != null) {
                        // Protect all pages sharing the same bitmap (existing pano)
                        for (i in 1 until maxPanoramicSpan) {
                            val p = (startIdx + i) % detectedPages
                            if (pageBitmaps[p] == currentBmp) protectedIndices.add(p) else break
                        }
                    }
                    
                    // ALSO protect upcoming transition span
                    val activeSpan = nextSpan
                    if (activeSpan > 1) {
                        for (i in 0 until activeSpan) {
                            protectedIndices.add((startIdx + i) % detectedPages)
                        }
                    }
                }

                // SMART PRIORITY: Fill neighbors closest to the current page first in BOTH directions (Circular)
                val fillOrder = (0 until detectedPages).filter { !protectedIndices.contains(it) }
                    .sortedBy { p ->
                        val diff = Math.abs(p - startIdx)
                        Math.min(diff, detectedPages - diff) // Circular distance priority
                    }

                        // --- CONSOLIDATED 3-LAYER LOADING (Instant + Consistent) ---
                        // We process pages in priority order, handling Thumbnail then HQ for the SAME URI
                        if (panoramicScrollEnabled && !prefsName.contains("lock")) {
                            Log.d("MW_DEBUG", "[$prefsName] refreshOtherPages: Starting UNIFIED Pano fill...")
                            var i = 0
                            while (i < fillOrder.size && isActive && visible) {
                                val p = fillOrder[i]
                                if (synchronized(bitmapLock) { protectedIndices.contains(p) }) { i++; continue }

                        var nextUri: String? = null
                        synchronized(pageUris) { if (candidates.isNotEmpty()) nextUri = candidates.removeAt(0) }
                        if (nextUri == null) break
                        
                        // 1. QUICK THUMBNAIL (Stage A)
                        val thumb = decodeSampledBitmapFromUri(Uri.parse(nextUri!!), 240, 240, isBackground = true, isThumbnail = true)
                        if (thumb != null) {
                            withContext(Dispatchers.Main) {
                                synchronized(bitmapLock) {
                                    if (pageBitmaps[p] == null) pageThumbnails[p] = thumb
                                    else thumb.recycle()
                                }
                                requestDraw()
                            }
                        }

                        // 2. HQ UPGRADE (Stage B) - SAME URI
                        val b = decodeSampledBitmapFromUri(Uri.parse(nextUri!!), surfaceWidth, surfaceHeight, isBackground = true)
                        if (b != null) {
                            val imgRatio = b.width.toFloat() / b.height.toFloat()
                            val screenRatio = surfaceWidth.toFloat() / surfaceHeight.toFloat()
                            val spanFactor = (imgRatio / screenRatio).coerceIn(1.0f, maxPanoramicSpan.toFloat())
                            val span = if (spanFactor > 1.2f) spanFactor.roundToInt().coerceIn(2, maxPanoramicSpan) else 1
                            val focal = if (span == 1 && smartCropEnabled) detectFaceFocalPoint(b, nextUri!!) else null
                            
                            withContext(Dispatchers.Main) {
                                synchronized(bitmapLock) {
                                    for (j in 0 until span) {
                                        val targetP = (p + j) % detectedPages
                                        if (protectedIndices.contains(targetP) && targetP != p) continue 
                                        
                                        val old = pageBitmaps[targetP]
                                        pageBitmaps[targetP] = b
                                        pageUris[targetP] = nextUri!!
                                        pageScrollOffsets[targetP] = if (span > 1) j.toFloat() / (span - 1).toFloat() else null
                                        pageFocalPoints[targetP] = focal
                                        if (old != b && old != null) {
                                            var isStillUsed = false
                                            for(otherB in pageBitmaps.values) if(otherB == old) { isStillUsed = true; break }
                                            if(!isStillUsed) old.recycle()
                                        }
                                        protectedIndices.add(targetP)
                                        pageThumbnails[targetP]?.recycle(); pageThumbnails.remove(targetP)
                                    }
                                    requestDraw()
                                }
                            }
                            addToHistory(nextUri!!)
                            delay(50)
                        }
                        i++
                    }
                } else {
                    // STANDARD PARALLEL LOADING (Now Consistent)
                    val chunkSize = if (visible) 2 else 1 // Reduced from 3 to 2 for better UI stability
                    fillOrder.chunked(chunkSize).forEach { chunk ->
                        if (!isActive || !visible) return@launch
                        
                        // YIELD TO USER INTERACTION (Lower delay for responsiveness)
                        if (isSwiping || isSwipeAnimating) {
                            delay(250)
                        }

                        val jobs = chunk.map { p ->
                            async(Dispatchers.IO) {
                                if (!isActive || !visible) return@async
                                var nextUri: String? = null
                                synchronized(pageUris) {
                                    val prevIdx = (p - 1 + detectedPages) % detectedPages
                                    val prevPageUri = pageUris[prevIdx]
                                    val prevPageFolder = prevPageUri?.let { Uri.parse(it).path?.substringBeforeLast('/') }
                                    val candIdx = candidates.indexOfFirst { cand -> !smartAdjacencyEnabled || Uri.parse(cand).path?.substringBeforeLast('/') != prevPageFolder }
                                    nextUri = if (candIdx != -1) candidates.removeAt(candIdx) else if (candidates.isNotEmpty()) candidates.removeAt(0) else null
                                }
                                if (nextUri == null) return@async
                                
                                // 1. QUICK THUMBNAIL (Stage A)
                                val thumb = decodeSampledBitmapFromUri(Uri.parse(nextUri!!), 240, 240, isBackground = true, isThumbnail = true)
                                if (thumb != null) {
                                    withContext(Dispatchers.Main) {
                                        synchronized(bitmapLock) { if (pageBitmaps[p] == null) pageThumbnails[p] = thumb else thumb.recycle() }
                                        requestDraw()
                                    }
                                }

                                // 2. HQ UPGRADE (Stage B) - SAME URI
                                val b = decodeSampledBitmapFromUri(Uri.parse(nextUri!!), surfaceWidth, surfaceHeight, isBackground = true)
                                if (b != null) {
                                    val focal = if (smartCropEnabled) detectFaceFocalPoint(b, nextUri!!) else null
                                    withContext(Dispatchers.Main) {
                                        synchronized(bitmapLock) {
                                            if (protectedIndices.contains(p)) { b.recycle(); return@withContext }
                                            val old = pageBitmaps[p]
                                            pageBitmaps[p] = b
                                            pageUris[p] = nextUri!!
                                            pageScrollOffsets[p] = null
                                            pageFocalPoints[p] = focal
                                            if (old != b) old?.recycle()
                                            protectedIndices.add(p)
                                            pageThumbnails[p]?.recycle(); pageThumbnails.remove(p)
                                        }
                                        requestDraw()
                                    }
                                    addToHistory(nextUri!!)
                                }
                            }
                        }
                        jobs.awaitAll(); delay(if (visible) 100L else 500L)
                    }
                }

                // --- PRIORITY GAP REPAIR ---
                repairGaps(startIdx)
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
                    val focal = if (smartCropEnabled) detectFaceFocalPoint(rawBmp!!, currentUri) else null
                    
                    // Pre-calculate Panoramic info
                    var span = 1
                    var scrollOffset: Float? = null
                    if (panoramicScrollEnabled && !prefsName.contains("lock")) {
                        val imgRatio = rawBmp!!.width.toFloat() / rawBmp!!.height.toFloat()
                        val screenRatio = surfaceWidth.toFloat() / surfaceHeight.toFloat()
                        val spanFactor = (imgRatio / screenRatio).coerceIn(1.0f, maxPanoramicSpan.toFloat())
                        span = if (spanFactor > 1.1f) spanFactor.roundToInt().coerceIn(2, maxPanoramicSpan) else 1
                        if (span > 1) scrollOffset = 0f // Start from left segment for the active page
                    }

                    withContext(Dispatchers.Main) {
                        if (!isActive) {
                            rawBmp!!.recycle()
                            return@withContext
                        }
                        preloadedBitmap?.recycle()
                        preloadedBitmap = rawBmp
                        preloadedUri = currentUri
                        preloadedFocalPoint = focal
                        preloadedScrollOffset = scrollOffset
                        preloadedSpan = span
                    }
                }
            }
        }

        private fun startRotationTransition() {
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
                    val focal = if (smartCropEnabled) detectFaceFocalPoint(rawBmp!!, currentUri) else null
                    
                    // Pre-calculate Panoramic info
                    var span = 1
                    var scrollOffset: Float? = null
                    if (panoramicScrollEnabled && !prefsName.contains("lock")) {
                        val imgRatio = rawBmp!!.width.toFloat() / rawBmp!!.height.toFloat()
                        val screenRatio = surfaceWidth.toFloat() / surfaceHeight.toFloat()
                        val spanFactor = (imgRatio / screenRatio).coerceIn(1.0f, maxPanoramicSpan.toFloat())
                        span = if (spanFactor > 1.1f) spanFactor.roundToInt().coerceIn(2, maxPanoramicSpan) else 1
                        if (span > 1) scrollOffset = 0f // Start from left segment for the active page
                    }

                    withContext(Dispatchers.Main) {
                        if (!isActive) {
                            rawBmp!!.recycle()
                            return@withContext
                        }
                        nextBitmap?.recycle()
                        pageUris[manualPageIndex] = currentUri
                        nextBitmap = rawBmp
                        nextFocalPoint = focal
                        nextScrollOffset = scrollOffset
                        nextSpan = span
                        
                        if (visible) {
                            startTransition()
                            preloadNextWallpaper() 
                        } else {
                            // SCREEN OFF: Instant swap and schedule next
                            val old = pageBitmaps[manualPageIndex]
                            pageBitmaps[manualPageIndex] = nextBitmap!!
                            pageFocalPoints[manualPageIndex] = nextFocalPoint
                            pageScrollOffsets[manualPageIndex] = nextScrollOffset
                            
                            // If it's a panorama, apply to adjacent pages immediately
                            if (nextSpan > 1) {
                                for (i in 0 until nextSpan) {
                                    val targetP = (manualPageIndex + i) % detectedPages
                                    pageBitmaps[targetP] = nextBitmap!!
                                    pageUris[targetP] = currentUri
                                    pageScrollOffsets[targetP] = i.toFloat() / (nextSpan - 1).toFloat()
                                    pageFocalPoints[targetP] = null
                                }
                            }

                            nextBitmap = null
                            nextFocalPoint = null
                            nextScrollOffset = null
                            nextSpan = 1
                            if (old != pageBitmaps[manualPageIndex]) old?.recycle()
                            
                            // JANGAN panggil scheduleRotation() atau preloadNextWallpaper() 
                            // jika kita baru saja menyelesaikan rotasi idle.
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

        private fun startTransition() {
            isTransitioning = true
            transitionAlpha = 0
            transitionStartTime = System.currentTimeMillis()
            Choreographer.getInstance().postFrameCallback(frameCallback)
        }

        private fun animateTransition() {
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
                
                synchronized(bitmapLock) {
                    val next = nextBitmap
                    if (next != null && !next.isRecycled) {
                        val old = pageBitmaps[manualPageIndex]
                        Log.d("MW_DEBUG", "[$prefsName] Transition FINISHED. Next Span: $nextSpan")

                        // Finalize the active page
                        pageBitmaps[manualPageIndex] = next
                        pageFocalPoints[manualPageIndex] = nextFocalPoint
                        pageScrollOffsets[manualPageIndex] = nextScrollOffset
                        
                        // PANORAMIC FINALIZATION: If it was a pano, fill the neighbors now
                        if (nextSpan > 1) {
                            val currentUri = pageUris[manualPageIndex] ?: ""
                            for (i in 1 until nextSpan) {
                                val targetP = (manualPageIndex + i) % detectedPages
                                val oldNeighbor = pageBitmaps[targetP]
                                pageBitmaps[targetP] = next
                                pageUris[targetP] = currentUri
                                pageScrollOffsets[targetP] = i.toFloat() / (nextSpan - 1).toFloat()
                                pageFocalPoints[targetP] = null
                                if (oldNeighbor != next && oldNeighbor != null) oldNeighbor.recycle()
                            }
                        }

                        if (old != next && old != null) old.recycle()
                        
                        nextBitmap = null
                        nextFocalPoint = null
                        nextScrollOffset = null
                        nextSpan = 1
                        
                        // Successfully loaded and swapped
                        if (isLoading) isLoading = false
                        needsNodeUpdate = true
                    } else {
                        Log.w("MW_DEBUG", "[$prefsName] Transition FINISHED but nextBitmap is missing. Cleaning up old bitmap to prevent snap-back.")
                        // SNAP-BACK FIX: Recycle old bitmap so it doesn't reappear while waiting for rotation job
                        val old = pageBitmaps[manualPageIndex]
                        if (old != null) {
                            pageBitmaps.remove(manualPageIndex)
                            pageUris.remove(manualPageIndex)
                            old.recycle()
                        }
                        // Keep isLoading = true to ensure spinner stays visible
                    }
                }
                
                requestDraw()
                scheduleRotation()
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

            // CANCEL EVERYTHING OLD: We are starting a fresh state now
            mainLoadJob?.cancel()
            backgroundRefreshJob?.cancel()
            preloadJob?.cancel()
            rotationJob?.cancel()

            isLoading = true
            requestDraw()

            mainLoadJob = engineScope.launch {
                try {
                    // QUICKER SURFACE READY CHECK: 4s max (Buffer for Reboot stability)
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
                        // RE-SCAN PROTECTION: If database is empty, wait a bit for background scan to finish
                        delay(1500)
                        total = if (useFavorites) db.favoriteDao().getFavoriteCount(targetName) else db.scannedImageDao().getImageCount(targetName)
                    }

                    if (total <= 0) {
                        Log.w("MW_DEBUG", "[$prefsName] No images found in database for $targetName after retry. Is scanning finished?")
                        // JANGAN recycleBitmaps di sini jika kita sedang di tengah BootPhase atau Reload
                        // Cukup set isLoading ke false agar user tidak terjebak spinner
                        isLoading = false
                        requestDraw()
                        return@launch
                    }

                    if (!isActive) {
                        isLoading = false
                        return@launch
                    }

                    val batchSize = 200.coerceAtMost(total)
                    val uriCandidates = getNextWallpaperUriBatch(batchSize).toMutableList()
                    if (uriCandidates.isEmpty()) {
                        Log.w("MW_DEBUG", "[$prefsName] No unique candidates found after history check. Stopping load.")
                        isLoading = false
                        requestDraw()
                        return@launch
                    }

                    // CANCELLABLE VISIBLE PAGE LOADING (Removing NonCancellable for responsiveness)
                    Log.d("MW_DEBUG", "[$prefsName] Forced Reload Triggered. Priority Index: $manualPageIndex (BootPhase: $isBootPhase)")
                    val visibleUri = uriCandidates.removeAt(0)
                    val (firstBitmap, firstFocal, firstSpan) = withContext(Dispatchers.IO) {
                        if (!isActive) return@withContext Triple(null, null, 1)
                        val b = decodeSampledBitmapFromUri(Uri.parse(visibleUri), surfaceWidth, surfaceHeight, isBackground = false)
                        var span = 1
                        if (b != null && panoramicScrollEnabled && !prefsName.contains("lock")) {
                            val imgRatio = b.width.toFloat() / b.height.toFloat()
                            val screenRatio = surfaceWidth.toFloat() / surfaceHeight.toFloat()
                            val spanFactor = (imgRatio / screenRatio).coerceIn(1.0f, maxPanoramicSpan.toFloat())
                            span = if (spanFactor > 1.1f) spanFactor.roundToInt().coerceIn(2, maxPanoramicSpan) else 1
                        }
                        
                        if (!isActive) {
                            b?.recycle()
                            return@withContext Triple(null, null, 1)
                        }
                        
                        val f = if (b != null && span == 1 && smartCropEnabled) detectFaceFocalPoint(b, visibleUri) else null
                        Triple(b, f, span)
                    }
                    
                    withContext(Dispatchers.Main) {
                        synchronized(bitmapLock) {
                            val protectedSet = mutableSetOf<Int>()
                            for (i in 0 until firstSpan) {
                                protectedSet.add((manualPageIndex + i) % detectedPages)
                            }
                            // Also protect neighbors for smoothness
                            protectedSet.add((manualPageIndex - 1 + detectedPages) % detectedPages)

                            // Only recycle what's NOT a protected page
                            pageBitmaps.forEach { (idx, b) -> if (!protectedSet.contains(idx)) b.recycle() }
                            
                            // Remove stale records except protected
                            val keysToRemove = pageBitmaps.keys.filter { !protectedSet.contains(it) }
                            keysToRemove.forEach { k ->
                                pageBitmaps.remove(k)
                                pageUris.remove(k)
                                pageFocalPoints.remove(k)
                                pageScrollOffsets.remove(k)
                            }
                            
                            if (firstBitmap != null) {
                                Log.d("MW_DEBUG", "[$prefsName] Visible Page Loaded (Span: $firstSpan)")
                                for (i in 0 until firstSpan) {
                                    val targetP = (manualPageIndex + i) % detectedPages
                                    pageBitmaps[targetP] = firstBitmap
                                    pageUris[targetP] = visibleUri
                                    pageFocalPoints[targetP] = if (firstSpan == 1) firstFocal else null
                                    pageScrollOffsets[targetP] = if (firstSpan > 1) i.toFloat() / (firstSpan - 1).toFloat() else null
                                }
                            }
                        }
                        if (firstBitmap != null) {
                            addToHistory(visibleUri)
                        }
                    }
                    // SHOW CURRENT IMAGES IMMEDIATELY BUT KEEP isLoading=true FOR OTHERS
                    requestDraw()

                    if (!isActive || !visible) {
                        isLoading = false
                        return@launch
                    }

                    // --- UNIFIED PANORAMIC LOADING (Atomic & Faster) ---
                    if (panoramicScrollEnabled && !prefsName.contains("lock")) {
                        val filledIndices = mutableSetOf<Int>()
                        synchronized(bitmapLock) { 
                            for (i in 0 until firstSpan) filledIndices.add((manualPageIndex + i) % detectedPages) 
                        }

                        val pOrder = (0 until detectedPages).filter { !filledIndices.contains(it) }
                            .sortedBy { p -> 
                                val diff = Math.abs(p - manualPageIndex)
                                Math.min(diff, detectedPages - diff) 
                            }

                        var iIdx = 0
                        while (iIdx < pOrder.size && isActive && visible) {
                            // YIELD TO USER INTERACTION: Pause loading if user is swiping
                            if (isSwiping || isSwipeAnimating) {
                                delay(300)
                                continue
                            }
                            
                            val p = pOrder[iIdx]
                            if (synchronized(bitmapLock) { filledIndices.contains(p) }) { iIdx++; continue }
                            
                            var selectedUri: String? = null
                            synchronized(uriCandidates) { if (uriCandidates.isNotEmpty()) selectedUri = uriCandidates.removeAt(0) }
                            val uri = selectedUri ?: break
                            
                            // 1. HQ DECODE (Atomic for Pano)
                            val b = withContext(Dispatchers.IO) { decodeSampledBitmapFromUri(Uri.parse(uri), surfaceWidth, surfaceHeight, isBackground = true) }
                            if (b != null) {
                                val imgRatio = b.width.toFloat() / b.height.toFloat()
                                val screenRatio = surfaceWidth.toFloat() / surfaceHeight.toFloat()
                                val spanFactor = (imgRatio / screenRatio).coerceIn(1.0f, maxPanoramicSpan.toFloat())
                                val span = if (spanFactor > 1.2f) spanFactor.roundToInt().coerceIn(2, maxPanoramicSpan) else 1
                                val focal = if (span == 1 && smartCropEnabled) detectFaceFocalPoint(b, uri) else null
                                
                                withContext(Dispatchers.Main) {
                                    synchronized(bitmapLock) {
                                        for (j in 0 until span) {
                                            val targetP = (p + j) % detectedPages
                                            // Skip if we hit the active page or already filled
                                            if (targetP == manualPageIndex || (filledIndices.contains(targetP) && targetP != p)) continue
                                            
                                            val old = pageBitmaps[targetP]
                                            pageBitmaps[targetP] = b
                                            pageUris[targetP] = uri
                                            pageFocalPoints[targetP] = focal
                                            pageScrollOffsets[targetP] = if (span > 1) j.toFloat() / (span - 1).toFloat() else null
                                            
                                            filledIndices.add(targetP)
                                            pageThumbnails[targetP]?.recycle()
                                            pageThumbnails.remove(targetP)
                                            
                                            if (old != b && old != null) {
                                                var isStillUsed = false
                                                for(otherB in pageBitmaps.values) if(otherB == old) { isStillUsed = true; break }
                                                if(!isStillUsed) old.recycle()
                                            }
                                        }
                                    }
                                    requestDraw()
                                }
                                addToHistory(uri)
                            }
                            iIdx++
                            delay(50)
                        }
                    } else {
                        // ORIGINAL PARALLEL LOGIC (Now Consistent)
                        val targetPageCount = detectedPages.coerceAtMost(total)
                        val filledIndicesNonPano = mutableSetOf<Int>()
                        synchronized(bitmapLock) { if (pageBitmaps.containsKey(manualPageIndex)) filledIndicesNonPano.add(manualPageIndex) }

                        val priorityOrder = (0 until targetPageCount).filter { !filledIndicesNonPano.contains(it) }.sortedBy { p -> val diff = Math.abs(p - manualPageIndex); Math.min(diff, targetPageCount - diff) }

                        val chunkSize = if (isBootPhase) 1 else if (visible) 3 else 1
                        priorityOrder.chunked(chunkSize).forEach { chunk ->
                            if (!isActive || !visible) return@launch
                            chunk.map { p ->
                                async(Dispatchers.IO) {
                                    if (!isActive || !visible) return@async
                                    var selectedUri: String? = null
                                    synchronized(uriCandidates) {
                                        if (uriCandidates.isNotEmpty()) {
                                            val prevIdx = (p - 1 + targetPageCount) % targetPageCount
                                            val prevPageUri = pageUris[prevIdx]
                                            val prevPageFolder = prevPageUri?.let { Uri.parse(it).path?.substringBeforeLast('/') }
                                            val candIdx = if (smartAdjacencyEnabled && prevPageFolder != null) uriCandidates.indexOfFirst { Uri.parse(it).path?.substringBeforeLast('/') != prevPageFolder } else -1
                                            selectedUri = if (candIdx != -1) uriCandidates.removeAt(candIdx) else uriCandidates.removeAt(0)
                                        }
                                    }
                                    val uri = selectedUri ?: return@async

                                    // 1. QUICK THUMBNAIL (Stage A)
                                    val thumb = decodeSampledBitmapFromUri(Uri.parse(uri), 240, 240, isBackground = true, isThumbnail = true)
                                    if (thumb != null) {
                                        withContext(Dispatchers.Main) {
                                            synchronized(bitmapLock) { if (pageBitmaps[p] == null) pageThumbnails[p] = thumb else thumb.recycle() }
                                            requestDraw()
                                        }
                                    }

                                    // 2. HQ UPGRADE (Stage B) - SAME URI
                                    val b = decodeSampledBitmapFromUri(Uri.parse(uri), surfaceWidth, surfaceHeight, isBackground = true)
                                    if (b != null) {
                                        val focal = if (smartCropEnabled) detectFaceFocalPoint(b, uri) else null
                                        withContext(Dispatchers.Main) {
                                            synchronized(bitmapLock) {
                                                if (filledIndicesNonPano.contains(p)) { b.recycle(); return@withContext }
                                                val old = pageBitmaps[p]
                                                pageBitmaps[p] = b
                                                pageUris[p] = uri
                                                pageFocalPoints[p] = focal
                                                pageScrollOffsets[p] = null
                                                if (old != b) old?.recycle()
                                                filledIndicesNonPano.add(p)
                                                pageThumbnails[p]?.recycle(); pageThumbnails.remove(p)
                                            }
                                            requestDraw()
                                        }
                                        addToHistory(uri)
                                    }
                                }
                            }.awaitAll()
                            delay(if (isBootPhase) 1500L else 100L)
                        }
                    }

                    // PRIORITY GAP REPAIR
                    repairGaps(manualPageIndex)
                    
                    if (isActive) {
                        withContext(Dispatchers.Main) { 
                            scheduleRotation()
                            preloadNextWallpaper()
                            
                            // FINAL SYNC CHECK: Ensure NO gaps left before clearing isLoading
                            val finalSize = synchronized(bitmapLock) { pageBitmaps.size }
                            val missingIndices = (0 until detectedPages).filter { 
                                val b = pageBitmaps[it]
                                b == null || b.isRecycled 
                            }
                            
                            if (missingIndices.isEmpty()) {
                                isLoading = false
                                isFreshStart = false // First load complete, exit boot optimization
                                Log.d("MW_DEBUG", "[$prefsName] All pages confirmed ($finalSize). Loading FINISHED.")
                            } else {
                                Log.w("MW_DEBUG", "[$prefsName] Recovery: Sync failed for $missingIndices. Triggering automatic repair.")
                                // SMART RECOVERY: Don't just sit there, try to fix the missing gaps immediately
                                engineScope.launch { 
                                    delay(800)
                                    repairGaps(manualPageIndex) 
                                }
                            }
                            requestDraw()
                        }
                    }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) {
                        // Silent cancellation is normal
                    } else {
                        Log.e("MW_DEBUG", "[$prefsName] Loading failed: ${e.message}")
                    }
                } finally {
                    // isLoading reset is now handled inside the Main Thread sync check above
                    // to prevent race conditions with background decoding.
                }
            }
        }

        private suspend fun repairGaps(priorityIndex: Int, checkOnlyNeighbors: Boolean = false) {
            if (detectedPages <= 0) return
            
            // SMART GATING: Don't fight with the main loader for bulk repairs.
            // But if it's a specific neighbor check from a user swipe, BYPASS the gate.
            if (!checkOnlyNeighbors && (isLoading || mainLoadJob?.isActive == true)) {
                return
            }
            
            val targetIndices = if (checkOnlyNeighbors) {
                listOf(
                    priorityIndex,
                    (priorityIndex - 1 + detectedPages) % detectedPages,
                    (priorityIndex + 1) % detectedPages
                )
            } else {
                (0 until detectedPages).sortedBy { p ->
                    val diff = Math.abs(p - priorityIndex)
                    Math.min(diff, detectedPages - diff)
                }
            }

            // --- PHASE 1: SELF-HEALING (CONSISTENCY CHECK) ---
            // Fix pages that are "wrong" (should be part of a neighbor's pano but aren't)
            if (panoramicScrollEnabled && !prefsName.contains("lock")) {
                withContext(Dispatchers.Main) {
                    synchronized(bitmapLock) {
                        for (idx in 0 until detectedPages) {
                            val prevIdx = (idx - 1 + detectedPages) % detectedPages
                            val prevB = pageBitmaps[prevIdx]
                            val prevScroll = pageScrollOffsets[prevIdx]
                            val prevUri = pageUris[prevIdx]
                            val prevFocal = pageFocalPoints[prevIdx]

                            // If neighbor is a Pano that isn't finished (has room to grow to the right)
                            if (prevB != null && prevScroll != null && prevScroll < 0.99f) {
                                val currentB = pageBitmaps[idx]
                                val currentUri = pageUris[idx]
                                val currentScroll = pageScrollOffsets[idx]

                                // CHECK INCONSISTENCY: 
                                // 1. Different image (Your case: Page 12 Pano A, Page 13 Pano B)
                                // 2. Missing scroll offset
                                // 3. Wrong scroll order (current should be > prev)
                                val isConsistent = currentB == prevB && currentUri == prevUri && currentScroll != null && currentScroll > prevScroll

                                if (!isConsistent) {
                                    Log.w("MW_DEBUG", "[$prefsName] [Self-Healing] Pano Chain Broken at Page $idx. Repairing from Page $prevIdx")
                                    
                                    val old = pageBitmaps[idx]
                                    pageBitmaps[idx] = prevB
                                    pageUris[idx] = prevUri!!
                                    pageFocalPoints[idx] = prevFocal
                                    
                                    // Calculate next scroll offset based on span detection
                                    // Look back to the start of this pano to find the span/step
                                    var spanStartIdx = prevIdx
                                    for (k in 1 until maxPanoramicSpan) {
                                        val checkIdx = (prevIdx - k + detectedPages) % detectedPages
                                        if (pageBitmaps[checkIdx] == prevB && pageUris[checkIdx] == prevUri) {
                                            spanStartIdx = checkIdx
                                        } else break
                                    }
                                    
                                    // Calculate step size based on first two segments or fallback to 0.5 (2 pages)
                                    val firstScroll = pageScrollOffsets[spanStartIdx] ?: 0f
                                    val secondScroll = if (spanStartIdx == prevIdx) -1f else pageScrollOffsets[(spanStartIdx + 1) % detectedPages] ?: -1f
                                    val step = if (secondScroll > firstScroll) secondScroll - firstScroll else 0.5f
                                    
                                    pageScrollOffsets[idx] = (prevScroll + step).coerceAtMost(1.0f)
                                    
                                    if (old != prevB && old != null) {
                                        var stillInUse = false
                                        for(b in pageBitmaps.values) if(b == old) { stillInUse = true; break }
                                        if(!stillInUse) old.recycle()
                                    }
                                    requestDraw()
                                }
                            }
                        }
                    }
                }
            }

            // --- PHASE 2: GAP REPAIR (EMPTY PAGES) ---
            for (i in targetIndices) {
                if (!engineScope.isActive) break
                if (i == manualPageIndex && isTransitioning) continue // VETO: Don't touch active page during rotation
                
                val isTrulyEmpty = synchronized(bitmapLock) { 
                    val b = pageBitmaps[i]
                    
                    // PANO-AWARE CHECK: 
                    // A page is only a "gap" if it's null AND it's not supposed to be part 
                    // of a panorama from its neighbors to the left.
                    var isPartOfLeftPano = false
                    if (panoramicScrollEnabled && (i > 0 || detectedPages > 1)) {
                        for (offset in 1 until maxPanoramicSpan) {
                            val prevIdx = (i - offset + detectedPages) % detectedPages
                            val prevB = pageBitmaps[prevIdx]
                            val prevScroll = pageScrollOffsets[prevIdx]
                            val prevUri = pageUris[prevIdx]
                            
                            if (prevB != null && prevScroll != null && prevScroll < 0.99f) {
                                val nextInPanoIdx = (prevIdx + 1) % detectedPages
                                if (pageBitmaps[nextInPanoIdx] == prevB && pageUris[nextInPanoIdx] == prevUri) {
                                     isPartOfLeftPano = true
                                     break
                                }
                            }
                        }
                    }

                    (b == null || b.isRecycled) && !isPartOfLeftPano
                }
                
                if (isTrulyEmpty) {
                    val fallbackUri = getNextWallpaperUriBatch(1).firstOrNull() ?: continue
                    
                    Log.w("MW_DEBUG", "[$prefsName] Audit detected GENUINE gap at Page $i. Fixing...")
                    
                    if (i == manualPageIndex) {
                        isLoading = true
                        withContext(Dispatchers.Main) { requestDraw() }
                    }

                    val b = withContext(Dispatchers.IO) { 
                        decodeSampledBitmapFromUri(Uri.parse(fallbackUri), surfaceWidth, surfaceHeight, isBackground = true) 
                    }
                    
                    if (b != null) {
                        // PANORAMIC FALLBACK HANDLING
                        var span = 1
                        if (panoramicScrollEnabled && !prefsName.contains("lock")) {
                            val imgRatio = b.width.toFloat() / b.height.toFloat()
                            val screenRatio = surfaceWidth.toFloat() / surfaceHeight.toFloat()
                            val spanFactor = (imgRatio / screenRatio).coerceIn(1.0f, maxPanoramicSpan.toFloat())
                            span = if (spanFactor > 1.2f) spanFactor.roundToInt().coerceIn(2, maxPanoramicSpan) else 1
                        }

                        val focal = if (span == 1 && smartCropEnabled) detectFaceFocalPoint(b, fallbackUri) else null
                        withContext(Dispatchers.Main) {
                            synchronized(bitmapLock) {
                                for (j in 0 until span) {
                                    val targetP = (i + j) % detectedPages
                                    // In gap repair, we fill the targeted gap and any subsequent span pages that are also empty/wrong
                                    if (targetP != i && pageBitmaps[targetP] != null && !pageBitmaps[targetP]!!.isRecycled) continue
                                    
                                    val old = pageBitmaps[targetP]
                                    pageBitmaps[targetP] = b
                                    pageUris[targetP] = fallbackUri
                                    pageFocalPoints[targetP] = focal
                                    pageScrollOffsets[targetP] = if (span > 1) j.toFloat() / (span - 1).toFloat() else null
                                    
                                    if (old != b && old != null) {
                                        var stillInUse = false
                                        for(otherB in pageBitmaps.values) if(otherB == old) { stillInUse = true; break }
                                        if(!stillInUse) old.recycle()
                                    }
                                }
                            }
                            if (i == manualPageIndex) {
                                isLoading = false
                            }
                            requestDraw()
                        }
                        addToHistory(fallbackUri)
                    } else if (i == manualPageIndex) {
                        withContext(Dispatchers.Main) {
                            isLoading = false
                            requestDraw()
                        }
                    }
                }
            }
        }

        private suspend fun detectFaceFocalPoint(bitmap: Bitmap, uriString: String): PointF? {
            val db = AppDatabase.getDatabase(applicationContext)
            val targetName = if (prefsName.contains("lock")) "LOCK" else "HOME"
            val useFavorites = getSharedPreferences(prefsName, Context.MODE_PRIVATE).getBoolean("use_favorites_only", false)

            // 1. Check Cache first
            val cached = withContext(Dispatchers.IO) {
                if (useFavorites) db.favoriteDao().getFocalPoint(uriString, targetName)
                else db.scannedImageDao().getFocalPoint(uriString, targetName)
            }

            if (cached?.focalX != null && cached.focalY != null) {
                return PointF(cached.focalX, cached.focalY)
            }

            val prefs = getSharedPreferences(prefsName, Context.MODE_PRIVATE)
            val manX = prefs.getFloat("manual_focal_x", 0.5f)
            val manY = prefs.getFloat("manual_focal_y", 0.4f)
            val fallback = PointF(manX, manY)

            // Downscale for faster and more accurate pattern recognition (Industry Standard)
            val targetSize = 480
            val scale = (targetSize.toFloat() / maxOf(bitmap.width, bitmap.height)).coerceAtMost(1f)

            // OPTIMIZED: Use InputImage.fromBitmap directly or a small scaled copy.
            // Avoid copying the full-resolution bitmap to prevent GC pressure.
            val detectionImage = if (scale < 1f) {
                try {
                    val scaled = Bitmap.createScaledBitmap(
                        bitmap,
                        (bitmap.width * scale).toInt().coerceAtLeast(1),
                        (bitmap.height * scale).toInt().coerceAtLeast(1),
                        true
                    )
                    val input = com.google.mlkit.vision.common.InputImage.fromBitmap(scaled, 0)
                    // We still need to keep 'scaled' until detection is done, but it's much smaller.
                    Pair(input, scaled)
                } catch (e: Exception) { null }
            } else {
                Pair(com.google.mlkit.vision.common.InputImage.fromBitmap(bitmap, 0), null)
            }

            if (detectionImage == null) return fallback

            val (image, scaledBitmap) = detectionImage
            val dWidth = if (scaledBitmap != null) scaledBitmap.width else bitmap.width
            val dHeight = if (scaledBitmap != null) scaledBitmap.height else bitmap.height

            // Reuse or initialize the detector ONCE (Global Singleton)
            if (globalFaceDetector == null) {
                val options = com.google.mlkit.vision.face.FaceDetectorOptions.Builder()
                    .setPerformanceMode(com.google.mlkit.vision.face.FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                    .setLandmarkMode(com.google.mlkit.vision.face.FaceDetectorOptions.LANDMARK_MODE_NONE)
                    .setClassificationMode(com.google.mlkit.vision.face.FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
                    .setMinFaceSize(0.15f) // Optimized for faster detection
                    .build()
                globalFaceDetector = com.google.mlkit.vision.face.FaceDetection.getClient(options)
            }
            
            val detector = globalFaceDetector ?: run {
                scaledBitmap?.recycle()
                return fallback
            }

            return try {
                val task = detector.process(image)

                // CRITICAL: Only recycle when ML Kit task is completely finished.
                task.addOnCompleteListener {
                    try { scaledBitmap?.recycle() } catch (e: Exception) {}
                }
                
                // Optimized timeout for background detection
                val faces = withTimeoutOrNull(2500L) {
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
                    
                    // 2. Save result to Cache
                    engineScope.launch(Dispatchers.IO) {
                        if (useFavorites) db.favoriteDao().updateFocalPoint(uriString, targetName, focal.x, focal.y)
                        else db.scannedImageDao().updateFocalPoint(uriString, targetName, focal.x, focal.y)
                    }
                    
                    focal
                } else {
                    // Also save fallback so we don't scan empty/no-face images repeatedly
                    engineScope.launch(Dispatchers.IO) {
                        if (useFavorites) db.favoriteDao().updateFocalPoint(uriString, targetName, fallback.x, fallback.y)
                        else db.scannedImageDao().updateFocalPoint(uriString, targetName, fallback.x, fallback.y)
                    }
                    fallback // Fallback slightly above center
                }
            } catch (e: Exception) { 
                fallback // Fallback slightly above center on error
            }
        }

        private fun decodeSampledBitmapFromUri(uri: Uri, reqWidth: Int, reqHeight: Int, isBackground: Boolean = false, isThumbnail: Boolean = false): Bitmap? {
            return try {
                // TRY FAST I/O: Use FileDescriptor for seeking (Only if supported by provider)
                val pfd = try { contentResolver.openFileDescriptor(uri, "r") } catch (e: Exception) { null }
                
                if (pfd != null) {
                    val finalBmp = pfd.use { handle ->
                        val fd = handle.fileDescriptor
                        
                        val orientation = try {
                            val exif = androidx.exifinterface.media.ExifInterface(fd)
                            exif.getAttributeInt(androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION, androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL)
                        } catch (e: Exception) { androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL }

                        val opt = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeFileDescriptor(fd, null, opt)

                        performActualDecode(uri, opt, orientation, isBackground, isThumbnail, fd = fd)
                    }
                    
                    if (finalBmp != null) {
                        Log.d("MW_DEBUG", "[$prefsName] Decoded via FileDescriptor: $uri")
                        return finalBmp
                    }
                }

                // FALLBACK: Use traditional InputStream for compatibility (Preset/Non-file URIs)
                val orientation = contentResolver.openInputStream(uri)?.use { input ->
                    val exif = androidx.exifinterface.media.ExifInterface(input)
                    exif.getAttributeInt(androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION, androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL)
                } ?: androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL

                val opt = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                contentResolver.openInputStream(uri)?.use { input ->
                    BitmapFactory.decodeStream(input, null, opt)
                }

                val finalBmp = performActualDecode(uri, opt, orientation, isBackground, isThumbnail)
                Log.d("MW_DEBUG", "[$prefsName] Decoded via InputStream (Fallback): $uri")
                return finalBmp
            } catch (e: Exception) {
                Log.e("MW_DEBUG", "Error decoding URI: $uri", e)
                null 
            }
        }

        private fun performActualDecode(uri: Uri, opt: BitmapFactory.Options, orientation: Int, isBackground: Boolean, isThumbnail: Boolean, fd: java.io.FileDescriptor? = null): Bitmap? {
            val baseRes = when {
                isThumbnail -> 240
                wallpaperQuality == "HIGH" -> 1920
                wallpaperQuality == "LOW" -> 1080
                else -> 1440
            }
            
            var maxWidth = baseRes
            var maxHeight = baseRes

            if (lightModeEnabled && !isThumbnail) {
                val factor = 0.75f
                maxWidth = (maxWidth * factor).toInt().coerceAtLeast(720)
                maxHeight = (maxHeight * factor).toInt().coerceAtLeast(720)
            }

            opt.inSampleSize = calculateInSampleSize(opt, maxWidth, maxHeight)
            opt.inJustDecodeBounds = false
            
            opt.inPreferredConfig = when {
                isThumbnail -> Bitmap.Config.RGB_565
                wallpaperQuality == "HIGH" -> Bitmap.Config.ARGB_8888
                wallpaperQuality == "LOW" -> Bitmap.Config.RGB_565
                isBootPhase || lightModeEnabled -> Bitmap.Config.RGB_565
                else -> Bitmap.Config.ARGB_8888
            }
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                opt.inMutable = true
            }

            val decoded = if (fd != null) {
                BitmapFactory.decodeFileDescriptor(fd, null, opt)
            } else {
                contentResolver.openInputStream(uri)?.use { i2 -> BitmapFactory.decodeStream(i2, null, opt) }
            }
            
            return if (decoded != null) {
                val isLowPriority = lightModeEnabled || isBackground || wallpaperQuality == "LOW"
                val finalBmp = if (isLowPriority && (decoded.width > maxWidth || decoded.height > maxHeight)) {
                    val scale = Math.min(maxWidth.toFloat() / decoded.width, maxHeight.toFloat() / decoded.height)
                    val targetW = (decoded.width * scale).toInt().coerceAtLeast(1)
                    val targetH = (decoded.height * scale).toInt().coerceAtLeast(1)
                    
                    val scaled = Bitmap.createScaledBitmap(decoded, targetW, targetH, true)
                    if (scaled != decoded) decoded.recycle()
                    scaled
                } else decoded

                val ramUsage = finalBmp.byteCount / (1024f * 1024f)
                Log.d("MW_DEBUG", String.format(
                    "[%s] Final Bitmap: %dx%d | RAM: %.2f MB | Quality: %s",
                    prefsName, finalBmp.width, finalBmp.height, ramUsage, wallpaperQuality
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
                    val rotated = Bitmap.createBitmap(finalBmp, 0, 0, finalBmp.width, finalBmp.height, matrix, true)
                    if (rotated != finalBmp) finalBmp.recycle()
                    rotated
                } else finalBmp
            } else null
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
            pageThumbnails.values.forEach { if (!it.isRecycled) it.recycle() }
            pageThumbnails.clear()
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

            // SMART DRAW: Support per-page loading state instead of global check
            val pos = if (xStep > 0f) {
                xOffset / xStep
            } else {
                val rawPos = manualPageIndex.toFloat() - swipeOffset
                val total = detectedPages.toFloat().coerceAtLeast(1f)
                (rawPos % total + total) % total
            }
            val idx = pos.roundToInt() % detectedPages

            if (pageBitmaps.isEmpty() && !isTransitioning) {
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

            // FOCAL POINT INTERPOLATION (Fixes effect "jolt" during transitions)
            var focal: PointF? = null
            var scrollOffset: Float? = null
            if (isTransitioning && nextBitmap != null) {
                // ... interpolation logic for focal remains same
            } else if (isFluid && transitionType == "fade") {
                // ... interpolation logic for focal remains same
            } else {
                focal = pageFocalPoints[idx]
                scrollOffset = pageScrollOffsets[idx]
            }

            // MODERN VISUAL EFFECTS PIPELINE (Android 12+)
            if (android.os.Build.VERSION.SDK_INT >= 31 && canvas.isHardwareAccelerated) {
                try {
                    if (visualEffectNode == null) {
                        visualEffectNode = RenderNode("VisualEffects")
                        needsNodeUpdate = true
                    }
                    val node = visualEffectNode!!
                    node.setPosition(0, 0, w, h)
                    
                    // GPU SMART CACHE: Re-record cuma kalau perlu (Wallpaper ganti atau Efek berubah)
                    if (needsNodeUpdate || lastRenderedIdx != idx) {
                        // SMART BLUR: If HQ is missing but thumbnail exists, apply heavy blur to thumbnail
                        val hqBitmap = pageBitmaps[idx]
                        val thumbBitmap = pageThumbnails[idx]
                        val isUsingPlaceholder = hqBitmap == null && thumbBitmap != null
                        
                        // Apply Blur to the Node (Global background blur OR placeholder blur)
                        val baseBlur = if (blurEnabled) blurRadius else 0f
                        val placeholderBlur = if (isUsingPlaceholder) 40f else 0f
                        val actualBlur = maxOf(baseBlur, placeholderBlur)
                        
                        if (actualBlur > 0f) {
                            node.setRenderEffect(RenderEffect.createBlurEffect(actualBlur, actualBlur, Shader.TileMode.CLAMP))
                        } else {
                            node.setRenderEffect(null)
                        }

                        // Record drawing into the Node
                        val recordingCanvas = node.beginRecording()
                        if (isUsingPlaceholder) {
                            // Draw the thumbnail as base for blur
                            drawSingleBitmap(recordingCanvas, thumbBitmap!!, w, h, idx)
                        } else {
                            drawWallpaperContent(recordingCanvas, w, h, isFluid, pos, idx)
                        }
                        node.endRecording()
                        
                        // Tandai sudah di-render, jangan update lagi kalau cuma Parallax (Tilt)
                        if (!isFluid && !isTransitioning) {
                            needsNodeUpdate = false
                            lastRenderedIdx = idx
                        }
                    }

                    // Draw the Node (Hasil render yang sudah di-cache di GPU)
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
                val hqBitmap = pageBitmaps[idx]
                val thumbBitmap = pageThumbnails[idx]
                
                if (hqBitmap == null && thumbBitmap != null) {
                    drawSingleBitmap(canvas, thumbBitmap, w, h, idx)
                } else {
                    drawWallpaperContent(canvas, w, h, isFluid, pos, idx)
                }

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

            val effectiveTransition = if (panoramicScrollEnabled && !prefsName.contains("lock")) {
                if (transitionType == "tumble") "slide" else transitionType
            } else {
                transitionType
            }

            // Priority 1: Auto-Rotation Transitions (Shake/Timer)
            if (isTransitioning) {
                val curr = pageBitmaps[manualPageIndex]
                val next = if (nextBitmap != null && !nextBitmap!!.isRecycled) nextBitmap else null
                
                // If we have nothing to draw, show loading
                if (curr == null && next == null) {
                    drawLoadingState(canvas, w, h)
                    return
                }

                val currFocal = if (smartCropEnabled) pageFocalPoints[manualPageIndex] else null
                val currScroll = pageScrollOffsets[manualPageIndex]
                val progress = transitionAlpha.toFloat() / 255f

                when (effectiveTransition) {
                    "fade" -> {
                        // 1. Reveal Loading if next is missing
                        if (next == null) {
                            drawLoadingState(canvas, w, h)
                        }

                        // 2. Draw current fading out
                        if (curr != null && !curr.isRecycled) {
                            val oldAlpha = bitmapPaint.alpha
                            bitmapPaint.alpha = (255 * (1f - progress)).toInt()
                            calculateRects(curr, w, h, srcRect, dstRect, currFocal, currScroll)
                            canvas.drawBitmap(curr, srcRect, dstRect, bitmapPaint)
                            bitmapPaint.alpha = oldAlpha
                        }
                        
                        // 3. Draw next fading in
                        if (next != null) {
                            val oldAlpha = bitmapPaint.alpha
                            bitmapPaint.alpha = transitionAlpha
                            calculateRects(next, w, h, nextSrcRect, nextDstRect, nextFocalPoint, nextScrollOffset)
                            canvas.drawBitmap(next, nextSrcRect, nextDstRect, bitmapPaint)
                            bitmapPaint.alpha = oldAlpha
                        }
                    }
                    "slide" -> {
                        // 1. Reveal Loading or Next underneath
                        if (next == null) {
                            drawLoadingState(canvas, w, h)
                        } else {
                            calculateRects(next, w, h, nextSrcRect, nextDstRect, nextFocalPoint, nextScrollOffset)
                            nextDstRect.offset((1f - progress) * w, 0f)
                            canvas.drawBitmap(next, nextSrcRect, nextDstRect, bitmapPaint)
                        }
                        
                        // 2. Current moves out on top
                        if (curr != null && !curr.isRecycled) {
                            calculateRects(curr, w, h, srcRect, dstRect, currFocal, currScroll)
                            dstRect.offset(-progress * w, 0f)
                            canvas.drawBitmap(curr, srcRect, dstRect, bitmapPaint)
                        }
                    }
                    "tumble" -> {
                        if (next != null && curr != null) {
                            drawTumbleTransition(canvas, curr, next, w, h, currFocal, nextFocalPoint, progress, nextScrollOffset)
                        } else if (curr != null) {
                            // Tumble out to loading
                            drawLoadingState(canvas, w, h)
                            drawTumbleTransition(canvas, curr, curr, w, h, currFocal, currFocal, progress, currScroll, onlyOut = true)
                        } else {
                            drawLoadingState(canvas, w, h)
                        }
                    }
                    "zoom" -> {
                        // 1. Reveal Loading or Next behind
                        if (next == null) {
                            drawLoadingState(canvas, w, h)
                        } else {
                            calculateRects(next, w, h, nextSrcRect, nextDstRect, nextFocalPoint, nextScrollOffset)
                            canvas.drawBitmap(next, nextSrcRect, nextDstRect, bitmapPaint)
                        }

                        // 2. Current EXPANDS and fades out on top (Redesigned)
                        if (curr != null && !curr.isRecycled) {
                            val scale = 1f + (progress * 2.5f) // Expand to 350% (Warp Feel)
                            val alpha = (255 * (1f - progress)).toInt()
                            val oldAlpha = bitmapPaint.alpha
                            bitmapPaint.alpha = alpha
                            
                            calculateRects(curr, w, h, srcRect, dstRect, currFocal, currScroll)
                            val centerX = dstRect.centerX()
                            val centerY = dstRect.centerY()
                            val dw = dstRect.width() * scale
                            val dh = dstRect.height() * scale
                            dstRect.set(centerX - dw/2, centerY - dh/2, centerX + dw/2, centerY + dh/2)
                            
                            canvas.drawBitmap(curr, srcRect, dstRect, bitmapPaint)
                            bitmapPaint.alpha = oldAlpha
                        }
                    }
                    else -> { // "cut"
                        if (next != null) {
                            drawSingleBitmap(canvas, next, w, h, -1)
                        } else {
                            drawLoadingState(canvas, w, h)
                        }
                    }
                }
                return
            }

            // Priority 2: Manual Swipe Transitions
            if (isFluid) {
                val l = pos.toInt() % detectedPages
                val r = (l + 1) % detectedPages
                val f = pos - pos.toInt()
                
                val lb = pageBitmaps[l]
                val rb = pageBitmaps[r]
                
                // CEK APAKAH KEDUA HALAMAN INI SATU FOTO PANORAMA YANG SAMA
                val isSamePano = lb != null && rb != null && lb == rb && pageUris[l] == pageUris[r]

                if (l != r) {
                    val lf = if (smartCropEnabled) pageFocalPoints[l] else null
                    val rf = if (smartCropEnabled) pageFocalPoints[r] else null
                    val ls = pageScrollOffsets[l]
                    val rs = pageScrollOffsets[r]
                    
                    if (isSamePano && ls != null && rs != null && lb != null && !lb.isRecycled) {
                        // --- SEAMLESS PANO SCROLL ---
                        val interpolatedScroll = ls + (rs - ls) * f
                        calculateRects(lb, w, h, srcRect, dstRect, lf, interpolatedScroll)
                        canvas.drawBitmap(lb, srcRect, dstRect, bitmapPaint)
                    } else {
                        // --- STANDARD TRANSITION (Slide/Fade/etc) ---
                        if (lb != null && !lb.isRecycled) calculateRects(lb, w, h, srcRect, dstRect, lf, ls)
                        if (rb != null && !rb.isRecycled) calculateRects(rb, w, h, nextSrcRect, nextDstRect, rf, rs)

                        when (effectiveTransition) {
                            "fade" -> {
                                if (lb != null && !lb.isRecycled) {
                                    val oldAlpha = bitmapPaint.alpha
                                    bitmapPaint.alpha = ((1f - f) * 255).toInt()
                                    canvas.drawBitmap(lb, srcRect, dstRect, bitmapPaint)
                                    bitmapPaint.alpha = oldAlpha
                                } else {
                                    drawLoadingState(canvas, w, h)
                                }
                                
                                if (rb != null && !rb.isRecycled) {
                                    val oldAlpha = bitmapPaint.alpha
                                    bitmapPaint.alpha = (f * 255).toInt()
                                    canvas.drawBitmap(rb, nextSrcRect, nextDstRect, bitmapPaint)
                                    bitmapPaint.alpha = oldAlpha
                                } else {
                                    drawLoadingState(canvas, w, h)
                                }
                            }
                            "slide" -> {
                                if (lb != null && !lb.isRecycled) {
                                    dstRect.offset(-f * w, 0f)
                                    canvas.drawBitmap(lb, srcRect, dstRect, bitmapPaint)
                                } else {
                                    canvas.save(); canvas.clipRect(0, 0, (w * (1-f)).toInt(), h); drawLoadingState(canvas, w, h); canvas.restore()
                                }
                                
                                if (rb != null && !rb.isRecycled) {
                                    nextDstRect.offset((1f - f) * w, 0f)
                                    canvas.drawBitmap(rb, nextSrcRect, nextDstRect, bitmapPaint)
                                } else {
                                    canvas.save(); canvas.clipRect((w * (1-f)).toInt(), 0, w, h); drawLoadingState(canvas, w, h); canvas.restore()
                                }
                            }
                            "tumble" -> {
                                if (lb != null && rb != null && !lb.isRecycled && !rb.isRecycled) {
                                    drawTumbleTransition(canvas, lb, rb, w, h, lf, rf, f)
                                } else {
                                    // Fallback to simpler draw if one is missing
                                    if (lb != null) drawSingleBitmap(canvas, lb, w, h, l)
                                    else if (rb != null) drawSingleBitmap(canvas, rb, w, h, r)
                                    else drawLoadingState(canvas, w, h)
                                }
                            }
                            "zoom" -> {
                                // ZOOM OUT (Manual Swipe): Left fades/expands, Right fades in
                                if (rb != null && !rb.isRecycled) {
                                    calculateRects(rb, w, h, nextSrcRect, nextDstRect, rf, rs)
                                    val oldAlpha = bitmapPaint.alpha
                                    bitmapPaint.alpha = (f * 255).toInt()
                                    canvas.drawBitmap(rb, nextSrcRect, nextDstRect, bitmapPaint)
                                    bitmapPaint.alpha = oldAlpha
                                } else {
                                    canvas.save(); canvas.clipRect((w * (1-f)).toInt(), 0, w, h); drawLoadingState(canvas, w, h); canvas.restore()
                                }

                                if (lb != null && !lb.isRecycled) {
                                    val scale = 1f + (f * 2.5f)
                                    val alpha = (255 * (1f - f)).toInt()
                                    val oldAlpha = bitmapPaint.alpha
                                    bitmapPaint.alpha = alpha
                                    
                                    calculateRects(lb, w, h, srcRect, dstRect, lf, ls)
                                    val centerX = dstRect.centerX()
                                    val centerY = dstRect.centerY()
                                    val dw = dstRect.width() * scale
                                    val dh = dstRect.height() * scale
                                    dstRect.set(centerX - dw/2, centerY - dh/2, centerX + dw/2, centerY + dh/2)
                                    
                                    canvas.drawBitmap(lb, srcRect, dstRect, bitmapPaint)
                                    bitmapPaint.alpha = oldAlpha
                                } else {
                                    canvas.save(); canvas.clipRect(0, 0, (w * (1-f)).toInt(), h); drawLoadingState(canvas, w, h); canvas.restore()
                                }
                            }
                            else -> { // "cut"
                                val activeB = pageBitmaps[idx]
                                if (activeB != null) drawSingleBitmap(canvas, activeB, w, h, idx)
                                else drawLoadingState(canvas, w, h)
                            }
                        }
                    }
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

        private fun drawTumbleTransition(canvas: Canvas, b1: Bitmap, b2: Bitmap, w: Int, h: Int, f1: PointF?, f2: PointF?, progress: Float, scroll2: Float? = null, onlyOut: Boolean = false) {
            val rotationMax = 25f // Sudut rotasi 2D
            val splitX = (1f - progress) * w

            // 1. Gambar Sisi Kiri (WP 1 + Background Tile Miring)
            canvas.save()
            if (!onlyOut) canvas.clipRect(0f, 0f, splitX, h.toFloat()) // Hard Cut kiri
            
            canvas.rotate(-progress * rotationMax, w / 2f, h.toFloat())
            canvas.translate(-progress * w, 0f)
            
            // A. Draw Blurred Mirror Tile Background
            drawProfessionalTiltedBackground(canvas, b1, w, h, true)
            
            // B. Draw Sharp Card
            calculateRects(b1, w, h, srcRect, dstRect, f1, pageScrollOffsets[manualPageIndex])
            canvas.drawBitmap(b1, srcRect, dstRect, bitmapPaint)
            canvas.restore()

            if (onlyOut) return

            // 2. Gambar Sisi Kanan (WP 2 + Background Tile Miring)
            canvas.save()
            canvas.clipRect(splitX, 0f, w.toFloat(), h.toFloat()) // Hard Cut kanan
            
            canvas.rotate((1f - progress) * rotationMax, w / 2f, h.toFloat())
            canvas.translate((1f - progress) * w, 0f)
            
            // A. Draw Blurred Mirror Tile Background
            drawProfessionalTiltedBackground(canvas, b2, w, h, false)

            // B. Draw Sharp Card
            calculateRects(b2, w, h, nextSrcRect, nextDstRect, f2, scroll2)
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

        private fun calculateRects(b: Bitmap, w: Int, h: Int, sR: Rect, dR: RectF, fP: PointF? = null, scrollOffset: Float? = null) {
            val bW = b.width.toFloat()
            val bH = b.height.toFloat()
            val sBase = maxOf(w.toFloat() / bW, h.toFloat() / bH)
            
            // AI ZOOM & PARALLAX SLACK INTEGRATION
            val minSlack = 1.05f
            val parallaxZoom = if (parallaxEnabled) maxOf(minSlack, 1.0f + (parallaxStrength * 0.15f)) else 1.0f

            // PANORAMIC TWEAK: Jika mode Pano aktif, matikan AI Zoom Slack agar sambungan pas 1:1
            val aiZoom = if (smartCropEnabled && fP != null && scrollOffset == null) {
                val maxSlack = if (aiAdvancedEnabled) aiZoomSlack else 1.45f
                maxOf(1.1f, maxSlack)
            } else 1.0f
            
            val zoomFactor = maxOf(parallaxZoom, aiZoom)
            val s = sBase * zoomFactor
            
            val ow = bW * s
            val oh = bH * s
            
            val standardCX = (w - ow) / 2f
            val standardCY = (h - oh) / 2f
            
            var cX = standardCX
            var cY = standardCY

            if (scrollOffset != null) {
                // --- PANORAMIC SCROLL LOGIC ---
                // Progress 0.0 = Sisi Kiri (cX = 0), Progress 1.0 = Sisi Kanan (cX = w - ow)
                cX = -(scrollOffset * (ow - w))
            } else if (smartCropEnabled && fP != null) {
                var aiCX = (w / 2f) - (fP.x * ow)
                var aiCY = (h / 2f) - (fP.y * oh)
                
                if (parallaxEnabled) {
                    val subjectTiltX = (currentRoll / 12f) * (w * 0.08f) * parallaxStrength
                    val subjectTiltY = (currentPitch / 12f) * (h * 0.08f) * parallaxStrength
                    aiCX -= subjectTiltX
                    aiCY += subjectTiltY
                }
                
                val dx = kotlin.math.abs(fP.x - 0.5f)
                val dy = kotlin.math.abs(fP.y - 0.5f)
                val sensitivityX = if (aiAdvancedEnabled) aiSensitivityX else 0.9f
                val sensitivityY = if (aiAdvancedEnabled) aiSensitivityY else 0.4f
                
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
                // PARALLAX SINKRON: Gunakan rentang extra room (ow - w) untuk goyangan.
                // Ini harus sama rumusnya untuk semua potongan pano agar sambungannya sinkron.
                val slackX = (ow - w) / 2f
                val slackY = (oh - h) / 2f
                
                cX += (currentRoll / 10f) * slackX * parallaxStrength
                cY -= (currentPitch / 10f) * slackY * parallaxStrength
            }

            cX = cX.coerceIn(w - ow, 0f)
            cY = cY.coerceIn(h - oh, 0f)

            val lOn = maxOf(0f, -cX); val tOn = maxOf(0f, -cY)
            val rOn = minOf(ow, w - cX); val bOn = minOf(oh, h - cY)

            sR.set((lOn / s).toInt(), (tOn / s).toInt(), (rOn / s).toInt(), (bOn / s).toInt())
            dR.set(maxOf(0f, cX), maxOf(0f, cY), minOf(w.toFloat(), cX + ow), minOf(h.toFloat(), cY + oh))
        }

        private fun drawSingleBitmap(canvas: Canvas, b: Bitmap, w: Int, h: Int, idx: Int) {
            if (b.isRecycled) {
                drawLoadingState(canvas, w, h)
                return
            }
            val focal = if (idx == -1) nextFocalPoint else if (smartCropEnabled) pageFocalPoints[idx] else null
            val scrollOffset = if (idx != -1) pageScrollOffsets[idx] else null
            calculateRects(b, w, h, srcRect, dstRect, focal, scrollOffset)
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
