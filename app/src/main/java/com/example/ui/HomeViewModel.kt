package com.example.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.AppDatabase
import com.example.data.FavoriteImageEntity
import com.example.data.FolderEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HomeViewModel(application: Application) : AndroidViewModel(application) {
    private val db = AppDatabase.getDatabase(application)
    private val folderDao = db.folderDao()
    private val favoriteDao = db.favoriteDao()

    // Preferences
    val prefs = application.getSharedPreferences("multi_wallpaper_prefs", Context.MODE_PRIVATE)

    // Flow of folders from DB
    val folders: StateFlow<List<FolderEntity>> = folderDao.getAllFolders()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Flow of favorites from DB
    val favorites: StateFlow<List<FavoriteImageEntity>> = favoriteDao.getAllFavorites()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // All available images from scanned folders
    private val _scannedImages = MutableStateFlow<List<WallpaperImg>>(emptyList())
    val scannedImages: StateFlow<List<WallpaperImg>> = _scannedImages.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    // Settings States
    private val _intervalMinutes = MutableStateFlow(prefs.getFloat("interval_minutes", 1f))
    val intervalMinutes = _intervalMinutes.asStateFlow()

    private val _useFavoritesOnly = MutableStateFlow(prefs.getBoolean("use_favorites_only", false))
    val useFavoritesOnly = _useFavoritesOnly.asStateFlow()

    private val _transitionType = MutableStateFlow(prefs.getString("transition_type", "slide") ?: "slide")
    val transitionType = _transitionType.asStateFlow()

    init {
        // Automatically start scanning when folders change
        viewModelScope.launch {
            folders.collect {
                scanFolders()
            }
        }
    }

    fun setTransitionType(type: String) {
        prefs.edit().putString("transition_type", type).apply()
        _transitionType.value = type
    }

    fun setIntervalMinutes(minutes: Float) {
        prefs.edit().putFloat("interval_minutes", minutes).apply()
        _intervalMinutes.value = minutes
    }

    fun setUseFavoritesOnly(enable: Boolean) {
        prefs.edit().putBoolean("use_favorites_only", enable).apply()
        _useFavoritesOnly.value = enable
    }

    fun addFolder(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val contentResolver = getApplication<Application>().contentResolver
            try {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
                // Get folder name
                var displayName = "Folder"
                val documentId = DocumentsContract.getTreeDocumentId(uri)
                val documentUri = DocumentsContract.buildDocumentUriUsingTree(uri, documentId)
                val cursor = contentResolver.query(
                    documentUri,
                    arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                    null, null, null
                )
                cursor?.use { c ->
                    if (c.moveToFirst()) {
                        displayName = c.getString(0) ?: "Folder"
                    }
                }

                folderDao.insertFolder(
                    FolderEntity(
                        uriString = uri.toString(),
                        displayName = displayName
                    )
                )
                withContext(Dispatchers.Main) {
                    Toast.makeText(getApplication(), "Berhasil menambahkan $displayName!", Toast.LENGTH_SHORT).show()
                }
                scanFolders()
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    Toast.makeText(getApplication(), "Gagal: pastikan memilih folder valid", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    fun deleteFolder(folder: FolderEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            folderDao.deleteFolder(folder)
            withContext(Dispatchers.Main) {
                Toast.makeText(getApplication(), "Folder dihapus dari daftar", Toast.LENGTH_SHORT).show()
            }
            scanFolders()
        }
    }

    fun toggleFavorite(img: WallpaperImg) {
        viewModelScope.launch(Dispatchers.IO) {
            val exists = favoriteDao.isFavoriteSync(img.uriString)
            if (exists) {
                favoriteDao.deleteFavoriteByUri(img.uriString)
            } else {
                favoriteDao.insertFavorite(
                    FavoriteImageEntity(
                        uriString = img.uriString,
                        folderUriString = img.folderUriString,
                        displayName = img.displayName
                    )
                )
            }
            // Rescan to update UI
            scanFolders()
        }
    }

    fun scanFolders() {
        if (_isScanning.value) return
        _isScanning.value = true
        viewModelScope.launch(Dispatchers.IO) {
            val context = getApplication<Application>()
            val foldersList = folders.value
            val tempImages = mutableListOf<WallpaperImg>()

            for (folder in foldersList) {
                try {
                    val treeUri = Uri.parse(folder.uriString)
                    
                    // Recursive scan BFS
                    val folderQueue = java.util.ArrayDeque<Uri>()
                    folderQueue.add(treeUri)

                    while (folderQueue.isNotEmpty()) {
                        val currentUri = folderQueue.poll() ?: continue
                        val treeId = if (currentUri == treeUri) {
                            DocumentsContract.getTreeDocumentId(currentUri)
                        } else {
                            DocumentsContract.getDocumentId(currentUri)
                        }
                        
                        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, treeId)
                        val cursor = context.contentResolver.query(
                            childrenUri,
                            arrayOf(
                                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                                DocumentsContract.Document.COLUMN_MIME_TYPE
                            ),
                            null, null, null
                        )
                        cursor?.use { c ->
                            val idCol = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                            val nameCol = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                            val mimeCol = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                            while (c.moveToNext()) {
                                val docId = c.getString(idCol)
                                val name = c.getString(nameCol) ?: "Gambar"
                                val mimeType = c.getString(mimeCol)
                                if (mimeType != null) {
                                    if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
                                        val subFolderUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                                        folderQueue.add(subFolderUri)
                                    } else if (mimeType.startsWith("image/")) {
                                        val childUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                                        val isFav = favoriteDao.isFavoriteSync(childUri.toString())
                                        tempImages.add(
                                            WallpaperImg(
                                                uriString = childUri.toString(),
                                                folderUriString = folder.uriString,
                                                displayName = name,
                                                isFavorite = isFav
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("HomeViewModel", "Error scanning documents for ${folder.displayName}", e)
                }
            }

            _scannedImages.value = tempImages
            _isScanning.value = false
        }
    }
}

data class WallpaperImg(
    val uriString: String,
    val folderUriString: String,
    val displayName: String,
    val isFavorite: Boolean
)
