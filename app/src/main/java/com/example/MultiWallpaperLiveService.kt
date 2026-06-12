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
import android.view.MotionEvent
import android.view.SurfaceHolder
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
            
            useFavoritesOnly = newUseFav
            parallaxEnabled = prefs.getBoolean("parallax_enabled", false)
            parallaxStrength = prefs.getFloat("parallax_strength", 0.5f)
            transitionType = prefs.getString("transition_type", "slide") ?: "slide"
            fadeSpeed = prefs.getInt("fade_speed", 15)
            
            if (useFavChanged) {
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
                sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
            }
        }

        private fun unregisterSensor() {
            sensorManager?.unregisterListener(this)
        }

        override fun onSensorChanged(event: SensorEvent?) {
            if (event?.sensor?.type == Sensor.TYPE_ACCELEROMETER) {
                val x = event.values[0]
                val y = event.values[1]
                
                // Dead-zone check: only update if delta is significant enough
                val deltaX = x - currentRoll
                val deltaY = y - currentPitch
                
                if (kotlin.math.abs(deltaX) > deadZoneThreshold || kotlin.math.abs(deltaY) > deadZoneThreshold) {
                    currentRoll += smoothingFactor * deltaX
                    currentPitch += smoothingFactor * deltaY
                    if (visible) requestDraw()
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

            if (this.xOffset != validXOffset || this.xStep != validXStep) {
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
                if (preloadedBitmap != null) {
                    // Use preloaded bitmap immediately
                    nextBitmap = preloadedBitmap
                    preloadedBitmap = null
                    isTransitioning = true
                    transitionAlpha = 0
                    animateFade()
                    preloadNextWallpaper() // Preload for the next time
                } else {
                    // Fallback if not preloaded yet
                    startFadeRotation()
                }
            } else {
                loadWallpapersForPages()
                scheduleRotation()
            }
        }

        private fun preloadNextWallpaper() {
            Thread {
                val uris = getAllAvailableUris()
                if (uris.isNotEmpty()) {
                    val bmp = decodeSampledBitmapFromUri(Uri.parse(uris[Random.nextInt(uris.size)]), surfaceWidth, surfaceHeight)
                    handler.post {
                        preloadedBitmap?.recycle()
                        preloadedBitmap = bmp
                    }
                }
            }.start()
        }

        private fun startFadeRotation() {
            Thread {
                val uris = getAllAvailableUris()
                if (uris.isNotEmpty()) {
                    val newBmp = decodeSampledBitmapFromUri(Uri.parse(uris[Random.nextInt(uris.size)]), surfaceWidth, surfaceHeight)
                    handler.post {
                        if (newBmp != null) {
                            nextBitmap = newBmp
                            isTransitioning = true
                            transitionAlpha = 0
                            animateFade()
                            preloadNextWallpaper() // Start preloading for future use
                        } else {
                            loadWallpapersForPages()
                            scheduleRotation()
                        }
                    }
                }
            }.start()
        }

        private fun animateFade() {
            if (!isTransitioning) return
            transitionAlpha += fadeSpeed
            if (transitionAlpha >= 255) {
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
                handler.postDelayed({ animateFade() }, 30)
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
                // Optimization: Cap the requested size to prevent OOM and lag with 4K images
                val maxWidth = if (reqWidth > 0) reqWidth.coerceAtMost(2048) else 2048
                val maxHeight = if (reqHeight > 0) reqHeight.coerceAtMost(2048) else 2048

                contentResolver.openInputStream(uri)?.use { input ->
                    val opt = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeStream(input, null, opt)
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
                    bitmapPaint.alpha = 255 - transitionAlpha
                    drawSingleBitmapWithPaint(canvas, curr, w, h, bitmapPaint)
                    bitmapPaint.alpha = transitionAlpha
                    drawSingleBitmapWithPaint(canvas, nextBitmap!!, w, h, bitmapPaint)
                    bitmapPaint.alpha = 255 // Reset alpha
                } else if (transitionType == "fade" && isFluid) {
                    val pos = xOffset * (pageBitmaps.size - 1); val l = pos.toInt(); val r = (l + 1).coerceAtMost(pageBitmaps.size - 1); val f = pos - l
                    val lb = pageBitmaps[l]; val rb = pageBitmaps[r]
                    if (lb != null && rb != null && l != r) {
                        bitmapPaint.alpha = ((1f - f) * 255).toInt()
                        drawSingleBitmapWithPaint(canvas, lb, w, h, bitmapPaint)
                        bitmapPaint.alpha = (f * 255).toInt()
                        drawSingleBitmapWithPaint(canvas, rb, w, h, bitmapPaint)
                        bitmapPaint.alpha = 255 // Reset alpha
                    } else if (lb != null) drawSingleBitmap(canvas, lb, w, h)
                } else drawSingleBitmap(canvas, curr, w, h)
            }
        }

        private fun drawSingleBitmap(canvas: Canvas, b: Bitmap, w: Int, h: Int) {
            drawSingleBitmapWithPaint(canvas, b, w, h, bitmapPaint)
        }

        private fun drawSingleBitmapWithPaint(canvas: Canvas, b: Bitmap, w: Int, h: Int, p: Paint) {
            val sBase = maxOf(w.toFloat() / b.width, h.toFloat() / b.height)
            
            // Zoom factor to prevent edges during parallax
            // Strength of 1.0 means up to 10% zoom (1.1x)
            val zoomFactor = if (parallaxEnabled) 1.0f + (parallaxStrength * 0.1f) else 1.0f
            val s = sBase * zoomFactor
            
            val ow = b.width * s; val oh = b.height * s
            
            var dx = (w - ow) / 2f
            var dy = (h - oh) / 2f
            
            if (parallaxEnabled) {
                // Max offset is the extra size provided by zoomFactor
                val maxOffsetX = (ow - w) / 2f
                val maxOffsetY = (oh - h) / 2f
                
                // Roll (side tilt) affects X, Pitch (front/back tilt) affects Y
                // Accelerometer values are typically -10 to 10
                dx += (currentRoll / 10f) * maxOffsetX
                dy -= (currentPitch / 10f) * maxOffsetY
            }
            
            canvas.drawBitmap(b, Rect(0, 0, b.width, b.height), RectF(dx, dy, dx + ow, dy + oh), p)
        }
    }
}
