package com.example

import android.content.Context
import android.graphics.*
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.service.wallpaper.WallpaperService
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

    inner class MultiWallpaperEngine : Engine() {
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

        private val drawRunnable = Runnable { drawFrame() }
        private val rotationRunnable = Runnable { rotateWallpapers() }

        override fun onCreate(surfaceHolder: SurfaceHolder?) {
            super.onCreate(surfaceHolder)
            setTouchEventsEnabled(true)
            
            val db = AppDatabase.getDatabase(this@MultiWallpaperLiveService)
            engineScope.launch {
                db.folderDao().getAllFolders().collectLatest {
                    loadWallpapersForPages()
                }
            }
        }

        override fun onDestroy() {
            super.onDestroy()
            engineScope.cancel()
            handler.removeCallbacks(drawRunnable)
            handler.removeCallbacks(rotationRunnable)
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
                        drawFrame()
                    }
                }
            }
        }

        override fun onVisibilityChanged(visible: Boolean) {
            this.visible = visible
            if (visible) {
                scheduleRotation()
                drawFrame()
            } else {
                handler.removeCallbacks(drawRunnable)
                handler.removeCallbacks(rotationRunnable)
            }
        }

        override fun onOffsetsChanged(xOffset: Float, yOffset: Float, xStep: Float, yStep: Float, xPixels: Int, yPixels: Int) {
            val validXOffset = if (xOffset.isNaN()) 0f else xOffset
            val validXStep = if (xStep.isNaN()) 0f else xStep
            
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
                if (visible) drawFrame()
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
            drawFrame()
        }

        private fun scheduleRotation() {
            handler.removeCallbacks(rotationRunnable)
            val intervalMs = getRotationIntervalMs()
            handler.postDelayed(rotationRunnable, intervalMs)
        }

        private fun rotateWallpapers() {
            if (isTransitioning) return // Avoid overlapping transitions

            val prefs = getSharedPreferences("multi_wallpaper_prefs", Context.MODE_PRIVATE)
            val transitionType = prefs.getString("transition_type", "slide")
            
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
            val prefs = getSharedPreferences("multi_wallpaper_prefs", Context.MODE_PRIVATE)
            val speed = prefs.getInt("fade_speed", 15)
            transitionAlpha += speed
            if (transitionAlpha >= 255) {
                transitionAlpha = 255
                isTransitioning = false
                val old = pageBitmaps[manualPageIndex]
                pageBitmaps[manualPageIndex] = nextBitmap!!
                nextBitmap = null
                old?.recycle()
                drawFrame()
                scheduleRotation()
            } else {
                drawFrame()
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
            val folders = db.folderDao().getAllFoldersSync()
            val favorites = db.favoriteDao().getAllFavoritesSync()
            val prefs = getSharedPreferences("multi_wallpaper_prefs", Context.MODE_PRIVATE)
            
            if (prefs.getBoolean("use_favorites_only", false)) return favorites.map { it.uriString }
            
            val all = mutableListOf<String>()
            for (f in folders) all.addAll(scanFolderForImages(Uri.parse(f.uriString)))
            return all
        }

        private fun loadWallpapersForPages() {
            Thread {
                try {
                    val allUris = getAllAvailableUris()
                    if (allUris.isEmpty()) {
                        handler.post { recycleBitmaps(); isLoading = false; drawFrame() }
                        return@Thread
                    }

                    handler.post { if (pageBitmaps.isEmpty()) { isLoading = true; drawFrame() } }

                    val random = Random(System.currentTimeMillis())
                    val firstBitmap = decodeSampledBitmapFromUri(Uri.parse(allUris[random.nextInt(allUris.size)]), surfaceWidth, surfaceHeight)
                    
                    handler.post {
                        recycleBitmaps()
                        if (firstBitmap != null) pageBitmaps[0] = firstBitmap
                        isLoading = false
                        drawFrame()
                        preloadNextWallpaper() // Preload after initial load
                    }

                    val temp = mutableMapOf<Int, Bitmap>()
                    for (p in 1 until 10) {
                        val b = decodeSampledBitmapFromUri(Uri.parse(allUris[random.nextInt(allUris.size)]), surfaceWidth, surfaceHeight)
                        if (b != null) temp[p] = b
                    }
                    handler.post { pageBitmaps.putAll(temp) }
                } catch (e: Exception) { handler.post { isLoading = false } }
            }.start()
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
                    opt.inSampleSize = calculateInSampleSize(opt, reqWidth, reqHeight)
                    opt.inJustDecodeBounds = false
                    // Correct stream re-reading
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
                val p = Paint().apply { color = Color.WHITE; textSize = 40f; textAlign = Paint.Align.CENTER; isAntiAlias = true }
                if (isLoading) {
                    canvas.drawText("Loading...", w / 2f, h / 2f, p)
                    p.style = Paint.Style.STROKE; p.strokeWidth = 4f
                    canvas.drawCircle(w / 2f, h / 2f + 60f, 30f, p)
                } else canvas.drawText("Select folders in App", w / 2f, h / 2f, p)
                return
            }

            val isFluid = if (xStep > 0f) kotlin.math.abs((xOffset / xStep) - (xOffset / xStep).roundToInt()) > 0.01f else false
            val idx = if (isFluid) (xOffset * (pageBitmaps.size - 1)).roundToInt().coerceIn(0, pageBitmaps.size - 1) else manualPageIndex.coerceIn(0, pageBitmaps.size - 1)

            val curr = pageBitmaps[idx]
            if (curr != null) {
                val transition = getSharedPreferences("multi_wallpaper_prefs", Context.MODE_PRIVATE).getString("transition_type", "slide")
                if (isTransitioning && nextBitmap != null) {
                    val paint = Paint().apply { isFilterBitmap = true }
                    paint.alpha = 255 - transitionAlpha
                    drawSingleBitmapWithPaint(canvas, curr, w, h, paint)
                    paint.alpha = transitionAlpha
                    drawSingleBitmapWithPaint(canvas, nextBitmap!!, w, h, paint)
                } else if (transition == "fade" && isFluid) {
                    val pos = xOffset * (pageBitmaps.size - 1); val l = pos.toInt(); val r = (l + 1).coerceAtMost(pageBitmaps.size - 1); val f = pos - l
                    val lb = pageBitmaps[l]; val rb = pageBitmaps[r]
                    if (lb != null && rb != null && l != r) {
                        val p = Paint().apply { isFilterBitmap = true }
                        p.alpha = ((1f - f) * 255).toInt(); drawSingleBitmapWithPaint(canvas, lb, w, h, p)
                        p.alpha = (f * 255).toInt(); drawSingleBitmapWithPaint(canvas, rb, w, h, p)
                    } else if (lb != null) drawSingleBitmap(canvas, lb, w, h)
                } else drawSingleBitmap(canvas, curr, w, h)
            }
        }

        private fun drawSingleBitmap(canvas: Canvas, b: Bitmap, w: Int, h: Int) {
            drawSingleBitmapWithPaint(canvas, b, w, h, Paint().apply { isFilterBitmap = true })
        }

        private fun drawSingleBitmapWithPaint(canvas: Canvas, b: Bitmap, w: Int, h: Int, p: Paint) {
            val s = maxOf(w.toFloat() / b.width, h.toFloat() / b.height)
            val ow = b.width * s; val oh = b.height * s
            canvas.drawBitmap(b, Rect(0, 0, b.width, b.height), RectF((w - ow) / 2f, (h - oh) / 2f, (w + ow) / 2f, (h + oh) / 2f), p)
        }
    }
}
