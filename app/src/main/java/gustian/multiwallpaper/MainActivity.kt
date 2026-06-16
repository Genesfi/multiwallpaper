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
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import gustian.multiwallpaper.data.FolderEntity
import gustian.multiwallpaper.data.PresetEntity
import kotlin.math.roundToInt
import gustian.multiwallpaper.ui.HomeViewModel
import gustian.multiwallpaper.ui.WallpaperImg
import gustian.multiwallpaper.ui.theme.MyApplicationTheme

import android.provider.Settings

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
                        IconButton(onClick = { triggerLiveWallpaperSelection(context) }) { Icon(Icons.Default.Wallpaper, null, tint = MaterialTheme.colorScheme.primary) }
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
                    NavigationTab.SETTINGS -> SettingsScreen(viewModel) { triggerLiveWallpaperSelection(context) }
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
                                     ) {
                            CircularProgressIndicator()
                            Spacer(modifier = Modifier.height(16.dp))
                            Text("Switching Preset...", fontWeight = FontWeight.Medium)
                            Text("Updating wallpaper collection", style = MaterialTheme.typography.labelSmall)
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
                    Text(text = currentPath.absolutePath.replace("/storage/emulated/0", "Internal"), modifier = Modifier.padding(8.dp), style = MaterialTheme.typography.labelSmall)
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
    val folders by viewModel.folders.collectAsState()
    val isScanning by viewModel.isScanning.collectAsState()
    val selectedIds by viewModel.selectedFolderIds.collectAsState()
    val scannedImages by viewModel.scannedImages.collectAsState()
    val presets by viewModel.presets.collectAsState()
    val activePresetName by viewModel.activePresetName.collectAsState()

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
        onAddClick = null
    )
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
    onDeletePreset: (PresetEntity) -> Unit = {}
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
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text("SOURCES (${folders.size})", fontWeight = FontWeight.Bold, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (activePresetName != null) {
                    Text("Active: $activePresetName", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                } else if (presets.isNotEmpty()) {
                    Text("${presets.size} Presets available", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.clickable { showPresetDialog = true })
                }
            }
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
                Spacer(modifier = Modifier.width(4.dp))
                if (folders.isNotEmpty()) {
                    TextButton(
                        onClick = onClearAllFolders,
                        contentPadding = PaddingValues(horizontal = 8.dp),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Text("Clear All", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                    }
                }
                if (isScanning) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp).align(Alignment.CenterVertically))
                } else if (folders.isNotEmpty()) {
                    TextButton(
                        onClick = onScan,
                        contentPadding = PaddingValues(horizontal = 8.dp),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Text("Re-Scan", fontSize = 12.sp)
                    }
                }
                onAddClick?.let {
                    IconButton(onClick = it, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Add, contentDescription = "Add Folder", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                    }
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
                onLoadPreset = onLoadPreset,
                onDeletePreset = onDeletePreset,
                onClearAllFolders = onClearAllFolders,
                onDismiss = { showPresetDialog = false }
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
                                Text(f.displayName, modifier = Modifier.weight(1f).padding(horizontal = 12.dp), style = MaterialTheme.typography.bodyMedium)
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
    val presets by viewModel.presets.collectAsState()
    PresetManagerDialog(
        presets = presets,
        onLoadPreset = { viewModel.loadPreset(it) },
        onDeletePreset = { viewModel.deletePreset(it) },
        onClearAllFolders = { viewModel.clearAllFolders() },
        onDismiss = onDismiss
    )
}

