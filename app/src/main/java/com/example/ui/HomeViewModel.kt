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

    // Selection State for deletion
    private val _selectedFolderIds = MutableStateFlow<Set<Int>>(emptySet())
    val selectedFolderIds = _selectedFolderIds.asStateFlow()

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
                        items.add(FileItem(file.name, Uri.fromFile(file), true))
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
            val foldersToInsert = mutableListOf<FolderEntity>()
            
            uris.forEach { uri ->
                try {
                    var displayName = "Folder"
                    if (uri.scheme == "file") {
                        displayName = java.io.File(uri.path ?: "").name
                    } else {
                        contentResolver.takePersistableUriPermission(
                            uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )
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
                    }

                    foldersToInsert.add(
                        FolderEntity(
                            uriString = uri.toString(),
                            displayName = displayName
                        )
                    )
                } catch (e: Exception) {
                    Log.e("HomeViewModel", "Failed to prepare folder: $uri", e)
                }
            }
            
            if (foldersToInsert.isNotEmpty()) {
                folderDao.insertFolders(foldersToInsert)
            }
            
            withContext(Dispatchers.Main) {
                Toast.makeText(getApplication(), "${foldersToInsert.size} folder ditambahkan!", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun deleteFolder(folder: FolderEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            folderDao.deleteFolder(folder)
            withContext(Dispatchers.Main) {
                Toast.makeText(getApplication(), "Folder dihapus", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun deleteSelectedFolders() {
        viewModelScope.launch(Dispatchers.IO) {
            val ids = _selectedFolderIds.value.toList()
            ids.forEach { id ->
                folderDao.deleteFolderById(id)
            }
            withContext(Dispatchers.Main) {
                Toast.makeText(getApplication(), "${ids.size} folder dihapus", Toast.LENGTH_SHORT).show()
                clearFolderIdSelection()
            }
        }
    }

    fun clearAllFolders() {
        viewModelScope.launch(Dispatchers.IO) {
            folderDao.deleteAllFolders()
            withContext(Dispatchers.Main) {
                Toast.makeText(getApplication(), "Daftar folder dibersihkan!", Toast.LENGTH_SHORT).show()
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
                favoriteDao.insertFavorite(
                    FavoriteImageEntity(
                        uriString = img.uriString,
                        folderUriString = img.folderUriString,
                        displayName = img.displayName
                    )
                )
            }
            // Real-time update in memory for the scanned list
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
            
            // Optimization: Fetch all favorite URIs once to avoid DB overhead during recursive scan
            val favoriteUris = favoriteDao.getAllFavoritesSync().map { it.uriString }.toSet()

            for (folder in foldersList) {
                try {
                    val uri = Uri.parse(folder.uriString)
                    
                    if (uri.scheme == "file") {
                        val root = java.io.File(uri.path ?: "")
                        if (root.exists() && root.isDirectory) {
                            scanRecursive(root, folder.uriString, tempImages, favoriteUris)
                        }
                    } else {
                        // SAF logic
                        scanSafRecursive(uri, folder.uriString, tempImages, favoriteUris)
                    }
                } catch (e: Exception) {
                    Log.e("HomeViewModel", "Error scanning for ${folder.displayName}", e)
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
                val isFav = favoriteUris.contains(fileUriStr)
                list.add(WallpaperImg(fileUriStr, rootUri, f.name, isFav))
            } else if (f.isDirectory && !f.name.startsWith(".")) {
                scanRecursive(f, rootUri, list, favoriteUris)
            }
        }
    }

    private fun scanSafRecursive(treeUri: Uri, rootUriStr: String, list: MutableList<WallpaperImg>, favoriteUris: Set<String>) {
        val context = getApplication<Application>()
        val folderQueue = java.util.ArrayDeque<Uri>()
        folderQueue.add(treeUri)

        while (folderQueue.isNotEmpty()) {
            val currentUri = folderQueue.poll() ?: continue
            try {
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
                                val childUriStr = childUri.toString()
                                val isFav = favoriteUris.contains(childUriStr)
                                list.add(WallpaperImg(childUriStr, rootUriStr, name, isFav))
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("HomeViewModel", "SAF Scan Error", e)
            }
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
