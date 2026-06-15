package gustian.multiwallpaper

import android.content.Context
import android.content.SharedPreferences
import android.graphics.*
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import android.os.Handler
import android.os.Looper
import android.service.wallpaper.WallpaperService
import android.util.Log
import android.view.Choreographer
import android.view.SurfaceHolder
import android.view.animation.DecelerateInterpolator
import gustian.multiwallpaper.data.AppDatabase
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import kotlin.coroutines.resume
import kotlin.math.sqrt
import kotlin.math.roundToInt
import kotlin.random.Random

class MultiWallpaperLiveService : WallpaperService() {

    override fun onCreateEngine(): Engine {
        val engine = MultiWallpaperEngine()
        engine.setOffsetNotificationsEnabled(true)
        return engine
    }

    inner class MultiWallpaperEngine : Engine(), SensorEventListener {
        private val handler = Handler(Looper.getMainLooper())
        private val engineScope = CoroutineScope(Dispatchers.Main + Job())
        private var visible = false
        private var xOffset = 0f
        private var xStep = 0f
        
        private var lastX = 0f
        private var manualPageIndex = 0
        private val swipeThreshold = 150f

        private var lastTapTime: Long = 0
        private val doubleTapThreshold = 500L

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
            // Always ensure the next rotation is scheduled
            scheduleRotation()
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
        private var useFavoritesOnly = false
        private var currentRoll = 0f
        private var currentPitch = 0f
        private val smoothingFactor = 0.05f // Stronger LPF to ignore jitter
        private var detectedPages = 20 // Default to 20 for launchers that don't report xStep (HyperOS)
        private val recentHistory = LinkedHashSet<String>()
        private val MAX_HISTORY = 50
        
        // Job tracking for concurrency safety
        private var mainLoadJob: Job? = null
        private var backgroundRefreshJob: Job? = null
        private var preloadJob: Job? = null
        private var rotationJob: Job? = null
        
        // Lock for thread-safety during bitmap operations
        private val bitmapLock = Any()
        
        private val deadZoneThreshold = 0.2f // Ignore very small tremors
        private var lastSensorDrawTime = 0L
        private val sensorThrottleMs = 50L // Aggressive throttling (~20fps) to save power
        private var lastShakeTime = 0L
        private val shakeThreshold = 14f // m/s^2 above gravity
        
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

        // Persistent Face Detector to avoid reloading models (saves massive RAM/CPU)
        private var faceDetector: com.google.mlkit.vision.face.FaceDetector? = null

        private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            updateSettings()
        }

        override fun onCreate(surfaceHolder: SurfaceHolder?) {
            super.onCreate(surfaceHolder)
            setTouchEventsEnabled(true)
            
            sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
            accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

            val db = AppDatabase.getDatabase(this@MultiWallpaperLiveService)
            engineScope.launch {
                db.folderDao().getAllFolders().collectLatest {
                    loadWallpapersForPages()
                }
            }
            
            val prefs = getSharedPreferences("multi_wallpaper_prefs", Context.MODE_PRIVATE)
            prefs.registerOnSharedPreferenceChangeListener(prefsListener)
            
            updateSettings()
        }

