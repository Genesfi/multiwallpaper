package com.example

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
import android.provider.DocumentsContract
import android.service.wallpaper.WallpaperService
import android.util.Log
import android.view.Choreographer
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import com.example.data.AppDatabase
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import java.io.InputStream
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
        private val rotationRunnable = Runnable { rotateWallpapers() }

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
            } else if (oldSmartCrop != smartCropEnabled) {
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

            // Mute parallax during transitions
            if (!parallaxEnabled || isTransitioning) return
            
            // Dead-zone check: only update if delta is significant enough
            val deltaX = x - currentRoll
            val deltaY = y - currentPitch
            
            if (kotlin.math.abs(deltaX) > deadZoneThreshold || kotlin.math.abs(deltaY) > deadZoneThreshold) {
                currentRoll += smoothingFactor * deltaX
                currentPitch += smoothingFactor * deltaY
                
                val now = System.currentTimeMillis()
                val throttle = if (lightModeEnabled) 100L else sensorThrottleMs
                if ((now - lastSensorDrawTime) >= throttle) {
                    lastSensorDrawTime = now
                    requestDraw()
                }
            }
        }

        private fun requestDraw() {
            if (!isDrawScheduled) {
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

                    if (isSwipe && numBitmaps > 1) {
                        if (deltaX > 0) manualPageIndex = if (manualPageIndex > 0) manualPageIndex - 1 else numBitmaps - 1
                        else manualPageIndex = if (manualPageIndex < numBitmaps - 1) manualPageIndex + 1 else 0
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
                isDrawScheduled = false
                handler.removeCallbacks(drawRunnable)
                handler.removeCallbacks(rotationRunnable)
                unregisterSensor()
                // Also cancel any ongoing transitions to stop power usage
                isTransitioning = false
                nextBitmap = null
            }
        }

        private var detectedPages = 20 // Default to 20 for launchers that don't report xStep (HyperOS)

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

        private fun rotateWallpapers() {
            if (isTransitioning) return // Avoid overlapping transitions

            if (transitionType == "fade" && pageBitmaps.isNotEmpty()) {
                // Tighter timing mapping for better feel
                transitionDuration = (1300L - (fadeSpeed * 21L)).coerceIn(250L, 1200L)
                
                if (preloadedBitmap != null) {
                    nextBitmap = preloadedBitmap
                    nextFocalPoint = preloadedFocalPoint
                    if (preloadedUri != null) pageUris[manualPageIndex] = preloadedUri!!
                    preloadedBitmap = null
                    preloadedUri = null
                    preloadedFocalPoint = null
                    startFade()
                    preloadNextWallpaper()
                } else {
                    startFadeRotation()
                }
                
                // Silently refresh other pages to keep them fresh
                refreshOtherPages()
            } else {
                loadWallpapersForPages()
                scheduleRotation()
            }
        }

        private fun refreshOtherPages() {
            engineScope.launch(Dispatchers.IO) {
                val uris = getAllAvailableUris()
                if (uris.isEmpty() || detectedPages <= 1) return@launch
                
                val currentUris = pageUris.values.toSet()
                val tempBitmaps = mutableMapOf<Int, Bitmap>()
                val tempUris = mutableMapOf<Int, String>()
                val tempFocalPoints = mutableMapOf<Int, PointF?>()
                
                for (p in 0 until detectedPages) {
                    if (p == manualPageIndex) continue // Current page is being handled by fade
                    
                    // Filter out already chosen URIs to avoid duplicates on different pages
                    val availableUris = uris.filter { !currentUris.contains(it) && !tempUris.values.contains(it) && it != preloadedUri }
                    val nextUri = if (availableUris.isNotEmpty()) availableUris.random() else uris.random()
                    
                    val b = decodeSampledBitmapFromUri(Uri.parse(nextUri), surfaceWidth, surfaceHeight)
                    if (b != null) {
                        tempBitmaps[p] = b
                        tempUris[p] = nextUri
                        if (smartCropEnabled) tempFocalPoints[p] = detectFaceFocalPoint(b)
                    }
                    delay(50) // Be gentle on CPU
                }
                
                withContext(Dispatchers.Main) {
                    tempBitmaps.forEach { (p, b) ->
                        val old = pageBitmaps[p]
                        pageBitmaps[p] = b
                        pageUris[p] = tempUris[p] ?: ""
                        pageFocalPoints[p] = tempFocalPoints[p]
                        old?.recycle()
                    }
                }
            }
        }

        private fun preloadNextWallpaper() {
            engineScope.launch(Dispatchers.IO) {
                val uris = getAllAvailableUris()
                if (uris.isNotEmpty()) {
                    // Exclude all current URIs AND any currently preloaded URI
                    val currentUris = (pageUris.values + listOfNotNull(preloadedUri)).toSet()
                    val availableUris = uris.filter { !currentUris.contains(it) }
                    val nextUri = if (availableUris.isNotEmpty()) availableUris.random() else uris.random()
                    
                    val rawBmp = decodeSampledBitmapFromUri(Uri.parse(nextUri), surfaceWidth, surfaceHeight)
                    if (rawBmp != null) {
                        val focal = if (smartCropEnabled) detectFaceFocalPoint(rawBmp) else null
                        // Pre-scale to screen size to eliminate scaling overhead during fade
                        val scaledBmp = createScreenScaledBitmap(rawBmp)
                        withContext(Dispatchers.Main) {
                            preloadedBitmap?.recycle()
                            preloadedBitmap = scaledBmp
                            preloadedUri = nextUri
                            preloadedFocalPoint = focal
                            rawBmp.recycle()
                        }
                    }
                }
            }
        }

        private fun startFadeRotation() {
            engineScope.launch(Dispatchers.IO) {
                val uris = getAllAvailableUris()
                if (uris.isNotEmpty()) {
                    // Pick a random URI that is NOT currently displayed on ANY page to maximize variety
                    val currentUris = pageUris.values.toSet()
                    val availableUris = uris.filter { !currentUris.contains(it) }
                    val nextUri = if (availableUris.isNotEmpty()) availableUris.random() else uris.random()
                    
                    val rawBmp = decodeSampledBitmapFromUri(Uri.parse(nextUri), surfaceWidth, surfaceHeight)
                    if (rawBmp != null) {
                        val focal = if (smartCropEnabled) detectFaceFocalPoint(rawBmp) else null
                        val scaledBmp = createScreenScaledBitmap(rawBmp)
                        withContext(Dispatchers.Main) {
                            pageUris[manualPageIndex] = nextUri
                            nextBitmap = scaledBmp
                            nextFocalPoint = focal
                            rawBmp.recycle()
                            startFade()
                            preloadNextWallpaper() 
                        }
                    } else {
                        withContext(Dispatchers.Main) {
                            loadWallpapersForPages()
                            scheduleRotation()
                        }
                    }
                }
            }
        }

        private fun createScreenScaledBitmap(raw: Bitmap): Bitmap? {
            return try {
                if (surfaceWidth <= 0 || surfaceHeight <= 0) return raw
                
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
            } catch (e: Exception) { raw }
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
                old?.recycle()
                requestDraw()
                scheduleRotation()
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
            engineScope.launch(Dispatchers.IO) {
                try {
                    val allUris = getAllAvailableUris()
                    if (allUris.isEmpty()) {
                        withContext(Dispatchers.Main) { recycleBitmaps(); isLoading = false; requestDraw() }
                        return@launch
                    }

                    withContext(Dispatchers.Main) { if (pageBitmaps.isEmpty()) { isLoading = true; requestDraw() } }

                    val random = Random(System.currentTimeMillis())
                    val shuffledUris = allUris.shuffled(random)
                    
                    // Priority 1: Load current visible page immediately
                    val visibleIdx = manualPageIndex.coerceIn(0, shuffledUris.size - 1)
                    val visibleUri = shuffledUris[visibleIdx]
                    val firstBitmap = decodeSampledBitmapFromUri(Uri.parse(visibleUri), surfaceWidth, surfaceHeight)
                    val firstFocal = if (firstBitmap != null) detectFaceFocalPoint(firstBitmap) else null
                    
                    withContext(Dispatchers.Main) {
                        pageUris.clear()
                        pageFocalPoints.clear()
                        
                        if (firstBitmap != null) {
                            val old = pageBitmaps[manualPageIndex]
                            pageBitmaps[manualPageIndex] = firstBitmap
                            pageUris[manualPageIndex] = visibleUri
                            pageFocalPoints[manualPageIndex] = firstFocal
                            old?.recycle()
                        }
                        isLoading = false
                        requestDraw()
                    }

                    // Priority 2: Load other pages lazily in background
                    val targetPageCount = detectedPages.coerceAtMost(shuffledUris.size)
                    for (p in 0 until targetPageCount) {
                        if (p == manualPageIndex) continue 
                        
                        val uriIdx = p % shuffledUris.size
                        val uri = shuffledUris[uriIdx]
                        
                        val b = decodeSampledBitmapFromUri(Uri.parse(uri), surfaceWidth, surfaceHeight)
                        if (b != null) {
                            val focal = detectFaceFocalPoint(b)
                            withContext(Dispatchers.Main) {
                                val old = pageBitmaps[p]
                                pageBitmaps[p] = b
                                pageUris[p] = uri
                                pageFocalPoints[p] = focal
                                old?.recycle()
                                requestDraw()
                            }
                        }
                        if (p % 2 == 0) delay(10) // Small yield
                    }
                    
                    withContext(Dispatchers.Main) { 
                        scheduleRotation()
                        preloadNextWallpaper()
                    }
                } catch (e: Exception) { withContext(Dispatchers.Main) { isLoading = false } }
            }
        }

        private fun scanFolderForImages(uri: Uri): List<String> {
            val list = mutableListOf<String>()
            if (uri.scheme == "file") {
                val root = java.io.File(uri.path ?: "")
                if (root.exists() && root.isDirectory) {
                    fun scan(file: java.io.File) {
                        file.listFiles()?.forEach { f ->
                            if (f.isFile && (f.name.endsWith(".jpg", true) || f.name.endsWith(".png", true) || f.name.endsWith(".webp", true))) list.add(Uri.fromFile(f).toString())
                            else if (f.isDirectory && !f.name.startsWith(".")) scan(f)
                        }
                    }
                    scan(root)
                }
                return list
            }
            val q = java.util.ArrayDeque<Uri>(); q.add(uri)
            while (q.isNotEmpty()) {
                val curr = q.poll() ?: continue
                try {
                    val id = if (curr == uri) DocumentsContract.getTreeDocumentId(curr) else DocumentsContract.getDocumentId(curr)
                    contentResolver.query(DocumentsContract.buildChildDocumentsUriUsingTree(uri, id), arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null)?.use { c ->
                        while (c.moveToNext()) {
                            val dId = c.getString(0); val mime = c.getString(1)
                            if (mime == DocumentsContract.Document.MIME_TYPE_DIR) q.add(DocumentsContract.buildDocumentUriUsingTree(uri, dId))
                            else if (mime != null && mime.startsWith("image/")) list.add(DocumentsContract.buildDocumentUriUsingTree(uri, dId).toString())
                        }
                    }
                } catch (e: Exception) {}
            }
            return list
        }

        private suspend fun detectFaceFocalPoint(bitmap: Bitmap): PointF? {
            return try {
                val options = FaceDetectorOptions.Builder()
                    .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                    .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                    .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
                    .setMinFaceSize(0.05f) // Much more sensitive for diverse compositions
                    .enableTracking()
                    .build()
                val detector = FaceDetection.getClient(options)
                val image = InputImage.fromBitmap(bitmap, 0)
                
                val faces = suspendCancellableCoroutine<List<com.google.mlkit.vision.face.Face>> { cont ->
                    detector.process(image)
                        .addOnSuccessListener { if (cont.isActive) cont.resume(it) }
                        .addOnFailureListener { if (cont.isActive) cont.resume(emptyList()) }
                }
                
                if (faces.isNotEmpty()) {
                    // Pick the LARGEST face (main subject) instead of averaging
                    // This prevents background "face-like" patterns from shifting the focus
                    val mainFace = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }!!
                    val focal = PointF(mainFace.boundingBox.centerX() / bitmap.width.toFloat(), 
                                       mainFace.boundingBox.centerY() / bitmap.height.toFloat())
                    Log.d("MultiWallpaper", "Main face detected at: $focal")
                    focal
                } else {
                    Log.d("MultiWallpaper", "No faces found in image")
                    null
                }
            } catch (e: Exception) { null }
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
            } catch (e: Exception) { null }
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
            nextBitmap?.recycle(); nextBitmap = null
            nextFocalPoint = null
            preloadedBitmap?.recycle(); preloadedBitmap = null
            preloadedFocalPoint = null
        }

        private fun drawFrame() {
            val holder = surfaceHolder ?: return
            var canvas: Canvas? = null
            try {
                canvas = holder.lockCanvas()
                if (canvas != null) drawCanvas(canvas)
            } finally {
                if (canvas != null) holder.unlockCanvasAndPost(canvas)
            }
        }

        private val srcRect = Rect()
        private val dstRect = RectF()
        private val nextSrcRect = Rect()
        private val nextDstRect = RectF()

        private fun drawCanvas(canvas: Canvas) {
            val w = canvas.width; val h = canvas.height
            if (pageBitmaps.isEmpty() || isLoading) {
                canvas.drawColor(Color.parseColor("#1A1F2C"))
                if (isLoading) {
                    canvas.drawText("Loading...", w / 2f, h / 2f, textPaint)
                    canvas.drawCircle(w / 2f, h / 2f + 60f, 30f, loadingCirclePaint)
                } else canvas.drawText("Select folders in App", w / 2f, h / 2f, textPaint)
                return
            }

            val isFluid = if (xStep > 0f) kotlin.math.abs((xOffset / xStep) - (xOffset / xStep).roundToInt()) > 0.001f else false
            val pos = if (xStep > 0f) xOffset / xStep else xOffset * (detectedPages - 1)
            val idx = if (isFluid) pos.roundToInt().coerceIn(0, pageBitmaps.size - 1) else manualPageIndex.coerceIn(0, pageBitmaps.size - 1)

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
                    val l = floatPos.toInt().coerceIn(0, pageBitmaps.size - 1)
                    val r = (l + 1).coerceAtMost(pageBitmaps.size - 1)
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
                } else drawSingleBitmap(canvas, curr, w, h)
            }
        }

        private fun calculateRects(b: Bitmap, w: Int, h: Int, sR: Rect, dR: RectF, focal: PointF? = null) {
            val bW = b.width.toFloat()
            val bH = b.height.toFloat()
            val sBase = maxOf(w.toFloat() / bW, h.toFloat() / bH)
            
            // Only apply parallax if NOT transitioning (already checked in onSensorChanged, 
            // but we use current values here)
            // FIX: Always apply zoom if parallax is enabled to prevent "popping" during transition
            var zoomFactor = if (parallaxEnabled) 1.0f + (parallaxStrength * 0.1f) else 1.0f

            // AI Smart Crop Adjustment - DYNAMIC SLACK
            if (smartCropEnabled && focal != null) {
                // If a face is detected, we ALWAYS apply at least a tiny zoom (1.1x)
                // to allow the image some "room" to shift even if the face is near center.
                val dxFromCenter = kotlin.math.abs(focal.x - 0.5f)
                val dyFromCenter = kotlin.math.abs(focal.y - 0.5f)
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

            if (smartCropEnabled && focal != null) {
                val aiCX = (w / 2f) - (focal.x * ow)
                val aiCY = (h / 2f) - (focal.y * oh)
                
                // REFINED SAFE ZONE:
                // Only keep standard center if the face is within the central 10%.
                // Otherwise, start nudging it towards the center to ensure visibility.
                val dx = kotlin.math.abs(focal.x - 0.5f)
                val dy = kotlin.math.abs(focal.y - 0.5f)
                
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
            
            if (parallaxEnabled && !isTransitioning) {
                cX += (currentRoll / 10f) * ((ow - w) / 2f)
                cY -= (currentPitch / 10f) * ((oh - h) / 2f)
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
            val isFluid = if (xStep > 0f) kotlin.math.abs((xOffset / xStep) - (xOffset / xStep).roundToInt()) > 0.001f else false
            val pos = if (xStep > 0f) xOffset / xStep else xOffset * (detectedPages - 1)
            val idx = if (isFluid) pos.roundToInt().coerceIn(0, pageBitmaps.size - 1) else manualPageIndex.coerceIn(0, pageBitmaps.size - 1)
            
            val focal = if (smartCropEnabled) pageFocalPoints[idx] else null
            calculateRects(b, w, h, srcRect, dstRect, focal)
            canvas.drawBitmap(b, srcRect, dstRect, bitmapPaint)
        }

        private fun drawSingleBitmapWithPaint(canvas: Canvas, b: Bitmap, w: Int, h: Int, p: Paint) {
            // Deprecated by drawCanvas refactor for efficiency
            calculateRects(b, w, h, srcRect, dstRect)
            canvas.drawBitmap(b, srcRect, dstRect, p)
        }
    }
}
