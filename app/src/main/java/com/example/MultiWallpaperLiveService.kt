package com.example

import android.content.Context
import android.content.SharedPreferences
import android.graphics.*
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
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
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import java.io.InputStream
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
        
        private var nextBitmap: Bitmap? = null
        private var preloadedBitmap: Bitmap? = null
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
        private var transitionType = "slide"
        private var fadeSpeed = 15
        private var useFavoritesOnly = false
        private var currentRoll = 0f
        private var currentPitch = 0f
        private val smoothingFactor = 0.05f // Stronger LPF to ignore jitter
        private val deadZoneThreshold = 0.2f // Ignore very small tremors
        private var lastSensorDrawTime = 0L
        private val sensorThrottleMs = 50L // Aggressive throttling (~20fps) to save power
        
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
            
            useFavoritesOnly = newUseFav
            parallaxEnabled = prefs.getBoolean("parallax_enabled", false)
            parallaxStrength = prefs.getFloat("parallax_strength", 0.5f)
            transitionType = prefs.getString("transition_type", "slide") ?: "slide"
            fadeSpeed = prefs.getInt("fade_speed", 15)
            
            if (useFavChanged || forceReload) {
                loadWallpapersForPages()
            }
            
            if (visible && parallaxEnabled) {
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
            // Mute parallax during transitions to focus CPU/GPU on alpha blending
            if (!visible || !parallaxEnabled || isTransitioning) return
            
            if (event?.sensor?.type == Sensor.TYPE_ACCELEROMETER) {
                val x = event.values[0]
                val y = event.values[1]
                
                // Dead-zone check: only update if delta is significant enough
                val deltaX = x - currentRoll
                val deltaY = y - currentPitch
                
                if (kotlin.math.abs(deltaX) > deadZoneThreshold || kotlin.math.abs(deltaY) > deadZoneThreshold) {
                    currentRoll += smoothingFactor * deltaX
                    currentPitch += smoothingFactor * deltaY
                    
                    val now = System.currentTimeMillis()
                    if ((now - lastSensorDrawTime) >= sensorThrottleMs) {
                        lastSensorDrawTime = now
                        requestDraw()
                    }
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
                    loadWallpapersForPages() // Re-load if page count changes
                }
            }

            // Only redraw if the offset changed significantly to save power
            val offsetDelta = kotlin.math.abs(this.xOffset - validXOffset)
            if (offsetDelta > 0.001f || this.xStep != validXStep) {
                this.xOffset = validXOffset
                this.xStep = validXStep
                
                val numBitmaps = pageBitmaps.size
                if (numBitmaps > 1) {
                    val systemPageIndex = if (validXStep > 0f) (validXOffset / validXStep).roundToInt()
                                         else (validXOffset * (numBitmaps - 1)).roundToInt()
                    val targetIndex = systemPageIndex.coerceIn(0, numBitmaps - 1)
                    if (manualPageIndex != targetIndex) manualPageIndex = targetIndex
                }
                if (visible) requestDraw()
            }
        }

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
                // fadeSpeed 5 -> ~1200ms (Slow)
                // fadeSpeed 25 -> ~600ms (Normal)
                // fadeSpeed 50 -> ~250ms (Fast)
                transitionDuration = (1300L - (fadeSpeed * 21L)).coerceIn(250L, 1200L)
                
                if (preloadedBitmap != null) {
                    nextBitmap = preloadedBitmap
                    preloadedBitmap = null
                    startFade()
                    preloadNextWallpaper()
                } else {
                    startFadeRotation()
                }
            } else {
                loadWallpapersForPages()
                scheduleRotation()
            }
        }

        private fun preloadNextWallpaper() {
            engineScope.launch(Dispatchers.IO) {
                val uris = getAllAvailableUris()
                if (uris.isNotEmpty()) {
                    val rawBmp = decodeSampledBitmapFromUri(Uri.parse(uris[Random.nextInt(uris.size)]), surfaceWidth, surfaceHeight)
                    if (rawBmp != null) {
                        // Pre-scale to screen size to eliminate scaling overhead during fade
                        val scaledBmp = createScreenScaledBitmap(rawBmp)
                        withContext(Dispatchers.Main) {
                            preloadedBitmap?.recycle()
                            preloadedBitmap = scaledBmp
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
                    val rawBmp = decodeSampledBitmapFromUri(Uri.parse(uris[Random.nextInt(uris.size)]), surfaceWidth, surfaceHeight)
                    if (rawBmp != null) {
                        val scaledBmp = createScreenScaledBitmap(rawBmp)
                        withContext(Dispatchers.Main) {
                            nextBitmap = scaledBmp
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
                
                val result = Bitmap.createBitmap(surfaceWidth, surfaceHeight, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(result)
                
                val bW = raw.width.toFloat()
                val bH = raw.height.toFloat()
                val s = maxOf(surfaceWidth.toFloat() / bW, surfaceHeight.toFloat() / bH)
                val ow = bW * s
                val oh = bH * s
                val dx = (surfaceWidth - ow) / 2f
                val dy = (surfaceHeight - oh) / 2f
                
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
                nextBitmap = null
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
                    
                    // Priority 1: Load current visible page immediately to avoid boot freeze/stutter
                    val visibleUri = shuffledUris[0]
                    val firstBitmap = decodeSampledBitmapFromUri(Uri.parse(visibleUri), surfaceWidth, surfaceHeight)
                    
                    withContext(Dispatchers.Main) {
                        // Priority cleanup: keep current page if it exists and we're just adding more, 
                        // but here we recycle to ensure a clean slate for the new page count.
                        recycleBitmaps()
                        if (firstBitmap != null) {
                            val safeIdx = manualPageIndex.coerceIn(0, detectedPages - 1)
                            pageBitmaps[safeIdx] = firstBitmap
                        }
                        isLoading = false
                        requestDraw()
                    }

                    // Priority 2: Load other pages lazily in background
                    val targetPageCount = detectedPages.coerceAtMost(shuffledUris.size)
                    val temp = mutableMapOf<Int, Bitmap>()
                    for (p in 0 until targetPageCount) {
                        if (p == manualPageIndex) continue // Already loaded
                        
                        val uriIdx = p % shuffledUris.size
                        val b = decodeSampledBitmapFromUri(Uri.parse(shuffledUris[uriIdx]), surfaceWidth, surfaceHeight)
                        if (b != null) temp[p] = b
                        
                        // Yield to prevent blocking IO thread for too long if many pages
                        if (p % 3 == 0) delay(50) 
                    }
                    
                    withContext(Dispatchers.Main) { 
                        pageBitmaps.putAll(temp)
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

        private fun decodeSampledBitmapFromUri(uri: Uri, reqWidth: Int, reqHeight: Int): Bitmap? {
            return try {
                contentResolver.openInputStream(uri)?.use { input ->
                    val opt = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeStream(input, null, opt)
                    
                    // Optimization: Detect wide (16:9) images and apply higher sampling for portrait display
                    val isWide = opt.outWidth > opt.outHeight * 1.5
                    val targetWidth = if (isWide && reqWidth < reqHeight) reqWidth / 2 else reqWidth
                    
                    // Optimization: Cap the requested size to prevent OOM and lag with 4K images
                    val maxWidth = if (targetWidth > 0) targetWidth.coerceAtMost(2048) else 2048
                    val maxHeight = if (reqHeight > 0) reqHeight.coerceAtMost(2048) else 2048

                    opt.inSampleSize = calculateInSampleSize(opt, maxWidth, maxHeight)
                    opt.inJustDecodeBounds = false
                    opt.inPreferredConfig = Bitmap.Config.RGB_565 // Use 16-bit color for memory efficiency if quality allows

                    contentResolver.openInputStream(uri)?.use { i2 -> BitmapFactory.decodeStream(i2, null, opt) }
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
            pageBitmaps.values.forEach { if (!it.isRecycled) it.recycle() }; pageBitmaps.clear()
            nextBitmap?.recycle(); nextBitmap = null
            preloadedBitmap?.recycle(); preloadedBitmap = null
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

            val isFluid = if (xStep > 0f) kotlin.math.abs((xOffset / xStep) - (xOffset / xStep).roundToInt()) > 0.01f else false
            val idx = if (isFluid) (xOffset * (pageBitmaps.size - 1)).roundToInt().coerceIn(0, pageBitmaps.size - 1) else manualPageIndex.coerceIn(0, pageBitmaps.size - 1)

            val curr = pageBitmaps[idx]
            if (curr != null) {
                if (isTransitioning && nextBitmap != null) {
                    // Single-Pass Blending: Draw background solid, draw next on top with alpha
                    // Since nextBitmap is already pre-scaled, we draw it 1:1
                    drawSingleBitmap(canvas, curr, w, h)
                    
                    bitmapPaint.alpha = transitionAlpha
                    canvas.drawBitmap(nextBitmap!!, 0f, 0f, bitmapPaint)
                    bitmapPaint.alpha = 255 // Reset
                } else if (transitionType == "fade" && isFluid) {
                    val pos = xOffset * (pageBitmaps.size - 1); val l = pos.toInt(); val r = (l + 1).coerceAtMost(pageBitmaps.size - 1); val f = pos - l
                    val lb = pageBitmaps[l]; val rb = pageBitmaps[r]
                    if (lb != null && rb != null && l != r) {
                        calculateRects(lb, w, h, srcRect, dstRect)
                        calculateRects(rb, w, h, nextSrcRect, nextDstRect)

                        bitmapPaint.alpha = ((1f - f) * 255).toInt()
                        canvas.drawBitmap(lb, srcRect, dstRect, bitmapPaint)
                        
                        bitmapPaint.alpha = (f * 255).toInt()
                        canvas.drawBitmap(rb, nextSrcRect, nextDstRect, bitmapPaint)
                        
                        bitmapPaint.alpha = 255 // Reset alpha
                    } else if (lb != null) drawSingleBitmap(canvas, lb, w, h)
                } else drawSingleBitmap(canvas, curr, w, h)
            }
        }

        private fun calculateRects(b: Bitmap, w: Int, h: Int, sR: Rect, dR: RectF) {
            val bW = b.width.toFloat()
            val bH = b.height.toFloat()
            val sBase = maxOf(w.toFloat() / bW, h.toFloat() / bH)
            
            // Only apply parallax if NOT transitioning (already checked in onSensorChanged, 
            // but we use current values here)
            val zoomFactor = if (parallaxEnabled && !isTransitioning) 1.0f + (parallaxStrength * 0.1f) else 1.0f
            val s = sBase * zoomFactor
            
            val ow = bW * s
            val oh = bH * s
            
            var cX = (w - ow) / 2f
            var cY = (h - oh) / 2f
            
            if (parallaxEnabled && !isTransitioning) {
                cX += (currentRoll / 10f) * ((ow - w) / 2f)
                cY -= (currentPitch / 10f) * ((oh - h) / 2f)
            }

            val lOn = maxOf(0f, -cX)
            val tOn = maxOf(0f, -cY)
            val rOn = minOf(ow, w - cX)
            val bOn = minOf(oh, h - cY)

            sR.set((lOn / s).toInt(), (tOn / s).toInt(), (rOn / s).toInt(), (bOn / s).toInt())
            dR.set(maxOf(0f, cX), maxOf(0f, cY), minOf(w.toFloat(), cX + ow), minOf(h.toFloat(), cY + oh))
        }

        private fun drawSingleBitmap(canvas: Canvas, b: Bitmap, w: Int, h: Int) {
            calculateRects(b, w, h, srcRect, dstRect)
            canvas.drawBitmap(b, srcRect, dstRect, bitmapPaint)
        }

        private fun drawSingleBitmapWithPaint(canvas: Canvas, b: Bitmap, w: Int, h: Int, p: Paint) {
            // Deprecated by drawCanvas refactor for efficiency
            calculateRects(b, w, h, srcRect, dstRect)
            canvas.drawBitmap(b, srcRect, dstRect, p)
        }
    }
}