        private fun updateSettings() {
            val prefs = getSharedPreferences("multi_wallpaper_prefs", Context.MODE_PRIVATE)
            val newUseFav = prefs.getBoolean("use_favorites_only", false)
            val useFavChanged = useFavoritesOnly != newUseFav
            
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
            
            useFavoritesOnly = newUseFav
            parallaxEnabled = prefs.getBoolean("parallax_enabled", false)
            parallaxStrength = prefs.getFloat("parallax_strength", 0.5f)
            shakeEnabled = prefs.getBoolean("shake_enabled", false)
            smartCropEnabled = prefs.getBoolean("smart_crop_enabled", true)
            lightModeEnabled = prefs.getBoolean("light_mode_enabled", false)
            aiAdvancedEnabled = prefs.getBoolean("ai_advanced_enabled", false)
            aiZoomSlack = prefs.getFloat("ai_zoom_slack", 1.45f)
            aiSensitivityX = prefs.getFloat("ai_sensitivity_x", 0.9f)
            aiSensitivityY = prefs.getFloat("ai_sensitivity_y", 0.4f)
            transitionType = prefs.getString("transition_type", "slide") ?: "slide"
            fadeSpeed = prefs.getInt("fade_speed", 15)
            
            if (useFavChanged || forceReload) {
                loadWallpapersForPages()
            } else if (oldSmartCrop != smartCropEnabled || 
                       oldAiAdv != aiAdvancedEnabled || 
                       oldZoom != aiZoomSlack || 
                       oldSensX != aiSensitivityX || 
                       oldSensY != aiSensitivityY) {
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

        override fun onDestroy() {
            super.onDestroy()
            engineScope.cancel()
            handler.removeCallbacks(drawRunnable)
            handler.removeCallbacks(rotationRunnable)
            val prefs = getSharedPreferences("multi_wallpaper_prefs", Context.MODE_PRIVATE)
            prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
            unregisterSensor()
            faceDetector?.close()
            faceDetector = null
            recycleBitmaps()
        }

        override fun onTouchEvent(event: android.view.MotionEvent) {
            super.onTouchEvent(event)
            val numBitmaps = pageBitmaps.size
            if (numBitmaps <= 0) return

            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    lastX = event.x
                }
                android.view.MotionEvent.ACTION_UP -> {
                    val currTime = System.currentTimeMillis()
                    val prefs = getSharedPreferences("multi_wallpaper_prefs", Context.MODE_PRIVATE)
                    val doubleTapEnabled = prefs.getBoolean("double_tap_enabled", true)
                    
                    val deltaX = event.x - lastX
                    val isSwipe = kotlin.math.abs(deltaX) > swipeThreshold

                    if (doubleTapEnabled && !isSwipe && (currTime - lastTapTime) < doubleTapThreshold) {
                        rotateWallpapers() // Trigger change
                        lastTapTime = 0
                    } else {
                        lastTapTime = currTime
                    }

                    if (isSwipe && detectedPages > 1) {
                        manualPageIndex = if (deltaX > 0) {
                            if (manualPageIndex > 0) manualPageIndex - 1 else detectedPages - 1
                        } else {
                            if (manualPageIndex < detectedPages - 1) manualPageIndex + 1 else 0
                        }
                        requestDraw()
                    }
                }
            }
        }

