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

enum class SettingTarget { HOME, LOCK }

class HomeViewModel(application: Application) : AndroidViewModel(application) {
    private val db = AppDatabase.getDatabase(application)
    private val folderDao = db.folderDao()
    private val favoriteDao = db.favoriteDao()
    private val presetDao = db.presetDao()
    private val scannedImageDao = db.scannedImageDao()
    private val blacklistedDao = db.blacklistedDao()
    private val historyDao = db.rotationHistoryDao()
    private val scheduleDao = db.scheduleDao()
    private val customPaletteDao = db.customPaletteDao()

    private val _settingsTarget = MutableStateFlow(SettingTarget.HOME)
    val settingsTarget = _settingsTarget.asStateFlow()

    private var currentPrefs: android.content.SharedPreferences? = application.getSharedPreferences("multi_wallpaper_prefs", Context.MODE_PRIVATE)

    val folders: StateFlow<List<FolderEntity>> = settingsTarget.flatMapLatest { target ->
        folderDao.getAllFolders(target.name)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val favorites: StateFlow<List<FavoriteImageEntity>> = settingsTarget.flatMapLatest { target ->
        favoriteDao.getAllFavorites(target.name)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val presets: StateFlow<List<PresetEntity>> = settingsTarget.flatMapLatest { target ->
        presetDao.getAllPresets(target.name)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val schedules: StateFlow<List<ScheduleEntity>> = settingsTarget.flatMapLatest { target ->
        scheduleDao.getAllSchedules(target.name)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

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
    private val _intervalSeconds = MutableStateFlow(60f)
    val intervalSeconds = _intervalSeconds.asStateFlow()

    private val _useFavoritesOnly = MutableStateFlow(false)
    val useFavoritesOnly = _useFavoritesOnly.asStateFlow()

    private val _transitionType = MutableStateFlow("slide")
    val transitionType = _transitionType.asStateFlow()

    private val _doubleTapEnabled = MutableStateFlow(true)
    val doubleTapEnabled = _doubleTapEnabled.asStateFlow()

    private val _fadeSpeed = MutableStateFlow(15)
    val fadeSpeed = _fadeSpeed.asStateFlow()

    private val _parallaxEnabled = MutableStateFlow(false)
    val parallaxEnabled = _parallaxEnabled.asStateFlow()

    private val _parallaxStrength = MutableStateFlow(0.5f)
    val parallaxStrength = _parallaxStrength.asStateFlow()

    private val _shakeEnabled = MutableStateFlow(false)
    val shakeEnabled = _shakeEnabled.asStateFlow()

    private val _shakeSensitivity = MutableStateFlow(0.9f)
    val shakeSensitivity = _shakeSensitivity.asStateFlow()

    private val _smartCropEnabled = MutableStateFlow(true)
    val smartCropEnabled = _smartCropEnabled.asStateFlow()

    private val _lightModeEnabled = MutableStateFlow(false)
    val lightModeEnabled = _lightModeEnabled.asStateFlow()

    private val _wallpaperQuality = MutableStateFlow("NORMAL")
    val wallpaperQuality = _wallpaperQuality.asStateFlow()

    private val _aiAdvancedEnabled = MutableStateFlow(false)
    val aiAdvancedEnabled = _aiAdvancedEnabled.asStateFlow()

    private val _aiZoomSlack = MutableStateFlow(1.45f)
    val aiZoomSlack = _aiZoomSlack.asStateFlow()

    private val _aiSensitivityX = MutableStateFlow(0.9f)
    val aiSensitivityX = _aiSensitivityX.asStateFlow()

    private val _aiSensitivityY = MutableStateFlow(0.4f)
    val aiSensitivityY = _aiSensitivityY.asStateFlow()

    private val _gallerySortType = MutableStateFlow("NAME")
    val gallerySortType = _gallerySortType.asStateFlow()

    private val _gallerySortOrder = MutableStateFlow("DESC")
    val gallerySortOrder = _gallerySortOrder.asStateFlow()

    private val _selectedGalleryFolderUris = MutableStateFlow<Set<String>>(emptySet())
    val selectedGalleryFolderUris = _selectedGalleryFolderUris.asStateFlow()

    private val _gallerySearchQuery = MutableStateFlow("")
    val gallerySearchQuery = _gallerySearchQuery.asStateFlow()

    private val _activePresetName = MutableStateFlow<String?>(null)
    val activePresetName = _activePresetName.asStateFlow()

    private val prefsListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
        if (key == "active_preset_name") {
            _activePresetName.value = prefs.getString("active_preset_name", null)
        }
    }

    private val _isLoadingPreset = MutableStateFlow(false)
    val isLoadingPreset = _isLoadingPreset.asStateFlow()

    private val _blurRadius = MutableStateFlow(0f)
    val blurRadius = _blurRadius.asStateFlow()

    private val _dimIntensity = MutableStateFlow(0f)
    val dimIntensity = _dimIntensity.asStateFlow()

    private val _blurEnabled = MutableStateFlow(false)
    val blurEnabled = _blurEnabled.asStateFlow()

    private val _dimEnabled = MutableStateFlow(false)
    val dimEnabled = _dimEnabled.asStateFlow()

    private val _subjectFocusEnabled = MutableStateFlow(false)
    val subjectFocusEnabled = _subjectFocusEnabled.asStateFlow()

    private val _subjectFocusSmoothing = MutableStateFlow(0.5f)
    val subjectFocusSmoothing = _subjectFocusSmoothing.asStateFlow()

    private val _vignetteModeEnabled = MutableStateFlow(false)
    val vignetteModeEnabled = _vignetteModeEnabled.asStateFlow()

    private val _vignetteSharpness = MutableStateFlow(0.5f)
    val vignetteSharpness = _vignetteSharpness.asStateFlow()

    private val _vignetteWidth = MutableStateFlow(0.2f)
    val vignetteWidth = _vignetteWidth.asStateFlow()

    private val _latestVersionInfo = MutableStateFlow<UpdateInfo?>(null)
    val latestVersionInfo = _latestVersionInfo.asStateFlow()

    private val _isCheckingUpdate = MutableStateFlow(false)
    val isCheckingUpdate = _isCheckingUpdate.asStateFlow()

    private val _updateMessage = MutableStateFlow<String?>(null)
    val updateMessage = _updateMessage.asStateFlow()

    private val _smartAdjacencyEnabled = MutableStateFlow(true)
    val smartAdjacencyEnabled = _smartAdjacencyEnabled.asStateFlow()

    private val _rotationSortOrder = MutableStateFlow("RANDOM")
    val rotationSortOrder = _rotationSortOrder.asStateFlow()

    private val _historyLimit = MutableStateFlow(150)
    val historyLimit = _historyLimit.asStateFlow()

    private val _autoLimitEnabled = MutableStateFlow(false)
    val autoLimitEnabled = _autoLimitEnabled.asStateFlow()

    val historyCount = settingsTarget.flatMapLatest { target ->
        historyDao.getHistoryCount(target.name)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)
    
    private val _manualFocalX = MutableStateFlow(0.5f)
    val manualFocalX = _manualFocalX.asStateFlow()

    private val _manualFocalY = MutableStateFlow(0.4f)
    val manualFocalY = _manualFocalY.asStateFlow()

    private val _manualPageCount = MutableStateFlow(0)
    val manualPageCount = _manualPageCount.asStateFlow()

    private val _filterType = MutableStateFlow("NONE")
    val filterType = _filterType.asStateFlow()

    val customPalettes: StateFlow<List<CustomPaletteEntity>> = _filterType.flatMapLatest { type ->
        if (type == "DUOTONE" || type == "TRITONE") customPaletteDao.getPalettesByType(type)
        else flowOf(emptyList())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _filterColor1 = MutableStateFlow(0xFF000000.toInt()) // Default Black
    val filterColor1 = _filterColor1.asStateFlow()

    private val _filterColor2 = MutableStateFlow(0xFFFFFFFF.toInt()) // Default White
    val filterColor2 = _filterColor2.asStateFlow()

    private val _filterColor3 = MutableStateFlow(0xFF808080.toInt()) // Default Gray (for Tritone Midtone)
    val filterColor3 = _filterColor3.asStateFlow()

    private val _historyList = MutableStateFlow<List<String>>(emptyList())
    val historyList: StateFlow<List<String>> = _historyList.asStateFlow()

    private val _isLoadingHistory = MutableStateFlow(false)
    val isLoadingHistory: StateFlow<Boolean> = _isLoadingHistory.asStateFlow()

    private var hasMoreHistory = true

    fun resetAndLoadHistory() {
        _historyList.value = emptyList()
        hasMoreHistory = true
        loadMoreHistory()
    }

    fun loadMoreHistory() {
        if (_isLoadingHistory.value || !hasMoreHistory) return
        
        viewModelScope.launch(Dispatchers.IO) {
            _isLoadingHistory.value = true
            val targetName = _settingsTarget.value.name
            val currentSize = _historyList.value.size
            val more = historyDao.getHistoryPaged(targetName, 100, currentSize)
            
            if (more.isNotEmpty()) {
                _historyList.value = _historyList.value + more
                hasMoreHistory = more.size == 100
            } else {
                hasMoreHistory = false
            }
            _isLoadingHistory.value = false
        }
    }

    fun hasMoreHistory(): Boolean = hasMoreHistory

    fun setSettingsTarget(target: SettingTarget) {
        currentPrefs?.unregisterOnSharedPreferenceChangeListener(prefsListener)
        _settingsTarget.value = target
        val prefsName = if (target == SettingTarget.HOME) "multi_wallpaper_prefs" else "multi_wallpaper_prefs_lock"
        Log.d("MultiWallpaper", "ViewModel setSettingsTarget: $target using $prefsName")
        currentPrefs = getApplication<Application>().getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        currentPrefs?.registerOnSharedPreferenceChangeListener(prefsListener)
        loadSettings()
    }

    private fun loadSettings() {
        val prefs = currentPrefs ?: return
        _activePresetName.value = prefs.getString("active_preset_name", null)
        _intervalSeconds.value = prefs.getFloat("interval_seconds", 60f)
        _useFavoritesOnly.value = prefs.getBoolean("use_favorites_only", false)
        _transitionType.value = prefs.getString("transition_type", "slide") ?: "slide"
        _doubleTapEnabled.value = prefs.getBoolean("double_tap_enabled", true)
        _fadeSpeed.value = prefs.getInt("fade_speed", 15)
        _parallaxEnabled.value = prefs.getBoolean("parallax_enabled", false)
        _parallaxStrength.value = prefs.getFloat("parallax_strength", 0.5f)
        _shakeEnabled.value = prefs.getBoolean("shake_enabled", false)
        _shakeSensitivity.value = prefs.getFloat("shake_sensitivity", 0.9f)
        _smartCropEnabled.value = prefs.getBoolean("smart_crop_enabled", true)
        _lightModeEnabled.value = prefs.getBoolean("light_mode_enabled", false)
        _wallpaperQuality.value = prefs.getString("wallpaper_quality", "NORMAL") ?: "NORMAL"
        _aiAdvancedEnabled.value = prefs.getBoolean("ai_advanced_enabled", false)
        _aiZoomSlack.value = prefs.getFloat("ai_zoom_slack", 1.45f)
        _aiSensitivityX.value = prefs.getFloat("ai_sensitivity_x", 0.9f)
        _aiSensitivityY.value = prefs.getFloat("ai_sensitivity_y", 0.4f)
        _gallerySortType.value = prefs.getString("gallery_sort_type", "NAME") ?: "NAME"
        _gallerySortOrder.value = prefs.getString("gallery_sort_order", "DESC") ?: "DESC"
        _blurRadius.value = prefs.getFloat("blur_radius", 0f)
        _dimIntensity.value = prefs.getFloat("dim_intensity", 0f)
        _blurEnabled.value = prefs.getBoolean("blur_enabled", false)
        _dimEnabled.value = prefs.getBoolean("dim_enabled", false)
        _subjectFocusEnabled.value = prefs.getBoolean("subject_focus_enabled", false)
        _subjectFocusSmoothing.value = prefs.getFloat("subject_focus_smoothing", 0.5f)
        _vignetteModeEnabled.value = prefs.getBoolean("vignette_mode_enabled", false)
        _vignetteSharpness.value = prefs.getFloat("vignette_sharpness", 0.5f)
        _vignetteWidth.value = prefs.getFloat("vignette_width", 0.2f)
        _smartAdjacencyEnabled.value = prefs.getBoolean("smart_adjacency_enabled", true)
        _rotationSortOrder.value = prefs.getString("rotation_sort_order", "RANDOM") ?: "RANDOM"
        _historyLimit.value = prefs.getInt("history_limit", 150)
        _autoLimitEnabled.value = prefs.getBoolean("auto_limit_enabled", false)
        _manualFocalX.value = prefs.getFloat("manual_focal_x", 0.5f)
        _manualFocalY.value = prefs.getFloat("manual_focal_y", 0.4f)
        _manualPageCount.value = prefs.getInt("manual_page_count", 0)
        _filterType.value = prefs.getString("filter_type", "NONE") ?: "NONE"
        _filterColor1.value = prefs.getInt("filter_color_1", 0xFF000000.toInt())
        _filterColor2.value = prefs.getInt("filter_color_2", 0xFFFFFFFF.toInt())
        _filterColor3.value = prefs.getInt("filter_color_3", 0xFF808080.toInt())
        
        if (_autoLimitEnabled.value) {
            _historyLimit.value = _scannedImages.value.size.coerceAtLeast(150)
        }
    }

    fun copySettingsFromOther() {
        val otherPrefsName = if (_settingsTarget.value == SettingTarget.HOME) "multi_wallpaper_prefs_lock" else "multi_wallpaper_prefs"
        val otherPrefs = getApplication<Application>().getSharedPreferences(otherPrefsName, Context.MODE_PRIVATE)
        val current = currentPrefs ?: return
        
        current.edit().apply {
            putFloat("interval_seconds", otherPrefs.getFloat("interval_seconds", 60f))
            putBoolean("use_favorites_only", otherPrefs.getBoolean("use_favorites_only", false))
            putString("transition_type", otherPrefs.getString("transition_type", "slide"))
            putBoolean("double_tap_enabled", otherPrefs.getBoolean("double_tap_enabled", true))
            putInt("fade_speed", otherPrefs.getInt("fade_speed", 15))
            putBoolean("parallax_enabled", otherPrefs.getBoolean("parallax_enabled", false))
            putFloat("parallax_strength", otherPrefs.getFloat("parallax_strength", 0.5f))
            putBoolean("shake_enabled", otherPrefs.getBoolean("shake_enabled", false))
            putFloat("shake_sensitivity", otherPrefs.getFloat("shake_sensitivity", 0.9f))
            putBoolean("smart_crop_enabled", otherPrefs.getBoolean("smart_crop_enabled", true))
            putBoolean("light_mode_enabled", otherPrefs.getBoolean("light_mode_enabled", false))
            putString("wallpaper_quality", otherPrefs.getString("wallpaper_quality", "NORMAL"))
            putBoolean("ai_advanced_enabled", otherPrefs.getBoolean("ai_advanced_enabled", false))
            putFloat("ai_zoom_slack", otherPrefs.getFloat("ai_zoom_slack", 1.45f))
            putFloat("ai_sensitivity_x", otherPrefs.getFloat("ai_sensitivity_x", 0.9f))
            putFloat("ai_sensitivity_y", otherPrefs.getFloat("ai_sensitivity_y", 0.4f))
            putFloat("blur_radius", otherPrefs.getFloat("blur_radius", 0f))
            putFloat("dim_intensity", otherPrefs.getFloat("dim_intensity", 0f))
            putBoolean("blur_enabled", otherPrefs.getBoolean("blur_enabled", false))
            putBoolean("dim_enabled", otherPrefs.getBoolean("dim_enabled", false))
            putBoolean("subject_focus_enabled", otherPrefs.getBoolean("subject_focus_enabled", false))
            putFloat("subject_focus_smoothing", otherPrefs.getFloat("subject_focus_smoothing", 0.5f))
            putBoolean("vignette_mode_enabled", otherPrefs.getBoolean("vignette_mode_enabled", false))
            putFloat("vignette_sharpness", otherPrefs.getFloat("vignette_sharpness", 0.5f))
            putFloat("vignette_width", otherPrefs.getFloat("vignette_width", 0.2f))
            putBoolean("smart_adjacency_enabled", otherPrefs.getBoolean("smart_adjacency_enabled", true))
            putString("rotation_sort_order", otherPrefs.getString("rotation_sort_order", "RANDOM"))
            putInt("history_limit", otherPrefs.getInt("history_limit", 150))
            putBoolean("auto_limit_enabled", otherPrefs.getBoolean("auto_limit_enabled", false))
            putFloat("manual_focal_x", otherPrefs.getFloat("manual_focal_x", 0.5f))
            putFloat("manual_focal_y", otherPrefs.getFloat("manual_focal_y", 0.4f))
            putString("filter_type", otherPrefs.getString("filter_type", "NONE"))
            putInt("filter_color_1", otherPrefs.getInt("filter_color_1", 0xFF000000.toInt()))
            putInt("filter_color_2", otherPrefs.getInt("filter_color_2", 0xFFFFFFFF.toInt()))
            putInt("manual_page_count", otherPrefs.getInt("manual_page_count", 0))
            putBoolean("force_reload_trigger", true)
            apply()
        }
        loadSettings()
        Toast.makeText(getApplication(), "Settings copied from ${if (_settingsTarget.value == SettingTarget.HOME) "Lock" else "Home"}", Toast.LENGTH_SHORT).show()
    }

    fun setManualFocalPoint(x: Float, y: Float) {
        currentPrefs?.edit()
            ?.putFloat("manual_focal_x", x)
            ?.putFloat("manual_focal_y", y)
            ?.apply()
        _manualFocalX.value = x
        _manualFocalY.value = y
    }

    fun setManualPageCount(count: Int) {
        currentPrefs?.edit()
            ?.putInt("manual_page_count", count)
            ?.putBoolean("force_reload_trigger", true)
            ?.apply()
        _manualPageCount.value = count
    }

    private var scanJob: Job? = null

    init {
        loadSettings()
        viewModelScope.launch {
            settingsTarget.flatMapLatest { target ->
                scannedImageDao.getAllImages(target.name)
            }.collect { entities ->
                val targetName = _settingsTarget.value.name
                val favUris = withContext(Dispatchers.IO) {
                    favoriteDao.getAllFavoritesSync(targetName).map { it.uriString }.toSet()
                }
                val images = entities.map { 
                    WallpaperImg(it.uriString, it.folderUriString, it.displayName, favUris.contains(it.uriString), it.dateModified)
                }
                _scannedImages.value = images
                currentPrefs?.edit()?.putInt("total_scanned_count", images.size)?.apply()
                
                if (_autoLimitEnabled.value) {
                    _historyLimit.value = images.size.coerceAtLeast(150)
                }
            }
        }

        viewModelScope.launch {
            @OptIn(FlowPreview::class)
            settingsTarget.flatMapLatest { target ->
                folderDao.getAllFolders(target.name)
            }.debounce(3000).collect {
                scanFolders()
            }
        }
    }

    // File Explorer State
    private val _currentPath = MutableStateFlow<File?>(Environment.getExternalStorageDirectory())
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
        val parent = _currentPath.value?.parentFile
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
                val path = _currentPath.value ?: return@launch
                val files = path.listFiles()
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
            val targetName = _settingsTarget.value.name
            val uris = _selectedGalleryUris.value
            val images = _scannedImages.value.filter { uris.contains(it.uriString) }

            images.forEach { img ->
                if (!img.isFavorite) {
                    favoriteDao.insertFavorite(
                        FavoriteImageEntity(
                            uriString = img.uriString,
                            folderUriString = img.folderUriString,
                            displayName = img.displayName,
                            target = targetName
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
            val targetName = _settingsTarget.value.name
            val folderImages = _scannedImages.value.filter { it.folderUriString == folderUri }
            val isCurrentlyFavorite = folderImages.any { it.isFavorite }

            if (isCurrentlyFavorite) {
                folderImages.forEach { favoriteDao.deleteFavoriteByUri(it.uriString, targetName) }
            } else {
                folderImages.forEach { img ->
                    favoriteDao.insertFavorite(
                        FavoriteImageEntity(
                            uriString = img.uriString,
                            folderUriString = img.folderUriString,
                            displayName = img.displayName,
                            target = targetName
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
        currentPrefs?.edit()?.putString("transition_type", type)?.apply()
        _transitionType.value = type
    }

    fun setIntervalSeconds(seconds: Float) {
        val capped = seconds.coerceAtLeast(5f)
        currentPrefs?.edit()?.putFloat("interval_seconds", capped)?.apply()
        _intervalSeconds.value = capped
    }

    fun setUseFavoritesOnly(enable: Boolean) {
        currentPrefs?.edit()?.putBoolean("use_favorites_only", enable)
            ?.putBoolean("force_reload_trigger", true) // Force service to react
            ?.apply()
        _useFavoritesOnly.value = enable
    }

    fun setHistoryLimit(limit: Int) {
        if (!_autoLimitEnabled.value) {
            val cappedLimit = limit.coerceIn(10, 8000)
            currentPrefs?.edit()?.putInt("history_limit", cappedLimit)?.apply()
            _historyLimit.value = cappedLimit
        }
    }

    fun setAutoLimitEnabled(enabled: Boolean) {
        currentPrefs?.edit()?.putBoolean("auto_limit_enabled", enabled)?.apply()
        _autoLimitEnabled.value = enabled
        if (enabled) {
            _historyLimit.value = _scannedImages.value.size.coerceAtLeast(150)
        } else {
            val savedLimit = currentPrefs?.getInt("history_limit", 150) ?: 150
            _historyLimit.value = savedLimit
        }
    }

    fun removeFromHistory(uri: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val targetName = _settingsTarget.value.name
            historyDao.deleteHistoryByUri(uri, targetName)
        }
    }

    fun removeMultipleFromHistory(uris: List<String>) {
        viewModelScope.launch(Dispatchers.IO) {
            val targetName = _settingsTarget.value.name
            historyDao.deleteMultipleHistoryByUri(uris, targetName)
        }
    }

    suspend fun getHistoryPaged(target: String, limit: Int, offset: Int): List<String> {
        return historyDao.getHistoryPaged(target, limit, offset)
    }

    fun setDoubleTapEnabled(enable: Boolean) {
        currentPrefs?.edit()?.putBoolean("double_tap_enabled", enable)?.apply()
        _doubleTapEnabled.value = enable
    }

    fun setFadeSpeed(speed: Int) {
        currentPrefs?.edit()?.putInt("fade_speed", speed)?.apply()
        _fadeSpeed.value = speed
    }

    fun setParallaxEnabled(enable: Boolean) {
        currentPrefs?.edit()?.putBoolean("parallax_enabled", enable)?.apply()
        _parallaxEnabled.value = enable
    }

    fun setParallaxStrength(strength: Float) {
        currentPrefs?.edit()?.putFloat("parallax_strength", strength)?.apply()
        _parallaxStrength.value = strength
    }

    fun setShakeEnabled(enable: Boolean) {
        currentPrefs?.edit()?.putBoolean("shake_enabled", enable)?.apply()
        _shakeEnabled.value = enable
    }

    fun setShakeSensitivity(value: Float) {
        currentPrefs?.edit()?.putFloat("shake_sensitivity", value)?.apply()
        _shakeSensitivity.value = value
    }

    fun setSmartCropEnabled(enable: Boolean) {
        currentPrefs?.edit()?.putBoolean("smart_crop_enabled", enable)?.apply()
        _smartCropEnabled.value = enable
    }

    fun setLightModeEnabled(enable: Boolean) {
        currentPrefs?.edit()?.putBoolean("light_mode_enabled", enable)?.apply()
        _lightModeEnabled.value = enable
    }

    fun setWallpaperQuality(quality: String) {
        currentPrefs?.edit()?.putString("wallpaper_quality", quality)?.apply()
        _wallpaperQuality.value = quality
    }

    fun setAiAdvancedEnabled(enable: Boolean) {
        currentPrefs?.edit()?.putBoolean("ai_advanced_enabled", enable)?.apply()
        _aiAdvancedEnabled.value = enable
    }

    fun setAiZoomSlack(value: Float) {
        currentPrefs?.edit()?.putFloat("ai_zoom_slack", value)?.apply()
        _aiZoomSlack.value = value
    }

    fun setAiSensitivityX(value: Float) {
        currentPrefs?.edit()?.putFloat("ai_sensitivity_x", value)?.apply()
        _aiSensitivityX.value = value
    }

    fun setAiSensitivityY(value: Float) {
        currentPrefs?.edit()?.putFloat("ai_sensitivity_y", value)?.apply()
        _aiSensitivityY.value = value
    }

    fun setBlurRadius(value: Float) {
        currentPrefs?.edit()?.putFloat("blur_radius", value)?.apply()
        _blurRadius.value = value
    }

    fun setDimIntensity(value: Float) {
        currentPrefs?.edit()?.putFloat("dim_intensity", value)?.apply()
        _dimIntensity.value = value
    }

    fun setBlurEnabled(enable: Boolean) {
        currentPrefs?.edit()?.putBoolean("blur_enabled", enable)?.apply()
        _blurEnabled.value = enable
    }

    fun setDimEnabled(enable: Boolean) {
        currentPrefs?.edit()?.putBoolean("dim_enabled", enable)?.apply()
        _dimEnabled.value = enable
    }

    fun setSmartAdjacencyEnabled(enable: Boolean) {
        currentPrefs?.edit()?.putBoolean("smart_adjacency_enabled", enable)?.apply()
        _smartAdjacencyEnabled.value = enable
        if (enable && _rotationSortOrder.value != "RANDOM") {
            setRotationSortOrder("RANDOM")
        }
    }

    fun setRotationSortOrder(order: String) {
        currentPrefs?.edit()?.putString("rotation_sort_order", order)?.apply()
        _rotationSortOrder.value = order
        if (order == "FOLDER" && _smartAdjacencyEnabled.value) {
            setSmartAdjacencyEnabled(false)
        }
    }

    fun setSubjectFocusEnabled(enable: Boolean) {
        currentPrefs?.edit()?.putBoolean("subject_focus_enabled", enable)?.apply()
        _subjectFocusEnabled.value = enable
        if (enable && _vignetteModeEnabled.value) {
            setVignetteModeEnabled(false)
        }
    }

    fun setVignetteModeEnabled(enable: Boolean) {
        currentPrefs?.edit()?.putBoolean("vignette_mode_enabled", enable)?.apply()
        _vignetteModeEnabled.value = enable
        if (enable && _subjectFocusEnabled.value) {
            setSubjectFocusEnabled(false)
        }
    }

    fun setVignetteSharpness(value: Float) {
        currentPrefs?.edit()?.putFloat("vignette_sharpness", value)?.apply()
        _vignetteSharpness.value = value
    }

    fun setVignetteWidth(value: Float) {
        currentPrefs?.edit()?.putFloat("vignette_width", value)?.apply()
        _vignetteWidth.value = value
    }

    fun setSubjectFocusSmoothing(value: Float) {
        currentPrefs?.edit()?.putFloat("subject_focus_smoothing", value)?.apply()
        _subjectFocusSmoothing.value = value
    }

    fun setFilterType(type: String) {
        currentPrefs?.edit()?.putString("filter_type", type)?.apply()
        _filterType.value = type
    }

    fun setFilterColor1(color: Int) {
        currentPrefs?.edit()?.putInt("filter_color_1", color)?.apply()
        _filterColor1.value = color
    }

    fun setFilterColor2(color: Int) {
        currentPrefs?.edit()?.putInt("filter_color_2", color)?.apply()
        _filterColor2.value = color
    }

    fun setFilterColor3(color: Int) {
        currentPrefs?.edit()?.putInt("filter_color_3", color)?.apply()
        _filterColor3.value = color
    }

    fun saveCustomPalette(name: String) {
        viewModelScope.launch {
            val type = _filterType.value
            if (type == "DUOTONE" || type == "TRITONE") {
                customPaletteDao.insertPalette(CustomPaletteEntity(
                    name = name,
                    color1 = _filterColor1.value,
                    color2 = _filterColor2.value,
                    color3 = if (type == "TRITONE") _filterColor3.value else null,
                    type = type
                ))
            }
        }
    }

    fun deleteCustomPalette(palette: CustomPaletteEntity) {
        viewModelScope.launch {
            customPaletteDao.deletePalette(palette)
        }
    }

    fun checkForUpdates() {
        viewModelScope.launch(Dispatchers.IO) {
            _isCheckingUpdate.value = true
            _updateMessage.value = null
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
                    
                    // Normalize versions by removing EVERYTHING except numbers and dots
                    // This makes "v.1.1.0", "v1.1.0", and "1.1.0" all become "1.1.0"
                    fun normalize(v: String): String = v.replace(Regex("[^0-9.]"), "").trim('.')
                    
                    val currentVersion = normalize(gustian.multiwallpaper.BuildConfig.VERSION_NAME)
                    val latestVersionFromGit = normalize(tagName)

                    withContext(Dispatchers.Main) {
                        if (currentVersion == latestVersionFromGit) {
                            _updateMessage.value = "Your version is up to date (v${gustian.multiwallpaper.BuildConfig.VERSION_NAME})"
                        } else {
                            _latestVersionInfo.value = UpdateInfo(tagName, body, htmlUrl)
                        }
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        _updateMessage.value = "Unable to check updates (Server error)"
                    }
                }
            } catch (e: Exception) {
                Log.e("HomeViewModel", "Failed to check for updates", e)
                withContext(Dispatchers.Main) {
                    _updateMessage.value = "Network error: ${e.message}"
                }
            } finally {
                _isCheckingUpdate.value = false
            }
        }
    }

    fun dismissUpdateDialog() {
        _latestVersionInfo.value = null
        _updateMessage.value = null
    }

    fun blacklistCurrentUri(uri: String, folderUri: String, displayName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val targetName = _settingsTarget.value.name
            blacklistedDao.insertBlacklist(BlacklistedImageEntity(uri, folderUri, displayName))
            favoriteDao.deleteFavoriteByUriSync(uri, targetName)
            scannedImageDao.deleteImageByUriSync(uri, targetName)
        }
    }

    fun blacklistSelectedImages() {
        viewModelScope.launch(Dispatchers.IO) {
            val targetName = _settingsTarget.value.name
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
                favoriteDao.deleteFavoriteByUriSync(img.uriString, targetName)
                scannedImageDao.deleteImageByUriSync(img.uriString, targetName)
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

    suspend fun saveCurrentAsBackupPreset(targetName: String) {
        val currentFolders = folderDao.getAllFoldersSync(targetName).map { it.uriString }
        if (currentFolders.isEmpty()) return

        val currentFavs = favoriteDao.getAllFavoritesSync(targetName)
        val currentBlacklist = blacklistedDao.getAllBlacklistedSync()
        
        val moshi = com.squareup.moshi.Moshi.Builder().add(com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory()).build()
        
        val favType = com.squareup.moshi.Types.newParameterizedType(List::class.java, FavoriteImageEntity::class.java)
        val favAdapter = moshi.adapter<List<FavoriteImageEntity>>(favType)
        val favJson = favAdapter.toJson(currentFavs)

        val blType = com.squareup.moshi.Types.newParameterizedType(List::class.java, BlacklistedImageEntity::class.java)
        val blAdapter = moshi.adapter<List<BlacklistedImageEntity>>(blType)
        val blJson = blAdapter.toJson(currentBlacklist)

        val existingBackup = presetDao.getAllPresets(targetName).first().find { it.name == "System_AutoBackup" }
        
        if (existingBackup != null) {
            presetDao.updatePreset(existingBackup.copy(
                folderUris = currentFolders,
                favoriteData = favJson,
                blacklistData = blJson,
                createdTime = System.currentTimeMillis()
            ))
        } else {
            presetDao.insertPreset(PresetEntity(
                name = "System_AutoBackup",
                thumbnailUri = null,
                folderUris = currentFolders,
                favoriteData = favJson,
                blacklistData = blJson,
                target = targetName
            ))
        }
    }

    suspend fun saveCurrentAsPresetSuspend(name: String) = withContext(Dispatchers.IO) {
        val targetName = _settingsTarget.value.name
        val currentFolders = folderDao.getAllFoldersSync(targetName).map { it.uriString }
        val currentFavs = favoriteDao.getAllFavoritesSync(targetName)
        val currentBlacklist = blacklistedDao.getAllBlacklistedSync()
        val thumb = currentFavs.firstOrNull()?.uriString ?: _scannedImages.value.firstOrNull()?.uriString

        val moshi = com.squareup.moshi.Moshi.Builder().add(com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory()).build()
        
        val favType = com.squareup.moshi.Types.newParameterizedType(List::class.java, FavoriteImageEntity::class.java)
        val favAdapter = moshi.adapter<List<FavoriteImageEntity>>(favType)
        val favJson = favAdapter.toJson(currentFavs)

        val blType = com.squareup.moshi.Types.newParameterizedType(List::class.java, BlacklistedImageEntity::class.java)
        val blAdapter = moshi.adapter<List<BlacklistedImageEntity>>(blType)
        val blJson = blAdapter.toJson(currentBlacklist)

        val existingList = presetDao.getAllPresets(targetName).first()
        val existing = existingList.find { it.name.equals(name, ignoreCase = true) }
        if (existing != null) {
            val updated = existing.copy(
                thumbnailUri = thumb,
                folderUris = currentFolders,
                favoriteData = favJson,
                blacklistData = blJson,
                target = targetName,
                createdTime = System.currentTimeMillis()
            )
            presetDao.updatePreset(updated)
        } else {
            val preset = PresetEntity(
                name = name,
                thumbnailUri = thumb,
                folderUris = currentFolders,
                favoriteData = favJson,
                blacklistData = blJson,
                target = targetName
            )
            presetDao.insertPreset(preset)
        }
        _activePresetName.value = name
    }

    fun saveCurrentAsPreset(name: String) {
        viewModelScope.launch {
            saveCurrentAsPresetSuspend(name)
            currentPrefs?.edit()?.putString("active_preset_name", name)?.apply()
            _activePresetName.value = name
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
        currentPrefs?.edit()?.putString("gallery_sort_type", type)?.apply()
        _gallerySortType.value = type
    }

    fun setGallerySortOrder(order: String) {
        currentPrefs?.edit()?.putString("gallery_sort_order", order)?.apply()
        _gallerySortOrder.value = order
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
            val targetName = _settingsTarget.value.name
            try {
                // Auto-save current state to the PREVIOUS active preset before switching
                _activePresetName.value?.let { activeName ->
                    saveCurrentAsPresetSuspend(activeName)
                }

                withContext(Dispatchers.IO) {
                    currentPrefs?.edit()?.putString("active_preset_name", preset.name)?.apply()
                    _activePresetName.value = preset.name
                    folderDao.deleteAllFolders(targetName)
                    favoriteDao.deleteAllFavorites(targetName)
                    blacklistedDao.deleteAllBlacklisted() // Option 1: Clear current blacklist when loading preset

                    val folderEntities = preset.folderUris.map { uri ->
                        val name = try {
                            val u = Uri.parse(uri)
                            if (u.scheme == "file") java.io.File(u.path!!).name else Uri.decode(uri).split("/").lastOrNull() ?: "Folder"
                        } catch (e: Exception) { "Folder" }
                        FolderEntity(uriString = uri, displayName = name, target = targetName)
                    }
                    folderDao.insertFolders(folderEntities)

                    val moshi = com.squareup.moshi.Moshi.Builder().add(com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory()).build()

                    // Restore Favorites
                    var favoriteCount = 0
                    try {
                        val favType = com.squareup.moshi.Types.newParameterizedType(List::class.java, FavoriteImageEntity::class.java)
                        val favAdapter = moshi.adapter<List<FavoriteImageEntity>>(favType)
                        val favs = favAdapter.fromJson(preset.favoriteData)

                        if (favs != null) {
                            val updatedFavs = favs.map { it.copy(target = targetName) }
                            favoriteDao.insertFavorites(updatedFavs)
                            favoriteCount = favs.size
                        }
                    } catch (e: Exception) {
                        Log.e("HomeViewModel", "Error loading favorites from preset", e)
                    }

                    // Restore Blacklist
                    try {
                        preset.blacklistData?.let { blJson ->
                            val blType = com.squareup.moshi.Types.newParameterizedType(List::class.java, BlacklistedImageEntity::class.java)
                            val blAdapter = moshi.adapter<List<BlacklistedImageEntity>>(blType)
                            val bls = blAdapter.fromJson(blJson)
                            if (bls != null) {
                                bls.forEach { blacklistedDao.insertBlacklist(it) }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("HomeViewModel", "Error loading blacklist from preset", e)
                    }

                    // Auto-Fallback: If preset has no favorites, disable "Use Favorites Only"
                    if (favoriteCount == 0 && _useFavoritesOnly.value) {
                        setUseFavoritesOnly(false)
                        withContext(Dispatchers.Main) {
                            Toast.makeText(getApplication(), "Favorites Only turned OFF (No favorites in preset)", Toast.LENGTH_SHORT).show()
                        }
                    }
                    
                    // Re-scan to update cached images for the new preset folders
                    scanFoldersSync()
                }
                triggerReload()
            } finally {
                _isLoadingPreset.value = false
            }
        }
    }

    private suspend fun scanFoldersSync() {
        val targetName = _settingsTarget.value.name
        val foldersList = folderDao.getAllFoldersSync(targetName)
        val tempImages = mutableListOf<WallpaperImg>()
        
        // Deep-Sync: Get all current entries to verify disk existence
        val favoriteEntities = favoriteDao.getAllFavoritesSync(targetName)
        val blacklistedEntities = blacklistedDao.getAllBlacklistedSync()
        val contentResolver = getApplication<Application>().contentResolver

        val favoriteUris = mutableSetOf<String>()
        val blacklistedUris = mutableSetOf<String>()

        // 1. Verify Favorites existence on disk
        favoriteEntities.forEach { fav ->
            if (verifyFileExists(Uri.parse(fav.uriString))) {
                favoriteUris.add(fav.uriString)
            } else {
                favoriteDao.deleteFavoriteByUriSync(fav.uriString, targetName)
                Log.d("HomeViewModel", "Deep-Sync: Removed orphaned favorite: ${fav.displayName}")
            }
        }

        // 2. Verify Blacklist existence on disk
        blacklistedEntities.forEach { bl ->
            if (verifyFileExists(Uri.parse(bl.uriString))) {
                blacklistedUris.add(bl.uriString)
            } else {
                blacklistedDao.deleteBlacklistByUri(bl.uriString)
                Log.d("HomeViewModel", "Deep-Sync: Removed orphaned blacklist entry: ${bl.displayName}")
            }
        }

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
        scannedImageDao.deleteAllImages(targetName)
        scannedImageDao.insertImages(tempImages.map { 
            ScannedImageEntity(it.uriString, it.folderUriString, it.displayName, targetName, dateModified = it.date)
        })
    }

    private fun verifyFileExists(uri: Uri): Boolean {
        return try {
            if (uri.scheme == "file") {
                java.io.File(uri.path ?: "").exists()
            } else {
                val pfd = getApplication<Application>().contentResolver.openFileDescriptor(uri, "r")
                pfd?.close()
                pfd != null
            }
        } catch (e: Exception) {
            false
        }
    }

    suspend fun getPresetsJson(): String? = withContext(Dispatchers.IO) {
        try {
            val targetName = _settingsTarget.value.name
            val allPresets = presetDao.getAllPresets(targetName).first()
            val moshi = com.squareup.moshi.Moshi.Builder()
                .add(com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory())
                .build()
            val type = com.squareup.moshi.Types.newParameterizedType(List::class.java, PresetEntity::class.java)
            moshi.adapter<List<PresetEntity>>(type).toJson(allPresets)
        } catch (e: Exception) {
            Log.e("HomeViewModel", "Failed to generate presets JSON", e)
            null
        }
    }

    fun importPresets(json: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val targetName = _settingsTarget.value.name
                val moshi = com.squareup.moshi.Moshi.Builder()
                    .add(com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory())
                    .build()
                val type = com.squareup.moshi.Types.newParameterizedType(List::class.java, PresetEntity::class.java)
                val imported = moshi.adapter<List<PresetEntity>>(type).fromJson(json)

                imported?.forEach {
                    presetDao.insertPreset(it.copy(id = 0, target = targetName))
                }
                withContext(Dispatchers.Main) {
                    Toast.makeText(getApplication(), "Imported ${imported?.size ?: 0} presets", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e("HomeViewModel", "Import fail", e)
            }
        }
    }

    suspend fun getFullBackupJson(): String? = withContext(Dispatchers.IO) {
        try {
            val targetName = _settingsTarget.value.name
            val prefs = currentPrefs ?: return@withContext null
            
            val backupMap = mutableMapOf<String, Any>()
            
            // 1. All Presets
            val allPresets = presetDao.getAllPresets(targetName).first()
            backupMap["presets"] = allPresets
            
            // 2. Global Settings (SharedPreferences)
            val settingsMap = mutableMapOf<String, Any>()
            prefs.all.forEach { (key, value) -> settingsMap[key] = value ?: "" }
            backupMap["settings"] = settingsMap
            
            // 3. Custom Palettes
            val palettes = customPaletteDao.getPalettesByType("DUOTONE").first() + 
                           customPaletteDao.getPalettesByType("TRITONE").first()
            backupMap["palettes"] = palettes
            
            // 4. Schedules
            val schedulesList = scheduleDao.getAllSchedules(targetName).first()
            backupMap["schedules"] = schedulesList

            val moshi = com.squareup.moshi.Moshi.Builder()
                .add(com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory())
                .build()
            val adapter = moshi.adapter(Any::class.java)
            adapter.toJson(backupMap)
        } catch (e: Exception) {
            Log.e("HomeViewModel", "Failed to generate full backup", e)
            null
        }
    }

    fun importFullBackup(json: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val targetName = _settingsTarget.value.name
                val moshi = com.squareup.moshi.Moshi.Builder()
                    .add(com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory())
                    .build()
                val map = moshi.adapter(Map::class.java).fromJson(json) as? Map<String, Any> ?: return@launch

                // 1. Restore Presets
                (map["presets"] as? List<*>)?.let { list ->
                    presetDao.deleteAllPresets(targetName)
                    val adapter = moshi.adapter(PresetEntity::class.java)
                    list.forEach { item ->
                        val p = adapter.fromJson(moshi.adapter(Any::class.java).toJson(item))
                        if (p != null) presetDao.insertPreset(p.copy(id = 0, target = targetName))
                    }
                }

                // 2. Restore Palettes
                (map["palettes"] as? List<*>)?.let { list ->
                    val adapter = moshi.adapter(CustomPaletteEntity::class.java)
                    list.forEach { item ->
                        val p = adapter.fromJson(moshi.adapter(Any::class.java).toJson(item))
                        if (p != null) customPaletteDao.insertPalette(p.copy(id = 0))
                    }
                }

                // 3. Restore Schedules
                (map["schedules"] as? List<*>)?.let { list ->
                    val adapter = moshi.adapter(ScheduleEntity::class.java)
                    list.forEach { item ->
                        val s = adapter.fromJson(moshi.adapter(Any::class.java).toJson(item))
                        if (s != null) scheduleDao.insertSchedule(s.copy(id = 0, target = targetName))
                    }
                }

                // 4. Restore Settings
                (map["settings"] as? Map<*, *>)?.let { settings ->
                    val editor = currentPrefs?.edit() ?: return@let
                    settings.forEach { (k, v) ->
                        val key = k.toString()
                        when (v) {
                            is Boolean -> editor.putBoolean(key, v)
                            is Float -> editor.putFloat(key, v)
                            is Int -> editor.putInt(key, v)
                            is Long -> editor.putLong(key, v)
                            is String -> editor.putString(key, v)
                        }
                    }
                    editor.apply()
                }

                withContext(Dispatchers.Main) {
                    loadSettings()
                    Toast.makeText(getApplication(), "Full Data Restored!", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e("HomeViewModel", "Import fail", e)
            }
        }
    }

    fun triggerReload() {
        Log.d("MultiWallpaper", "ViewModel triggerReload for ${if (_settingsTarget.value == SettingTarget.HOME) "Home" else "Lock"}")
        currentPrefs?.edit()?.putBoolean("force_reload_trigger", true)?.apply()
        Toast.makeText(getApplication(), "Wallpaper reload triggered", Toast.LENGTH_SHORT).show()
    }

    private val _selectedPresetIds = MutableStateFlow<Set<Int>>(emptySet())
    val selectedPresetIds = _selectedPresetIds.asStateFlow()

    fun togglePresetSelection(id: Int) {
        val current = _selectedPresetIds.value.toMutableSet()
        if (current.contains(id)) current.remove(id) else current.add(id)
        _selectedPresetIds.value = current
    }

    fun clearPresetSelection() {
        _selectedPresetIds.value = emptySet()
    }

    fun deleteSelectedPresets() {
        viewModelScope.launch(Dispatchers.IO) {
            val ids = _selectedPresetIds.value
            ids.forEach { id ->
                val preset = presets.value.find { it.id == id }
                if (preset != null) {
                    if (_activePresetName.value == preset.name) {
                        _activePresetName.value = null
                        currentPrefs?.edit()?.remove("active_preset_name")?.apply()
                    }
                    presetDao.deletePreset(preset)
                }
            }
            withContext(Dispatchers.Main) {
                clearPresetSelection()
            }
        }
    }

    fun deletePreset(preset: PresetEntity) {
        viewModelScope.launch {
            presetDao.deletePreset(preset)
        }
    }

    fun addSchedule(schedule: ScheduleEntity) {
        viewModelScope.launch {
            scheduleDao.insertSchedule(schedule)
            triggerScheduleReload()
        }
    }

    fun updateSchedule(schedule: ScheduleEntity) {
        viewModelScope.launch {
            scheduleDao.updateSchedule(schedule)
            triggerScheduleReload()
        }
    }

    fun deleteSchedule(schedule: ScheduleEntity) {
        viewModelScope.launch {
            scheduleDao.deleteSchedule(schedule)
            triggerScheduleReload()
        }
    }

    fun toggleSchedule(schedule: ScheduleEntity) {
        viewModelScope.launch {
            scheduleDao.updateSchedule(schedule.copy(isEnabled = !schedule.isEnabled))
            triggerScheduleReload()
        }
    }

    private fun triggerScheduleReload() {
        val intent = Intent("gustian.multiwallpaper.RELOAD_SCHEDULES").apply {
            setPackage(getApplication<Application>().packageName)
        }
        getApplication<Application>().sendBroadcast(intent)
    }

    fun addFolders(uris: List<Uri>) {
        viewModelScope.launch(Dispatchers.IO) {
            val targetName = _settingsTarget.value.name
            val contentResolver = getApplication<Application>().contentResolver
            val currentFolderUris = folderDao.getAllFoldersSync(targetName).map { it.uriString }.toSet()
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
                    foldersToInsert.add(FolderEntity(uriString = uri.toString(), displayName = displayName, target = targetName))
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
            val targetName = _settingsTarget.value.name
            folderDao.deleteFolder(folder)
            favoriteDao.deleteFavoritesByFolderUri(folder.uriString, targetName)
        }
    }

    fun deleteSelectedFolders() {
        viewModelScope.launch(Dispatchers.IO) {
            val targetName = _settingsTarget.value.name
            val selectedIds = _selectedFolderIds.value.toList()
            val currentFolders = folderDao.getAllFoldersSync(targetName)
            val folderUrisToDelete = currentFolders.filter { selectedIds.contains(it.id) }.map { it.uriString }

            selectedIds.forEach { folderDao.deleteFolderById(it) }
            folderUrisToDelete.forEach { uri -> favoriteDao.deleteFavoritesByFolderUri(uri, targetName) }

            withContext(Dispatchers.Main) {
                clearFolderIdSelection()
            }
        }
    }

    fun clearAllFolders() {
        viewModelScope.launch(Dispatchers.IO) {
            val targetName = _settingsTarget.value.name
            currentPrefs?.edit()?.remove("active_preset_name")?.apply()
            _activePresetName.value = null
            folderDao.deleteAllFolders(targetName)
            favoriteDao.deleteAllFavorites(targetName)
            withContext(Dispatchers.Main) {
                clearFolderIdSelection()
            }
        }
    }

    fun toggleFavorite(img: WallpaperImg) {
        viewModelScope.launch(Dispatchers.IO) {
            val targetName = _settingsTarget.value.name
            val exists = favoriteDao.isFavoriteSync(img.uriString, targetName)
            if (exists) {
                favoriteDao.deleteFavoriteByUri(img.uriString, targetName)
            } else {
                favoriteDao.insertFavorite(FavoriteImageEntity(img.uriString, img.folderUriString, img.displayName, targetName))
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
                    list.add(WallpaperImg(fileUriStr, parentUriStr, f.name, favoriteUris.contains(fileUriStr), f.lastModified()))
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
                val cursor = context.contentResolver.query(
                    childrenUri, 
                    arrayOf(
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID, 
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME, 
                        DocumentsContract.Document.COLUMN_MIME_TYPE,
                        DocumentsContract.Document.COLUMN_LAST_MODIFIED
                    ), 
                    null, 
                    null, 
                    null
                )
                cursor?.use { c ->
                    while (c.moveToNext()) {
                        val docId = c.getString(0)
                        val name = c.getString(1) ?: "Image"
                        val mimeType = c.getString(2)
                        val lastMod = c.getLong(3)
                        if (mimeType != null) {
                            if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
                                folderQueue.add(DocumentsContract.buildDocumentUriUsingTree(treeUri, docId))
                            } else if (mimeType.startsWith("image/")) {
                                val childUriStr = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId).toString()
                                if (!blacklistedUris.contains(childUriStr)) {
                                    // For SAF, the currentUri is the immediate parent
                                    list.add(WallpaperImg(childUriStr, currentUri.toString(), name, favoriteUris.contains(childUriStr), lastMod))
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
    val isFavorite: Boolean,
    val date: Long = 0
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
