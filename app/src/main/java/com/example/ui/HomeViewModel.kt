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
import com.example.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class HomeViewModel(application: Application) : AndroidViewModel(application) {
    private val db = AppDatabase.getDatabase(application)
    private val folderDao = db.folderDao()
    private val favoriteDao = db.favoriteDao()
    private val presetDao = db.presetDao()
    private val scannedImageDao = db.scannedImageDao()

    val prefs = application.getSharedPreferences("multi_wallpaper_prefs", Context.MODE_PRIVATE)

    val folders: StateFlow<List<FolderEntity>> = folderDao.getAllFolders()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val favorites: StateFlow<List<FavoriteImageEntity>> = favoriteDao.getAllFavorites()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val presets: StateFlow<List<PresetEntity>> = presetDao.getAllPresets()
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

    private val _fadeSpeed = MutableStateFlow(prefs.getInt("fade_speed", 15))
    val fadeSpeed = _fadeSpeed.asStateFlow()

    private val _parallaxEnabled = MutableStateFlow(prefs.getBoolean("parallax_enabled", false))
    val parallaxEnabled = _parallaxEnabled.asStateFlow()

    private val _parallaxStrength = MutableStateFlow(prefs.getFloat("parallax_strength", 0.5f))
    val parallaxStrength = _parallaxStrength.asStateFlow()

    private val _gallerySortType = MutableStateFlow(prefs.getString("gallery_sort_type", "NAME") ?: "NAME")
    val gallerySortType = _gallerySortType.asStateFlow()

    private val _selectedGalleryFolderUris = MutableStateFlow<Set<String>>(emptySet())
    val selectedGalleryFolderUris = _selectedGalleryFolderUris.asStateFlow()

    private val _gallerySearchQuery = MutableStateFlow("")
    val gallerySearchQuery = _gallerySearchQuery.asStateFlow()

    private val _activePresetName = MutableStateFlow<String?>(null)
    val activePresetName = _activePresetName.asStateFlow()

    init {
        viewModelScope.launch {
            scannedImageDao.getAllImages().collect { entities ->
                val favUris = withContext(Dispatchers.IO) {
                    favoriteDao.getAllFavoritesSync().map { it.uriString }.toSet()
                }
                _scannedImages.value = entities.map { 
                    WallpaperImg(it.uriString, it.folderUriString, it.displayName, favUris.contains(it.uriString))
                }
            }
        }

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

    fun setFadeSpeed(speed: Int) {
        prefs.edit().putInt("fade_speed", speed).apply()
        _fadeSpeed.value = speed
    }

    fun setParallaxEnabled(enable: Boolean) {
        prefs.edit().putBoolean("parallax_enabled", enable).apply()
        _parallaxEnabled.value = enable
    }

    fun setParallaxStrength(strength: Float) {
        prefs.edit().putFloat("parallax_strength", strength).apply()
        _parallaxStrength.value = strength
    }

    fun saveCurrentAsPreset(name: String) {
        viewModelScope.launch {
            val folderUris = folders.value.map { it.uriString }
            val thumb = favorites.value.firstOrNull()?.uriString ?: scannedImages.value.firstOrNull()?.uriString

            val moshi = com.squareup.moshi.Moshi.Builder().add(com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory()).build()
            val type = com.squareup.moshi.Types.newParameterizedType(List::class.java, FavoriteImageEntity::class.java)
            val adapter = moshi.adapter<List<FavoriteImageEntity>>(type)
            val favJson = adapter.toJson(favorites.value)

            val existing = presets.value.find { it.name.equals(name, ignoreCase = true) }
            if (existing != null) {
                val updated = existing.copy(
                    thumbnailUri = thumb,
                    folderUris = folderUris,
                    favoriteData = favJson,
                    createdTime = System.currentTimeMillis()
                )
                presetDao.updatePreset(updated)
            } else {
                val preset = PresetEntity(
                    name = name,
                    thumbnailUri = thumb,
                    folderUris = folderUris,
                    favoriteData = favJson
                )
                presetDao.insertPreset(preset)
            }
            _activePresetName.value = name
        }
    }

    fun updateActivePreset() {
        val name = _activePresetName.value ?: return
        saveCurrentAsPreset(name)
    }

    fun setGallerySortType(type: String) {
        prefs.edit().putString("gallery_sort_type", type).apply()
        _gallerySortType.value = type
    }

    fun setGallerySearchQuery(query: String) {
        _gallerySearchQuery.value = query
    }

    fun toggleGalleryFolderSelection(uri: String) {
        val current = _selectedGalleryFolderUris.value.toMutableSet()
        if (current.contains(uri)) current.remove(uri) else current.add(uri)
        _selectedGalleryFolderUris.value = current
    }

    fun clearGalleryFolderSelection() {
        _selectedGalleryFolderUris.value = emptySet()
    }

    fun toggleFavoriteSelectedFolders() {
        viewModelScope.launch {
            val folderUris = _selectedGalleryFolderUris.value
            if (folderUris.isEmpty()) return@launch

            // Check if ANY image in these folders is NOT a favorite
            val allImagesInSelected = scannedImages.value.filter { folderUris.contains(it.folderUriString) }
            val allFav = allImagesInSelected.all { it.isFavorite }

            if (allFav) {
                // Unstar all in these folders
                allImagesInSelected.forEach { toggleFavorite(it) }
            } else {
                // Star all in these folders
                allImagesInSelected.filter { !it.isFavorite }.forEach { toggleFavorite(it) }
            }
            clearGalleryFolderSelection()
        }
    }

    fun loadPreset(preset: PresetEntity) {
        viewModelScope.launch {
            _activePresetName.value = preset.name
            folderDao.deleteAllFolders()
            favoriteDao.deleteAllFavorites() // Fix: Clear previous favorites

            val folderEntities = preset.folderUris.map { uri ->
                val name = try {
                    val u = Uri.parse(uri)
                    if (u.scheme == "file") File(u.path!!).name else Uri.decode(uri).split("/").lastOrNull() ?: "Folder"
                } catch (e: Exception) { "Folder" }
                FolderEntity(uriString = uri, displayName = name)
            }
            folderDao.insertFolders(folderEntities)

            // Restore Favorites
            try {
                val moshi = com.squareup.moshi.Moshi.Builder().add(com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory()).build()
                val type = com.squareup.moshi.Types.newParameterizedType(List::class.java, FavoriteImageEntity::class.java)
                val adapter = moshi.adapter<List<FavoriteImageEntity>>(type)
                val favs = adapter.fromJson(preset.favoriteData)

                if (favs != null) {
                    favoriteDao.insertFavorites(favs)
                }
            } catch (e: Exception) {
                Log.e("HomeViewModel", "Error loading favorites from preset", e)
            }
            
            // Re-scan to update cached images for the new preset folders
            scanFolders()
        }
    }

    fun deletePreset(preset: PresetEntity) {
        viewModelScope.launch {
            presetDao.deletePreset(preset)
        }
    }

    fun addFolders(uris: List<Uri>) {
        viewModelScope.launch(Dispatchers.IO) {
            val contentResolver = getApplication<Application>().contentResolver
            val currentFolderUris = folders.value.map { it.uriString }.toSet()
            val foldersToInsert = mutableListOf<FolderEntity>()

            uris.forEach { uri ->
                try {
                    val uriStr = uri.toString()
                    if (currentFolderUris.contains(uriStr)) return@forEach // Skip duplicates

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
            favoriteDao.deleteFavoritesByFolderUri(folder.uriString)
        }
    }

    fun deleteSelectedFolders() {
        viewModelScope.launch(Dispatchers.IO) {
            val selectedIds = _selectedFolderIds.value.toList()
            val folderUrisToDelete = folders.value.filter { selectedIds.contains(it.id) }.map { it.uriString }

            selectedIds.forEach { folderDao.deleteFolderById(it) }
            folderUrisToDelete.forEach { uri -> favoriteDao.deleteFavoritesByFolderUri(uri) }

            withContext(Dispatchers.Main) {
                clearFolderIdSelection()
            }
        }
    }

    fun clearAllFolders() {
        viewModelScope.launch(Dispatchers.IO) {
            _activePresetName.value = null
            folderDao.deleteAllFolders()
            favoriteDao.deleteAllFavorites()
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

            // Sync with Database Cache
            scannedImageDao.deleteAllImages()
            scannedImageDao.insertImages(tempImages.map { 
                ScannedImageEntity(it.uriString, it.folderUriString, it.displayName)
            })

            _isScanning.value = false
        }
    }

    private fun scanRecursive(file: java.io.File, rootUri: String, list: MutableList<WallpaperImg>, favoriteUris: Set<String>) {
        val files = file.listFiles()
        files?.forEach { f ->
            if (f.isFile && (f.name.endsWith(".jpg", true) || f.name.endsWith(".png", true) || f.name.endsWith(".webp", true))) {
                val fileUriStr = Uri.fromFile(f).toString()
                // Use immediate parent as folderUriString for better search/grouping
                val parentUriStr = Uri.fromFile(f.parentFile).toString()
                list.add(WallpaperImg(fileUriStr, parentUriStr, f.name, favoriteUris.contains(fileUriStr)))
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
                                // For SAF, the currentUri is the immediate parent
                                list.add(WallpaperImg(childUriStr, currentUri.toString(), name, favoriteUris.contains(childUriStr)))
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