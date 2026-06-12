package com.example.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
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
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HomeViewModel(application: Application) : AndroidViewModel(application) {
    private val db = AppDatabase.getDatabase(application)
    private val folderDao = db.folderDao()
    private val favoriteDao = db.favoriteDao()

    val prefs = application.getSharedPreferences("multi_wallpaper_prefs", Context.MODE_PRIVATE)

    val folders: StateFlow<List<FolderEntity>> = folderDao.getAllFolders()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val favorites: StateFlow<List<FavoriteImageEntity>> = favoriteDao.getAllFavorites()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _scannedImages = MutableStateFlow<List<WallpaperImg>>(emptyList())
    val scannedImages: StateFlow<List<WallpaperImg>> = _scannedImages.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _selectedFolderIds = MutableStateFlow<Set<Int>>(emptySet())
    val selectedFolderIds = _selectedFolderIds.asStateFlow()

    private val _selectedGalleryUris = MutableStateFlow<Set<String>>(emptySet())
    val selectedGalleryUris = _selectedGalleryUris.asStateFlow()

    // Settings States
    private val _intervalSeconds = MutableStateFlow(prefs.getFloat("interval_seconds", 60f))
    val intervalSeconds = _intervalSeconds.asStateFlow()

    private val _useFavoritesOnly = MutableStateFlow(prefs.getBoolean("use_favorites_only", false))
    val useFavoritesOnly = _useFavoritesOnly.asStateFlow()

    private val _transitionType = MutableStateFlow(prefs.getString("transition_type", "slide") ?: "slide")
    val transitionType = _transitionType.asStateFlow()

    private val _doubleTapEnabled = MutableStateFlow(prefs.getBoolean("double_tap_enabled", true))
    val doubleTapEnabled = _doubleTapEnabled.asStateFlow()

    init {
        viewModelScope.launch {
            @OptIn(FlowPreview::class)
            folders.debounce(1000).collect {
                scanFolders()
            }
        }
    }

    // File Explorer State
    private val _currentPath = MutableStateFlow(Environment.getExternalStorageDirectory())
    val currentPath = _currentPath.asStateFlow()

    private val _currentPathItems = MutableStateFlow<List<FileItem>>(emptyList())
    val currentPathItems = _currentPathItems.asStateFlow()

    private val _selectedFolders = MutableStateFlow<Set<Uri>>(emptySet())
    val selectedFolders = _selectedFolders.asStateFlow()

    private val _isAllSelected = MutableStateFlow(false)
    val isAllSelected = _isAllSelected.asStateFlow()

    fun navigateTo(directory: java.io.File) {
        _currentPath.value = directory
        refreshCurrentPath()
    }

    fun navigateBack(): Boolean {
        val parent = _currentPath.value.parentFile
        val root = Environment.getExternalStorageDirectory()
        if (parent != null && parent.absolutePath.startsWith(root.absolutePath) && parent.absolutePath != root.parentFile?.absolutePath) {
            navigateTo(parent)
            return true
        }
        return false
    }

    fun refreshCurrentPath() {
        viewModelScope.launch(Dispatchers.IO) {
            val items = mutableListOf<FileItem>()
            try {
                val files = _currentPath.value.listFiles()
                files?.forEach { file ->
                    if (file.isDirectory && !file.name.startsWith(".")) {
                        // Find a preview image for this folder
                        val previewUri = file.listFiles()?.firstOrNull { 
                            val n = it.name.lowercase()
                            n.endsWith(".jpg") || n.endsWith(".png") || n.endsWith(".webp")
                        }?.let { Uri.fromFile(it) }
                        
                        items.add(FileItem(file.name, Uri.fromFile(file), true, previewUri))
                    }
                }
            } catch (e: Exception) {
                Log.e("HomeViewModel", "Failed to list files", e)
            }
            _currentPathItems.value = items.sortedBy { it.name }
            updateAllSelectedState()
        }
    }

    private fun updateAllSelectedState() {
        val currentItems = _currentPathItems.value
        if (currentItems.isEmpty()) {
            _isAllSelected.value = false
            return
        }
        _isAllSelected.value = currentItems.all { _selectedFolders.value.contains(it.uri) }
    }

    fun toggleSelectAll() {
        val newState = !_isAllSelected.value
        _isAllSelected.value = newState
        val currentItems = _currentPathItems.value
        val currentSelected = _selectedFolders.value.toMutableSet()
        
        if (newState) {
            currentItems.forEach { currentSelected.add(it.uri) }
        } else {
            currentItems.forEach { currentSelected.remove(it.uri) }
        }
        _selectedFolders.value = currentSelected
    }

    fun toggleFolderSelection(uri: Uri) {
        val current = _selectedFolders.value.toMutableSet()
        if (current.contains(uri)) current.remove(uri) else current.add(uri)
        _selectedFolders.value = current
        updateAllSelectedState()
    }

    fun toggleFolderIdSelection(id: Int) {
        val current = _selectedFolderIds.value.toMutableSet()
        if (current.contains(id)) current.remove(id) else current.add(id)
        _selectedFolderIds.value = current
    }

    fun clearFolderIdSelection() {
        _selectedFolderIds.value = emptySet()
    }

    fun toggleGalleryUriSelection(uri: String) {
        val current = _selectedGalleryUris.value.toMutableSet()
        if (current.contains(uri)) current.remove(uri) else current.add(uri)
        _selectedGalleryUris.value = current
    }

    fun clearGallerySelection() {
        _selectedGalleryUris.value = emptySet()
    }

    fun addSelectedToFavorites() {
        viewModelScope.launch(Dispatchers.IO) {
            val uris = _selectedGalleryUris.value
            val images = _scannedImages.value.filter { uris.contains(it.uriString) }
            
            images.forEach { img ->
                if (!img.isFavorite) {
                    favoriteDao.insertFavorite(
                        FavoriteImageEntity(
                            uriString = img.uriString,
                            folderUriString = img.folderUriString,
                            displayName = img.displayName
                        )
                    )
                }
            }
            
            withContext(Dispatchers.Main) {
                Toast.makeText(getApplication(), "${images.size} items added to favorites", Toast.LENGTH_SHORT).show()
                clearGallerySelection()
                val updatedList = _scannedImages.value.map { 
                    if (uris.contains(it.uriString)) it.copy(isFavorite = true) else it 
                }
                _scannedImages.value = updatedList
            }
        }
    }

    fun toggleFavoriteFolder(folderUri: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val folderImages = _scannedImages.value.filter { it.folderUriString == folderUri }
            val isCurrentlyFavorite = folderImages.any { it.isFavorite }
            
            if (isCurrentlyFavorite) {
                folderImages.forEach { favoriteDao.deleteFavoriteByUri(it.uriString) }
            } else {
                folderImages.forEach { img ->
                    favoriteDao.insertFavorite(
                        FavoriteImageEntity(
                            uriString = img.uriString,
                            folderUriString = img.folderUriString,
                            displayName = img.displayName
                        )
                    )
                }
            }
            
            withContext(Dispatchers.Main) {
                val updatedList = _scannedImages.value.map { 
                    if (it.folderUriString == folderUri) it.copy(isFavorite = !isCurrentlyFavorite) else it 
                }
                _scannedImages.value = updatedList
            }
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

    fun setIntervalSeconds(seconds: Float) {
        val capped = seconds.coerceAtLeast(5f)
        prefs.edit().putFloat("interval_seconds", capped).apply()
        _intervalSeconds.value = capped
    }

    fun setUseFavoritesOnly(enable: Boolean) {
        prefs.edit().putBoolean("use_favorites_only", enable).apply()
        _useFavoritesOnly.value = enable
    }

    fun setDoubleTapEnabled(enable: Boolean) {
        prefs.edit().putBoolean("double_tap_enabled", enable).apply()
        _doubleTapEnabled.value = enable
    }

    fun addFolders(uris: List<Uri>) {
        viewModelScope.launch(Dispatchers.IO) {
            val contentResolver = getApplication<Application>().contentResolver
            val foldersToInsert = mutableListOf<FolderEntity>()
            
            uris.forEach { uri ->
                try {
                    var displayName = "Folder"
                    if (uri.scheme == "file") {
                        displayName = java.io.File(uri.path ?: "").name
                    } else {
                        contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        val documentId = DocumentsContract.getTreeDocumentId(uri)
                        val documentUri = DocumentsContract.buildDocumentUriUsingTree(uri, documentId)
                        val cursor = contentResolver.query(
                            documentUri,
                            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                            null, null, null
                        )
                        cursor?.use { c -> if (c.moveToFirst()) displayName = c.getString(0) ?: "Folder" }
                    }
                    foldersToInsert.add(FolderEntity(uriString = uri.toString(), displayName = displayName))
                } catch (e: Exception) {
                    Log.e("HomeViewModel", "Prepare folder fail: $uri", e)
                }
            }
            
            if (foldersToInsert.isNotEmpty()) {
                folderDao.insertFolders(foldersToInsert)
            }
            
            withContext(Dispatchers.Main) {
                Toast.makeText(getApplication(), "${foldersToInsert.size} folders added!", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun deleteFolder(folder: FolderEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            folderDao.deleteFolder(folder)
        }
    }

    fun deleteSelectedFolders() {
        viewModelScope.launch(Dispatchers.IO) {
            val ids = _selectedFolderIds.value.toList()
            ids.forEach { folderDao.deleteFolderById(it) }
            withContext(Dispatchers.Main) {
                clearFolderIdSelection()
            }
        }
    }

    fun clearAllFolders() {
        viewModelScope.launch(Dispatchers.IO) {
            folderDao.deleteAllFolders()
            withContext(Dispatchers.Main) {
                clearFolderIdSelection()
            }
        }
    }

    fun toggleFavorite(img: WallpaperImg) {
        viewModelScope.launch(Dispatchers.IO) {
            val exists = favoriteDao.isFavoriteSync(img.uriString)
            if (exists) {
                favoriteDao.deleteFavoriteByUri(img.uriString)
            } else {
                favoriteDao.insertFavorite(FavoriteImageEntity(img.uriString, img.folderUriString, img.displayName))
            }
            val currentList = _scannedImages.value.toMutableList()
            val index = currentList.indexOfFirst { it.uriString == img.uriString }
            if (index != -1) {
                currentList[index] = currentList[index].copy(isFavorite = !exists)
                _scannedImages.value = currentList
            }
        }
    }

    fun scanFolders() {
        if (_isScanning.value) return
        _isScanning.value = true
        viewModelScope.launch(Dispatchers.IO) {
            val foldersList = folders.value
            val tempImages = mutableListOf<WallpaperImg>()
            val favoriteUris = favoriteDao.getAllFavoritesSync().map { it.uriString }.toSet()

            for (folder in foldersList) {
                try {
                    val uri = Uri.parse(folder.uriString)
                    if (uri.scheme == "file") {
                        val root = java.io.File(uri.path ?: "")
                        if (root.exists() && root.isDirectory) scanRecursive(root, folder.uriString, tempImages, favoriteUris)
                    } else {
                        scanSafRecursive(uri, folder.uriString, tempImages, favoriteUris)
                    }
                } catch (e: Exception) {
                    Log.e("HomeViewModel", "Scan error", e)
                }
            }

            _scannedImages.value = tempImages
            _isScanning.value = false
        }
    }

    private fun scanRecursive(file: java.io.File, rootUri: String, list: MutableList<WallpaperImg>, favoriteUris: Set<String>) {
        val files = file.listFiles()
        files?.forEach { f ->
            if (f.isFile && (f.name.endsWith(".jpg", true) || f.name.endsWith(".png", true) || f.name.endsWith(".webp", true))) {
                val fileUriStr = Uri.fromFile(f).toString()
                list.add(WallpaperImg(fileUriStr, rootUri, f.name, favoriteUris.contains(fileUriStr)))
            } else if (f.isDirectory && !f.name.startsWith(".")) {
                scanRecursive(f, rootUri, list, favoriteUris)
            }
        }
    }

    private fun scanSafRecursive(treeUri: Uri, rootUriStr: String, list: MutableList<WallpaperImg>, favoriteUris: Set<String>) {
        val folderQueue = java.util.ArrayDeque<Uri>()
        folderQueue.add(treeUri)
        while (folderQueue.isNotEmpty()) {
            val currentUri = folderQueue.poll() ?: continue
            try {
                val treeId = if (currentUri == treeUri) DocumentsContract.getTreeDocumentId(currentUri) 
                             else DocumentsContract.getDocumentId(currentUri)
                val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, treeId)
                val context = getApplication<Application>()
                val cursor = context.contentResolver.query(childrenUri, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null)
                cursor?.use { c ->
                    while (c.moveToNext()) {
                        val docId = c.getString(0)
                        val name = c.getString(1) ?: "Image"
                        val mimeType = c.getString(2)
                        if (mimeType != null) {
                            if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
                                folderQueue.add(DocumentsContract.buildDocumentUriUsingTree(treeUri, docId))
                            } else if (mimeType.startsWith("image/")) {
                                val childUriStr = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId).toString()
                                list.add(WallpaperImg(childUriStr, rootUriStr, name, favoriteUris.contains(childUriStr)))
                            }
                        }
                    }
                }
            } catch (e: Exception) {}
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
    val isDirectory: Boolean,
    val previewUri: Uri? = null
)
