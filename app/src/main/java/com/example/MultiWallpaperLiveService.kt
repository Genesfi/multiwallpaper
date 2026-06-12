package com.example

import android.content.Context
import android.graphics.*
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.service.wallpaper.WallpaperService
import android.util.Log
import android.view.SurfaceHolder
import com.example.data.AppDatabase
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
        private var visible = false
        private var xOffset = 0f
        private var xStep = 0f
        
        // Manual Swipe Detection for HyperOS
        private var lastX = 0f
        private var manualPageIndex = 0
        private val swipeThreshold = 150f // pixels to trigger a page change

        // Cached bitmaps for each screen index
        private val pageBitmaps = mutableMapOf<Int, Bitmap>()

        // Draw and rotation execution
        private val drawRunnable = Runnable { drawFrame() }
        private val rotationRunnable = Runnable { rotateWallpapers() }

        init {
            // Trigger initial loading
            loadWallpapersForPages()
        }

        override fun onCreate(surfaceHolder: SurfaceHolder?) {
            super.onCreate(surfaceHolder)
            // Listen for touches to handle manual swipes on HyperOS
            setTouchEventsEnabled(true)
        }

        override fun onTouchEvent(event: android.view.MotionEvent) {
            super.onTouchEvent(event)
            
            val numBitmaps = pageBitmaps.size
            if (numBitmaps <= 1) return

            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    lastX = event.x
                }
                android.view.MotionEvent.ACTION_UP -> {
                    val deltaX = event.x - lastX
                    if (kotlin.math.abs(deltaX) > swipeThreshold) {
                        if (deltaX > 0) {
                            // Swipe Right -> Go to Previous Page
                            manualPageIndex = (manualPageIndex - 1).coerceAtLeast(0)
                        } else {
                            // Swipe Left -> Go to Next Page
                            manualPageIndex = (manualPageIndex + 1).coerceAtMost(numBitmaps - 1)
                        }
                        Log.d("MultiWallpaperDebug", "Manual Swipe Detected! New Page: $manualPageIndex")
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

        override fun onOffsetsChanged(
            xOffset: Float,
            yOffset: Float,
            xStep: Float,
            yStep: Float,
            xPixels: Int,
            yPixels: Int
        ) {
            val validXOffset = if (xOffset.isNaN()) 0f else xOffset
            val validXStep = if (xStep.isNaN()) 0f else xStep
            
            // LOG DATA UNTUK DEBUG
            Log.d("MultiWallpaperDebug", "Slide Event -> xOffset: $validXOffset, xStep: $validXStep, xPixels: $xPixels")
            
            // Simpan nilai terbaru
            if (this.xOffset != validXOffset || this.xStep != validXStep) {
                this.xOffset = validXOffset
                this.xStep = validXStep
                if (visible) {
                    drawFrame()
                }
            }
        }

        override fun onSurfaceChanged(holder: SurfaceHolder?, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            
            // Suggest a very wide width (5x screen width) to force HyperOS/MIUI 
            // to recognize this as a scrollable live wallpaper.
            try {
                val wm = getSystemService(Context.WALLPAPER_SERVICE) as android.app.WallpaperManager
                wm.suggestDesiredDimensions(width * 5, height)
            } catch (e: Exception) {
                Log.e("MultiWallpaperEngine", "Error suggesting dimensions", e)
            }

            drawFrame()
        }

        override fun onCommand(
            action: String?,
            x: Int,
            y: Int,
            z: Int,
            extras: android.os.Bundle?,
            resultRequested: Boolean
        ): android.os.Bundle? {
            // MIUI/HyperOS sometimes sends commands instead of smooth offsets
            if (action == "android.wallpaper.tap" || action == "android.home.drop") {
                // Potential page change indicator, trigger a redraw
                drawFrame()
            }
            return super.onCommand(action, x, y, z, extras, resultRequested)
        }

        private fun scheduleRotation() {
            handler.removeCallbacks(rotationRunnable)
            val intervalMs = getRotationIntervalMs()
            handler.postDelayed(rotationRunnable, intervalMs)
        }

        private fun rotateWallpapers() {
            loadWallpapersForPages()
            scheduleRotation()
        }

        private fun getRotationIntervalMs(): Long {
            val prefs = getSharedPreferences("multi_wallpaper_prefs", Context.MODE_PRIVATE)
            val minutes = prefs.getFloat("interval_minutes", 1f) // default 1 minute for easy test
            return (minutes * 60 * 1000L).toLong()
        }

        private fun loadWallpapersForPages() {
            val context = this@MultiWallpaperLiveService
            val db = AppDatabase.getDatabase(context)

            Thread {
                try {
                    val folders = db.folderDao().getAllFoldersSync()
                    val favorites = db.favoriteDao().getAllFavoritesSync()

                    val prefs = context.getSharedPreferences("multi_wallpaper_prefs", Context.MODE_PRIVATE)
                    val useFavoritesOnly = prefs.getBoolean("use_favorites_only", false)

                    val allImageUris = mutableListOf<String>()
                    if (useFavoritesOnly) {
                        allImageUris.addAll(favorites.map { it.uriString })
                    } else {
                        // Scan user folders
                        for (folder in folders) {
                            try {
                                val list = scanFolderForImages(Uri.parse(folder.uriString))
                                allImageUris.addAll(list)
                            } catch (e: Exception) {
                                Log.e("MultiWallpaperEngine", "Error scanning ${folder.uriString}", e)
                            }
                        }
                    }

                    if (allImageUris.isNotEmpty()) {
                        val tempBitmaps = mutableMapOf<Int, Bitmap>()
                        val random = Random(System.currentTimeMillis())

                        // Populate a pool of up to 20 pages to handle many home screen pages
                        val poolSize = 20
                        for (page in 0 until poolSize) {
                            val uriStr = allImageUris[random.nextInt(allImageUris.size)]
                            val bitmap = decodeSampledBitmapFromUri(Uri.parse(uriStr), 1080, 2400)
                            if (bitmap != null) {
                                tempBitmaps[page] = bitmap
                            }
                        }

                        handler.post {
                            recycleBitmaps()
                            pageBitmaps.putAll(tempBitmaps)
                            drawFrame()
                        }
                    } else {
                        Log.d("MultiWallpaperEngine", "No image URIs found to set.")
                    }
                } catch (e: Exception) {
                    Log.e("MultiWallpaperEngine", "Error loading wallpapers", e)
                }
            }.start()
        }

        private fun scanFolderForImages(treeUri: Uri): List<String> {
            val list = mutableListOf<String>()
            val context = this@MultiWallpaperLiveService
            
            // Queue for BFS recursive scanning
            val folderQueue = java.util.ArrayDeque<Uri>()
            folderQueue.add(treeUri)

            while (folderQueue.isNotEmpty()) {
                val currentFolderUri = folderQueue.poll() ?: continue
                try {
                    val treeId = if (currentFolderUri == treeUri) {
                        DocumentsContract.getTreeDocumentId(currentFolderUri)
                    } else {
                        DocumentsContract.getDocumentId(currentFolderUri)
                    }
                    
                    val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, treeId)
                    val cursor = context.contentResolver.query(
                        childrenUri,
                        arrayOf(
                            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                            DocumentsContract.Document.COLUMN_MIME_TYPE
                        ),
                        null, null, null
                    )
                    
                    cursor?.use { c ->
                        val idCol = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                        val mimeCol = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                        while (c.moveToNext()) {
                            val docId = c.getString(idCol)
                            val mimeType = c.getString(mimeCol)
                            if (mimeType != null) {
                                if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
                                    val subFolderUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                                    folderQueue.add(subFolderUri)
                                } else if (mimeType.startsWith("image/")) {
                                    val childUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                                    list.add(childUri.toString())
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("MultiWallpaperEngine", "Failed querying document tree at $currentFolderUri", e)
                }
            }
            return list
        }

        private fun decodeSampledBitmapFromUri(uri: Uri, reqWidth: Int, reqHeight: Int): Bitmap? {
            var input: InputStream? = null
            return try {
                val context = this@MultiWallpaperLiveService
                input = context.contentResolver.openInputStream(uri) ?: return null
                val options = BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }
                BitmapFactory.decodeStream(input, null, options)
                input.close()

                options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight)
                options.inJustDecodeBounds = false

                input = context.contentResolver.openInputStream(uri)
                BitmapFactory.decodeStream(input, null, options)
            } catch (e: Exception) {
                Log.e("MultiWallpaperEngine", "Error decoding uri $uri", e)
                null
            } finally {
                try { input?.close() } catch (ignored: Exception) {}
            }
        }

        private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
            val (height: Int, width: Int) = options.outHeight to options.outWidth
            var inSampleSize = 1

            if (height > reqHeight || width > reqWidth) {
                val halfHeight = height / 2
                val halfWidth = width / 2
                while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                    inSampleSize *= 2
                }
            }
            return inSampleSize
        }

        private fun recycleBitmaps() {
            for (bitmap in pageBitmaps.values) {
                if (!bitmap.isRecycled) {
                    bitmap.recycle()
                }
            }
            pageBitmaps.clear()
        }

        private fun drawFrame() {
            val holder = surfaceHolder ?: return
            var canvas: Canvas? = null
            try {
                canvas = holder.lockCanvas()
                if (canvas != null) {
                    drawCanvas(canvas)
                }
            } catch (e: Exception) {
                Log.e("MultiWallpaperEngine", "Error locking canvas", e)
            } finally {
                if (canvas != null) {
                    try {
                        holder.unlockCanvasAndPost(canvas)
                    } catch (ignored: Exception) {}
                }
            }
        }

        private fun drawCanvas(canvas: Canvas) {
            val width = canvas.width
            val height = canvas.height

            if (pageBitmaps.isEmpty()) {
                val paint = Paint().apply {
                    color = Color.parseColor("#1A1F2C")
                }
                canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)

                val textPaint = Paint().apply {
                    color = Color.parseColor("#E2E8F0")
                    textSize = 42f
                    textAlign = Paint.Align.CENTER
                    isAntiAlias = true
                    typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
                }
                val subTextPaint = Paint().apply {
                    color = Color.parseColor("#94A3B8")
                    textSize = 34f
                    textAlign = Paint.Align.CENTER
                    isAntiAlias = true
                    typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
                }
                canvas.drawText("Pilih Folder di Aplikasi", width / 2f, height / 2f - 40f, textPaint)
                canvas.drawText("Lalu Klik Atur Sebagai Wallpaper", width / 2f, height / 2f + 20f, subTextPaint)
                return
            }

            val numBitmaps = pageBitmaps.size
            
            // LOGIC FOR NORMAL LAUNCHERS (If xOffset is working)
            // LOGIC FOR HYPER OS (If xOffset is blocked, use manualPageIndex)
            val pageIndex = if (xOffset > 0f || xStep > 0f) {
                (xOffset * (numBitmaps - 1)).roundToInt().coerceIn(0, numBitmaps - 1)
            } else {
                manualPageIndex.coerceIn(0, numBitmaps - 1)
            }
            
            val currentBitmap = pageBitmaps[pageIndex]
            if (currentBitmap != null) {
                val prefs = getSharedPreferences("multi_wallpaper_prefs", Context.MODE_PRIVATE)
                val transitionType = prefs.getString("transition_type", "slide") // slide or fade

                if (transitionType == "fade" && (xOffset > 0f || xStep > 0f)) {
                    // Smooth Fade logic for standard launchers
                    val position = xOffset * (numBitmaps - 1)
                    val leftIdx = position.toInt()
                    val rightIdx = (leftIdx + 1).coerceAtMost(numBitmaps - 1)
                    val fraction = position - leftIdx
                    
                    val leftBitmap = pageBitmaps[leftIdx]
                    val rightBitmap = pageBitmaps[rightIdx]
                    
                    if (leftBitmap != null && rightBitmap != null && leftIdx != rightIdx) {
                        val paint = Paint().apply { isFilterBitmap = true }
                        paint.alpha = ((1f - fraction) * 255).toInt()
                        drawSingleBitmapWithPaint(canvas, leftBitmap, width, height, paint)
                        paint.alpha = (fraction * 255).toInt()
                        drawSingleBitmapWithPaint(canvas, rightBitmap, width, height, paint)
                    } else if (leftBitmap != null) {
                        drawSingleBitmap(canvas, leftBitmap, width, height)
                    }
                } else {
                    // Default Slide/Instant logic (Standard or Manual)
                    drawSingleBitmap(canvas, currentBitmap, width, height)
                }
            }
        }

        private fun drawSingleBitmap(canvas: Canvas, bitmap: Bitmap, width: Int, height: Int) {
            val paint = Paint().apply { isFilterBitmap = true }
            drawSingleBitmapWithPaint(canvas, bitmap, width, height, paint)
        }

        private fun drawSingleBitmapWithPaint(canvas: Canvas, bitmap: Bitmap, width: Int, height: Int, paint: Paint) {
            val src = Rect(0, 0, bitmap.width, bitmap.height)
            val scaleX = width.toFloat() / bitmap.width
            val scaleY = height.toFloat() / bitmap.height
            val scale = maxOf(scaleX, scaleY)

            val outWidth = bitmap.width * scale
            val outHeight = bitmap.height * scale

            val left = (width - outWidth) / 2f
            val top = (height - outHeight) / 2f
            val right = left + outWidth
            val bottom = top + outHeight

            val dst = Rect(left.toInt(), top.toInt(), right.toInt(), bottom.toInt())
            canvas.drawBitmap(bitmap, src, dst, paint)
        }
    }
}