@Composable
fun PresetManagerDialog(
    presets: List<PresetEntity>,
    onLoadPreset: (PresetEntity) -> Unit,
    onDeletePreset: (PresetEntity) -> Unit,
    onClearAllFolders: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
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
        },
        text = {
            if (presets.isEmpty()) {
                Box(modifier = Modifier.fillMaxWidth().height(100.dp), contentAlignment = Alignment.Center) {
                    Text("No presets saved yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(presets) { preset ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                onLoadPreset(preset)
                                onDismiss()
                            }
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
                                }
                                Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                    Text(preset.name, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                                    Text("${preset.folderUris.size} folders", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                IconButton(onClick = { onDeletePreset(preset) }) {
                                    Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
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
        when (sortType) {
            "NAME" -> groups.entries.sortedBy { entry -> 
                val uri = Uri.parse(entry.key)
                if (uri.scheme == "file") java.io.File(uri.path ?: "").name else Uri.decode(entry.key).split("/").lastOrNull() ?: "Folder"
            }
            "DATE" -> groups.entries.toList() // Scan order is basically date added
            "STAR" -> groups.entries.sortedByDescending { it.value.any { img -> img.isFavorite } }
            else -> groups.entries.toList()
        }.associate { it.key to it.value }
    }
    
    val expanded = remember { mutableStateMapOf<String, Boolean>() }
    var showBlacklistSection by remember { mutableStateOf(false) }
    
    Column(modifier = Modifier.fillMaxSize()) {
        if (isScanning) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())

        // Header for toggling Blacklist view
        if (blacklisted.isNotEmpty()) {
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Blacklisted Images (${blacklisted.size})", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary, modifier = Modifier.weight(1f))
                TextButton(onClick = { showBlacklistSection = !showBlacklistSection }) {
                    Text(if (showBlacklistSection) "Hide" else "Show")
                }
            }
            if (showBlacklistSection) {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.height(100.dp)) {
                    items(blacklisted, key = { it.uriString }) { item ->
                        Box(modifier = Modifier.width(80.dp).fillMaxHeight().clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.errorContainer)) {
                            AsyncImage(model = Uri.parse(item.uriString), contentDescription = null, modifier = Modifier.fillMaxSize().alpha(0.6f), contentScale = ContentScale.Crop)
                            IconButton(onClick = { viewModel.restoreBlacklistedImage(item) }, modifier = Modifier.align(Alignment.Center)) {
                                Icon(Icons.Default.RestoreFromTrash, null, tint = MaterialTheme.colorScheme.onErrorContainer)
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            }
        }

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
    val favorites by viewModel.favorites.collectAsState()
    var selectedFavIndex by remember { mutableIntStateOf(-1) }
    
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("FAVORITES (${favorites.size})", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (favorites.isEmpty()) Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No Favorites yet", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        else LazyVerticalGrid(columns = GridCells.Adaptive(100.dp), modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            itemsIndexed(favorites) { index, f ->
                Box(modifier = Modifier.aspectRatio(0.85f).clip(RoundedCornerShape(16.dp)).clickable { selectedFavIndex = index }) {
                    AsyncImage(model = Uri.parse(f.uriString), contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    IconButton(onClick = { viewModel.toggleFavorite(WallpaperImg(f.uriString, f.folderUriString, f.displayName, true)) }, modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).background(MaterialTheme.colorScheme.surface.copy(alpha = 0.7f), RoundedCornerShape(8.dp)).size(32.dp)) { Icon(Icons.Default.Star, null, tint = Color(0xFFEAB308), modifier = Modifier.size(18.dp)) }
                }
            }
        }
    }
    
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
}

@Composable
fun SettingsScreen(viewModel: HomeViewModel, onSetWallpaperClick: () -> Unit) {
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
    val aiAdvancedEnabled by viewModel.aiAdvancedEnabled.collectAsState()
    val aiZoomSlack by viewModel.aiZoomSlack.collectAsState()
    val aiSensitivityX by viewModel.aiSensitivityX.collectAsState()
    val aiSensitivityY by viewModel.aiSensitivityY.collectAsState()
    val blurRadius by viewModel.blurRadius.collectAsState()
    val dimIntensity by viewModel.dimIntensity.collectAsState()
    val blurEnabled by viewModel.blurEnabled.collectAsState()
    val dimEnabled by viewModel.dimEnabled.collectAsState()
    val subjectFocusEnabled by viewModel.subjectFocusEnabled.collectAsState()
    val subjectFocusSmoothing by viewModel.subjectFocusSmoothing.collectAsState()
    val latestVersionInfo by viewModel.latestVersionInfo.collectAsState()
    val isCheckingUpdate by viewModel.isCheckingUpdate.collectAsState()
    val updateMessage by viewModel.updateMessage.collectAsState()
    
    var unit by remember { mutableStateOf(if (totalSeconds < 60) "Sec" else if (totalSeconds < 3600) "Min" else "Hour") }
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
    Column(modifier = Modifier.fillMaxSize().padding(20.dp).verticalScroll(scrollState)) {
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
                    title = "Enable Dimming",
                    subtitle = if (subjectFocusEnabled) "Vignette spotlight on face" else "Full screen darkening",
                    checked = dimEnabled,
                    onCheckedChange = { viewModel.setDimEnabled(it) }
                )
                
                if (dimEnabled) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Dim Intensity", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    Slider(
                        value = dimIntensity,
                        onValueChange = { viewModel.setDimIntensity(it) },
                        valueRange = 0f..1.0f
                    )
                    Text("${(dimIntensity * 100).toInt()}% Intensity", style = MaterialTheme.typography.labelSmall, modifier = Modifier.align(Alignment.End))
                }

                if (android.os.Build.VERSION.SDK_INT >= 31) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    SettingRow(
                        title = "Enable Blur",
                        subtitle = if (subjectFocusEnabled) "Portrait bokeh (sharp face)" else "Full screen blur",
                        checked = blurEnabled,
                        onCheckedChange = { viewModel.setBlurEnabled(it) }
                    )
                    
                    if (blurEnabled) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Blur Intensity", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                        Slider(
                            value = blurRadius,
                            onValueChange = { viewModel.setBlurRadius(it) },
                            valueRange = 0f..100f
                        )
                        Text("${blurRadius.toInt()}px Radius", style = MaterialTheme.typography.labelSmall, modifier = Modifier.align(Alignment.End))
                    }
                }

                if (subjectFocusEnabled) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    Text("Focus Smoothing", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    Slider(
                        value = subjectFocusSmoothing,
                        onValueChange = { viewModel.setSubjectFocusSmoothing(it) },
                        valueRange = 0.1f..0.9f
                    )
                    Text("${(subjectFocusSmoothing * 100).toInt()}% Softness", style = MaterialTheme.typography.labelSmall, modifier = Modifier.align(Alignment.End))
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
                    Text("2 Finger Tap on Home Screen to remove current wallpaper from rotation.", style = MaterialTheme.typography.labelSmall)
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
        
        Spacer(modifier = Modifier.height(32.dp))
        Text("DATA MANAGEMENT", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(modifier = Modifier.height(12.dp))
        
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val context = LocalContext.current
            val launcher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
                uri?.let {
                    context.contentResolver.openInputStream(it)?.use { input ->
                        val json = input.bufferedReader().use { r -> r.readText() }
                        viewModel.importPresets(json)
                    }
                }
            }

            OutlinedButton(
                onClick = { viewModel.exportPresets() },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.Upload, null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Export")
            }
            
            OutlinedButton(
                onClick = { launcher.launch("application/json") },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.Download, null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Import")
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
        Button(onClick = onSetWallpaperClick, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), contentPadding = PaddingValues(16.dp)) { 
            Icon(Icons.Default.Wallpaper, null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Set Live Wallpaper") 
        }
        Spacer(modifier = Modifier.height(40.dp))
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
                        scaleX = dialogScale * scale
                        scaleY = dialogScale * scale
                        translationX = offset.x
                        translationY = offset.y
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

private fun triggerLiveWallpaperSelection(context: Context) {
    try {
        val intent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).apply { putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, ComponentName(context, MultiWallpaperLiveService::class.java)) }
        context.startActivity(intent)
    } catch (e: Exception) { try { context.startActivity(Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER)) } catch (e2: Exception) {} }
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

