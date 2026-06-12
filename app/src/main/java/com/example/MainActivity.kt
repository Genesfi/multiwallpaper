package com.example

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
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.grid.*
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.example.data.FolderEntity
import kotlin.math.roundToInt
import com.example.ui.HomeViewModel
import com.example.ui.WallpaperImg
import com.example.ui.theme.MyApplicationTheme

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
                    } else {
                        IconButton(onClick = { triggerLiveWallpaperSelection(context) }) { Icon(Icons.Default.Wallpaper, null, tint = MaterialTheme.colorScheme.primary) }
                    }
                },
                actions = {
                    if (currentTab == NavigationTab.FOLDERS && selectedFolderIds.isNotEmpty()) {
                        IconButton(onClick = { viewModel.deleteSelectedFolders() }) { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) }
                    } else if (currentTab == NavigationTab.GALLERY && selectedGalleryUris.isNotEmpty()) {
                        IconButton(onClick = { viewModel.addSelectedToFavorites() }) { Icon(Icons.Default.Star, null, tint = Color(0xFFEAB308)) }
                    } else if (currentTab == NavigationTab.GALLERY && selectedGalleryFolderUris.isNotEmpty()) {
                        IconButton(onClick = { viewModel.toggleFavoriteSelectedFolders() }) { Icon(Icons.Default.Star, null, tint = Color(0xFFEAB308)) }
                    } else if (currentTab == NavigationTab.GALLERY) {
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
                        IconButton(onClick = { viewModel.scanFolders() }) { Icon(Icons.Default.Refresh, null, tint = MaterialTheme.colorScheme.primary) }
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
                if (presets.isNotEmpty()) {
                    Text("${presets.size} Presets available", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.clickable { showPresetDialog = true })
                }
            }
            Row {
                IconButton(onClick = { showSavePresetDialog = true }, enabled = folders.isNotEmpty()) {
                    Icon(Icons.Default.Save, contentDescription = "Save Preset", tint = MaterialTheme.colorScheme.primary)
                }
                IconButton(onClick = { showPresetDialog = true }) {
                    Icon(Icons.Default.CollectionsBookmark, contentDescription = "Presets")
                }
                if (folders.isNotEmpty()) TextButton(onClick = { viewModel.clearAllFolders() }) { Text("Clear All", color = MaterialTheme.colorScheme.error) }
                if (isScanning) CircularProgressIndicator(modifier = Modifier.size(16.dp).align(Alignment.CenterVertically))
                else if (folders.isNotEmpty()) TextButton(onClick = { viewModel.scanFolders() }) { Text("Re-Scan") }
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
                            viewModel.saveCurrentAsPreset(presetName)
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
            PresetManagerDialog(viewModel) { showPresetDialog = false }
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
                            scannedImages.firstOrNull { it.folderUriString == f.uriString }?.uriString?.let { Uri.parse(it) }
                        }
                        
                        Card(modifier = Modifier.padding(start = 16.dp).combinedClickable(onClick = { if (selectedIds.isNotEmpty()) viewModel.toggleFolderIdSelection(f.id) }, onLongClick = { viewModel.toggleFolderIdSelection(f.id) }), colors = CardDefaults.cardColors(containerColor = if (sel) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)) {
                            Row(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                if (selectedIds.isNotEmpty()) Checkbox(sel, { viewModel.toggleFolderIdSelection(f.id) }) 
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
                                if (selectedIds.isEmpty()) IconButton(onClick = { viewModel.deleteFolder(f) }) { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) }
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
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Presets") },
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
                                viewModel.loadPreset(preset)
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
                                IconButton(onClick = { viewModel.deletePreset(preset) }) {
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
    val isScanning by viewModel.isScanning.collectAsState()
    val selectedUris by viewModel.selectedGalleryUris.collectAsState()
    val selectedFolderUris by viewModel.selectedGalleryFolderUris.collectAsState()
    val sortType by viewModel.gallerySortType.collectAsState()

    var selectedImg by remember { mutableStateOf<WallpaperImg?>(null) }
    
    val grouped = remember(images, sortType) {
        val groups = images.groupBy { it.folderUriString }
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
    Column(modifier = Modifier.fillMaxSize()) {
        if (isScanning) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            grouped.forEach { (uri, imgs) ->
                val isExp = expanded[uri] ?: false
                val anyFav = imgs.any { it.isFavorite }
                val isSelected = selectedFolderUris.contains(uri)
                
                item {
                    val name = remember(uri) { val u = Uri.parse(uri); if (u.scheme == "file") java.io.File(u.path ?: "").name else Uri.decode(uri).split("/").lastOrNull() ?: "Folder" }
                    Card(
                        onClick = { if (selectedFolderUris.isNotEmpty()) viewModel.toggleGalleryFolderSelection(uri) else expanded[uri] = !isExp },
                        modifier = Modifier.combinedClickable(
                            onClick = { if (selectedFolderUris.isNotEmpty()) viewModel.toggleGalleryFolderSelection(uri) else expanded[uri] = !isExp },
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
                                Box(modifier = Modifier.weight(1f).aspectRatio(0.85f).clip(RoundedCornerShape(12.dp)).background(if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant).combinedClickable(onClick = { if (selectedUris.isNotEmpty()) viewModel.toggleGalleryUriSelection(img.uriString) else selectedImg = img }, onLongClick = { viewModel.toggleGalleryUriSelection(img.uriString) })) {
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
    if (selectedImg != null) ImageDetailDialog(selectedImg!!, { selectedImg = null }, { viewModel.toggleFavorite(it) })
}

@Composable
fun FavoritesScreen(viewModel: HomeViewModel) {
    val favorites by viewModel.favorites.collectAsState()
    var selectedImg by remember { mutableStateOf<com.example.data.FavoriteImageEntity?>(null) }
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("FAVORITES (${favorites.size})", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (favorites.isEmpty()) Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No Favorites yet", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        else LazyVerticalGrid(columns = GridCells.Adaptive(100.dp), modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(favorites) { f ->
                Box(modifier = Modifier.aspectRatio(0.85f).clip(RoundedCornerShape(16.dp)).clickable { selectedImg = f }) {
                    AsyncImage(model = Uri.parse(f.uriString), contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    IconButton(onClick = { viewModel.toggleFavorite(WallpaperImg(f.uriString, f.folderUriString, f.displayName, true)) }, modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).background(MaterialTheme.colorScheme.surface.copy(alpha = 0.7f), RoundedCornerShape(8.dp)).size(32.dp)) { Icon(Icons.Default.Star, null, tint = Color(0xFFEAB308), modifier = Modifier.size(18.dp)) }
                }
            }
        }
    }
    if (selectedImg != null) ImageDetailDialog(WallpaperImg(selectedImg!!.uriString, selectedImg!!.folderUriString, selectedImg!!.displayName, true), { selectedImg = null }, { viewModel.toggleFavorite(it) })
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
        Text("SETTINGS", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(modifier = Modifier.height(16.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Use Favorites Only", fontWeight = FontWeight.Bold)
            Switch(useFav, { viewModel.setUseFavoritesOnly(it) })
        }
        Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Double Tap to Change", fontWeight = FontWeight.Bold)
            Switch(doubleTap, { viewModel.setDoubleTapEnabled(it) })
        }
        Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("React to Motion", fontWeight = FontWeight.Bold)
            Switch(parallaxEnabled, { viewModel.setParallaxEnabled(it) })
        }
        
        if (parallaxEnabled) {
            Spacer(modifier = Modifier.height(16.dp))
            Text("Motion Strength", fontWeight = FontWeight.Bold)
            Slider(
                value = parallaxStrength,
                onValueChange = { viewModel.setParallaxStrength(it) },
                valueRange = 0.1f..1f,
                steps = 8
            )
        }
        
        Spacer(modifier = Modifier.height(24.dp))
        Text("Rotation Interval", fontWeight = FontWeight.Bold)
        
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
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
                modifier = Modifier.width(110.dp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                shape = RoundedCornerShape(12.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            var expanded by remember { mutableStateOf(false) }
            Box {
                Button(onClick = { expanded = true }, shape = RoundedCornerShape(12.dp)) { 
                    Text(unit)
                    Icon(Icons.Default.ArrowDropDown, null)
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
        
        Spacer(modifier = Modifier.height(24.dp))
        Text("Transition Effect", fontWeight = FontWeight.Bold)
        Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { viewModel.setTransitionType("slide") }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp), colors = ButtonDefaults.buttonColors(containerColor = if (transition == "slide") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)) { Text("Slide") }
            Button(onClick = { viewModel.setTransitionType("fade") }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp), colors = ButtonDefaults.buttonColors(containerColor = if (transition == "fade") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)) { Text("Fade") }
        }
        
        if (transition == "fade") {
            Spacer(modifier = Modifier.height(16.dp))
            Text("Fade Speed", fontWeight = FontWeight.Bold)
            Slider(
                value = fadeSpeed.toFloat(),
                onValueChange = { viewModel.setFadeSpeed(it.roundToInt()) },
                valueRange = 5f..50f,
                steps = 9
            )
            Text(
                text = when {
                    fadeSpeed < 15 -> "Slow"
                    fadeSpeed < 35 -> "Normal"
                    else -> "Fast"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        
        Spacer(modifier = Modifier.height(32.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(modifier = Modifier.height(16.dp))
        Text("ABOUT", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Card(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Multi Wallpaper Live Changer", fontWeight = FontWeight.Bold)
                Text("Version 1.0.0", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.height(8.dp))
                Text("Developer: Migi Gustian", style = MaterialTheme.typography.bodyMedium)
                val context = LocalContext.current
                Text("GitHub: Genesfi/multiwallpaper", color = MaterialTheme.colorScheme.primary, modifier = Modifier.clickable { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/Genesfi/multiwallpaper"))) })
            }
        }
        Spacer(modifier = Modifier.height(24.dp))
        Button(onClick = onSetWallpaperClick, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) { Text("Activate Live Wallpaper") }
    }
}

@Composable
fun ImageDetailDialog(img: WallpaperImg, onDismiss: () -> Unit, onToggleFavorite: (WallpaperImg) -> Unit) {
    val context = LocalContext.current
    Dialog(onDismissRequest = onDismiss) {
        Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(modifier = Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(img.displayName, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(modifier = Modifier.height(12.dp))
                AsyncImage(model = Uri.parse(img.uriString), contentDescription = null, modifier = Modifier.fillMaxWidth().height(300.dp).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceVariant), contentScale = ContentScale.Fit)
                Row(modifier = Modifier.fillMaxWidth().padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = { onToggleFavorite(img) }, modifier = Modifier.weight(1f)) { Icon(if (img.isFavorite) Icons.Filled.Star else Icons.Outlined.StarBorder, null); Spacer(Modifier.width(4.dp)); Text(if (img.isFavorite) "Starred" else "Star") }
                    Button(onClick = { saveImageToGallery(context, Uri.parse(img.uriString), img.displayName) }, modifier = Modifier.weight(1f)) { Icon(Icons.Default.SaveAlt, null); Spacer(Modifier.width(4.dp)); Text("Export") }
                }
                TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Close") }
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
