package gustian.multiwallpaper

import android.app.WallpaperManager
import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.pager.*
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import gustian.multiwallpaper.data.FolderEntity
import gustian.multiwallpaper.data.PresetEntity
import gustian.multiwallpaper.data.ScheduleEntity
import kotlin.math.roundToInt
import java.util.Locale
import gustian.multiwallpaper.ui.HomeViewModel
import gustian.multiwallpaper.ui.WallpaperImg
import gustian.multiwallpaper.ui.theme.MyApplicationTheme

import kotlinx.coroutines.launch
import android.util.Log
import android.provider.Settings
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.draw.drawWithContent
import android.graphics.Shader as AndroidShader
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                PermissionRequestWrapper {
                    MainLayout()
                }
            }
        }
    }
}

@Composable
fun PermissionRequestWrapper(content: @Composable () -> Unit) {
    val context = LocalContext.current
    var hasStoragePermission by remember { 
        mutableStateOf(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager() else true)
    }
    val launcher = rememberLauncherForActivityResult(contract = ActivityResultContracts.StartActivityForResult()) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) hasStoragePermission = Environment.isExternalStorageManager()
    }
    if (!hasStoragePermission && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
                Icon(Icons.Default.Storage, null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
                Text("Permission Required", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Text("App needs storage access to scan for wallpapers.", textAlign = TextAlign.Center, fontSize = 14.sp)
                Spacer(modifier = Modifier.height(24.dp))
                Button(onClick = { launcher.launch(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }) { Text("Grant Permission") }
            }
        }
    } else content()
}

enum class NavigationTab { FOLDERS, GALLERY, FAVORITES, SETTINGS }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainLayout() {
    val viewModel: HomeViewModel = viewModel()
    var currentTab by remember { mutableStateOf(NavigationTab.FOLDERS) }
    var showMultiSelectDialog by remember { mutableStateOf(false) }
    val selectedFolderIds by viewModel.selectedFolderIds.collectAsState()
    val selectedGalleryUris by viewModel.selectedGalleryUris.collectAsState()
    val selectedGalleryFolderUris by viewModel.selectedGalleryFolderUris.collectAsState()
    val gallerySearchQuery by viewModel.gallerySearchQuery.collectAsState()
    val isLoadingPreset by viewModel.isLoadingPreset.collectAsState()
    val latestVersionInfo by viewModel.latestVersionInfo.collectAsState()
    val updateMessage by viewModel.updateMessage.collectAsState()
    val settingsTarget by viewModel.settingsTarget.collectAsState()
    val context = LocalContext.current

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Multi Wallpaper", fontWeight = FontWeight.SemiBold, fontSize = 20.sp) },
                navigationIcon = {
                    if (currentTab == NavigationTab.FOLDERS && selectedFolderIds.isNotEmpty()) {
                        IconButton(onClick = { viewModel.clearFolderIdSelection() }) { Icon(Icons.Default.Close, null) }
                    } else if (currentTab == NavigationTab.GALLERY && (selectedGalleryUris.isNotEmpty() || selectedGalleryFolderUris.isNotEmpty())) {
                        IconButton(onClick = { viewModel.clearGallerySelection(); viewModel.clearGalleryFolderSelection() }) { Icon(Icons.Default.Close, null) }
                    } else if (currentTab == NavigationTab.GALLERY && gallerySearchQuery.isNotEmpty()) {
                        IconButton(onClick = { viewModel.setGallerySearchQuery("") }) { Icon(Icons.Default.Close, null) }
                    } else {
                        IconButton(onClick = { 
                            val service = if (settingsTarget == gustian.multiwallpaper.ui.SettingTarget.LOCK)
                                MultiWallpaperLockService::class.java
                            else 
                                MultiWallpaperHomeService::class.java
                            triggerLiveWallpaperSelection(context, service) 
                        }) { Icon(Icons.Default.Wallpaper, null, tint = MaterialTheme.colorScheme.primary) }
                    }
                },
                actions = {
                    if (currentTab == NavigationTab.FOLDERS && selectedFolderIds.isNotEmpty()) {
                        IconButton(onClick = { viewModel.deleteSelectedFolders() }) { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) }
                    } else if (currentTab == NavigationTab.GALLERY && selectedGalleryUris.isNotEmpty()) {
                        IconButton(onClick = { viewModel.blacklistSelectedImages() }) { Icon(Icons.Default.Block, null, tint = MaterialTheme.colorScheme.error) }
                        IconButton(onClick = { viewModel.addSelectedToFavorites() }) { Icon(Icons.Default.Star, null, tint = Color(0xFFEAB308)) }
                    } else if (currentTab == NavigationTab.GALLERY && selectedGalleryFolderUris.isNotEmpty()) {
                        IconButton(onClick = { viewModel.toggleFavoriteSelectedFolders() }) { Icon(Icons.Default.Star, null, tint = Color(0xFFEAB308)) }
                    } else if (currentTab == NavigationTab.GALLERY) {
                        var showSearch by remember { mutableStateOf(false) }
                        if (showSearch) {
                            OutlinedTextField(
                                value = gallerySearchQuery,
                                onValueChange = { viewModel.setGallerySearchQuery(it) },
                                modifier = Modifier.width(180.dp).padding(end = 8.dp),
                                placeholder = { Text("Search...", fontSize = 12.sp) },
                                singleLine = true,
                                shape = RoundedCornerShape(12.dp),
                                trailingIcon = {
                                    IconButton(onClick = { showSearch = false; viewModel.setGallerySearchQuery("") }) {
                                        Icon(Icons.Default.Close, null, modifier = Modifier.size(16.dp))
                                    }
                                }
                            )
                        } else {
                            IconButton(onClick = { showSearch = true }) { Icon(Icons.Default.Search, null, tint = MaterialTheme.colorScheme.primary) }
                        }
                        
                        var showSortMenu by remember { mutableStateOf(false) }
                        Box {
                            IconButton(onClick = { showSortMenu = true }) { Icon(Icons.AutoMirrored.Filled.Sort, null, tint = MaterialTheme.colorScheme.primary) }
                            DropdownMenu(expanded = showSortMenu, onDismissRequest = { showSortMenu = false }) {
                                listOf("NAME" to "Name", "DATE" to "Date Added", "STAR" to "Star First").forEach { (type, label) ->
                                    DropdownMenuItem(text = { Text(label) }, onClick = { viewModel.setGallerySortType(type); showSortMenu = false })
                                }
                            }
                        }
                    } else {
                        IconButton(onClick = { 
                            viewModel.scanFolders()
                            viewModel.triggerReload()
                        }) { Icon(Icons.Default.Refresh, null, tint = MaterialTheme.colorScheme.primary) }
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
                listOf(
                    NavigationTab.FOLDERS to Icons.Default.Folder,
                    NavigationTab.GALLERY to Icons.Default.Image,
                    NavigationTab.FAVORITES to Icons.Default.Star,
                    NavigationTab.SETTINGS to Icons.Default.Settings
                ).forEach { (tab, icon) ->
                    NavigationBarItem(
                        selected = currentTab == tab,
                        onClick = { 
                            currentTab = tab
                            viewModel.clearFolderIdSelection()
                            viewModel.clearGallerySelection()
                        },
                        icon = { Icon(icon, null) },
                        label = { Text(tab.name.lowercase().replaceFirstChar { it.uppercase() }, fontSize = 11.sp) }
                    )
                }
            }
        },
        floatingActionButton = {
            if (currentTab == NavigationTab.FOLDERS) {
                FloatingActionButton(onClick = { viewModel.refreshCurrentPath(); showMultiSelectDialog = true }, containerColor = MaterialTheme.colorScheme.primary) {
                    Icon(Icons.Default.Checklist, "Mark Folders")
                }
            }
        }
    ) { paddingValues ->
        Box(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
            AnimatedContent(targetState = currentTab, label = "Tab") { tab ->
                when (tab) {
                    NavigationTab.FOLDERS -> FolderScreen(viewModel)
                    NavigationTab.GALLERY -> GalleryScreen(viewModel)
                    NavigationTab.FAVORITES -> FavoritesScreen(viewModel)
                    NavigationTab.SETTINGS -> SettingsScreen(viewModel)
                }
            }
            if (showMultiSelectDialog) MultiFolderSelectDialog(viewModel, { showMultiSelectDialog = false })

            if (isLoadingPreset) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.5f))
                        .clickable(enabled = false) { },
                    contentAlignment = Alignment.Center
                ) {
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(48.dp),
                                color = MaterialTheme.colorScheme.primary,
                                strokeWidth = 4.dp
                            )
                            Spacer(modifier = Modifier.height(24.dp))
                            Text(
                                text = "Switching Preset...",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Updating wallpaper collection",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }

            // Update Available Dialog
            latestVersionInfo?.let { info ->
                AlertDialog(
                    onDismissRequest = { viewModel.dismissUpdateDialog() },
                    title = { Text("What's New in ${info.tagName}") },
                    text = { 
                        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                            Text(info.changelog, style = MaterialTheme.typography.bodyMedium)
                        }
                    },
                    confirmButton = {
                        val currentContext = LocalContext.current
                        Button(onClick = { 
                            currentContext.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(info.downloadUrl)))
                            viewModel.dismissUpdateDialog()
                        }) {
                            Text("Download Update")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { viewModel.dismissUpdateDialog() }) {
                            Text("Later")
                        }
                    },
                    shape = RoundedCornerShape(16.dp)
                )
            }

            // Up-to-date or Info Dialog
            updateMessage?.let { msg ->
                AlertDialog(
                    onDismissRequest = { viewModel.dismissUpdateDialog() },
                    title = { Text("Update Check") },
                    text = { Text(msg) },
                    confirmButton = {
                        TextButton(onClick = { viewModel.dismissUpdateDialog() }) {
                            Text("OK")
                        }
                    },
                    shape = RoundedCornerShape(16.dp)
                )
            }
        }
    }
}

