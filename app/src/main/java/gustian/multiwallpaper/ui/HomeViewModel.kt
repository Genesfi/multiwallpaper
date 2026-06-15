package gustian.multiwallpaper.ui

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
import gustian.multiwallpaper.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.yield
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class HomeViewModel(application: Application) : AndroidViewModel(application) {
    private val db = AppDatabase.getDatabase(application)
    private val folderDao = db.folderDao()
    private val favoriteDao = db.favoriteDao()
    private val presetDao = db.presetDao()
    private val scannedImageDao = db.scannedImageDao()
    private val blacklistedDao = db.blacklistedDao()

    val prefs = application.getSharedPreferences("multi_wallpaper_prefs", Context.MODE_PRIVATE)

    val folders: StateFlow<List<FolderEntity>> = folderDao.getAllFolders()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val favorites: StateFlow<List<FavoriteImageEntity>> = favoriteDao.getAllFavorites()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val presets: StateFlow<List<PresetEntity>> = presetDao.getAllPresets()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val blacklisted: StateFlow<List<BlacklistedImageEntity>> = blacklistedDao.getAllBlacklisted()
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

    private val _shakeEnabled = MutableStateFlow(prefs.getBoolean("shake_enabled", false))
    val shakeEnabled = _shakeEnabled.asStateFlow()

    private val _smartCropEnabled = MutableStateFlow(prefs.getBoolean("smart_crop_enabled", true))
    val smartCropEnabled = _smartCropEnabled.asStateFlow()

    private val _lightModeEnabled = MutableStateFlow(prefs.getBoolean("light_mode_enabled", false))
    val lightModeEnabled = _lightModeEnabled.asStateFlow()

    private val _aiAdvancedEnabled = MutableStateFlow(prefs.getBoolean("ai_advanced_enabled", false))
    val aiAdvancedEnabled = _aiAdvancedEnabled.asStateFlow()

    private val _aiZoomSlack = MutableStateFlow(prefs.getFloat("ai_zoom_slack", 1.45f))
    val aiZoomSlack = _aiZoomSlack.asStateFlow()

    private val _aiSensitivityX = MutableStateFlow(prefs.getFloat("ai_sensitivity_x", 0.9f))
    val aiSensitivityX = _aiSensitivityX.asStateFlow()

    private val _aiSensitivityY = MutableStateFlow(prefs.getFloat("ai_sensitivity_y", 0.4f))
    val aiSensitivityY = _aiSensitivityY.asStateFlow()

    private val _gallerySortType = MutableStateFlow(prefs.getString("gallery_sort_type", "NAME") ?: "NAME")
    val gallerySortType = _gallerySortType.asStateFlow()

    private val _selectedGalleryFolderUris = MutableStateFlow<Set<String>>(emptySet())
    val selectedGalleryFolderUris = _selectedGalleryFolderUris.asStateFlow()

    private val _gallerySearchQuery = MutableStateFlow("")
    val gallerySearchQuery = _gallerySearchQuery.asStateFlow()

    private val _activePresetName = MutableStateFlow<String?>(null)
    val activePresetName = _activePresetName.asStateFlow()

    private val _isLoadingPreset = MutableStateFlow(false)
    val isLoadingPreset = _isLoadingPreset.asStateFlow()

    private val _blurRadius = MutableStateFlow(prefs.getFloat("blur_radius", 0f))
    val blurRadius = _blurRadius.asStateFlow()

    private val _dimIntensity = MutableStateFlow(prefs.getFloat("dim_intensity", 0f))
    val dimIntensity = _dimIntensity.asStateFlow()

    private val _blurEnabled = MutableStateFlow(prefs.getBoolean("blur_enabled", false))
    val blurEnabled = _blurEnabled.asStateFlow()

    private val _dimEnabled = MutableStateFlow(prefs.getBoolean("dim_enabled", false))
    val dimEnabled = _dimEnabled.asStateFlow()

    private val _subjectFocusEnabled = MutableStateFlow(prefs.getBoolean("subject_focus_enabled", false))
    val subjectFocusEnabled = _subjectFocusEnabled.asStateFlow()

    private val _subjectFocusSmoothing = MutableStateFlow(prefs.getFloat("subject_focus_smoothing", 0.5f))
    val subjectFocusSmoothing = _subjectFocusSmoothing.asStateFlow()

    private val _latestVersionInfo = MutableStateFlow<UpdateInfo?>(null)
    val latestVersionInfo = _latestVersionInfo.asStateFlow()

    private val _isCheckingUpdate = MutableStateFlow(false)
    val isCheckingUpdate = _isCheckingUpdate.asStateFlow()
    
    private var scanJob: Job? = null

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
            folders.debounce(3000).collect { // Increased debounce to 3s to reduce boot contention
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
        prefs.edit().putBoolean("use_favorites_only", enable)
            .putBoolean("force_reload_trigger", true) // Force service to react
            .apply()
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

    fun setShakeEnabled(enable: Boolean) {
        prefs.edit().putBoolean("shake_enabled", enable).apply()
        _shakeEnabled.value = enable
    }

    fun setSmartCropEnabled(enable: Boolean) {
        prefs.edit().putBoolean("smart_crop_enabled", enable).apply()
        _smartCropEnabled.value = enable
    }

    fun setLightModeEnabled(enable: Boolean) {
        prefs.edit().putBoolean("light_mode_enabled", enable).apply()
        _lightModeEnabled.value = enable
    }

    fun setAiAdvancedEnabled(enable: Boolean) {
        prefs.edit().putBoolean("ai_advanced_enabled", enable).apply()
        _aiAdvancedEnabled.value = enable
    }

    fun setAiZoomSlack(value: Float) {
        prefs.edit().putFloat("ai_zoom_slack", value).apply()
        _aiZoomSlack.value = value
    }

    fun setAiSensitivityX(value: Float) {
        prefs.edit().putFloat("ai_sensitivity_x", value).apply()
        _aiSensitivityX.value = value
    }

    fun setAiSensitivityY(value: Float) {
        prefs.edit().putFloat("ai_sensitivity_y", value).apply()
        _aiSensitivityY.value = value
    }

    fun setBlurRadius(value: Float) {
        prefs.edit().putFloat("blur_radius", value).apply()
        _blurRadius.value = value
    }

    fun setDimIntensity(value: Float) {
        prefs.edit().putFloat("dim_intensity", value).apply()
        _dimIntensity.value = value
    }

    fun setBlurEnabled(enable: Boolean) {
        prefs.edit().putBoolean("blur_enabled", enable).apply()
        _blurEnabled.value = enable
    }

    fun setDimEnabled(enable: Boolean) {
        prefs.edit().putBoolean("dim_enabled", enable).apply()
        _dimEnabled.value = enable
    }

    fun setSubjectFocusEnabled(enable: Boolean) {
        prefs.edit().putBoolean("subject_focus_enabled", enable).apply()
        _subjectFocusEnabled.value = enable
    }

    fun setSubjectFocusSmoothing(value: Float) {
        prefs.edit().putFloat("subject_focus_smoothing", value).apply()
        _subjectFocusSmoothing.value = value
    }

    fun checkForUpdates() {
        viewModelScope.launch(Dispatchers.IO) {
            _isCheckingUpdate.value = true
            try {
                val url = java.net.URL("https://api.github.com/repos/Genesfi/multiwallpaper/releases/latest")
                val connection = url.openConnection() as java.net.HttpURLConnection
                connection.requestMethod = "GET"
                connection.setRequestProperty("Accept", "application/vnd.github.v3+json")
                
                if (connection.responseCode == 200) {
                    val response = connection.inputStream.bufferedReader().use { it.readText() }
                    val json = org.json.JSONObject(response)
                    val tagName = json.getString("tag_name")
                    val body = json.getString("body")
                    val htmlUrl = json.getString("html_url")
                    
                    withContext(Dispatchers.Main) {
                        _latestVersionInfo.value = UpdateInfo(tagName, body, htmlUrl)
                    }
                }
            } catch (e: Exception) {
                Log.e("HomeViewModel", "Failed to check for updates", e)
            } finally {
                _isCheckingUpdate.value = false
            }
        }
    }

    fun dismissUpdateDialog() {
        _latestVersionInfo.value = null
    }

    fun blacklistCurrentUri(uri: String, folderUri: String, displayName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            blacklistedDao.insertBlacklist(BlacklistedImageEntity(uri, folderUri, displayName))
            favoriteDao.deleteFavoriteByUriSync(uri)
            scannedImageDao.deleteImageByUriSync(uri)
        }
    }

    fun blacklistSelectedImages() {
        viewModelScope.launch(Dispatchers.IO) {
            val uris = _selectedGalleryUris.value
            val images = _scannedImages.value.filter { uris.contains(it.uriString) }

            images.forEach { img ->
                blacklistedDao.insertBlacklist(
                    BlacklistedImageEntity(
                        uriString = img.uriString,
                        folderUriString = img.folderUriString,
                        displayName = img.displayName
                    )
                )
                favoriteDao.deleteFavoriteByUriSync(img.uriString)
                scannedImageDao.deleteImageByUriSync(img.uriString)
            }

            withContext(Dispatchers.Main) {
                Toast.makeText(getApplication(), "${images.size} items blacklisted", Toast.LENGTH_SHORT).show()
                clearGallerySelection()
            }
        }
    }

    fun restoreBlacklistedImage(entity: BlacklistedImageEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            blacklistedDao.deleteBlacklist(entity)
            // Trigger a re-scan in background but don't force a heavy service reload immediately
            scanFolders()
        }
    }

    suspend fun saveCurrentAsPresetSuspend(name: String) {
        val currentFolders = folders.value.map { it.uriString }
        // Fetch the ACTUAL current favorites from DB instead of relying on StateFlow which might be lagging
        val currentFavs = withContext(Dispatchers.IO) { favoriteDao.getAllFavoritesSync() }
        val thumb = currentFavs.firstOrNull()?.uriString ?: scannedImages.value.firstOrNull()?.uriString

        val moshi = com.squareup.moshi.Moshi.Builder().add(com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory()).build()
        val type = com.squareup.moshi.Types.newParameterizedType(List::class.java, FavoriteImageEntity::class.java)
        val adapter = moshi.adapter<List<FavoriteImageEntity>>(type)
        val favJson = adapter.toJson(currentFavs)

        val existing = presets.value.find { it.name.equals(name, ignoreCase = true) }
        if (existing != null) {
            val updated = existing.copy(
                thumbnailUri = thumb,
                folderUris = currentFolders,
                favoriteData = favJson,
                createdTime = System.currentTimeMillis()
            )
            presetDao.updatePreset(updated)
        } else {
            val preset = PresetEntity(
                name = name,
                thumbnailUri = thumb,
                folderUris = currentFolders,
                favoriteData = favJson
            )
            presetDao.insertPreset(preset)
        }
        _activePresetName.value = name
    }

    fun saveCurrentAsPreset(name: String) {
        viewModelScope.launch {
            saveCurrentAsPresetSuspend(name)
            triggerReload()
            withContext(Dispatchers.Main) {
                Toast.makeText(getApplication(), "Preset '$name' saved", Toast.LENGTH_SHORT).show()
            }
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
            _isLoadingPreset.value = true
            try {
                // Auto-save current state to the PREVIOUS active preset before switching
                _activePresetName.value?.let { activeName ->
                    saveCurrentAsPresetSuspend(activeName)
                }

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
                var favoriteCount = 0
                try {
                    val moshi = com.squareup.moshi.Moshi.Builder().add(com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory()).build()
                    val type = com.squareup.moshi.Types.newParameterizedType(List::class.java, FavoriteImageEntity::class.java)
                    val adapter = moshi.adapter<List<FavoriteImageEntity>>(type)
                    val favs = adapter.fromJson(preset.favoriteData)

                    if (favs != null) {
                        favoriteDao.insertFavorites(favs)
                        favoriteCount = favs.size
                    }
                } catch (e: Exception) {
                    Log.e("HomeViewModel", "Error loading favorites from preset", e)
                }

                // Auto-Fallback: If preset has no favorites, disable "Use Favorites Only"
                if (favoriteCount == 0 && _useFavoritesOnly.value) {
                    setUseFavoritesOnly(false)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(getApplication(), "Favorites Only turned OFF (No favorites in preset)", Toast.LENGTH_SHORT).show()
                    }
                }
                
                // Re-scan to update cached images for the new preset folders
                // We await scanning to ensure sync with service trigger
                withContext(Dispatchers.IO) {
                    scanFoldersSync()
                }
                triggerReload()
            } finally {
                _isLoadingPreset.value = false
            }
        }
    }

    private suspend fun scanFoldersSync() {
        val foldersList = folders.value
        val tempImages = mutableListOf<WallpaperImg>()
        val favoriteUris = favoriteDao.getAllFavoritesSync().map { it.uriString }.toSet()
        val blacklistedUris = blacklistedDao.getAllBlacklistedSync().map { it.uriString }.toSet()

        for (folder in foldersList) {
            yield()
            try {
                val uri = Uri.parse(folder.uriString)
                if (uri.scheme == "file") {
                    val root = java.io.File(uri.path ?: "")
                    if (root.exists() && root.isDirectory) scanRecursive(root, folder.uriString, tempImages, favoriteUris, blacklistedUris)
                } else {
                    scanSafRecursive(uri, folder.uriString, tempImages, favoriteUris, blacklistedUris)
                }
            } catch (e: Exception) {
                Log.e("HomeViewModel", "Scan error", e)
            }
        }

        yield()
        // Sync with Database Cache
        scannedImageDao.deleteAllImages()
        scannedImageDao.insertImages(tempImages.map { 
            ScannedImageEntity(it.uriString, it.folderUriString, it.displayName)
        })
    }

    fun exportPresets() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val allPresets = presetDao.getAllPresets().first()
                val moshi = com.squareup.moshi.Moshi.Builder().add(com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory()).build()
                val type = com.squareup.moshi.Types.newParameterizedType(List::class.java, PresetEntity::class.java)
                val adapter = moshi.adapter<List<PresetEntity>>(type)
                val json = adapter.toJson(allPresets)

                val file = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "multi_wallpaper_presets.json")
                file.writeText(json)

                withContext(Dispatchers.Main) {
                    Toast.makeText(getApplication(), "Exported to Downloads/multi_wallpaper_presets.json", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Log.e("HomeViewModel", "Export fail", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(getApplication(), "Export failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun importPresets(json: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val moshi = com.squareup.moshi.Moshi.Builder().add(com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory()).build()
                val type = com.squareup.moshi.Types.newParameterizedType(List::class.java, PresetEntity::class.java)
                val adapter = moshi.adapter<List<PresetEntity>>(type)
                val imported = adapter.fromJson(json)

                imported?.forEach {
                    // Check for duplicate names, maybe append (Imported) if exists
                    val existing = presets.value.find { p -> p.name == it.name }
                    val finalPreset = if (existing != null) it.copy(id = 0, name = "${it.name} (Imported)") else it.copy(id = 0)
                    presetDao.insertPreset(finalPreset)
                }

                withContext(Dispatchers.Main) {
                    Toast.makeText(getApplication(), "Imported ${imported?.size ?: 0} presets", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e("HomeViewModel", "Import fail", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(getApplication(), "Import failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun triggerReload() {
        prefs.edit().putBoolean("force_reload_trigger", true).apply()
        Toast.makeText(getApplication(), "Wallpaper reload triggered", Toast.LENGTH_SHORT).show()
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
        scanJob?.cancel()
        scanJob = viewModelScope.launch(Dispatchers.IO) {
            _isScanning.value = true
            try {
                scanFoldersSync()
            } finally {
                _isScanning.value = false
            }
        }
    }

    private fun scanRecursive(file: java.io.File, rootUri: String, list: MutableList<WallpaperImg>, favoriteUris: Set<String>, blacklistedUris: Set<String>) {
        val files = file.listFiles()
        files?.forEach { f ->
            if (f.isFile && (f.name.endsWith(".jpg", true) || f.name.endsWith(".png", true) || f.name.endsWith(".webp", true))) {
                val fileUriStr = Uri.fromFile(f).toString()
                if (!blacklistedUris.contains(fileUriStr)) {
                    // Use immediate parent as folderUriString for better search/grouping
                    val parentUriStr = Uri.fromFile(f.parentFile).toString()
                    list.add(WallpaperImg(fileUriStr, parentUriStr, f.name, favoriteUris.contains(fileUriStr)))
                }
            } else if (f.isDirectory && !f.name.startsWith(".")) {
                scanRecursive(f, rootUri, list, favoriteUris, blacklistedUris)
            }
        }
    }

    private fun scanSafRecursive(treeUri: Uri, rootUriStr: String, list: MutableList<WallpaperImg>, favoriteUris: Set<String>, blacklistedUris: Set<String>) {
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
                                if (!blacklistedUris.contains(childUriStr)) {
                                    // For SAF, the currentUri is the immediate parent
                                    list.add(WallpaperImg(childUriStr, currentUri.toString(), name, favoriteUris.contains(childUriStr)))
                                }
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

data class UpdateInfo(
    val tagName: String,
    val changelog: String,
    val downloadUrl: String
)