        override fun onVisibilityChanged(visible: Boolean) {
            this.visible = visible
            if (visible) {
                updateSettings()
                scheduleRotation()
                requestDraw()
            } else {
                // NUCLEAR CLEANUP: Stop all active background processes immediately
                mainLoadJob?.cancel()
                backgroundRefreshJob?.cancel()
                preloadJob?.cancel()
                rotationJob?.cancel()
                
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

        override fun onOffsetsChanged(xOffset: Float, yOffset: Float, xStep: Float, yStep: Float, xPixels: Int, yPixels: Int) {
            val validXOffset = if (xOffset.isNaN()) 0f else xOffset
            val validXStep = if (xStep.isNaN()) 0f else xStep
            
            if (validXStep > 0f) {
                val newDetectedPages = (1f / validXStep).roundToInt() + 1
                if (newDetectedPages != detectedPages && newDetectedPages in 1..50) {
                    detectedPages = newDetectedPages
                    // Use a shorter delay to avoid frequent reloads if user is still adjusting
                    handler.removeCallbacks(reloadRunnable)
                    handler.postDelayed(reloadRunnable, 500)
                }
            }

            // Only redraw if the offset changed significantly to save power
            val offsetDelta = kotlin.math.abs(this.xOffset - validXOffset)
            if (offsetDelta > 0.0001f || this.xStep != validXStep) {
                this.xOffset = validXOffset
                this.xStep = validXStep
                
                val numBitmaps = pageBitmaps.size
                if (numBitmaps > 0) {
                    // CRITICAL: Accurate page mapping for launchers like HyperOS
                    val targetIndex = if (validXStep > 0f) (validXOffset / validXStep).roundToInt()
                                     else (validXOffset * (detectedPages - 1)).roundToInt()
                    val clampedIndex = targetIndex.coerceIn(0, numBitmaps - 1)
                    if (manualPageIndex != clampedIndex) {
                        manualPageIndex = clampedIndex
                        requestDraw()
                    }
                }
            }
        }

        private val reloadRunnable = Runnable { loadWallpapersForPages() }

        override fun onSurfaceChanged(holder: SurfaceHolder?, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            this.surfaceWidth = width
            this.surfaceHeight = height
            try {
                val wm = getSystemService(Context.WALLPAPER_SERVICE) as android.app.WallpaperManager
                wm.suggestDesiredDimensions(width * 5, height)
            } catch (e: Exception) {}
            requestDraw()
        }

        private fun scheduleRotation() {
            handler.removeCallbacks(rotationRunnable)
            val intervalMs = getRotationIntervalMs()
            handler.postDelayed(rotationRunnable, intervalMs)
        }

        private suspend fun getSingleRandomUri(): String? {
            return try {
                // Use the application-wide database instance to avoid connection leaks
                val db = AppDatabase.getDatabase(applicationContext)
                val prefs = getSharedPreferences("multi_wallpaper_prefs", Context.MODE_PRIVATE)
                val useFavorites = prefs.getBoolean("use_favorites_only", false)
                
                val total = if (useFavorites) db.favoriteDao().getFavoriteCount() 
                            else db.scannedImageDao().getImageCount()
                
                if (total <= 0) return null
                
                var finalUri: String? = null
                // Try to find one not in recent history (fair randomness)
                repeat(5) {
                    val randomIndex = Random.nextInt(total)
                    val candidate = if (useFavorites) db.favoriteDao().getUriAtPosition(randomIndex)
                                    else db.scannedImageDao().getUriAtPosition(randomIndex)
                    
                    if (candidate != null) {
                        if (!recentHistory.contains(candidate)) {
                            finalUri = candidate
                            return@repeat
                        }
                        finalUri = candidate // Fallback
                    }
                }
                
                finalUri?.let {
                    recentHistory.add(it)
                    if (recentHistory.size > MAX_HISTORY) {
                        val iterator = recentHistory.iterator()
                        if (iterator.hasNext()) {
                            iterator.next()
                            iterator.remove()
                        }
                    }
                }
                finalUri
            } catch (e: Exception) { 
                Log.e("MultiWallpaper", "Database error in getSingleRandomUri", e)
                null 
            }
        }

        private fun rotateWallpapers() {
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
                            if (preloadedUri != null) pageUris[manualPageIndex] = preloadedUri!!
                            
                            preloadedBitmap = null
                            preloadedUri = null
                            preloadedFocalPoint = null
                            
                            startFade()
                        } else {
                            // SCREEN OFF: Respect timer, but swap instantly to free memory
                            val old = pageBitmaps[manualPageIndex]
                            pageBitmaps[manualPageIndex] = preloadedBitmap!!
                            pageFocalPoints[manualPageIndex] = preloadedFocalPoint
                            if (preloadedUri != null) pageUris[manualPageIndex] = preloadedUri!!
                            
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
                val db = AppDatabase.getDatabase(this@MultiWallpaperLiveService)
                val total = db.scannedImageDao().getImageCount()
                if (total <= 0 || detectedPages <= 1) return@launch
                
                (0 until detectedPages).filter { it != manualPageIndex }.forEach { p ->
                    if (!isActive) return@launch
                    val nextUri = getSingleRandomUri() ?: return@forEach
                    
                    val b = decodeSampledBitmapFromUri(Uri.parse(nextUri), surfaceWidth, surfaceHeight)
                    if (b != null) {
                        val focal = if (smartCropEnabled) detectFaceFocalPoint(b) else null
                        withContext(Dispatchers.Main) {
                            val old = pageBitmaps[p]
                            pageBitmaps[p] = b
                            pageUris[p] = nextUri
                            pageFocalPoints[p] = focal
                            old?.recycle()
                            requestDraw()
                        }
                    }
                    delay(100) // Yield to prevent CPU spikes and keep UI smooth
                }
            }
        }

        private fun preloadNextWallpaper() {
            preloadJob?.cancel()
            preloadJob = engineScope.launch(Dispatchers.IO) {
                val nextUri = getSingleRandomUri() ?: return@launch
                
                val rawBmp = decodeSampledBitmapFromUri(Uri.parse(nextUri), surfaceWidth, surfaceHeight)
                if (rawBmp != null) {
                    val focal = if (smartCropEnabled) detectFaceFocalPoint(rawBmp) else null
                    // Pre-scale to screen size to eliminate scaling overhead during fade
                    val scaledBmp = createScreenScaledBitmap(rawBmp)
                    withContext(Dispatchers.Main) {
                        if (!isActive) {
                            scaledBmp?.recycle()
                            rawBmp.recycle()
                            return@withContext
                        }
                        preloadedBitmap?.recycle()
                        preloadedBitmap = scaledBmp ?: rawBmp
                        preloadedUri = nextUri
                        preloadedFocalPoint = focal
                        if (scaledBmp != null) rawBmp.recycle()
                    }
                }
            }
        }

        private fun startFadeRotation() {
            rotationJob?.cancel()
            rotationJob = engineScope.launch(Dispatchers.IO) {
                val nextUri = getSingleRandomUri()
                if (nextUri != null) {
                    val rawBmp = decodeSampledBitmapFromUri(Uri.parse(nextUri), surfaceWidth, surfaceHeight)
                    if (rawBmp != null) {
                        val focal = if (smartCropEnabled) detectFaceFocalPoint(rawBmp) else null
                        val scaledBmp = createScreenScaledBitmap(rawBmp)
                        withContext(Dispatchers.Main) {
                            if (!isActive) {
                                scaledBmp?.recycle()
                                rawBmp.recycle()
                                return@withContext
                            }
                            nextBitmap?.recycle()
                            pageUris[manualPageIndex] = nextUri
                            nextBitmap = scaledBmp ?: rawBmp
                            nextFocalPoint = focal
                            if (scaledBmp != null) rawBmp.recycle()
                            
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
                        }
                    } else {
                        withContext(Dispatchers.Main) {
                            scheduleRotation()
                        }
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        scheduleRotation()
                    }
                }
            }
        }

        private fun createScreenScaledBitmap(raw: Bitmap): Bitmap? {
            return try {
                if (surfaceWidth <= 0 || surfaceHeight <= 0) return null
                
                // Pre-scale including parallax zoom for consistency and to avoid crash
                val zoomFactor = if (parallaxEnabled) 1.0f + (parallaxStrength * 0.1f) else 1.0f
                val targetW = (surfaceWidth * zoomFactor).toInt()
                val targetH = (surfaceHeight * zoomFactor).toInt()

                val result = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(result)
                
                val bW = raw.width.toFloat()
                val bH = raw.height.toFloat()
                val s = maxOf(targetW.toFloat() / bW, targetH.toFloat() / bH)
                val ow = bW * s
                val oh = bH * s
                val dx = (targetW - ow) / 2f
                val dy = (targetH - oh) / 2f
                
                val paint = Paint(Paint.FILTER_BITMAP_FLAG)
                canvas.drawBitmap(raw, null, RectF(dx, dy, dx + ow, dy + oh), paint)
                result
            } catch (e: Exception) { null }
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
                requestDraw()
                scheduleRotation()
                
                // Hint for GC after rotation to reclaim any overhead
                System.gc()
            } else {
                requestDraw()
            }
        }

