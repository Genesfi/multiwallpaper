package com.example.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
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

    // File Explorer State
    private val _currentPathItems = MutableStateFlow<List<FileItem>>(emptyList())
    val currentPathItems = _currentPathItems.asStateFlow()

    private val _selectedFolders = MutableStateFlow<Set<Uri>>(emptySet())
    val selectedFolders = _selectedFolders.asStateFlow()

    private val _isAllSelected = MutableStateFlow(false)
    val isAllSelected = _isAllSelected.asStateFlow()

    fun toggleSelectAll() {
        val newState = !_isAllSelected.value
        _isAllSelected.value = newState
        if (newState) {
            _selectedFolders.value = _currentPathItems.value.map { it.uri }.toSet()
        } else {
            _selectedFolders.value = emptySet()
        }
    }

    fun toggleFolderSelection(uri: Uri) {
        val current = _selectedFolders.value.toMutableSet()
        if (current.contains(uri)) current.remove(uri) else current.add(uri)
        _selectedFolders.value = current
    }

    fun browseFolder(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val context = getApplication<Application>()
            val items = mutableListOf<FileItem>()
            
            // Try standard File API first if permission is granted
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()) {
                try {
                    // Logic to extract real path from URI if possible, or use root
                    val root = Environment.getExternalStorageDirectory()
                    val files = root.listFiles()
                    files?.forEach { file ->
                        if (file.isDirectory && !file.name.startsWith(".")) {
                            items.add(FileItem(file.name, Uri.fromFile(file), true))
                        }
                    }
                } catch (e: Exception) {
                    Log.e("HomeViewModel", "File API browsing failed", e)
                }
            }
            
            // Fallback to DocumentTree if items are empty or standard API fails
            if (items.isEmpty()) {
                try {
                    val treeId = DocumentsContract.getTreeDocumentId(uri)
                    val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(uri, treeId)
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
                        while (c.moveToNext()) {
                            val id = c.getString(0)
                            val name = c.getString(1)
                            val mime = c.getString(2)
                            if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                                items.add(FileItem(name, DocumentsContract.buildDocumentUriUsingTree(uri, id), true))
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("HomeViewModel", "Error browsing folder", e)
                }
            }
            _currentPathItems.value = items.sortedBy { it.name }
        }
    }

    fun confirmMultiSelect() {
        val uris = _selectedFolders.value.toList()
        if (uris.isNotEmpty()) {
            addFolders(uris)
            _selectedFolders.value = emptySet()
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

    fun addFolders(uris: List<Uri>) {
        viewModelScope.launch(Dispatchers.IO) {
            val contentResolver = getApplication<Application>().contentResolver
            uris.forEach { uri ->
                try {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                    
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
                } catch (e: Exception) {
                    Log.e("HomeViewModel", "Failed to add folder: $uri", e)
                }
            }
            withContext(Dispatchers.Main) {
                Toast.makeText(getApplication(), "${uris.size} folder ditambahkan!", Toast.LENGTH_SHORT).show()
            }
            scanFolders()
        }
    }

    fun addImagesDirectly(uris: List<Uri>) {
        viewModelScope.launch(Dispatchers.IO) {
            val contentResolver = getApplication<Application>().contentResolver
            uris.forEach { uri ->
                try {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                    // We treat individual images as a special virtual folder for simplicity
                    // or we can add them to a "Manual Selection" folder in DB.
                } catch (e: Exception) {
                    Log.e("HomeViewModel", "Permission failed for image: $uri")
                }
            }
            // For now, let's notify user this is coming soon or just use recursive folder
            withContext(Dispatchers.Main) {
                Toast.makeText(getApplication(), "${uris.size} gambar dipilih!", Toast.LENGTH_SHORT).show()
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

data class FileItem(
    val name: String,
    val uri: Uri,
    val isDirectory: Boolean
)
