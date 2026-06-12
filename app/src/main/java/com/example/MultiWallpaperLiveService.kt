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

        private var isLoading = false
        private var surfaceWidth = 1080
        private var surfaceHeight = 2400

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
                    // Increase threshold slightly to prevent accidental swipes while scrolling home apps
                    if (kotlin.math.abs(deltaX) > swipeThreshold) {
                        if (deltaX > 0) {
                            // Swipe Right (Gesture moves left to right) -> Show PREVIOUS wallpaper
                            manualPageIndex = if (manualPageIndex > 0) manualPageIndex - 1 else numBitmaps - 1
                        } else {
                            // Swipe Left (Gesture moves right to left) -> Show NEXT wallpaper
                            manualPageIndex = if (manualPageIndex < numBitmaps - 1) manualPageIndex + 1 else 0
                        }
                        
                        Log.d("MultiWallpaperDebug", "Manual Swipe Action! Delta: $deltaX, New Index: $manualPageIndex")
                        
                        // FORCE REDRAW IMMEDIATELY
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
                
                // SYNC manualPageIndex: Pastikan manual swipe dan auto mode sinkron
                val numBitmaps = pageBitmaps.size
                if (numBitmaps > 1) {
                    val systemPageIndex = if (validXStep > 0f) {
                        (validXOffset / validXStep).roundToInt()
                    } else {
                        (validXOffset * (numBitmaps - 1)).roundToInt()
                    }.coerceIn(0, numBitmaps - 1)
                    
                    if (manualPageIndex != systemPageIndex) {
                        manualPageIndex = systemPageIndex
                        Log.d("MultiWallpaperDebug", "Syncing manualPageIndex to $manualPageIndex")
                    }
                }
                
                if (visible) {
                    drawFrame()
                }
            }
        }

        override fun onSurfaceChanged(holder: SurfaceHolder?, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            this.surfaceWidth = width
            this.surfaceHeight = height
            
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

                    if (folders.isEmpty() && favorites.isEmpty()) {
                        handler.post { 
                            isLoading = false
                            drawFrame() 
                        }
                        return@Thread
                    }

                    handler.post {
                        if (pageBitmaps.isEmpty()) {
                            isLoading = true
                            drawFrame()
                        }
                    }

                    val prefs = context.getSharedPreferences("multi_wallpaper_prefs", Context.MODE_PRIVATE)
                    val useFavoritesOnly = prefs.getBoolean("use_favorites_only", false)

                    val allImageUris = mutableListOf<String>()
                    if (useFavoritesOnly) {
                        allImageUris.addAll(favorites.map { it.uriString })
                    } else {
                        for (folder in folders) {
                            try {
                                val uri = Uri.parse(folder.uriString)
                                val list = scanFolderForImages(uri)
                                allImageUris.addAll(list)
                            } catch (e: Exception) {
                                Log.e("MultiWallpaperEngine", "Error scanning ${folder.uriString}", e)
                            }
                        }
                    }

                    if (allImageUris.isNotEmpty()) {
                        val random = Random(System.currentTimeMillis())
                        
                        // 1. LOAD SATU GAMBAR PERTAMA AGAR CEPAT MUNCUL
                        val firstUriStr = allImageUris[random.nextInt(allImageUris.size)]
                        val firstBitmap = decodeSampledBitmapFromUri(Uri.parse(firstUriStr), surfaceWidth, surfaceHeight)
                        
                        handler.post {
                            if (firstBitmap != null) {
                                recycleBitmaps()
                                pageBitmaps[0] = firstBitmap
                            }
                            isLoading = false
                            drawFrame()
                        }

                        // 2. LOAD SISANYA DI BACKGROUND
                        val tempBitmaps = mutableMapOf<Int, Bitmap>()
                        val poolSize = 10 
                        for (page in 1 until poolSize) {
                            val uriStr = allImageUris[random.nextInt(allImageUris.size)]
                            val bitmap = decodeSampledBitmapFromUri(Uri.parse(uriStr), surfaceWidth, surfaceHeight)
                            if (bitmap != null) {
                                tempBitmaps[page] = bitmap
                            }
                        }

                        handler.post {
                            pageBitmaps.putAll(tempBitmaps)
                        }
                    } else {
                        handler.post { 
                            isLoading = false
                            drawFrame() 
                        }
                    }
                } catch (e: Exception) {
                    Log.e("MultiWallpaperEngine", "Error loading wallpapers", e)
                    handler.post { isLoading = false }
                }
            }.start()
        }

        private fun scanFolderForImages(uri: Uri): List<String> {
            val list = mutableListOf<String>()
            val context = this@MultiWallpaperLiveService
            
            if (uri.scheme == "file") {
                val root = java.io.File(uri.path ?: "")
                if (root.exists() && root.isDirectory) {
                    val files = root.listFiles()
                    files?.forEach { file ->
                        if (file.isFile && (file.name.endsWith(".jpg", true) || file.name.endsWith(".png", true) || file.name.endsWith(".webp", true))) {
                            list.add(Uri.fromFile(file).toString())
                        } else if (file.isDirectory) {
                            // Recursive scan for file system
                            list.addAll(scanFolderForImages(Uri.fromFile(file)))
                        }
                    }
                }
                return list
            }

            // SAF logic
            val folderQueue = java.util.ArrayDeque<Uri>()
            folderQueue.add(uri)

            while (folderQueue.isNotEmpty()) {
                val currentFolderUri = folderQueue.poll() ?: continue
                try {
                    val treeId = if (currentFolderUri == uri) {
                        DocumentsContract.getTreeDocumentId(currentFolderUri)
                    } else {
                        DocumentsContract.getDocumentId(currentFolderUri)
                    }
                    
                    val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(uri, treeId)
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
                                    val subFolderUri = DocumentsContract.buildDocumentUriUsingTree(uri, docId)
                                    folderQueue.add(subFolderUri)
                                } else if (mimeType.startsWith("image/")) {
                                    val childUri = DocumentsContract.buildDocumentUriUsingTree(uri, docId)
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

            if (pageBitmaps.isEmpty() || isLoading) {
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
                
                val title = if (isLoading) "Sedang Memuat..." else "Pilih Folder di Aplikasi"
                val subtitle = if (isLoading) "Harap tunggu sebentar" else "Lalu Klik Atur Sebagai Wallpaper"
                
                canvas.drawText(title, width / 2f, height / 2f - 40f, textPaint)
                canvas.drawText(subtitle, width / 2f, height / 2f + 20f, subTextPaint)
                return
            }

            val numBitmaps = pageBitmaps.size
            
            // HYPER OS FIX: Deteksi apakah kita sedang transisi lancar atau di titik snap (halaman diam)
            // xStep memberi tahu jarak antar halaman. Jika xOffset bukan kelipatan xStep, berarti sedang transisi.
            val isFluid = if (xStep > 0f) {
                val offsetInPages = xOffset / xStep
                val distToSnap = kotlin.math.abs(offsetInPages - offsetInPages.roundToInt())
                distToSnap > 0.01f // Jika lebih dari 1% dari titik snap, anggap fluid
            } else {
                // Jika xStep 0 (HyperOS awal), anggap tidak fluid agar manual mode jalan
                false
            }
            
            // Di HyperOS, xStep sering tersisa dari launcher sebelumnya (misal 0.5) tapi xOffset diam (stuck).
            // Kita gunakan manualPageIndex sebagai sumber utama saat launcher sedang diam.
            val pageIndex = if (isFluid) {
                (xOffset * (numBitmaps - 1)).roundToInt().coerceIn(0, numBitmaps - 1)
            } else {
                manualPageIndex.coerceIn(0, numBitmaps - 1)
            }
            
            Log.d("MultiWallpaperDebug", "Drawing -> Mode: ${if(isFluid) "Auto" else "Manual"}, Index: $pageIndex, xOffset: $xOffset, manualIndex: $manualPageIndex")

            val currentBitmap = pageBitmaps[pageIndex]
            if (currentBitmap != null) {
                val prefs = getSharedPreferences("multi_wallpaper_prefs", Context.MODE_PRIVATE)
                val transitionType = prefs.getString("transition_type", "slide") // slide or fade

                if (transitionType == "fade" && isFluid) {
                    // Smooth Fade logic only for standard launchers
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
                    // Force Instant Snap for Manual Mode (HyperOS)
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