        private fun getRotationIntervalMs(): Long {
            val prefs = getSharedPreferences("multi_wallpaper_prefs", Context.MODE_PRIVATE)
            val seconds = prefs.getFloat("interval_seconds", 60f)
            return (seconds * 1000L).toLong()
        }

        private fun getAllAvailableUris(): List<String> {
            val db = AppDatabase.getDatabase(this@MultiWallpaperLiveService)
            val prefs = getSharedPreferences("multi_wallpaper_prefs", Context.MODE_PRIVATE)
            
            return if (prefs.getBoolean("use_favorites_only", false)) {
                db.favoriteDao().getAllFavoritesSync().map { it.uriString }
            } else {
                // Optimization: Use cached scanned images instead of re-scanning folders
                db.scannedImageDao().getAllImagesSync().map { it.uriString }
            }
        }

        private fun loadWallpapersForPages() {
            mainLoadJob?.cancel()
            backgroundRefreshJob?.cancel()
            mainLoadJob = engineScope.launch(Dispatchers.IO) {
                try {
                    val allUris = getAllAvailableUris()
                    if (allUris.isEmpty()) {
                        withContext(Dispatchers.Main) { recycleBitmaps(); isLoading = false; requestDraw() }
                        return@launch
                    }

                    if (!isActive) return@launch
                    withContext(Dispatchers.Main) { if (pageBitmaps.isEmpty()) { isLoading = true; requestDraw() } }

                    val random = Random(System.currentTimeMillis())
                    val shuffledUris = allUris.shuffled(random)
                    
                    // Priority 1: Load current visible page immediately
                    val visibleIdx = manualPageIndex.coerceIn(0, shuffledUris.size - 1)
                    val visibleUri = shuffledUris[visibleIdx]
                    val firstBitmap = decodeSampledBitmapFromUri(Uri.parse(visibleUri), surfaceWidth, surfaceHeight)
                    if (!isActive) { firstBitmap?.recycle(); return@launch }
                    
                    val firstFocal = if (firstBitmap != null && smartCropEnabled) detectFaceFocalPoint(firstBitmap) else null
                    
                    withContext(Dispatchers.Main) {
                        synchronized(bitmapLock) {
                            // CRITICAL: Clear stale data immediately after first bitmap is ready
                            // This prevents showing old wallpapers on other pages during transitions
                            pageBitmaps.forEach { (idx, bitmap) -> if (idx != manualPageIndex) bitmap.recycle() }
                            val currentBitmap = pageBitmaps[manualPageIndex]
                            pageBitmaps.clear()
                            pageUris.clear()
                            pageFocalPoints.clear()
                            
                            if (firstBitmap != null) {
                                pageBitmaps[manualPageIndex] = firstBitmap
                                pageUris[manualPageIndex] = visibleUri
                                pageFocalPoints[manualPageIndex] = firstFocal
                                if (currentBitmap != firstBitmap) currentBitmap?.recycle()
                            }
                            isLoading = false
                            requestDraw()
                        }
                    }

                    if (!isActive) return@launch

                    // Priority 2: Load other pages sequentially with delay in background
                    val targetPageCount = detectedPages.coerceAtMost(shuffledUris.size)
                    (0 until targetPageCount).filter { it != manualPageIndex }.forEach { p ->
                        if (!isActive) return@launch
                        val uriIdx = p % shuffledUris.size
                        val uri = shuffledUris[uriIdx]
                        
                        val b = decodeSampledBitmapFromUri(Uri.parse(uri), surfaceWidth, surfaceHeight)
                        if (b != null) {
                            if (!isActive) { b.recycle(); return@launch }
                            val focal = if (smartCropEnabled) detectFaceFocalPoint(b) else null
                            withContext(Dispatchers.Main) {
                                synchronized(bitmapLock) {
                                    val old = pageBitmaps[p]
                                    pageBitmaps[p] = b
                                    pageUris[p] = uri
                                    pageFocalPoints[p] = focal
                                    old?.recycle()
                                    requestDraw()
                                }
                            }
                        }
                        delay(100) // yield
                    }
                    
                    if (isActive) {
                        withContext(Dispatchers.Main) { 
                            scheduleRotation()
                            preloadNextWallpaper()
                        }
                    }
                } catch (e: Exception) { withContext(Dispatchers.Main) { isLoading = false } }
            }
        }