@Composable
fun MultiFolderSelectDialog(viewModel: HomeViewModel, onDismiss: () -> Unit) {
    val items by viewModel.currentPathItems.collectAsState()
    val currentPath by viewModel.currentPath.collectAsState()
    val selected by viewModel.selectedFolders.collectAsState()
    val isAllSelected by viewModel.isAllSelected.collectAsState()
    Dialog(onDismissRequest = onDismiss) {
        Card(modifier = Modifier.fillMaxWidth().height(600.dp), shape = RoundedCornerShape(24.dp)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { if (!viewModel.navigateBack()) onDismiss() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) }
                    Text("Select Folders", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    TextButton(onClick = { viewModel.toggleSelectAll() }) { Text(if (isAllSelected) "None" else "All") }
                }
                Surface(color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f), shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
                    val pathText = currentPath?.absolutePath?.replace("/storage/emulated/0", "Internal") ?: "Internal"
                    Text(text = pathText, modifier = Modifier.padding(8.dp), style = MaterialTheme.typography.labelSmall)
                }
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(items) { item ->
                        val isSelected = selected.contains(item.uri)
                        Row(modifier = Modifier.fillMaxWidth().clickable { viewModel.navigateTo(java.io.File(item.uri.path ?: "")) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = isSelected, onCheckedChange = { viewModel.toggleFolderSelection(item.uri) })
                            Box(modifier = Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
                                if (item.previewUri != null) {
                                    AsyncImage(model = item.previewUri, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                                } else {
                                    Icon(if (isSelected) Icons.Filled.Folder else Icons.Outlined.Folder, null, modifier = Modifier.align(Alignment.Center))
                                }
                            }
                            Text(item.name, modifier = Modifier.weight(1f).padding(start = 12.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Icon(Icons.Default.ChevronRight, null)
                        }
                    }
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Button(enabled = selected.isNotEmpty(), onClick = { viewModel.confirmMultiSelect(); onDismiss() }) { Text("Add (${selected.size})") }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FolderScreen(viewModel: HomeViewModel) {
    val settingsTarget by viewModel.settingsTarget.collectAsState()
    val folders by viewModel.folders.collectAsState(initial = emptyList())
    val isScanning by viewModel.isScanning.collectAsState()
    val selectedIds by viewModel.selectedFolderIds.collectAsState()
    val scannedImages by viewModel.scannedImages.collectAsState()
    val presets by viewModel.presets.collectAsState(initial = emptyList())
    val activePresetName by viewModel.activePresetName.collectAsState()
    val selectedPresetIds by viewModel.selectedPresetIds.collectAsState()

    Column(modifier = Modifier.fillMaxSize()) {
        // Target Switcher at Top
        TargetSwitcher(
            selectedTarget = settingsTarget,
            onTargetSelected = { viewModel.setSettingsTarget(it) }
        )
        
        FolderScreen(
            folders = folders,
            isScanning = isScanning,
            selectedIds = selectedIds,
            scannedImages = scannedImages,
            presets = presets,
            activePresetName = activePresetName,
            onDeleteFolder = { viewModel.deleteFolder(it) },
            onScan = { viewModel.scanFolders() },
            onClearAllFolders = { viewModel.clearAllFolders() },
            onToggleFolderIdSelection = { viewModel.toggleFolderIdSelection(it) },
            onUpdateActivePreset = { viewModel.updateActivePreset() },
            onSavePreset = { viewModel.saveCurrentAsPreset(it) },
            onLoadPreset = { viewModel.loadPreset(it) },
            onDeletePreset = { viewModel.deletePreset(it) },
            selectedPresetIds = selectedPresetIds,
            onTogglePresetSelection = { viewModel.togglePresetSelection(it) },
            onDeleteSelectedPresets = { viewModel.deleteSelectedPresets() },
            onClearPresetSelection = { viewModel.clearPresetSelection() },
            onAddClick = null
        )
    }
}

@Composable
fun TargetSwitcher(
    selectedTarget: gustian.multiwallpaper.ui.SettingTarget,
    onTargetSelected: (gustian.multiwallpaper.ui.SettingTarget) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
    ) {
        Row(modifier = Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { onTargetSelected(gustian.multiwallpaper.ui.SettingTarget.HOME) },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (selectedTarget == gustian.multiwallpaper.ui.SettingTarget.HOME) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = if (selectedTarget == gustian.multiwallpaper.ui.SettingTarget.HOME) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            ) {
                Icon(Icons.Default.Home, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Home Sources", fontSize = 12.sp)
            }
            Button(
                onClick = { onTargetSelected(gustian.multiwallpaper.ui.SettingTarget.LOCK) },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (selectedTarget == gustian.multiwallpaper.ui.SettingTarget.LOCK) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = if (selectedTarget == gustian.multiwallpaper.ui.SettingTarget.LOCK) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            ) {
                Icon(Icons.Default.Lock, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Lock Sources", fontSize = 12.sp)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FolderScreen(
    folders: List<FolderEntity>,
    isScanning: Boolean = false,
    onDeleteFolder: (FolderEntity) -> Unit = {},
    onScan: () -> Unit = {},
    onAddClick: (() -> Unit)? = null,
    selectedIds: Set<Int> = emptySet(),
    scannedImages: List<WallpaperImg> = emptyList(),
    presets: List<PresetEntity> = emptyList(),
    activePresetName: String? = null,
    onClearAllFolders: () -> Unit = {},
    onToggleFolderIdSelection: (Int) -> Unit = {},
    onUpdateActivePreset: () -> Unit = {},
    onSavePreset: (String) -> Unit = {},
    onLoadPreset: (PresetEntity) -> Unit = {},
    onDeletePreset: (PresetEntity) -> Unit = {},
    selectedPresetIds: Set<Int> = emptySet(),
    onTogglePresetSelection: (Int) -> Unit = {},
    onDeleteSelectedPresets: () -> Unit = {},
    onClearPresetSelection: () -> Unit = {}
) {
    var showPresetDialog by remember { mutableStateOf(false) }
    var showSavePresetDialog by remember { mutableStateOf(false) }
    var presetName by remember { mutableStateOf("") }
    
    val grouped = remember(folders) { 
        folders.groupBy { 
            val uri = Uri.parse(it.uriString)
            if (uri.scheme == "file") java.io.File(uri.path ?: "").parentFile?.name ?: "Root" else "SAF Root" 
        } 
    }
    val expanded = remember { mutableStateMapOf<String, Boolean>() }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        // Redesigned Header: Two Rows
        Column(modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "SOURCES (${folders.size})",
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (activePresetName != null) {
                        IconButton(onClick = onUpdateActivePreset, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.CloudSync, contentDescription = "Update Preset", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                        }
                    }
                    IconButton(onClick = { showSavePresetDialog = true }, enabled = folders.isNotEmpty(), modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Save, contentDescription = "Save Preset", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                    }
                    IconButton(onClick = { showPresetDialog = true }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.CollectionsBookmark, contentDescription = "Presets", modifier = Modifier.size(20.dp))
                    }
                    
                    var showMoreMenu by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { showMoreMenu = true }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.MoreVert, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                        }
                        DropdownMenu(expanded = showMoreMenu, onDismissRequest = { showMoreMenu = false }) {
                            DropdownMenuItem(
                                text = { Text("Re-Scan All") },
                                onClick = { onScan(); showMoreMenu = false },
                                leadingIcon = { Icon(Icons.Default.Refresh, null, modifier = Modifier.size(18.dp)) }
                            )
                            DropdownMenuItem(
                                text = { Text("Clear All Folders") },
                                onClick = { onClearAllFolders(); showMoreMenu = false },
                                leadingIcon = { Icon(Icons.Default.DeleteSweep, null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error) }
                            )
                        }
                    }
                    
                    onAddClick?.let {
                        IconButton(onClick = it, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.Add, contentDescription = "Add Folder", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }
            
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text(
                        "Total: ${scannedImages.size} Images",
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
                
                if (activePresetName != null) {
                    Text(
                        "Preset: $activePresetName",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                } else if (presets.isNotEmpty()) {
                    Text(
                        "${presets.size} Presets available",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { showPresetDialog = true }
                    )
                }
            }
        }
        
        if (showSavePresetDialog) {
            AlertDialog(
                onDismissRequest = { showSavePresetDialog = false },
                title = { Text("Save Preset") },
                text = {
                    OutlinedTextField(
                        value = presetName,
                        onValueChange = { presetName = it },
                        label = { Text("Preset Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                },
                confirmButton = {
                    Button(onClick = {
                        if (presetName.isNotBlank()) {
                            onSavePreset(presetName)
                            presetName = ""
                            showSavePresetDialog = false
                        }
                    }) { Text("Save") }
                },
                dismissButton = {
                    TextButton(onClick = { showSavePresetDialog = false }) { Text("Cancel") }
                }
            )
        }

        if (showPresetDialog) {
            PresetManagerDialog(
                presets = presets,
                scannedImages = scannedImages,
                selectedIds = selectedPresetIds,
                onToggleSelection = onTogglePresetSelection,
                onDeleteSelected = onDeleteSelectedPresets,
                onLoadPreset = onLoadPreset,
                onDeletePreset = onDeletePreset,
                onClearAllFolders = onClearAllFolders,
                onDismiss = { 
                    onClearPresetSelection()
                    showPresetDialog = false 
                }
            )
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            grouped.forEach { (parent, parentFolders) ->
                val isExp = expanded[parent] ?: true
                item {
                    Card(onClick = { expanded[parent] = !isExp }, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))) {
                        Row(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Source, null, tint = MaterialTheme.colorScheme.primary)
                            Text(parent, modifier = Modifier.weight(1f).padding(horizontal = 12.dp), fontWeight = FontWeight.Bold)
                            Icon(if (isExp) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
                        }
                    }
                }
                if (isExp) {
                    items(parentFolders) { f ->
                        val sel = selectedIds.contains(f.id)
                        val folderPreview = remember(f.uriString, scannedImages) {
                            // First try exact match (images directly in this folder)
                            scannedImages.firstOrNull { it.folderUriString == f.uriString }?.uriString
                                ?: // Then try recursive match (images in subfolders)
                                scannedImages.firstOrNull { img -> 
                                    img.uriString.startsWith(f.uriString) 
                                }?.uriString
                        }?.let { Uri.parse(it) }
                        
                        Card(modifier = Modifier.padding(start = 16.dp).combinedClickable(onClick = { if (selectedIds.isNotEmpty()) onToggleFolderIdSelection(f.id) }, onLongClick = { onToggleFolderIdSelection(f.id) }), colors = CardDefaults.cardColors(containerColor = if (sel) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)) {
                            Row(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                if (selectedIds.isNotEmpty()) Checkbox(sel, { onToggleFolderIdSelection(f.id) }) 
                                else {
                                    Box(modifier = Modifier.size(36.dp).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
                                        if (folderPreview != null) {
                                            AsyncImage(model = folderPreview, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                                        } else {
                                            Icon(Icons.Default.Folder, null, modifier = Modifier.align(Alignment.Center), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                }
                                Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                    Text(f.displayName, style = MaterialTheme.typography.bodyMedium)
                                    val count = remember(f.uriString, scannedImages) {
                                        scannedImages.count { it.uriString.startsWith(f.uriString) }
                                    }
                                    Text("$count images", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                }
                                if (selectedIds.isEmpty()) IconButton(onClick = { onDeleteFolder(f) }) { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PresetManagerDialog(viewModel: HomeViewModel, onDismiss: () -> Unit) {
    val presets by viewModel.presets.collectAsState(initial = emptyList())
    val scannedImages by viewModel.scannedImages.collectAsState()
    val selectedIds by viewModel.selectedPresetIds.collectAsState()
    
    PresetManagerDialog(
        presets = presets,
        scannedImages = scannedImages,
        selectedIds = selectedIds,
        onToggleSelection = { viewModel.togglePresetSelection(it) },
        onDeleteSelected = { viewModel.deleteSelectedPresets() },
        onLoadPreset = { viewModel.loadPreset(it) },
        onDeletePreset = { viewModel.deletePreset(it) },
        onClearAllFolders = { viewModel.clearAllFolders() },
        onDismiss = {
            viewModel.clearPresetSelection()
            onDismiss()
        }
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PresetManagerDialog(
    presets: List<PresetEntity>,
    scannedImages: List<WallpaperImg>,
    selectedIds: Set<Int>,
    onToggleSelection: (Int) -> Unit,
    onDeleteSelected: () -> Unit,
    onLoadPreset: (PresetEntity) -> Unit,
    onDeletePreset: (PresetEntity) -> Unit,
    onClearAllFolders: () -> Unit,
    onDismiss: () -> Unit
) {
    var presetToDelete by remember { mutableStateOf<PresetEntity?>(null) }
    var showBulkDeleteConfirm by remember { mutableStateOf(false) }

    if (showBulkDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showBulkDeleteConfirm = false },
            title = { Text("Delete Selected Presets?") },
            text = { Text("Are you sure you want to delete ${selectedIds.size} selected presets? This cannot be undone.") },
            confirmButton = {
                Button(
                    onClick = { 
                        onDeleteSelected()
                        showBulkDeleteConfirm = false 
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { showBulkDeleteConfirm = false }) { Text("Cancel") }
            }
        )
    }
    
    if (presetToDelete != null) {
        AlertDialog(
            onDismissRequest = { presetToDelete = null },
            title = { Text("Delete Preset?") },
            text = { Text("Are you sure you want to delete '${presetToDelete?.name}'? This cannot be undone.") },
            confirmButton = {
                Button(
                    onClick = { 
                        onDeletePreset(presetToDelete!!)
                        presetToDelete = null 
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { presetToDelete = null }) { Text("Cancel") }
            }
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (selectedIds.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { onDeleteSelected() }) { // Shortcut delete or just use long text?
                             Icon(Icons.Default.Delete, "Bulk Delete", tint = MaterialTheme.colorScheme.error)
                        }
                        Text("${selectedIds.size} Selected", style = MaterialTheme.typography.titleMedium)
                    }
                    TextButton(onClick = { showBulkDeleteConfirm = true }) {
                         Text("Delete", color = MaterialTheme.colorScheme.error)
                    }
                } else {
                    Text("Presets")
                    TextButton(onClick = { 
                        onClearAllFolders()
                        onDismiss()
                    }) {
                        Icon(Icons.Default.Add, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("New Preset")
                    }
                }
            }
        },
        text = {
            if (presets.isEmpty()) {
                Box(modifier = Modifier.fillMaxWidth().height(100.dp), contentAlignment = Alignment.Center) {
                    Text("No presets saved yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(presets, key = { it.id ?: 0 }) { preset ->
                        val isSelected = selectedIds.contains(preset.id)
                        Card(
                            modifier = Modifier.fillMaxWidth().combinedClickable(
                                onClick = {
                                    if (selectedIds.isNotEmpty()) {
                                        onToggleSelection(preset.id!!)
                                    } else {
                                        onLoadPreset(preset)
                                        onDismiss()
                                    }
                                },
                                onLongClick = {
                                    onToggleSelection(preset.id!!)
                                }
                            ),
                            colors = if (isSelected) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer) else CardDefaults.cardColors()
                        ) {
                            Row(modifier = Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(modifier = Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
                                    if (preset.thumbnailUri != null) {
                                        AsyncImage(
                                            model = Uri.parse(preset.thumbnailUri),
                                            contentDescription = null,
                                            modifier = Modifier.fillMaxSize(),
                                            contentScale = ContentScale.Crop
                                        )
                                    } else {
                                        Icon(Icons.Default.Collections, null, modifier = Modifier.align(Alignment.Center), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    if (isSelected) {
                                        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.primary.copy(alpha = 0.4f))) {
                                            Icon(Icons.Default.Check, null, modifier = Modifier.align(Alignment.Center), tint = MaterialTheme.colorScheme.onPrimary)
                                        }
                                    }
                                }
                                Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                    Text(preset.name, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                                    val totalImgs = remember(preset.folderUris, scannedImages) {
                                        scannedImages.count { img -> preset.folderUris.any { fUri -> img.uriString.startsWith(fUri) } }
                                    }
                                    Text("${preset.folderUris.size} folders • $totalImgs images", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                if (selectedIds.isEmpty()) {
                                    IconButton(onClick = { presetToDelete = preset }) {
                                        Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}


@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GalleryScreen(viewModel: HomeViewModel) {
    val settingsTarget by viewModel.settingsTarget.collectAsState()
    val images by viewModel.scannedImages.collectAsState()
    val blacklisted by viewModel.blacklisted.collectAsState()
    val isScanning by viewModel.isScanning.collectAsState()
    val selectedUris by viewModel.selectedGalleryUris.collectAsState()
    val selectedFolderUris by viewModel.selectedGalleryFolderUris.collectAsState()
    val sortType by viewModel.gallerySortType.collectAsState()
    val searchQuery by viewModel.gallerySearchQuery.collectAsState()

    var selectedImgUri by remember { mutableStateOf<String?>(null) }
    var activeFolderUri by remember { mutableStateOf<String?>(null) }
    
    val grouped = remember(images, sortType, searchQuery) {
        val filtered = if (searchQuery.isBlank()) images 
                       else images.filter { img ->
                           val folderName = try {
                               val u = Uri.parse(img.folderUriString)
                               if (u.scheme == "file") java.io.File(u.path ?: "").name else Uri.decode(img.folderUriString).split("/").lastOrNull() ?: ""
                           } catch (e: Exception) { "" }
                           
                           img.displayName.contains(searchQuery, ignoreCase = true) || 
                           folderName.contains(searchQuery, ignoreCase = true) ||
                           img.folderUriString.contains(searchQuery, ignoreCase = true)
                       }
        
        val groups = filtered.groupBy { it.folderUriString }
        val sorted = when (sortType) {
            "NAME" -> groups.entries.sortedBy { entry -> 
                val uri = Uri.parse(entry.key)
                if (uri.scheme == "file") java.io.File(uri.path ?: "").name else Uri.decode(entry.key).split("/").lastOrNull() ?: "Folder"
            }
            "DATE" -> groups.entries.toList() // Scan order is basically date added
            "STAR" -> groups.entries.sortedByDescending { it.value.any { img -> img.isFavorite } }
            else -> groups.entries.toList()
        }
        sorted.associate { it.key to it.value }
    }
    
    val expanded = remember { mutableStateMapOf<String, Boolean>() }
    var showBlacklistSection by remember { mutableStateOf(false) }
    
    Column(modifier = Modifier.fillMaxSize()) {
        TargetSwitcher(
            selectedTarget = settingsTarget,
            onTargetSelected = { viewModel.setSettingsTarget(it) }
        )

        if (isScanning) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())

        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            grouped.forEach { (uri, imgs) ->
                val isExp = expanded[uri] ?: false
                val anyFav = imgs.any { it.isFavorite }
                val isSelected = selectedFolderUris.contains(uri)
                
                item {
                    val name = remember(uri) { val u = Uri.parse(uri); if (u.scheme == "file") java.io.File(u.path ?: "").name else Uri.decode(uri).split("/").lastOrNull() ?: "Folder" }
                    Card(
                        modifier = Modifier.combinedClickable(
                            onClick = { 
                                if (selectedFolderUris.isNotEmpty()) viewModel.toggleGalleryFolderSelection(uri) 
                                else expanded[uri] = !(expanded[uri] ?: false)
                            },
                            onLongClick = { viewModel.toggleGalleryFolderSelection(uri) }
                        ),
                        border = BorderStroke(1.dp, if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                        colors = CardDefaults.cardColors(containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)
                    ) {
                        Row(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (selectedFolderUris.isNotEmpty()) Checkbox(isSelected, { viewModel.toggleGalleryFolderSelection(uri) })
                            Box(modifier = Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
                                val firstImg = imgs.firstOrNull()
                                if (firstImg != null) {
                                    AsyncImage(model = Uri.parse(firstImg.uriString), contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                                } else {
                                    Icon(if (isExp) Icons.Default.FolderOpen else Icons.Default.Folder, null, modifier = Modifier.align(Alignment.Center), tint = MaterialTheme.colorScheme.primary)
                                }
                            }
                            Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                Text(name, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${imgs.size} images", style = MaterialTheme.typography.labelSmall)
                            }
                            IconButton(onClick = { viewModel.toggleFavoriteFolder(uri) }) { Icon(if (anyFav) Icons.Default.Star else Icons.Default.StarOutline, null, tint = if (anyFav) Color(0xFFEAB308) else MaterialTheme.colorScheme.onSurfaceVariant) }
                            Icon(if (isExp) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
                        }
                    }
                }
                if (isExp) {
                    val chunks = imgs.chunked(3)
                    items(chunks.size) { i ->
                        Row(modifier = Modifier.padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            chunks[i].forEach { img ->
                                val sel = selectedUris.contains(img.uriString)
                                Box(modifier = Modifier.weight(1f).aspectRatio(0.85f).clip(RoundedCornerShape(12.dp)).background(if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant).combinedClickable(
                                    onClick = { 
                                        if (selectedUris.isNotEmpty()) viewModel.toggleGalleryUriSelection(img.uriString) 
                                        else {
                                            selectedImgUri = img.uriString
                                            activeFolderUri = uri
                                        }
                                    }, 
                                    onLongClick = { viewModel.toggleGalleryUriSelection(img.uriString) }
                                )) {
                                    AsyncImage(model = Uri.parse(img.uriString), contentDescription = null, modifier = Modifier.fillMaxSize().alpha(if (sel) 0.6f else 1f), contentScale = ContentScale.Crop)
                                    if (img.isFavorite) Icon(Icons.Default.Star, null, tint = Color.Yellow, modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(16.dp))
                                    if (sel) Icon(Icons.Default.CheckCircle, null, tint = Color.White, modifier = Modifier.align(Alignment.Center).size(32.dp))
                                }
                            }
                            repeat(3 - chunks[i].size) { Spacer(modifier = Modifier.weight(1f)) }
                        }
                    }
                }
            }
        }
    }
    
    val activeImgs = grouped[activeFolderUri] ?: emptyList()
    val activeIndex = activeImgs.indexOfFirst { it.uriString == selectedImgUri }
    
    if (selectedImgUri != null && activeIndex != -1) {
        ImageDetailDialog(
            images = activeImgs,
            initialIndex = activeIndex,
            onDismiss = { selectedImgUri = null },
            onToggleFavorite = { viewModel.toggleFavorite(it) },
            onBlacklist = { img ->
                viewModel.blacklistCurrentUri(img.uriString, img.folderUriString, img.displayName)
                selectedImgUri = null
            }
        )
    }
}

@Composable
fun FavoritesScreen(viewModel: HomeViewModel) {
    val settingsTarget by viewModel.settingsTarget.collectAsState()
    val favorites by viewModel.favorites.collectAsState(initial = emptyList())
    val blacklisted by viewModel.blacklisted.collectAsState(initial = emptyList())
    var selectedFavIndex by remember { mutableIntStateOf(-1) }
    var selectedBlIndex by remember { mutableIntStateOf(-1) }
    
    // Sub-tab state
    var selectedSubTab by remember { mutableIntStateOf(0) } // 0 for Favorites, 1 for Blacklist

    Column(modifier = Modifier.fillMaxSize()) {
        TargetSwitcher(
            selectedTarget = settingsTarget,
            onTargetSelected = { viewModel.setSettingsTarget(it) }
        )

        // --- SUB-TABS NAVIGATION ---
        TabRow(
            selectedTabIndex = selectedSubTab,
            containerColor = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.primary,
            indicator = { tabPositions ->
                TabRowDefaults.SecondaryIndicator(
                    Modifier.tabIndicatorOffset(tabPositions[selectedSubTab]),
                    color = MaterialTheme.colorScheme.primary
                )
            },
            divider = {}
        ) {
            Tab(
                selected = selectedSubTab == 0,
                onClick = { selectedSubTab = 0 },
                text = { 
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Star, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Favorites (${favorites.size})", fontSize = 13.sp, fontWeight = FontWeight.Bold) 
                    }
                }
            )
            Tab(
                selected = selectedSubTab == 1,
                onClick = { selectedSubTab = 1 },
                text = { 
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Block, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Blacklist (${blacklisted.size})", fontSize = 13.sp, fontWeight = FontWeight.Bold) 
                    }
                }
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Box(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            if (selectedSubTab == 0) {
                // --- FAVORITES TAB ---
                if (favorites.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { 
                        Text("No Favorites yet", color = MaterialTheme.colorScheme.onSurfaceVariant) 
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(100.dp),
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        contentPadding = PaddingValues(bottom = 32.dp)
                    ) {
                        itemsIndexed(favorites) { index, f ->
                            Box(modifier = Modifier.aspectRatio(0.85f).clip(RoundedCornerShape(16.dp)).clickable { selectedFavIndex = index }) {
                                AsyncImage(model = Uri.parse(f.uriString), contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                                IconButton(
                                    onClick = { viewModel.toggleFavorite(WallpaperImg(f.uriString, f.folderUriString, f.displayName, true)) },
                                    modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).background(MaterialTheme.colorScheme.surface.copy(alpha = 0.7f), RoundedCornerShape(8.dp)).size(32.dp)
                                ) { 
                                    Icon(Icons.Default.Star, null, tint = Color(0xFFEAB308), modifier = Modifier.size(18.dp)) 
                                }
                            }
                        }
                    }
                }
            } else {
                // --- BLACKLIST TAB ---
                if (blacklisted.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { 
                        Text("No blacklisted images", color = MaterialTheme.colorScheme.onSurfaceVariant) 
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(100.dp),
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        contentPadding = PaddingValues(bottom = 32.dp)
                    ) {
                        itemsIndexed(blacklisted) { index, bl ->
                            Box(modifier = Modifier.aspectRatio(0.85f).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.errorContainer).clickable { selectedBlIndex = index }) {
                                AsyncImage(model = Uri.parse(bl.uriString), contentDescription = null, modifier = Modifier.fillMaxSize().alpha(0.6f), contentScale = ContentScale.Crop)
                                IconButton(
                                    onClick = { viewModel.restoreBlacklistedImage(bl) },
                                    modifier = Modifier.align(Alignment.Center).background(MaterialTheme.colorScheme.surface.copy(alpha = 0.8f), CircleShape).size(32.dp)
                                ) {
                                    Icon(Icons.Default.RestoreFromTrash, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    
    // --- DIALOGS ---
    if (selectedFavIndex != -1) {
        val favImgs = favorites.map { WallpaperImg(it.uriString, it.folderUriString, it.displayName, true) }
        ImageDetailDialog(
            images = favImgs,
            initialIndex = selectedFavIndex,
            onDismiss = { selectedFavIndex = -1 },
            onToggleFavorite = { viewModel.toggleFavorite(it) },
            onBlacklist = { img ->
                viewModel.blacklistCurrentUri(img.uriString, img.folderUriString, img.displayName)
                selectedFavIndex = -1
            }
        )
    }

    if (selectedBlIndex != -1) {
        val blImgs = blacklisted.map { WallpaperImg(it.uriString, it.folderUriString, it.displayName, false) }
        ImageDetailDialog(
            images = blImgs,
            initialIndex = selectedBlIndex,
            onDismiss = { selectedBlIndex = -1 },
            onToggleFavorite = { viewModel.toggleFavorite(it) },
            onBlacklist = { /* Already blacklisted */ }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SettingsScreen(viewModel: HomeViewModel) {
    val settingsTarget by viewModel.settingsTarget.collectAsState()
    val totalSeconds by viewModel.intervalSeconds.collectAsState()
    val transition by viewModel.transitionType.collectAsState()
    val useFav by viewModel.useFavoritesOnly.collectAsState()
    val doubleTap by viewModel.doubleTapEnabled.collectAsState()
    val fadeSpeed by viewModel.fadeSpeed.collectAsState()
    val parallaxEnabled by viewModel.parallaxEnabled.collectAsState()
    val parallaxStrength by viewModel.parallaxStrength.collectAsState()
    val shakeEnabled by viewModel.shakeEnabled.collectAsState()
    val smartCropEnabled by viewModel.smartCropEnabled.collectAsState()
    val lightModeEnabled by viewModel.lightModeEnabled.collectAsState()
    val wallpaperQuality by viewModel.wallpaperQuality.collectAsState()
    val aiAdvancedEnabled by viewModel.aiAdvancedEnabled.collectAsState()
    val aiZoomSlack by viewModel.aiZoomSlack.collectAsState()
    val aiSensitivityX by viewModel.aiSensitivityX.collectAsState()
    val aiSensitivityY by viewModel.aiSensitivityY.collectAsState()
    val manualFocalX by viewModel.manualFocalX.collectAsState()
    val manualFocalY by viewModel.manualFocalY.collectAsState()
    val smartAdjacencyEnabled by viewModel.smartAdjacencyEnabled.collectAsState()
    val blurRadius by viewModel.blurRadius.collectAsState()
    val dimIntensity by viewModel.dimIntensity.collectAsState()
    val blurEnabled by viewModel.blurEnabled.collectAsState()
    val dimEnabled by viewModel.dimEnabled.collectAsState()
    val subjectFocusEnabled by viewModel.subjectFocusEnabled.collectAsState()
    val subjectFocusSmoothing by viewModel.subjectFocusSmoothing.collectAsState()
    val vignetteModeEnabled by viewModel.vignetteModeEnabled.collectAsState()
    val vignetteSharpness by viewModel.vignetteSharpness.collectAsState()
    val vignetteWidth by viewModel.vignetteWidth.collectAsState()
    val filterType by viewModel.filterType.collectAsState()
    val filterColor1 by viewModel.filterColor1.collectAsState()
    val filterColor2 by viewModel.filterColor2.collectAsState()
    val filterColor3 by viewModel.filterColor3.collectAsState()
    val customPalettes by viewModel.customPalettes.collectAsState()
    val latestVersionInfo by viewModel.latestVersionInfo.collectAsState()
    val isCheckingUpdate by viewModel.isCheckingUpdate.collectAsState()
    val updateMessage by viewModel.updateMessage.collectAsState()
    val historyLimit by viewModel.historyLimit.collectAsState()
    val historyCount by viewModel.historyCount.collectAsState()
    val sortOrder by viewModel.rotationSortOrder.collectAsState()
    val schedules by viewModel.schedules.collectAsState(initial = emptyList())
    val presets by viewModel.presets.collectAsState(initial = emptyList())
    
    var unit by remember(totalSeconds) { 
        mutableStateOf(if (totalSeconds < 60) "Sec" else if (totalSeconds < 3600) "Min" else "Hour") 
    }
    val displayValue = remember(totalSeconds, unit) {
        val v = when(unit) {
            "Sec" -> totalSeconds
            "Min" -> totalSeconds / 60
            "Hour" -> totalSeconds / 3600
            else -> totalSeconds
        }
        v.roundToInt()
    }
    
    val scrollState = rememberScrollState()
    val context = LocalContext.current
    
    // --- REAL-TIME PREVIEW STATE ---
    var isDraggingSlider by remember { mutableStateOf(false) }
    val favorites by viewModel.favorites.collectAsState(initial = emptyList())
    val scannedImages by viewModel.scannedImages.collectAsState()
    val previewUri = remember(favorites, scannedImages) {
        favorites.firstOrNull()?.uriString ?: scannedImages.firstOrNull()?.uriString
    }

    val isPreviewActive = isDraggingSlider

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // --- FIXED HEADER: Settings Target Switcher ---
            Column(modifier = Modifier.background(MaterialTheme.colorScheme.surface).padding(horizontal = 20.dp, vertical = 12.dp)) {
                Text("TARGET SCREEN", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.height(12.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { viewModel.setSettingsTarget(gustian.multiwallpaper.ui.SettingTarget.HOME) },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (settingsTarget == gustian.multiwallpaper.ui.SettingTarget.HOME) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = if (settingsTarget == gustian.multiwallpaper.ui.SettingTarget.HOME) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    ) {
                        Icon(Icons.Default.Home, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Home")
                    }
                    Button(
                        onClick = { viewModel.setSettingsTarget(gustian.multiwallpaper.ui.SettingTarget.LOCK) },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (settingsTarget == gustian.multiwallpaper.ui.SettingTarget.LOCK) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = if (settingsTarget == gustian.multiwallpaper.ui.SettingTarget.LOCK) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    ) {
                        Icon(Icons.Default.Lock, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Lock")
                    }
                }
                
                TextButton(
                    onClick = { viewModel.copySettingsFromOther() },
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Icon(Icons.Default.ContentCopy, null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Copy from ${if (settingsTarget == gustian.multiwallpaper.ui.SettingTarget.HOME) "Lock" else "Home"}", fontSize = 11.sp)
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            // --- SCROLLABLE CONTENT ---
            Column(modifier = Modifier.weight(1f).padding(horizontal = 20.dp).verticalScroll(scrollState)) {
                Spacer(modifier = Modifier.height(16.dp))
                Text("GENERAL SETTINGS", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.height(16.dp))

                // --- Interaction Group ---
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        SettingRow(title = "Use Favorites Only", checked = useFav, onCheckedChange = { viewModel.setUseFavoritesOnly(it) })
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        SettingRow(
                            title = "Smart Adjacency locking",
                            subtitle = if (sortOrder == "FOLDER") "Disabled in 'By Folder' order" else "Prevent same folder on adjacent pages",
                            checked = smartAdjacencyEnabled && sortOrder != "FOLDER",
                            onCheckedChange = { if (sortOrder != "FOLDER") viewModel.setSmartAdjacencyEnabled(it) }
                        )
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        SettingRow(title = "Double Tap to Change", checked = doubleTap, onCheckedChange = { viewModel.setDoubleTapEnabled(it) })
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        SettingRow(title = "Shake to Change", checked = shakeEnabled, onCheckedChange = { viewModel.setShakeEnabled(it) })
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))
                Text("MOTION & PARALLAX", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.height(12.dp))

                // --- Motion Group ---
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        SettingRow(title = "React to Motion (Parallax)", checked = parallaxEnabled, onCheckedChange = { viewModel.setParallaxEnabled(it) })
                        
                        if (parallaxEnabled) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("Motion Strength", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                            Slider(
                                value = parallaxStrength,
                                onValueChange = { viewModel.setParallaxStrength(it) },
                                valueRange = 0.1f..1f,
                                steps = 8
                            )
                            Text("${(parallaxStrength * 100).toInt()}%", style = MaterialTheme.typography.labelSmall, modifier = Modifier.align(Alignment.End))
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))
                Text("AI SMART CONTROLS", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.height(12.dp))

                // --- AI Group ---
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        SettingRow(title = "AI Smart Crop Face", checked = smartCropEnabled, onCheckedChange = { viewModel.setSmartCropEnabled(it) })
                        
                        if (smartCropEnabled) {
                            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                            SettingRow(
                                title = "Advanced AI Controls", 
                                subtitle = "Unlock fine-tuned crop limits",
                                checked = aiAdvancedEnabled, 
                                onCheckedChange = { viewModel.setAiAdvancedEnabled(it) }
                            )

                            if (aiAdvancedEnabled) {
                                Column(modifier = Modifier.padding(top = 8.dp)) {
                                    Text("AI Zoom Slack (Max Zoom)", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                                    Slider(
                                        value = aiZoomSlack,
                                        onValueChange = { viewModel.setAiZoomSlack(it) },
                                        valueRange = 1.1f..2.0f,
                                        steps = 8
                                    )
                                    Text("${(aiZoomSlack * 100).toInt()}%", style = MaterialTheme.typography.labelSmall, modifier = Modifier.align(Alignment.End))

                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text("Horizontal Sensitivity", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                                    Slider(
                                        value = aiSensitivityX,
                                        onValueChange = { viewModel.setAiSensitivityX(it) },
                                        valueRange = 0.1f..1.0f
                                    )
                                    Text("${(aiSensitivityX * 100).toInt()}%", style = MaterialTheme.typography.labelSmall, modifier = Modifier.align(Alignment.End))

                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text("Vertical Sensitivity", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                                    Slider(
                                        value = aiSensitivityY,
                                        onValueChange = { viewModel.setAiSensitivityY(it) },
                                        valueRange = 0.1f..1.0f
                                    )
                                    Text("${(aiSensitivityY * 100).toInt()}%", style = MaterialTheme.typography.labelSmall, modifier = Modifier.align(Alignment.End))
                                }
                            }

                            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                            
                            var showFocalEditor by remember { mutableStateOf(false) }
                            Row(
                                modifier = Modifier.fillMaxWidth().clickable { showFocalEditor = true }.padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text("Adjust Manual Fallback", fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodyLarge)
                                    Text("Set focus if AI detection fails", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Icon(Icons.Default.Adjust, null, tint = MaterialTheme.colorScheme.primary)
                            }

                            if (showFocalEditor) {
                                ManualFocalEditorDialog(viewModel) { showFocalEditor = false }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))
                Text("PERFORMANCE", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.height(12.dp))

                // --- Performance Group ---
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("Wallpaper Quality", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("LOW", "NORMAL", "HIGH").forEach { q ->
                                val selected = wallpaperQuality == q
                                FilterChip(
                                    selected = selected,
                                    onClick = { viewModel.setWallpaperQuality(q) },
                                    label = { Text(q, fontSize = 11.sp) },
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(8.dp),
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                                        selectedLabelColor = MaterialTheme.colorScheme.primary
                                    )
                                )
                            }
                        }
                        val qualityDesc = when(wallpaperQuality) {
                            "LOW" -> "Lowest RAM usage, RGB_565 format (16-bit), reduced resolution."
                            "HIGH" -> "Maximum sharpness, ARGB_8888 format (32-bit), highest resolution."
                            else -> "Balanced RAM & quality. Uses 32-bit for active page only."
                        }
                        Text(qualityDesc, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))

                        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        SettingRow(title = "Power Saver (Light Mode)", subtitle = "Saves battery & RAM", checked = lightModeEnabled, onCheckedChange = { viewModel.setLightModeEnabled(it) })
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))
                Text("VISUAL EFFECTS", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.height(12.dp))

                // --- Visual Group ---
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        SettingRow(
                            title = "AI Subject Focus",
                            subtitle = "Effects follow the subject's face",
                            checked = subjectFocusEnabled,
                            onCheckedChange = { viewModel.setSubjectFocusEnabled(it) }
                        )
                        
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                        SettingRow(
                            title = "Vignette Mode (Edge Focus)",
                            subtitle = "Edge-mask focusing on screen center",
                            checked = vignetteModeEnabled,
                            onCheckedChange = { viewModel.setVignetteModeEnabled(it) }
                        )

                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                        SettingRow(
                            title = "Enable Dimming",
                            subtitle = if (subjectFocusEnabled || vignetteModeEnabled) "Edge darkening (Spotlight)" else "Full screen darkening",
                            checked = dimEnabled,
                            onCheckedChange = { viewModel.setDimEnabled(it) }
                        )
                        
                        if (dimEnabled) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("Dim Intensity", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                            Slider(
                                value = dimIntensity,
                                onValueChange = { 
                                    isDraggingSlider = true
                                    viewModel.setDimIntensity(it) 
                                },
                                onValueChangeFinished = { isDraggingSlider = false },
                                valueRange = 0f..1.0f
                            )
                            Text("${(dimIntensity * 100).toInt()}% Intensity", style = MaterialTheme.typography.labelSmall, modifier = Modifier.align(Alignment.End))
                        }

                        if (android.os.Build.VERSION.SDK_INT >= 31) {
                            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                            SettingRow(
                                title = "Enable Blur",
                                subtitle = if (subjectFocusEnabled || vignetteModeEnabled) "Edge bokeh (sharp center)" else "Full screen blur",
                                checked = blurEnabled,
                                onCheckedChange = { viewModel.setBlurEnabled(it) }
                            )
                            
                            if (blurEnabled) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Text("Blur Intensity", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                                Slider(
                                    value = blurRadius,
                                    onValueChange = { 
                                        isDraggingSlider = true
                                        viewModel.setBlurRadius(it) 
                                    },
                                    onValueChangeFinished = { isDraggingSlider = false },
                                    valueRange = 0f..100f
                                )
                                Text("${blurRadius.toInt()}px Radius", style = MaterialTheme.typography.labelSmall, modifier = Modifier.align(Alignment.End))
                            }
                        }

                        if (subjectFocusEnabled || vignetteModeEnabled) {
                            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                            
                            if (vignetteModeEnabled) {
                                Text("Shadow Width (Spread)", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                                Slider(
                                    value = vignetteWidth,
                                    onValueChange = { 
                                        isDraggingSlider = true
                                        viewModel.setVignetteWidth(it) 
                                    },
                                    onValueChangeFinished = { isDraggingSlider = false },
                                    valueRange = 0.05f..0.5f
                                )
                                Text("${(vignetteWidth * 100).toInt()}% Width", style = MaterialTheme.typography.labelSmall, modifier = Modifier.align(Alignment.End))

                                Spacer(modifier = Modifier.height(8.dp))
                                Text("Shadow Sharpness (Edge Focus)", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                                Slider(
                                    value = vignetteSharpness,
                                    onValueChange = { 
                                        isDraggingSlider = true
                                        viewModel.setVignetteSharpness(it) 
                                    },
                                    onValueChangeFinished = { isDraggingSlider = false },
                                    valueRange = 0.1f..0.9f
                                )
                                Text("${(vignetteSharpness * 100).toInt()}% Sharpness", style = MaterialTheme.typography.labelSmall, modifier = Modifier.align(Alignment.End))
                            } else {
                                Text("Spotlight Size (Diameter)", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                                Slider(
                                    value = subjectFocusSmoothing,
                                    onValueChange = { 
                                        isDraggingSlider = true
                                        viewModel.setSubjectFocusSmoothing(it) 
                                    },
                                    onValueChangeFinished = { isDraggingSlider = false },
                                    valueRange = 0.1f..0.9f
                                )
                                Text("${(subjectFocusSmoothing * 100).toInt()}% Size", style = MaterialTheme.typography.labelSmall, modifier = Modifier.align(Alignment.End))

                                Spacer(modifier = Modifier.height(8.dp))
                                Text("Focus Smoothing (Softness)", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                                Slider(
                                    value = vignetteSharpness,
                                    onValueChange = { 
                                        isDraggingSlider = true
                                        viewModel.setVignetteSharpness(it) 
                                    },
                                    onValueChangeFinished = { isDraggingSlider = false },
                                    valueRange = 0.1f..0.9f
                                )
                                Text("${(vignetteSharpness * 100).toInt()}% Softness", style = MaterialTheme.typography.labelSmall, modifier = Modifier.align(Alignment.End))
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))
                Text("GESTURE GUIDE", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.height(12.dp))

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.2f))
                ) {
                    Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.DeleteSweep, null, tint = MaterialTheme.colorScheme.tertiary)
                        Spacer(modifier = Modifier.width(16.dp))
                        Column {
                            Text("Instant Blacklist", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                            val hintText = if (settingsTarget == gustian.multiwallpaper.ui.SettingTarget.LOCK) "Lock Screen" else "Home Screen"
                            Text("Tap with 2 fingers on $hintText to remove current wallpaper from rotation.", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))
                Text("TIMING & EFFECTS", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 12.dp)) {
                    Text("Rotation Interval", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                    OutlinedTextField(
                        value = displayValue.toString(),
                        onValueChange = { 
                            val v = it.toIntOrNull() ?: 0
                            val newSec = when(unit) {
                                "Sec" -> v.toFloat()
                                "Min" -> (v * 60).toFloat()
                                "Hour" -> (v * 3600).toFloat()
                                else -> v.toFloat()
                            }
                            viewModel.setIntervalSeconds(newSec)
                        },
                        modifier = Modifier.width(90.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    var expanded by remember { mutableStateOf(false) }
                    Box {
                        Button(onClick = { expanded = true }, shape = RoundedCornerShape(12.dp), contentPadding = PaddingValues(horizontal = 12.dp)) { 
                            Text(unit, fontSize = 12.sp)
                        }
                        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                            listOf("Sec", "Min", "Hour").forEach { u ->
                                DropdownMenuItem(text = { Text(u) }, onClick = { unit = u; expanded = false })
                            }
                        }
                    }
                }
                
                Slider(
                    value = displayValue.toFloat().coerceIn(1f, 60f),
                    onValueChange = { 
                        val rounded = it.roundToInt()
                        val newSec = when(unit) {
                            "Sec" -> rounded.toFloat()
                            "Min" -> (rounded * 60).toFloat()
                            "Hour" -> (rounded * 3600).toFloat()
                            else -> rounded.toFloat()
                        }
                        viewModel.setIntervalSeconds(newSec)
                    },
                    valueRange = 1f..60f,
                    steps = 59
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                Text("Transition Effect", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { viewModel.setTransitionType("slide") }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp), colors = ButtonDefaults.buttonColors(containerColor = if (transition == "slide") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)) { Text("Slide") }
                    Button(onClick = { viewModel.setTransitionType("fade") }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp), colors = ButtonDefaults.buttonColors(containerColor = if (transition == "fade") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)) { Text("Fade") }
                }
                
                if (transition == "fade") {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Fade Speed", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    Slider(
                        value = fadeSpeed.toFloat(),
                        onValueChange = { viewModel.setFadeSpeed(it.roundToInt()) },
                        valueRange = 5f..50f,
                        steps = 9
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))
                Text("Rotation Order", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { viewModel.setRotationSortOrder("RANDOM") }, 
                        modifier = Modifier.weight(1f), 
                        shape = RoundedCornerShape(12.dp), 
                        colors = ButtonDefaults.buttonColors(containerColor = if (sortOrder == "RANDOM") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant, contentColor = if (sortOrder == "RANDOM") MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant)
                    ) { 
                        Text("Random") 
                    }
                    Button(
                        onClick = { viewModel.setRotationSortOrder("FOLDER") }, 
                        modifier = Modifier.weight(1f), 
                        shape = RoundedCornerShape(12.dp), 
                        colors = ButtonDefaults.buttonColors(containerColor = if (sortOrder == "FOLDER") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant, contentColor = if (sortOrder == "FOLDER") MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant)
                    ) { 
                        Text("By Folder") 
                    }
                }
                if (sortOrder == "FOLDER") {
                    Text("Smart Adjacency is limited in Folder Order", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 4.dp))
                }

                Spacer(modifier = Modifier.height(24.dp))
                var showCooldownDialog by remember { mutableStateOf(false) }
                val autoLimitEnabled by viewModel.autoLimitEnabled.collectAsState()

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Rotation History Limit", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                        TextButton(onClick = { showCooldownDialog = true }, contentPadding = PaddingValues(0.dp)) {
                            Text("View History ($historyCount)", fontSize = 12.sp)
                        }
                    }
                    
                    Column(horizontalAlignment = Alignment.End) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Auto", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.width(4.dp))
                            Switch(
                                checked = autoLimitEnabled,
                                onCheckedChange = { viewModel.setAutoLimitEnabled(it) },
                                modifier = Modifier.graphicsLayer(scaleX = 0.7f, scaleY = 0.7f)
                            )
                        }
                        OutlinedTextField(
                            value = if (autoLimitEnabled) "AUTO" else historyLimit.toString(),
                            onValueChange = { 
                                if (!autoLimitEnabled) {
                                    val v = it.toIntOrNull() ?: 0
                                    viewModel.setHistoryLimit(v.coerceIn(0, 8000))
                                }
                            },
                            modifier = Modifier.width(90.dp),
                            enabled = !autoLimitEnabled,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyMedium,
                            shape = RoundedCornerShape(12.dp)
                        )
                    }
                }
                
                Slider(
                    value = historyLimit.toFloat().coerceIn(10f, 8000f),
                    onValueChange = { viewModel.setHistoryLimit(it.roundToInt()) },
                    valueRange = 10f..8000f,
                    steps = 799,
                    enabled = !autoLimitEnabled
                )
                Text(
                    text = if (autoLimitEnabled) "All ${historyLimit} images (Preset Total)" else "$historyLimit images (Anti-repetition limit)", 
                    style = MaterialTheme.typography.labelSmall, 
                    color = if (autoLimitEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, 
                    modifier = Modifier.align(Alignment.End)
                )

                Spacer(modifier = Modifier.height(32.dp))
                Text("COLOR FILTERS", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.height(12.dp))

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("NONE" to "Off", "GRAYSCALE" to "B&W", "DUOTONE" to "2-tone", "TRITONE" to "3-tone").forEach { (type, label) ->
                                val selected = filterType == type
                                FilterChip(
                                    selected = selected,
                                    onClick = { viewModel.setFilterType(type) },
                                    label = { Text(label, fontSize = 11.sp) },
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(8.dp),
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                                        selectedLabelColor = MaterialTheme.colorScheme.primary
                                    )
                                )
                            }
                        }

                        if (filterType == "DUOTONE" || filterType == "TRITONE") {
                            var showPicker1 by remember { mutableStateOf(false) }
                            var showPicker2 by remember { mutableStateOf(false) }
                            var showPicker3 by remember { mutableStateOf(false) }

                            if (showPicker1) {
                                ColorPickerDialog(
                                    initialColor = Color(filterColor1),
                                    onDismiss = { showPicker1 = false },
                                    onColorSelected = { 
                                        viewModel.setFilterColor1(it.toArgb())
                                        showPicker1 = false
                                    }
                                )
                            }
                            if (showPicker2) {
                                ColorPickerDialog(
                                    initialColor = Color(filterColor2),
                                    onDismiss = { showPicker2 = false },
                                    onColorSelected = { 
                                        viewModel.setFilterColor2(it.toArgb())
                                        showPicker2 = false
                                    }
                                )
                            }
                            if (showPicker3) {
                                ColorPickerDialog(
                                    initialColor = Color(filterColor3),
                                    onDismiss = { showPicker3 = false },
                                    onColorSelected = { 
                                        viewModel.setFilterColor3(it.toArgb())
                                        showPicker3 = false
                                    }
                                )
                            }

                            Spacer(modifier = Modifier.height(16.dp))
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Dark/Shadow", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                                    Spacer(Modifier.height(4.dp))
                                    ColorPickerButton(color = Color(filterColor1), onClick = { showPicker1 = true })
                                }
                                
                                if (filterType == "TRITONE") {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("Midtone", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                                        Spacer(Modifier.height(4.dp))
                                        ColorPickerButton(color = Color(filterColor3), onClick = { showPicker3 = true })
                                    }
                                }
                                
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Light/Highlight", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                                    Spacer(Modifier.height(4.dp))
                                    ColorPickerButton(color = Color(filterColor2), onClick = { showPicker2 = true })
                                }
                            }
                            
                            Spacer(modifier = Modifier.height(16.dp))
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text("Saved Palettes", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                                Row {
                                    TextButton(onClick = { 
                                        val randomColor1 = (0xFF000000..0xFFFFFFFF).random().toInt()
                                        val randomColor2 = (0xFF000000..0xFFFFFFFF).random().toInt()
                                        viewModel.setFilterColor1(randomColor1)
                                        viewModel.setFilterColor2(randomColor2)
                                        if (filterType == "TRITONE") {
                                            val randomColor3 = (0xFF000000..0xFFFFFFFF).random().toInt()
                                            viewModel.setFilterColor3(randomColor3)
                                        }
                                    }) {
                                        Icon(Icons.Default.Casino, null, modifier = Modifier.size(14.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("Randomize", fontSize = 10.sp)
                                    }
                                    
                                    var showSaveDialog by remember { mutableStateOf(false) }
                                    TextButton(onClick = { showSaveDialog = true }) {
                                        Icon(Icons.Default.Save, null, modifier = Modifier.size(14.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("Save Current", fontSize = 10.sp)
                                    }
                                    
                                    if (showSaveDialog) {
                                        var paletteName by remember { mutableStateOf("") }
                                        AlertDialog(
                                            onDismissRequest = { showSaveDialog = false },
                                            title = { Text("Save Palette") },
                                            text = {
                                                OutlinedTextField(
                                                    value = paletteName,
                                                    onValueChange = { paletteName = it },
                                                    label = { Text("Palette Name") },
                                                    singleLine = true
                                                )
                                            },
                                            confirmButton = {
                                                Button(onClick = {
                                                    if (paletteName.isNotBlank()) {
                                                        viewModel.saveCustomPalette(paletteName)
                                                        showSaveDialog = false
                                                    }
                                                }) { Text("Save") }
                                            },
                                            dismissButton = {
                                                TextButton(onClick = { showSaveDialog = false }) { Text("Cancel") }
                                            }
                                        )
                                    }
                                }
                            }
                            
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
                                // 1. Custom Saved Palettes
                                    items(customPalettes) { palette ->
                                        val isSelected = filterColor1 == palette.color1 && filterColor2 == palette.color2 && (filterType == "DUOTONE" || filterColor3 == palette.color3)
                                        var showDeleteConfirm by remember { mutableStateOf(false) }

                                        if (showDeleteConfirm) {
                                            AlertDialog(
                                                onDismissRequest = { showDeleteConfirm = false },
                                                title = { Text("Delete Palette?") },
                                                text = { Text("Delete '${palette.name}' palette?") },
                                                confirmButton = {
                                                    Button(onClick = { viewModel.deleteCustomPalette(palette); showDeleteConfirm = false }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("Delete") }
                                                },
                                                dismissButton = {
                                                    TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") }
                                                }
                                            )
                                        }

                                        Card(
                                            onClick = { 
                                                viewModel.setFilterColor1(palette.color1)
                                                viewModel.setFilterColor2(palette.color2)
                                                palette.color3?.let { viewModel.setFilterColor3(it) }
                                            },
                                            modifier = Modifier.width(100.dp),
                                            shape = RoundedCornerShape(8.dp),
                                            border = if (isSelected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
                                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                                        ) {
                                            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(6.dp)) {
                                                Row(modifier = Modifier.height(20.dp).fillMaxWidth().clip(RoundedCornerShape(4.dp))) {
                                                    Box(modifier = Modifier.weight(1f).fillMaxHeight().background(Color(palette.color1)))
                                                    if (palette.color3 != null) Box(modifier = Modifier.weight(1f).fillMaxHeight().background(Color(palette.color3)))
                                                    Box(modifier = Modifier.weight(1f).fillMaxHeight().background(Color(palette.color2)))
                                                }
                                                Row(modifier = Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                                    Text(palette.name, fontSize = 9.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                                    IconButton(onClick = { showDeleteConfirm = true }, modifier = Modifier.size(16.dp)) {
                                                        Icon(Icons.Default.Close, null, modifier = Modifier.size(10.dp), tint = MaterialTheme.colorScheme.error)
                                                    }
                                                }
                                            }
                                        }
                                    }

                                // 2. Default Palettes
                                val palettes = if (filterType == "TRITONE") listOf(
                                    listOf(0xFF000000, 0xFFFFFFFF, 0xFF808080) to "Classic",
                                    listOf(0xFF1A1F2C, 0xFFD8B4FE, 0xFF6D28D9) to "Cyber",
                                    listOf(0xFF2D1B69, 0xFFFB7185, 0xFFBE123C) to "Neon",
                                    listOf(0xFF1E1B4B, 0xFF38BDF8, 0xFF1D4ED8) to "Ocean",
                                    listOf(0xFF431407, 0xFFFCD34D, 0xFFB91C1C) to "Sunset"
                                ) else listOf(
                                    listOf(0xFF000000, 0xFFFFFFFF) to "Classic",
                                    listOf(0xFF1A1F2C, 0xFFD8B4FE) to "Cyber",
                                    listOf(0xFF2D1B69, 0xFFFB7185) to "Neon",
                                    listOf(0xFF1E1B4B, 0xFF38BDF8) to "Ocean",
                                    listOf(0xFF431407, 0xFFFCD34D) to "Sunset"
                                )

                                items(palettes) { (colors, name) ->
                                    val isSelected = filterColor1 == colors[0].toInt() && filterColor2 == colors[1].toInt() && (filterType == "DUOTONE" || filterColor3 == colors[2].toInt())
                                    Card(
                                        onClick = { 
                                            viewModel.setFilterColor1(colors[0].toInt())
                                            viewModel.setFilterColor2(colors[1].toInt())
                                            if (filterType == "TRITONE") viewModel.setFilterColor3(colors[2].toInt())
                                        },
                                        modifier = Modifier.width(100.dp),
                                        shape = RoundedCornerShape(8.dp),
                                        border = if (isSelected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
                                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                                    ) {
                                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(6.dp)) {
                                            Row(modifier = Modifier.height(20.dp).fillMaxWidth().clip(RoundedCornerShape(4.dp))) {
                                                Box(modifier = Modifier.weight(1f).fillMaxHeight().background(Color(colors[0])))
                                                if (filterType == "TRITONE") Box(modifier = Modifier.weight(1f).fillMaxHeight().background(Color(colors[2])))
                                                Box(modifier = Modifier.weight(1f).fillMaxHeight().background(Color(colors[1])))
                                            }
                                            Text(name, fontSize = 10.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 4.dp))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(32.dp))
                Text("AUTOMATION", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.height(12.dp))
                
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("Schedules", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            var showScheduleEditor by remember { mutableStateOf(false) }
                            IconButton(onClick = { showScheduleEditor = true }) {
                                Icon(Icons.Default.Add, null, tint = MaterialTheme.colorScheme.primary)
                            }
                            if (showScheduleEditor) {
                                ScheduleEditorDialog(
                                    viewModel = viewModel,
                                    presets = presets,
                                    onDismiss = { showScheduleEditor = false },
                                    onSave = { viewModel.addSchedule(it.copy(target = settingsTarget.name)) }
                                )
                            }
                        }
                        
                        if (schedules.isEmpty()) {
                            Text("No schedules set. Automate your settings by time.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
                        } else {
                            schedules.forEach { schedule ->
                                ScheduleItem(
                                    viewModel = viewModel,
                                    schedule = schedule,
                                    presets = presets,
                                    onToggle = { viewModel.toggleSchedule(schedule) },
                                    onDelete = { viewModel.deleteSchedule(schedule) },
                                    onEdit = { updated -> viewModel.updateSchedule(updated) }
                                )
                                if (schedule != schedules.last()) {
                                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                                }
                            }
                        }
                    }
                }

                if (showCooldownDialog) {
                    val historyList by viewModel.historyList.collectAsState()
                    val isLoadingHistory by viewModel.isLoadingHistory.collectAsState()
                    
                    LaunchedEffect(Unit) {
                        viewModel.resetAndLoadHistory()
                    }

                    val selectedUris = remember { mutableStateListOf<String>() }
                    var previewIndex by remember { mutableStateOf<Int?>(null) }
                    
                    AlertDialog(
                        onDismissRequest = { 
                            if (selectedUris.isNotEmpty()) selectedUris.clear()
                            else showCooldownDialog = false 
                        },
                        title = { 
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("History ($historyCount)") 
                                    Text(
                                        if (selectedUris.isEmpty()) "Click to preview • Long press to mark" 
                                        else "${selectedUris.size} selected", 
                                        style = MaterialTheme.typography.labelSmall, 
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                if (selectedUris.isNotEmpty()) {
                                    IconButton(onClick = { 
                                        viewModel.removeMultipleFromHistory(selectedUris.toList())
                                        selectedUris.clear()
                                        viewModel.resetAndLoadHistory() // Refresh after deletion
                                    }) {
                                        Icon(Icons.Default.Delete, "Release Selected", tint = MaterialTheme.colorScheme.error)
                                    }
                                }
                            }
                        },
                        text = {
                            if (historyList.isEmpty() && !isLoadingHistory) {
                                Text("No images in history yet.")
                            } else {
                                LazyVerticalGrid(
                                    columns = GridCells.Fixed(3),
                                    modifier = Modifier.heightIn(max = 450.dp),
                                    contentPadding = PaddingValues(4.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    itemsIndexed(historyList) { index, uri ->
                                        // Load more trigger
                                        if (index >= historyList.size - 10) {
                                            SideEffect {
                                                viewModel.loadMoreHistory()
                                            }
                                        }

                                        val isSelected = selectedUris.contains(uri)
                                        Box(
                                            modifier = Modifier
                                                .aspectRatio(0.7f)
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                                .combinedClickable(
                                                    onClick = { 
                                                        if (selectedUris.isNotEmpty()) {
                                                            if (isSelected) selectedUris.remove(uri) else selectedUris.add(uri)
                                                        } else {
                                                            previewIndex = index
                                                        }
                                                    },
                                                    onLongClick = {
                                                        if (!isSelected) selectedUris.add(uri)
                                                    }
                                                )
                                        ) {
                                            AsyncImage(
                                                model = Uri.parse(uri),
                                                contentDescription = null,
                                                modifier = Modifier.fillMaxSize().alpha(if (isSelected) 0.6f else 1f),
                                                contentScale = ContentScale.Crop
                                            )
                                            if (isSelected) {
                                                Box(
                                                    modifier = Modifier
                                                        .fillMaxSize()
                                                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
                                                }
                                            }
                                        }
                                    }
                                    
                                    if (isLoadingHistory) {
                                        item(span = { GridItemSpan(3) }) {
                                            Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
                                                CircularProgressIndicator(Modifier.size(24.dp))
                                            }
                                        }
                                    }
                                }
                            }
                        },
                        confirmButton = { 
                            TextButton(onClick = { 
                                if (selectedUris.isNotEmpty()) selectedUris.clear() 
                                else showCooldownDialog = false 
                            }) { 
                                Text(if (selectedUris.isNotEmpty()) "Cancel" else "Close") 
                            } 
                        }
                    )
                    
                    if (previewIndex != null) {
                        val hasMoreHistory = viewModel.hasMoreHistory()
                        val pagerState = rememberPagerState(initialPage = previewIndex!!) { 
                            if (hasMoreHistory) historyList.size + 1 else historyList.size 
                        }
                        var scale by remember { mutableFloatStateOf(1f) }
                        var offset by remember { mutableStateOf(Offset.Zero) }

                        val animatedScale by animateFloatAsState(targetValue = scale, label = "hist_zoom_scale")
                        val animatedOffsetX by animateFloatAsState(targetValue = offset.x, label = "hist_pan_offset_x")
                        val animatedOffsetY by animateFloatAsState(targetValue = offset.y, label = "hist_pan_offset_y")

                        Dialog(
                            onDismissRequest = { previewIndex = null },
                            properties = DialogProperties(usePlatformDefaultWidth = false)
                        ) {
                            Box(modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.9f))
                                .pointerInput(Unit) {
                                    detectTapGestures(
                                        onDoubleTap = {
                                            if (scale > 1.1f) {
                                                scale = 1f
                                                offset = Offset.Zero
                                            } else {
                                                scale = 2.5f
                                            }
                                        }
                                    )
                                }
                                .pointerInput(Unit) {
                                    detectTransformGestures { _, pan, zoom, _ ->
                                        scale = (scale * zoom).coerceIn(1f, 5f)
                                        if (scale > 1f) {
                                            offset += pan
                                        } else {
                                            offset = Offset.Zero
                                        }
                                    }
                                }
                            ) {
                                HorizontalPager(
                                    state = pagerState,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .graphicsLayer(
                                            scaleX = animatedScale,
                                            scaleY = animatedScale,
                                            translationX = animatedOffsetX,
                                            translationY = animatedOffsetY
                                        ),
                                    pageSpacing = 16.dp,
                                    userScrollEnabled = scale <= 1.05f,
                                    key = { index -> 
                                        if (index < historyList.size) historyList[index] 
                                        else "loading_${historyList.size}" 
                                    }
                                ) { page ->
                                    if (page >= historyList.size) {
                                        // Load more trigger in pager
                                        LaunchedEffect(Unit) {
                                            viewModel.loadMoreHistory()
                                        }
                                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                            CircularProgressIndicator(color = Color.White)
                                        }
                                        return@HorizontalPager
                                    }

                                    // Reset zoom when page changes
                                    LaunchedEffect(page) {
                                        scale = 1f
                                        offset = Offset.Zero
                                    }

                                    val uri = historyList[page]
                                    val currentFavorites by viewModel.favorites.collectAsState(initial = emptyList())
                                    val favorite = currentFavorites.any { it.uriString == uri }
                                    
                                    Column(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .padding(16.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.Center
                                    ) {
                                        AsyncImage(
                                            model = Uri.parse(uri),
                                            contentDescription = null,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .weight(1f)
                                                .clip(RoundedCornerShape(12.dp)),
                                            contentScale = ContentScale.Fit
                                        )
                                        Spacer(Modifier.height(16.dp))
                                        val cleanName = remember(uri) {
                                            val rawName = uri.substringAfterLast("/")
                                            try { android.net.Uri.decode(rawName) } catch (e: Exception) { rawName }
                                        }
                                        Text(
                                            text = cleanName, 
                                            color = Color.White,
                                            fontWeight = FontWeight.Bold, 
                                            textAlign = TextAlign.Center,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Spacer(Modifier.height(16.dp))
                                        
                                        // Paging indicator
                                        Text(
                                            text = "Item ${page + 1} of $historyCount",
                                            color = Color.White.copy(alpha = 0.6f),
                                            style = MaterialTheme.typography.labelSmall
                                        )
                                        Spacer(Modifier.height(8.dp))

                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .navigationBarsPadding()
                                                .padding(bottom = 32.dp),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            IconButton(
                                                onClick = { 
                                                    val displayName = uri.substringAfterLast("/")
                                                    val folderUri = uri.substringBeforeLast("/")
                                                    viewModel.toggleFavorite(WallpaperImg(uri, folderUri, displayName, favorite))
                                                },
                                                modifier = Modifier.background(Color.White.copy(alpha = 0.2f), CircleShape)
                                            ) {
                                                Icon(if (favorite) Icons.Default.Star else Icons.Default.StarOutline, null, tint = if (favorite) Color.Yellow else Color.White)
                                            }
                                            
                                            Button(
                                                onClick = { 
                                                    val displayName = uri.substringAfterLast("/")
                                                    val folderUri = uri.substringBeforeLast("/")
                                                    viewModel.blacklistCurrentUri(uri, folderUri, displayName)
                                                    viewModel.resetAndLoadHistory() // Refresh the centralized list
                                                    if (historyList.isEmpty()) previewIndex = null
                                                },
                                                modifier = Modifier.weight(1f),
                                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                                            ) {
                                                Icon(Icons.Default.Block, null)
                                                Spacer(Modifier.width(8.dp))
                                                Text("Blacklist")
                                            }

                                            Button(
                                                onClick = { 
                                                    viewModel.removeFromHistory(uri)
                                                    viewModel.resetAndLoadHistory() // Refresh the centralized list
                                                    if (historyList.isEmpty()) previewIndex = null
                                                },
                                                modifier = Modifier.weight(1f),
                                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                                            ) {
                                                Text("Release")
                                            }
                                        }
                                    }
                                }
                                
                                // Close button on top
                                IconButton(
                                    onClick = { previewIndex = null },
                                    modifier = Modifier.align(Alignment.TopEnd).padding(16.dp)
                                ) {
                                    Icon(Icons.Default.Close, null, tint = Color.White)
                                }
                            }
                        }
                    }
                }
                
                // --- PRESET MANAGEMENT ---
                Text("PRESET MANAGEMENT", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.height(12.dp))
                
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val scope = rememberCoroutineScope()
                    
                    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
                        uri?.let {
                            scope.launch {
                                val json = viewModel.getPresetsJson()
                                if (json != null) {
                                    try {
                                        context.contentResolver.openOutputStream(it)?.use { output ->
                                            output.write(json.toByteArray())
                                        }
                                        Toast.makeText(context, "Presets exported successfully!", Toast.LENGTH_SHORT).show()
                                    } catch (e: Exception) {
                                        Toast.makeText(context, "Export failed: ${e.message}", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        }
                    }

                    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
                        uri?.let {
                            context.contentResolver.openInputStream(it)?.use { input ->
                                val json = input.bufferedReader().use { r -> r.readText() }
                                viewModel.importPresets(json)
                            }
                        }
                    }

                    OutlinedButton(
                        onClick = { 
                            val targetName = settingsTarget.name.lowercase()
                            exportLauncher.launch("multi_wallpaper_presets_$targetName.json") 
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.Upload, null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Export Presets")
                    }
                    
                    OutlinedButton(
                        onClick = { importLauncher.launch("application/json") },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.Download, null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Import Presets")
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))
                
                // --- FULL BACKUP (Total Sync) ---
                Text("DATA MANAGEMENT (TOTAL BACKUP)", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.height(12.dp))
                
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val scope = rememberCoroutineScope()
                    
                    val fullExportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
                        uri?.let {
                            scope.launch {
                                val json = viewModel.getFullBackupJson()
                                if (json != null) {
                                    try {
                                        context.contentResolver.openOutputStream(it)?.use { output ->
                                            output.write(json.toByteArray())
                                        }
                                        Toast.makeText(context, "Full Backup exported!", Toast.LENGTH_SHORT).show()
                                    } catch (e: Exception) {
                                        Toast.makeText(context, "Export failed: ${e.message}", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        }
                    }

                    val fullImportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
                        uri?.let {
                            context.contentResolver.openInputStream(it)?.use { input ->
                                val json = input.bufferedReader().use { r -> r.readText() }
                                viewModel.importFullBackup(json)
                            }
                        }
                    }

                    Button(
                        onClick = { 
                            val targetName = settingsTarget.name.lowercase()
                            fullExportLauncher.launch("multi_wallpaper_full_backup_$targetName.json") 
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                    ) {
                        Icon(Icons.Default.Backup, null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Backup All")
                    }
                    
                    Button(
                        onClick = { fullImportLauncher.launch("application/json") },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                    ) {
                        Icon(Icons.Default.Restore, null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Restore All")
                    }
                }

                Spacer(modifier = Modifier.height(32.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(modifier = Modifier.height(16.dp))
                Text("ABOUT", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Card(modifier = Modifier.fillMaxWidth().padding(top = 12.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Multi Wallpaper Live", fontWeight = FontWeight.Bold)
                        Text("Version 1.1.0", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        Button(
                            onClick = { viewModel.checkForUpdates() },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp),
                            enabled = !isCheckingUpdate,
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                        ) {
                            if (isCheckingUpdate) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Default.Update, null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Check for Update", fontSize = 12.sp)
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                        var showHistoryDialog by remember { mutableStateOf(false) }
                        Text(
                            "Release History", 
                            color = MaterialTheme.colorScheme.primary, 
                            fontSize = 12.sp, 
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.clickable { showHistoryDialog = true }
                        )
                        
                        if (showHistoryDialog) {
                            AlertDialog(
                                onDismissRequest = { showHistoryDialog = false },
                                title = { Text("Release History") },
                                text = {
                                    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                                        HistoryItem("v1.1.0", "• Blacklist System (Gesture & Gallery)\n• AI Portrait Mode (Background Blur)\n• GitHub Update Sync\n• Focus Smoothing Control")
                                        Spacer(modifier = Modifier.height(12.dp))
                                        HistoryItem("v1.0.1", "• Critical Memory Leak Fix\n• AI 480px Downscaling Optimization\n• Scalable Database Indexing\n• Hardware Canvas Acceleration")
                                    }
                                },
                                confirmButton = {
                                    TextButton(onClick = { showHistoryDialog = false }) { Text("Close") }
                                },
                                shape = RoundedCornerShape(16.dp)
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))
                        Text("Developer: Migi Gustian", style = MaterialTheme.typography.bodyMedium)
                        val context = LocalContext.current
                        Text("GitHub: Genesfi/multiwallpaper", color = MaterialTheme.colorScheme.primary, modifier = Modifier.clickable { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/Genesfi/multiwallpaper"))) })
                    }
                }
                Spacer(modifier = Modifier.height(24.dp))
                Button(
                    onClick = { triggerLiveWallpaperSelection(context, MultiWallpaperHomeService::class.java) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(16.dp)
                ) { 
                    Icon(Icons.Default.Wallpaper, null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Set Home Wallpaper") 
                }
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { triggerLiveWallpaperSelection(context, MultiWallpaperLockService::class.java) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(16.dp)
                ) { 
                    Icon(Icons.Default.Lock, null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Set Lock Wallpaper") 
                }
                Spacer(modifier = Modifier.height(40.dp))
            }
        }

        // --- FULL SCREEN PREVIEW OVERLAY ---
        if (isPreviewActive) {
            Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
                if (previewUri != null) {
                    // 1. BACKGROUND LAYER (Blurred)
                    AsyncImage(
                        model = previewUri,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize().let { 
                            if (Build.VERSION.SDK_INT >= 31 && blurEnabled && blurRadius > 0f) {
                                it.graphicsLayer {
                                    renderEffect = android.graphics.RenderEffect.createBlurEffect(
                                        blurRadius, blurRadius, android.graphics.Shader.TileMode.CLAMP
                                    ).asComposeRenderEffect()
                                }
                            } else it
                        },
                        contentScale = ContentScale.Crop
                    )
                    
                    // 2. FOREGROUND LAYER (Sharp with Masking)
                    if (vignetteModeEnabled || subjectFocusEnabled) {
                        AsyncImage(
                            model = previewUri,
                            contentDescription = null,
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer(alpha = 0.99f) // Required for DST_OUT masking
                                .drawWithContent {
                                    drawContent()
                                    val w = size.width
                                    val h = size.height

                                    drawIntoCanvas { canvas ->
                                        val nativeCanvas = canvas.nativeCanvas
                                        val maskPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
                                        maskPaint.xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_OUT)
                                        
                                        val transparent = android.graphics.Color.TRANSPARENT
                                        val black = android.graphics.Color.BLACK

                                        if (vignetteModeEnabled) {
                                            val edgeW = w * vignetteWidth
                                            val edgeH = h * vignetteWidth
                                            val smoothing = 1.0f - vignetteSharpness
                                            
                                            val colorsArr = intArrayOf(black, transparent)
                                            val stops = floatArrayOf(
                                                (0.6f - (smoothing * 0.55f)).coerceIn(0.01f, 0.59f),
                                                (0.6f + (smoothing * 0.35f)).coerceIn(0.61f, 0.99f)
                                            )
                                            
                                            // Left
                                            maskPaint.shader = android.graphics.LinearGradient(0f, 0f, edgeW, 0f, colorsArr, stops, android.graphics.Shader.TileMode.CLAMP)
                                            nativeCanvas.drawRect(0f, 0f, edgeW, h, maskPaint)
                                            // Right
                                            maskPaint.shader = android.graphics.LinearGradient(w, 0f, w - edgeW, 0f, colorsArr, stops, android.graphics.Shader.TileMode.CLAMP)
                                            nativeCanvas.drawRect(w - edgeW, 0f, w, h, maskPaint)
                                            // Top
                                            maskPaint.shader = android.graphics.LinearGradient(0f, 0f, 0f, edgeH, colorsArr, stops, android.graphics.Shader.TileMode.CLAMP)
                                            nativeCanvas.drawRect(0f, 0f, w, edgeH, maskPaint)
                                            // Bottom
                                            maskPaint.shader = android.graphics.LinearGradient(0f, h, 0f, h - edgeH, colorsArr, stops, android.graphics.Shader.TileMode.CLAMP)
                                            nativeCanvas.drawRect(0f, h - edgeH, w, h, maskPaint)

                                        } else if (subjectFocusEnabled) {
                                            val faceX = w / 2f
                                            val faceY = h / 2f
                                            val diagonal = kotlin.math.sqrt(w * w + h * h) / 2f
                                            val radius = diagonal * (0.5f + subjectFocusSmoothing * 1.5f)
                                            
                                            val smoothing = 1.0f - (vignetteSharpness * 0.9f)
                                            val colors = intArrayOf(transparent, black)
                                            val radialStops = floatArrayOf(
                                                (0.6f - (smoothing * 0.55f)).coerceIn(0.01f, 0.59f),
                                                (0.6f + (smoothing * 0.35f)).coerceIn(0.61f, 0.99f)
                                            )
                                            
                                            maskPaint.shader = android.graphics.RadialGradient(faceX, faceY, radius, colors, radialStops, android.graphics.Shader.TileMode.CLAMP)
                                            nativeCanvas.drawRect(0f, 0f, w, h, maskPaint)
                                        }
                                    }
                                },
                            contentScale = ContentScale.Crop
                        )
                    }

                    // 3. DIMMING LAYER (Shadows)
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val w = size.width
                        val h = size.height
                        val alpha = (dimIntensity * 255).toInt().coerceIn(0, 255)
                        val shadowColor = android.graphics.Color.argb(alpha, 0, 0, 0)
                        val transparent = android.graphics.Color.TRANSPARENT

                        drawIntoCanvas { canvas ->
                            val nativeCanvas = canvas.nativeCanvas
                            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)

                            if (vignetteModeEnabled && dimEnabled) {
                                val edgeW = w * vignetteWidth
                                val edgeH = h * vignetteWidth
                                
                                val smoothing = 1.0f - vignetteSharpness
                                val colorsArr = intArrayOf(shadowColor, transparent)
                                val stops = floatArrayOf(
                                    (0.6f - (smoothing * 0.55f)).coerceIn(0.01f, 0.59f),
                                    (0.6f + (smoothing * 0.35f)).coerceIn(0.61f, 0.99f)
                                )
                                
                                // Left
                                paint.shader = android.graphics.LinearGradient(0f, 0f, edgeW, 0f, colorsArr, stops, android.graphics.Shader.TileMode.CLAMP)
                                nativeCanvas.drawRect(0f, 0f, edgeW, h, paint)
                                // Right
                                paint.shader = android.graphics.LinearGradient(w, 0f, w - edgeW, 0f, colorsArr, stops, android.graphics.Shader.TileMode.CLAMP)
                                nativeCanvas.drawRect(w - edgeW, 0f, w, h, paint)
                                // Top
                                paint.shader = android.graphics.LinearGradient(0f, 0f, 0f, edgeH, colorsArr, stops, android.graphics.Shader.TileMode.CLAMP)
                                nativeCanvas.drawRect(0f, 0f, w, edgeH, paint)
                                // Bottom
                                paint.shader = android.graphics.LinearGradient(0f, h, 0f, h - edgeH, colorsArr, stops, android.graphics.Shader.TileMode.CLAMP)
                                nativeCanvas.drawRect(0f, h - edgeH, w, h, paint)
                            } else if (subjectFocusEnabled && dimEnabled) {
                                val faceX = w / 2f
                                val faceY = h / 2f
                                val diagonal = kotlin.math.sqrt(w * w + h * h) / 2f
                                val radius = diagonal * (0.5f + subjectFocusSmoothing * 1.5f)
                                
                                val smoothing = 1.0f - (vignetteSharpness * 0.9f)
                                val colors = intArrayOf(transparent, shadowColor)
                                val radialStops = floatArrayOf(
                                    (0.6f - (smoothing * 0.55f)).coerceIn(0.01f, 0.59f),
                                    (0.6f + (smoothing * 0.35f)).coerceIn(0.61f, 0.99f)
                                )
                                
                                paint.shader = android.graphics.RadialGradient(faceX, faceY, radius, colors, radialStops, android.graphics.Shader.TileMode.CLAMP)
                                nativeCanvas.drawRect(0f, 0f, w, h, paint)
                            } else if (dimEnabled && !subjectFocusEnabled && !vignetteModeEnabled) {
                                nativeCanvas.drawColor(shadowColor)
                            }
                        }
                    }
                } else {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("No Images Found for Preview\nAdd some folders first", color = Color.White, textAlign = TextAlign.Center)
                    }
                }
                
                // Indicate this is a preview
                Surface(
                    color = Color.Black.copy(alpha = 0.7f),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.align(Alignment.Center).padding(bottom = 120.dp)
                ) {
                    Text(
                        "REAL-TIME PREVIEW",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun SettingRow(title: String, subtitle: String? = null, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(checked, onCheckedChange)
    }
}

@Composable
fun HistoryItem(version: String, details: String) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(version, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, fontSize = 14.sp)
        Text(details, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 18.sp)
    }
}

@Composable
fun ImageDetailDialog(
    images: List<WallpaperImg>,
    initialIndex: Int,
    onDismiss: () -> Unit,
    onToggleFavorite: (WallpaperImg) -> Unit,
    onBlacklist: (WallpaperImg) -> Unit
) {
    val context = LocalContext.current
    var currentIndex by remember { mutableIntStateOf(initialIndex) }
    val img = images.getOrNull(currentIndex) ?: run { onDismiss(); return }

    // Animation states for opening/closing
    var isVisible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { isVisible = true }
    
    val dialogAlpha by animateFloatAsState(if (isVisible) 1f else 0f, tween(300))
    val dialogScale by animateFloatAsState(if (isVisible) 1f else 0.9f, tween(300))

    // Zoom & Pan states
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    
    val animatedScale by animateFloatAsState(targetValue = scale, label = "zoom_scale")
    val animatedOffsetX by animateFloatAsState(targetValue = offset.x, label = "pan_offset_x")
    val animatedOffsetY by animateFloatAsState(targetValue = offset.y, label = "pan_offset_y")
    
    // Smart Preview state
    var isSmartPreview by remember { mutableStateOf(false) }

    // Reset zoom when image changes
    LaunchedEffect(currentIndex) {
        scale = 1f
        offset = Offset.Zero
    }

    Dialog(
        onDismissRequest = { 
            isVisible = false
            // Small delay before actual dismiss to allow animation to play
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ onDismiss() }, 300)
        },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .alpha(dialogAlpha)
                .background(Color.Black)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onDoubleTap = {
                            if (scale > 1.1f) {
                                scale = 1f
                                offset = Offset.Zero
                            } else {
                                scale = 2.5f
                            }
                        }
                    )
                }
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(1f, 5f)
                        if (scale > 1f) {
                            offset += pan
                        } else {
                            offset = Offset.Zero
                        }
                    }
                }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures { change, dragAmount ->
                        // Only allow swipe if not zoomed in
                        if (scale <= 1.05f) {
                            if (dragAmount > 50) {
                                if (currentIndex > 0) currentIndex--
                                change.consume()
                            } else if (dragAmount < -50) {
                                if (currentIndex < images.size - 1) currentIndex++
                                change.consume()
                            }
                        }
                    }
                }
        ) {
            // Main Image View
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = 120.dp)
                    .graphicsLayer {
                        scaleX = dialogScale * animatedScale
                        scaleY = dialogScale * animatedScale
                        translationX = animatedOffsetX
                        translationY = animatedOffsetY
                    },
                contentAlignment = Alignment.Center
            ) {
                AsyncImage(
                    model = Uri.parse(img.uriString),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = if (isSmartPreview) ContentScale.Crop else ContentScale.Fit
                )
            }

            // Navigation Overlays (Arrows) - Only show if not zoomed
            if (scale <= 1.05f) {
                if (currentIndex > 0) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .width(60.dp)
                            .align(Alignment.CenterStart)
                            .clickable { currentIndex-- },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.ChevronLeft,
                            null,
                            tint = Color.White.copy(alpha = 0.5f),
                            modifier = Modifier.size(48.dp)
                        )
                    }
                }
                if (currentIndex < images.size - 1) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .width(60.dp)
                            .align(Alignment.CenterEnd)
                            .clickable { currentIndex++ },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.ChevronRight,
                            null,
                            tint = Color.White.copy(alpha = 0.5f),
                            modifier = Modifier.size(48.dp)
                        )
                    }
                }
            }

            // Top Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 48.dp, start = 20.dp, end = 20.dp)
                    .align(Alignment.TopCenter),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "${currentIndex + 1} / ${images.size}",
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    if (isSmartPreview) {
                        Text(
                            "Wallpaper Preview Mode",
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
                IconButton(
                    onClick = { 
                        isVisible = false
                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ onDismiss() }, 300)
                    },
                    modifier = Modifier.background(Color.White.copy(alpha = 0.1f), CircleShape)
                ) {
                    Icon(Icons.Default.Close, null, tint = Color.White)
                }
            }

            // Bottom Control Panel
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 24.dp)
                    .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(28.dp))
                    .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(28.dp))
                    .padding(16.dp)
                    .navigationBarsPadding(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = img.displayName,
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Smart Preview Toggle
                    IconButton(
                        onClick = { isSmartPreview = !isSmartPreview },
                        modifier = Modifier
                            .size(48.dp)
                            .background(
                                if (isSmartPreview) MaterialTheme.colorScheme.primary 
                                else Color.White.copy(alpha = 0.1f), 
                                RoundedCornerShape(12.dp)
                            )
                    ) {
                        Icon(
                            if (isSmartPreview) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                            null,
                            modifier = Modifier.size(20.dp),
                            tint = if (isSmartPreview) Color.White else Color.White.copy(alpha = 0.7f)
                        )
                    }

                    Button(
                        onClick = { onToggleFavorite(img) },
                        modifier = Modifier.weight(1f).height(48.dp),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (img.isFavorite) Color(0xFFEAB308) else MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                        )
                    ) {
                        Icon(
                            if (img.isFavorite) Icons.Filled.Star else Icons.Outlined.StarBorder, 
                            null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = if (img.isFavorite) "Starred" else "Star", 
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                    
                    Button(
                        onClick = { saveImageToGallery(context, Uri.parse(img.uriString), img.displayName) },
                        modifier = Modifier.weight(1f).height(48.dp),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.White.copy(alpha = 0.1f)
                        )
                    ) {
                        Icon(
                            Icons.Default.Download, 
                            null, 
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "Export", 
                            fontWeight = FontWeight.Bold, 
                            color = Color.White,
                            fontSize = 12.sp,
                            maxLines = 1,
                            softWrap = false
                        )
                    }

                    // Blacklist Button
                    IconButton(
                        onClick = { onBlacklist(img) },
                        modifier = Modifier
                            .size(48.dp)
                            .background(MaterialTheme.colorScheme.error.copy(alpha = 0.2f), RoundedCornerShape(12.dp))
                    ) {
                        Icon(
                            Icons.Default.Block,
                            null,
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
                
                TextButton(
                    onClick = { 
                        isVisible = false
                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ onDismiss() }, 300)
                    },
                    modifier = Modifier.padding(top = 8.dp)
                ) {
                    Text("CLOSE", color = Color.White.copy(alpha = 0.5f), letterSpacing = 1.sp, fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
fun ManualFocalEditorDialog(viewModel: HomeViewModel, onDismiss: () -> Unit) {
    val favorites by viewModel.favorites.collectAsState(initial = emptyList())
    val scannedImages by viewModel.scannedImages.collectAsState()
    val manualFocalX by viewModel.manualFocalX.collectAsState()
    val manualFocalY by viewModel.manualFocalY.collectAsState()
    
    // Choose a preview image: prefer first favorite, then first scanned image, then null
    val previewUri = remember(favorites, scannedImages) {
        favorites.firstOrNull()?.uriString ?: scannedImages.firstOrNull()?.uriString
    }

    var currentX by remember { mutableFloatStateOf(manualFocalX) }
    var currentY by remember { mutableFloatStateOf(manualFocalY) }

    Dialog(
        onDismissRequest = { onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color.Black
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                if (previewUri != null) {
                    AsyncImage(
                        model = Uri.parse(previewUri),
                        contentDescription = null,
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(Unit) {
                                detectTransformGestures { _, pan, _, _ ->
                                    val newX = (currentX + pan.x / size.width).coerceIn(0f, 1f)
                                    val newY = (currentY + pan.y / size.height).coerceIn(0f, 1f)
                                    currentX = newX
                                    currentY = newY
                                }
                            }
                            .pointerInput(Unit) {
                                detectTapGestures { offset ->
                                    currentX = (offset.x / size.width).coerceIn(0f, 1f)
                                    currentY = (offset.y / size.height).coerceIn(0f, 1f)
                                }
                            },
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Add some wallpapers first to see preview", color = Color.White)
                    }
                }

                // Focal Point Indicator (Red Circle)
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val centerX = currentX * size.width
                    val centerY = currentY * size.height
                    
                    // Outer glow
                    drawCircle(
                        color = Color.White.copy(alpha = 0.5f),
                        radius = 24.dp.toPx(),
                        center = Offset(centerX, centerY)
                    )
                    // Inner point
                    drawCircle(
                        color = Color.Red,
                        radius = 8.dp.toPx(),
                        center = Offset(centerX, centerY)
                    )
                }

                // Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent)))
                        .padding(top = 48.dp, start = 20.dp, end = 20.dp, bottom = 20.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("Manual Focal Fallback", color = Color.White, fontWeight = FontWeight.Bold)
                        Text("Drag or tap to set focus area", color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
                    }
                    IconButton(onClick = onDismiss, modifier = Modifier.background(Color.White.copy(alpha = 0.2f), CircleShape)) {
                        Icon(Icons.Default.Close, null, tint = Color.White)
                    }
                }

                // Bottom Controls
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(24.dp)
                        .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(24.dp))
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        "Position: ${(currentX * 100).roundToInt()}% X, ${(currentY * 100).roundToInt()}% Y",
                        color = Color.White,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(
                            onClick = { 
                                currentX = 0.5f
                                currentY = 0.4f
                            },
                            modifier = Modifier.weight(1f),
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.3f))
                        ) {
                            Text("Reset", color = Color.White)
                        }
                        Button(
                            onClick = { 
                                viewModel.setManualFocalPoint(currentX, currentY)
                                onDismiss()
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Save Changes")
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ScheduleItem(
    viewModel: HomeViewModel,
    schedule: ScheduleEntity,
    presets: List<PresetEntity>,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
    onEdit: (ScheduleEntity) -> Unit
) {
    var showEditDialog by remember { mutableStateOf(false) }

    var showDeleteConfirm by remember { mutableStateOf(false) }
    
    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete Schedule?") },
            text = { Text("Delete '${schedule.name}' schedule?") },
            confirmButton = {
                Button(onClick = { onDelete(); showDeleteConfirm = false }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") }
            }
        )
    }

    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).clickable { showEditDialog = true }) {
            Text(schedule.name, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
            
            // Format time and days
            val dayNames = listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
            val selected = schedule.selectedDays.split(",").filter { it.isNotEmpty() }.map { it.toInt() }
            val dayText = if (selected.size == 7) "Everyday" 
                          else selected.sorted().joinToString(", ") { dayNames[it - 1] }
            
            Text("${schedule.startTime} - ${schedule.endTime} • $dayText", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            
            val presetName = presets.find { it.id == schedule.presetId }?.name ?: "No Preset"
            var effects = ""
            if (schedule.blurEnabled == true) effects += "Blur (${schedule.blurRadius?.toInt()}px) "
            if (schedule.dimEnabled == true) effects += "Dim (${((schedule.dimIntensity ?: 0f) * 100).toInt()}%) "
            if (schedule.filterType != null && schedule.filterType != "NONE") effects += "Filter (${schedule.filterType}) "
            if (schedule.lightModeEnabled == true) effects += "PowerSaver "
            
            Text("Preset: $presetName", style = MaterialTheme.typography.labelSmall)
            if (effects.isNotEmpty()) {
                Text(effects, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        
        Switch(
            checked = schedule.isEnabled,
            onCheckedChange = { onToggle() },
            modifier = Modifier.graphicsLayer(scaleX = 0.8f, scaleY = 0.8f)
        )
        
        IconButton(onClick = { showDeleteConfirm = true }) {
            Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
        }
    }

    if (showEditDialog) {
        ScheduleEditorDialog(
            viewModel = viewModel,
            schedule = schedule,
            presets = presets,
            onDismiss = { showEditDialog = false },
            onSave = { onEdit(it); showEditDialog = false }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleEditorDialog(
    viewModel: HomeViewModel,
    schedule: ScheduleEntity? = null,
    presets: List<PresetEntity>,
    onDismiss: () -> Unit,
    onSave: (ScheduleEntity) -> Unit
) {
    var name by remember { mutableStateOf(schedule?.name ?: "") }
    var startTime by remember { mutableStateOf(schedule?.startTime ?: "08:00") }
    var endTime by remember { mutableStateOf(schedule?.endTime ?: "17:00") }
    var presetId by remember { mutableStateOf(schedule?.presetId) }
    var blurEnabled by remember { mutableStateOf(schedule?.blurEnabled ?: false) }
    var blurRadius by remember { mutableFloatStateOf(schedule?.blurRadius ?: 20f) }
    var dimEnabled by remember { mutableStateOf(schedule?.dimEnabled ?: false) }
    var dimIntensity by remember { mutableFloatStateOf(schedule?.dimIntensity ?: 0.3f) }
    var lightModeEnabled by remember { mutableStateOf(schedule?.lightModeEnabled ?: false) }
    
    var filterType by remember { mutableStateOf(schedule?.filterType ?: "NONE") }
    var filterColor1 by remember { mutableIntStateOf(schedule?.filterColor1 ?: 0xFF000000.toInt()) }
    var filterColor2 by remember { mutableIntStateOf(schedule?.filterColor2 ?: 0xFFFFFFFF.toInt()) }
    var filterColor3 by remember { mutableIntStateOf(schedule?.filterColor3 ?: 0xFF808080.toInt()) }

    // Fetch custom palettes for the picker
    val customPalettes by viewModel.customPalettes.collectAsState(initial = emptyList())

    val dayNames = listOf("S", "M", "T", "W", "T", "F", "S")
    var selectedDays by remember { 
        mutableStateOf(schedule?.selectedDays?.split(",")?.filter { it.isNotEmpty() }?.map { it.toInt() }?.toSet() ?: setOf(1,2,3,4,5,6,7)) 
    }

    var showStartPicker by remember { mutableStateOf(false) }
    var showEndPicker by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (schedule == null) "New Schedule" else "Edit Schedule") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // DAY PICKER UI
                Text("Repeat", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    dayNames.forEachIndexed { index, name ->
                        val dayNum = index + 1
                        val isSelected = selectedDays.contains(dayNum)
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
                                .clickable {
                                    val newSet = selectedDays.toMutableSet()
                                    if (isSelected) {
                                        if (newSet.size > 1) newSet.remove(dayNum)
                                    } else {
                                        newSet.add(dayNum)
                                    }
                                    selectedDays = newSet
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(name, color = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(12.dp))
                
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TimePickerButton(
                        label = "Start",
                        time = startTime,
                        onClick = { showStartPicker = true },
                        modifier = Modifier.weight(1f)
                    )
                    TimePickerButton(
                        label = "End",
                        time = endTime,
                        onClick = { showEndPicker = true },
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))
                Text("Select Preset", style = MaterialTheme.typography.labelMedium)
                
                var expanded by remember { mutableStateOf(false) }
                Box {
                    val currentPreset = presets.find { it.id == presetId }?.name ?: "None"
                    OutlinedButton(
                        onClick = { expanded = true },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(currentPreset)
                    }
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        DropdownMenuItem(text = { Text("None") }, onClick = { presetId = null; expanded = false })
                        presets.forEach { p ->
                            DropdownMenuItem(text = { Text(p.name) }, onClick = { presetId = p.id; expanded = false })
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                SettingRow(title = "Enable Blur Override", checked = blurEnabled, onCheckedChange = { blurEnabled = it })
                if (blurEnabled) {
                    Slider(value = blurRadius, onValueChange = { blurRadius = it }, valueRange = 0f..100f)
                }

                SettingRow(title = "Enable Dim Override", checked = dimEnabled, onCheckedChange = { dimEnabled = it })
                if (dimEnabled) {
                    Slider(value = dimIntensity, onValueChange = { dimIntensity = it }, valueRange = 0f..1f)
                }

                SettingRow(title = "Power Saver Mode", checked = lightModeEnabled, onCheckedChange = { lightModeEnabled = it })

                Spacer(modifier = Modifier.height(16.dp))
                Text("Color Filter Override", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                
                Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf("NONE" to "Off", "GRAYSCALE" to "B&W", "DUOTONE" to "Duo", "TRITONE" to "Tri").forEach { (type, label) ->
                        FilterChip(
                            selected = filterType == type,
                            onClick = { filterType = type },
                            label = { Text(label, fontSize = 10.sp) },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp)
                        )
                    }
                }

                if (filterType == "DUOTONE" || filterType == "TRITONE") {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("Select Colors", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    
                    var showS1 by remember { mutableStateOf(false) }
                    var showS2 by remember { mutableStateOf(false) }
                    var showS3 by remember { mutableStateOf(false) }

                    if (showS1) ColorPickerDialog(initialColor = Color(filterColor1), onDismiss = { showS1 = false }, onColorSelected = { filterColor1 = it.toArgb(); showS1 = false })
                    if (showS2) ColorPickerDialog(initialColor = Color(filterColor2), onDismiss = { showS2 = false }, onColorSelected = { filterColor2 = it.toArgb(); showS2 = false })
                    if (showS3) ColorPickerDialog(initialColor = Color(filterColor3), onDismiss = { showS3 = false }, onColorSelected = { filterColor3 = it.toArgb(); showS3 = false })

                    Row(modifier = Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(modifier = Modifier.size(32.dp).clip(CircleShape).background(Color(filterColor1)).border(1.dp, Color.White.copy(alpha = 0.3f), CircleShape).clickable { showS1 = true })
                        if (filterType == "TRITONE") {
                            Box(modifier = Modifier.size(32.dp).clip(CircleShape).background(Color(filterColor3)).border(1.dp, Color.White.copy(alpha = 0.3f), CircleShape).clickable { showS3 = true })
                        }
                        Box(modifier = Modifier.size(32.dp).clip(CircleShape).background(Color(filterColor2)).border(1.dp, Color.White.copy(alpha = 0.3f), CircleShape).clickable { showS2 = true })
                        
                        Spacer(modifier = Modifier.weight(1f))
                        
                        // Mini Palettes inside Schedule
                        TextButton(onClick = { 
                            // Quick Cyberpunk Palette
                            filterColor1 = 0xFF1A1F2C.toInt()
                            filterColor2 = 0xFFD8B4FE.toInt()
                            filterColor3 = 0xFF6D28D9.toInt()
                        }) {
                            Text("Cyber", fontSize = 10.sp)
                        }
                    }

                    if (customPalettes.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Your Palettes", style = MaterialTheme.typography.labelSmall)
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                            items(customPalettes) { p ->
                                Box(
                                    modifier = Modifier
                                        .size(32.dp, 20.dp)
                                        .clip(RoundedCornerShape(4.dp))
                                        .clickable {
                                            filterColor1 = p.color1
                                            filterColor2 = p.color2
                                            p.color3?.let { filterColor3 = it }
                                        }
                                ) {
                                    Row(modifier = Modifier.fillMaxSize()) {
                                        Box(modifier = Modifier.weight(1f).fillMaxHeight().background(Color(p.color1)))
                                        if (p.color3 != null) Box(modifier = Modifier.weight(1f).fillMaxHeight().background(Color(p.color3)))
                                        Box(modifier = Modifier.weight(1f).fillMaxHeight().background(Color(p.color2)))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                onSave(
                    ScheduleEntity(
                        id = schedule?.id ?: 0,
                        name = name.ifBlank { "Schedule" },
                        startTime = startTime,
                        endTime = endTime,
                        presetId = presetId,
                        blurEnabled = blurEnabled,
                        blurRadius = blurRadius,
                        dimEnabled = dimEnabled,
                        dimIntensity = dimIntensity,
                        lightModeEnabled = lightModeEnabled,
                        filterType = if (filterType == "NONE") null else filterType,
                        filterColor1 = if (filterType == "NONE") null else filterColor1,
                        filterColor2 = if (filterType == "NONE") null else filterColor2,
                        filterColor3 = if (filterType == "TRITONE") filterColor3 else null,
                        selectedDays = selectedDays.sorted().joinToString(","),
                        target = schedule?.target ?: "HOME"
                    )
                )
                onDismiss() // Automatically close dialog after saving
            }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )

    if (showStartPicker) {
        WheelTimePickerDialog(
            initialTime = startTime,
            onDismiss = { showStartPicker = false },
            onConfirm = { 
                startTime = it
                showStartPicker = false
            }
        )
    }

    if (showEndPicker) {
        WheelTimePickerDialog(
            initialTime = endTime,
            onDismiss = { showEndPicker = false },
            onConfirm = { 
                endTime = it
                showEndPicker = false
            }
        )
    }
}

@Composable
fun TimePickerButton(
    label: String,
    time: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 4.dp, bottom = 4.dp))
        Surface(
            modifier = Modifier.fillMaxWidth().height(56.dp).clickable { onClick() },
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            color = Color.Transparent
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(time, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
fun WheelTimePickerDialog(
    initialTime: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    val initialHour = initialTime.substringBefore(":").toIntOrNull() ?: 8
    val initialMinute = initialTime.substringAfter(":").toIntOrNull() ?: 0

    var selectedHour by remember { mutableIntStateOf(initialHour) }
    var selectedMinute by remember { mutableIntStateOf(initialMinute) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select Time", textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) },
        text = {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                WheelPicker(
                    count = 24,
                    initialIndex = initialHour,
                    onIndexSelected = { selectedHour = it },
                    label = "H",
                    modifier = Modifier.weight(1f)
                )
                Text(":", style = MaterialTheme.typography.headlineLarge, modifier = Modifier.padding(horizontal = 8.dp))
                WheelPicker(
                    count = 60,
                    initialIndex = initialMinute,
                    onIndexSelected = { selectedMinute = it },
                    label = "M",
                    modifier = Modifier.weight(1f)
                )
            }
        },
        confirmButton = {
            Button(onClick = { 
                val timeStr = String.format(Locale.getDefault(), "%02d:%02d", selectedHour, selectedMinute)
                onConfirm(timeStr) 
            }) { Text("Confirm") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
fun WheelPicker(
    count: Int,
    initialIndex: Int,
    onIndexSelected: (Int) -> Unit,
    label: String,
    modifier: Modifier = Modifier
) {
    val itemHeight = 50.dp // Fixed height for absolute predictability
    val visibleItems = 3   // Reduced to 3 for better focus and easier math
    
    // We want the target item to be exactly in the center (index 1 of 3)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (initialIndex + count * 100) - 1)
    
    // Force snapping to the middle of the container
    LaunchedEffect(listState.isScrollInProgress) {
        if (!listState.isScrollInProgress) {
            val layoutInfo = listState.layoutInfo
            // Since all items have exact fixed height, we don't need viewport math
            val firstVisibleIndex = listState.firstVisibleItemIndex
            val firstVisibleOffset = listState.firstVisibleItemScrollOffset
            
            // If the first item is more than half hidden, snap to the next one
            val finalIndex = if (firstVisibleOffset > 50) firstVisibleIndex + 1 else firstVisibleIndex
            
            listState.animateScrollToItem(finalIndex, 0)
            // The selected index is always "firstVisible + 1" to be in the center bar
            onIndexSelected((finalIndex + 1) % count)
        }
    }

    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(8.dp))
        Box(
            modifier = Modifier
                .height(itemHeight * visibleItems)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            // Highlight background - Fixed at exactly the middle item position
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(itemHeight),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f))
            ) {}
            
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                flingBehavior = ScrollableDefaults.flingBehavior()
            ) {
                items(Int.MAX_VALUE) { index ->
                    val actualIndex = index % count
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(itemHeight),
                        contentAlignment = Alignment.Center
                    ) {
                        val isSelected = remember {
                            derivedStateOf {
                                // In a 3-item view, the selected one is at index 'firstVisible + 1'
                                index == listState.firstVisibleItemIndex + 1
                            }
                        }
                        
                        Text(
                            text = String.format(Locale.getDefault(), "%02d", actualIndex),
                            style = if (isSelected.value) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.titleMedium,
                            color = if (isSelected.value) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                            fontWeight = if (isSelected.value) FontWeight.ExtraBold else FontWeight.Normal
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ColorPickerButton(
    color: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
            .clickable { onClick() },
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        color = Color.Transparent
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(color)
                    .border(1.dp, Color.White.copy(alpha = 0.2f), CircleShape)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = String.format("#%06X", (0xFFFFFF and color.toArgb())),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            Icon(Icons.Default.ColorLens, null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
fun ColorPickerDialog(
    initialColor: Color,
    onDismiss: () -> Unit,
    onColorSelected: (Color) -> Unit
) {
    val hsv = remember { 
        val arr = FloatArray(3)
        android.graphics.Color.colorToHSV(initialColor.toArgb(), arr)
        arr
    }
    
    var hue by remember { mutableFloatStateOf(hsv[0]) }
    var saturation by remember { mutableFloatStateOf(hsv[1]) }
    var value by remember { mutableFloatStateOf(hsv[2]) }

    val currentColor = remember(hue, saturation, value) {
        Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, saturation, value)))
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        confirmButton = {
            Button(onClick = { onColorSelected(currentColor) }, modifier = Modifier.padding(end = 16.dp, bottom = 16.dp)) { Text("Select") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.padding(bottom = 16.dp)) { Text("Cancel") }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("Pick Color", fontWeight = FontWeight.ExtraBold, fontSize = 18.sp, color = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.height(16.dp))

                // 1. Photoshop-style Saturation/Value Box
                Box(
                    modifier = Modifier
                        .size(240.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .pointerInput(hue) {
                            detectTapGestures { offset ->
                                saturation = (offset.x / size.width).coerceIn(0f, 1f)
                                value = (1f - (offset.y / size.height)).coerceIn(0f, 1f)
                            }
                        }
                        .pointerInput(hue) {
                            detectTransformGestures { _, pan, _, _ ->
                                saturation = (saturation + pan.x / size.width).coerceIn(0f, 1f)
                                value = (value - pan.y / size.height).coerceIn(0f, 1f)
                            }
                        }
                ) {
                    // Base color (Hue)
                    val baseColor = Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, 1f, 1f)))
                    Box(modifier = Modifier.fillMaxSize().background(baseColor))
                    
                    // Horizontal White Gradient (Saturation)
                    Box(modifier = Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Color.White, Color.Transparent))))
                    
                    // Vertical Black Gradient (Value/Brightness)
                    Box(modifier = Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black))))
                    
                    // Selection Cursor (Photoshop Circle)
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val cursorX = saturation * size.width
                        val cursorY = (1f - value) * size.height
                        drawCircle(
                            color = if (value > 0.5f) Color.Black else Color.White,
                            radius = 8.dp.toPx(),
                            center = Offset(cursorX, cursorY),
                            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx())
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // 2. Hue Bar
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(32.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(
                            Brush.horizontalGradient(
                                listOf(Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red)
                            )
                        )
                        .pointerInput(Unit) {
                            detectTapGestures { offset ->
                                hue = (offset.x / size.width * 360f).coerceIn(0f, 360f)
                            }
                        }
                        .pointerInput(Unit) {
                            detectTransformGestures { _, pan, _, _ ->
                                hue = (hue + (pan.x / size.width * 360f)).coerceIn(0f, 360f)
                            }
                        }
                ) {
                    // Hue Cursor
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val cursorX = (hue / 360f) * size.width
                        drawRect(
                            color = Color.White,
                            topLeft = Offset(cursorX - 2.dp.toPx(), 0f),
                            size = androidx.compose.ui.geometry.Size(4.dp.toPx(), size.height),
                            style = androidx.compose.ui.graphics.drawscope.Fill
                        )
                        drawRect(
                            color = Color.Black,
                            topLeft = Offset(cursorX - 2.dp.toPx(), 0f),
                            size = androidx.compose.ui.geometry.Size(4.dp.toPx(), size.height),
                            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.dp.toPx())
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // 3. Final Result & Hex Input
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(currentColor)
                            .border(2.dp, MaterialTheme.colorScheme.outline, CircleShape)
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    
                    var hexInput by remember(currentColor) { 
                        mutableStateOf(String.format("%06X", (0xFFFFFF and currentColor.toArgb()))) 
                    }
                    
                    OutlinedTextField(
                        value = hexInput,
                        onValueChange = { input ->
                            val clean = input.replace("#", "").uppercase().take(6)
                            hexInput = clean
                            if (clean.length == 6) {
                                try {
                                    val parsed = android.graphics.Color.parseColor("#$clean")
                                    val newHsv = FloatArray(3)
                                    android.graphics.Color.colorToHSV(parsed, newHsv)
                                    hue = newHsv[0]
                                    saturation = newHsv[1]
                                    value = newHsv[2]
                                } catch (e: Exception) {}
                            }
                        },
                        prefix = { Text("#") },
                        label = { Text("HEX Code", fontSize = 10.sp) },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        textStyle = MaterialTheme.typography.bodyLarge.copy(
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                    )
                }
            }
        }
    )
}

private fun triggerLiveWallpaperSelection(context: Context, serviceClass: Class<*> = MultiWallpaperHomeService::class.java) {
    Log.d("MultiWallpaper", "MainActivity trigger selection: ${serviceClass.simpleName}")
    try {
        val intent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).apply { 
            putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, ComponentName(context.packageName, serviceClass.name))
        }
        context.startActivity(intent)
    } catch (e: Exception) { 
        Log.e("MultiWallpaper", "MainActivity selection error", e)
        try { 
            context.startActivity(Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER)) 
        } catch (e2: Exception) {} 
    }
}

private fun saveImageToGallery(context: Context, imageUri: Uri, displayName: String) {
    try {
        val resolver = context.contentResolver
        val name = if (displayName.contains(".")) displayName else "$displayName.jpg"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "MW_$name")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/MultiWallpaper")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }
        val uri = resolver.insert(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) else MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        if (uri != null) {
            resolver.openInputStream(imageUri)?.use { input -> resolver.openOutputStream(uri)?.use { output -> input.copyTo(output) } }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear(); values.put(MediaStore.MediaColumns.IS_PENDING, 0); resolver.update(uri, values, null, null)
            }
            Toast.makeText(context, "Saved!", Toast.LENGTH_SHORT).show()
        }
    } catch (e: Exception) {}
}