        private suspend fun detectFaceFocalPoint(bitmap: Bitmap): PointF? {
            // Downscale for faster and more accurate pattern recognition (Industry Standard)
            val targetSize = 480
            val scale = (targetSize.toFloat() / maxOf(bitmap.width, bitmap.height)).coerceAtMost(1f)

            val detectionBitmap = if (scale < 1f) {
                try {
                    Bitmap.createScaledBitmap(
                        bitmap,
                        (bitmap.width * scale).toInt(),
                        (bitmap.height * scale).toInt(),
                        true
                    )
                } catch (e: Exception) { bitmap }
            } else {
                bitmap
            }

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
            
            val detector = faceDetector ?: return null

            return try {
                val image = InputImage.fromBitmap(detectionBitmap, 0)
                
                val faces = suspendCancellableCoroutine<List<com.google.mlkit.vision.face.Face>> { cont ->
                    detector.process(image)
                        .addOnSuccessListener { if (cont.isActive) cont.resume(it) }
                        .addOnFailureListener { if (cont.isActive) cont.resume(emptyList()) }
                }
                
                if (faces.isNotEmpty()) {
                    val mainFace = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }!!
                    val focal = PointF(mainFace.boundingBox.centerX() / detectionBitmap.width.toFloat(), 
                                       mainFace.boundingBox.centerY() / detectionBitmap.height.toFloat())
                    Log.d("MultiWallpaper", "Main face detected at: $focal (using ${detectionBitmap.width}x${detectionBitmap.height} proxy)")
                    focal
                } else {
                    // SMART FALLBACK: If no face, estimate subject position (usually upper 1/3 for humans)
                    val fallbackFocal = PointF(0.5f, 0.35f) 
                    Log.d("MultiWallpaper", "No faces found. Using smart fallback subject position.")
                    fallbackFocal
                }
            } catch (e: Exception) { 
                null 
            } finally {
                // CRITICAL: Recycle the proxy bitmap, but KEEP the detector open for reuse
                if (detectionBitmap != bitmap) detectionBitmap.recycle()
            }
        }

        private fun decodeSampledBitmapFromUri(uri: Uri, reqWidth: Int, reqHeight: Int): Bitmap? {
            return try {
                // Get Orientation first
                val orientation = contentResolver.openInputStream(uri)?.use { input ->
                    val exif = ExifInterface(input)
                    exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                } ?: ExifInterface.ORIENTATION_NORMAL

                contentResolver.openInputStream(uri)?.use { input ->
                    val opt = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeStream(input, null, opt)
                    
                    var maxWidth = if (reqWidth > 0) reqWidth.coerceAtMost(2048) else 2048
                    var maxHeight = if (reqHeight > 0) reqHeight.coerceAtMost(2048) else 2048

                    if (lightModeEnabled) {
                        maxWidth = (maxWidth * 0.6f).toInt().coerceAtLeast(720)
                        maxHeight = (maxHeight * 0.6f).toInt().coerceAtLeast(1280)
                    }

                    opt.inSampleSize = calculateInSampleSize(opt, maxWidth, maxHeight)
                    opt.inJustDecodeBounds = false
                    opt.inPreferredConfig = if (lightModeEnabled) Bitmap.Config.RGB_565 else Bitmap.Config.ARGB_8888
                    
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        opt.inMutable = true
                    }

                    val decoded = contentResolver.openInputStream(uri)?.use { i2 -> BitmapFactory.decodeStream(i2, null, opt) }
                    
                    if (decoded != null && orientation != ExifInterface.ORIENTATION_NORMAL) {
                        val matrix = Matrix()
                        when (orientation) {
                            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
                            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
                            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
                            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
                        }
                        val rotated = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
                        decoded.recycle()
                        rotated
                    } else decoded
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
            recentHistory.clear()
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
            if (pageBitmaps.isEmpty() || isLoading) {
                if (isLoading) {
                    drawLoadingState(canvas, w, h)
                } else {
                    canvas.drawColor(Color.parseColor("#1A1F2C"))
                    canvas.drawText("Select folders in App", w / 2f, h / 2f, textPaint)
                }
                return
            }

            val isFluid = if (xStep > 0f) kotlin.math.abs((xOffset / xStep) - (xOffset / xStep).roundToInt()) > 0.001f else false
            val pos = if (xStep > 0f) xOffset / xStep else xOffset * (detectedPages - 1)
            val maxIdx = (detectedPages - 1).coerceAtLeast(0)
            val idx = if (isFluid) pos.roundToInt().coerceIn(0, maxIdx) else manualPageIndex.coerceIn(0, maxIdx)

            val curr = pageBitmaps[idx]
            if (curr != null && !curr.isRecycled) {
                if (isTransitioning && nextBitmap != null && !nextBitmap!!.isRecycled) {
                    val currFocal = if (smartCropEnabled) pageFocalPoints[idx] else null
                    calculateRects(curr, w, h, srcRect, dstRect, currFocal)
                    canvas.drawBitmap(curr, srcRect, dstRect, bitmapPaint)
                    
                    bitmapPaint.alpha = transitionAlpha
                    calculateRects(nextBitmap!!, w, h, nextSrcRect, nextDstRect, nextFocalPoint)
                    canvas.drawBitmap(nextBitmap!!, nextSrcRect, nextDstRect, bitmapPaint)
                    bitmapPaint.alpha = 255 // Reset
                } else if (transitionType == "fade" && isFluid) {
                    val floatPos = if (xStep > 0f) xOffset / xStep else xOffset * (detectedPages - 1)
                    val l = floatPos.toInt().coerceIn(0, maxIdx)
                    val r = (l + 1).coerceAtMost(maxIdx)
                    val f = floatPos - l
                    
                    val lb = pageBitmaps[l]; val rb = pageBitmaps[r]
                    if (lb != null && rb != null && l != r) {
                        val lf = if (smartCropEnabled) pageFocalPoints[l] else null
                        val rf = if (smartCropEnabled) pageFocalPoints[r] else null
                        calculateRects(lb, w, h, srcRect, dstRect, lf)
                        calculateRects(rb, w, h, nextSrcRect, nextDstRect, rf)

                        bitmapPaint.alpha = ((1f - f) * 255).toInt()
                        canvas.drawBitmap(lb, srcRect, dstRect, bitmapPaint)
                        
                        bitmapPaint.alpha = (f * 255).toInt()
                        canvas.drawBitmap(rb, nextSrcRect, nextDstRect, bitmapPaint)
                        
                        bitmapPaint.alpha = 255 // Reset alpha
                    } else if (lb != null) drawSingleBitmap(canvas, lb, w, h)
                    else drawLoadingState(canvas, w, h)
                } else drawSingleBitmap(canvas, curr, w, h)
            } else {
                drawLoadingState(canvas, w, h)
            }
        }

        private fun calculateRects(b: Bitmap, w: Int, h: Int, sR: Rect, dR: RectF, fP: PointF? = null) {
            val bW = b.width.toFloat()
            val bH = b.height.toFloat()
            val sBase = maxOf(w.toFloat() / bW, h.toFloat() / bH)
            
            // Only apply parallax if NOT transitioning (already checked in onSensorChanged, 
            // but we use current values here)
            // FIX: Always apply zoom if parallax is enabled to prevent "popping" during transition
            var zoomFactor = if (parallaxEnabled) 1.0f + (parallaxStrength * 0.1f) else 1.0f

            // AI Smart Crop Adjustment - DYNAMIC SLACK
            if (smartCropEnabled && fP != null) {
                // If a face is detected, we ALWAYS apply at least a tiny zoom (1.1x)
                // to allow the image some "room" to shift even if the face is near center.
                val dxFromCenter = kotlin.math.abs(fP.x - 0.5f)
                val dyFromCenter = kotlin.math.abs(fP.y - 0.5f)
                val maxOffset = maxOf(dxFromCenter, dyFromCenter)
                
                // Base AI zoom 1.1x, increasing up to zoomSlack (default 1.45x) for extreme offsets
                val maxSlack = if (aiAdvancedEnabled) aiZoomSlack else 1.45f
                val slackRange = (maxSlack - 1.1f).coerceAtLeast(0.0f)
                
                val extraZoom = 1.1f + (maxOffset * (slackRange * 2f)).coerceIn(0.0f, slackRange)
                zoomFactor *= extraZoom
            }

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
                    val subjectTiltX = (currentRoll / 12f) * (w * 0.08f) 
                    val subjectTiltY = (currentPitch / 12f) * (h * 0.08f)
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
                // the image actually has.
                val slackX = (ow - w) / 2f
                val slackY = (oh - h) / 2f
                
                cX += (currentRoll / 10f) * slackX
                cY -= (currentPitch / 10f) * slackY
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

        private fun drawSingleBitmap(canvas: Canvas, b: Bitmap, w: Int, h: Int) {
            if (b.isRecycled) {
                drawLoadingState(canvas, w, h)
                return
            }
            val isFluid = if (xStep > 0f) kotlin.math.abs((xOffset / xStep) - (xOffset / xStep).roundToInt()) > 0.001f else false
            val pos = if (xStep > 0f) xOffset / xStep else xOffset * (detectedPages - 1)
            val maxIdx = (detectedPages - 1).coerceAtLeast(0)
            val idx = if (isFluid) pos.roundToInt().coerceIn(0, maxIdx) else manualPageIndex.coerceIn(0, maxIdx)
            
            val focal = if (smartCropEnabled) pageFocalPoints[idx] else null
            calculateRects(b, w, h, srcRect, dstRect, focal)
            canvas.drawBitmap(b, srcRect, dstRect, bitmapPaint)
        }
    }
}

