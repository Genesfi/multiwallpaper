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
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.example.data.FolderEntity
import com.example.ui.HomeViewModel
import com.example.ui.WallpaperImg
import com.example.ui.theme.MyApplicationTheme

import android.provider.Settings
import androidx.activity.result.ActivityResultLauncher
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

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
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager() else true 
        )
    }

    val launcher = rememberLauncherForActivityResult(contract = ActivityResultContracts.StartActivityForResult()) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) hasStoragePermission = Environment.isExternalStorageManager()
    }

    if (!hasStoragePermission && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
                Icon(Icons.Default.Storage, null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
                Text("Permission Required", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Text("Aplikasi butuh izin akses semua file untuk scanning folder.", textAlign = TextAlign.Center, fontSize = 14.sp)
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
    
    val folders by viewModel.folders.collectAsState()
    val favorites by viewModel.favorites.collectAsState()
    val scannedImages by viewModel.scannedImages.collectAsState()
    val isScanning by viewModel.isScanning.collectAsState()
    val selectedFolderIds by viewModel.selectedFolderIds.collectAsState()
    val selectedGalleryUris by viewModel.selectedGalleryUris.collectAsState()
    
    val context = LocalContext.current

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Multi Wallpaper", fontWeight = FontWeight.SemiBold, fontSize = 20.sp) },
                navigationIcon = {
                    if (currentTab == NavigationTab.FOLDERS && selectedFolderIds.isNotEmpty()) {
                        IconButton(onClick = { viewModel.clearFolderIdSelection() }) { Icon(Icons.Default.Close, null) }
                    } else if (currentTab == NavigationTab.GALLERY && selectedGalleryUris.isNotEmpty()) {
                        IconButton(onClick = { viewModel.clearGallerySelection() }) { Icon(Icons.Default.Close, null) }
                    } else {
                        IconButton(onClick = { triggerLiveWallpaperSelection(context) }) { Icon(Icons.Default.Wallpaper, null, tint = MaterialTheme.colorScheme.primary) }
                    }
                },
                actions = {
                    if (currentTab == NavigationTab.FOLDERS && selectedFolderIds.isNotEmpty()) {
                        IconButton(onClick = { viewModel.deleteSelectedFolders() }) { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) }
                    } else if (currentTab == NavigationTab.GALLERY && selectedGalleryUris.isNotEmpty()) {
                        IconButton(onClick = { viewModel.addSelectedToFavorites() }) { Icon(Icons.Default.Star, null, tint = Color(0xFFEAB308)) }
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
                        onClick = { currentTab = tab; viewModel.clearFolderIdSelection(); viewModel.clearGallerySelection() },
                        icon = { Icon(icon, null) },
                        label = { Text(tab.name.lowercase().replaceFirstChar { it.uppercase() }, fontSize = 11.sp) }
                    )
                }
            }
        },
        floatingActionButton = {
            if (currentTab == NavigationTab.FOLDERS) {
                FloatingActionButton(onClick = { viewModel.refreshCurrentPath(); showMultiSelectDialog = true }, containerColor = MaterialTheme.colorScheme.primary) {
                    Icon(Icons.Default.Checklist, null)
                }
            }
        }
    ) { paddingValues ->
        Box(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
            AnimatedContent(targetState = currentTab, label = "Tab") { tab ->
                when (tab) {
                    NavigationTab.FOLDERS -> FolderScreen(folders, { viewModel.deleteFolder(it) }, { viewModel.clearAllFolders() }, { viewModel.scanFolders() }, isScanning, selectedFolderIds, { viewModel.toggleFolderIdSelection(it) })
                    NavigationTab.GALLERY -> GalleryScreen(scannedImages, isScanning, selectedGalleryUris, { viewModel.toggleFavorite(it) }, { viewModel.toggleGalleryUriSelection(it) }, { viewModel.toggleFavoriteFolder(it) })
                    NavigationTab.FAVORITES -> FavoritesScreen(favorites) { viewModel.toggleFavorite(WallpaperImg(it.uriString, it.folderUriString, it.displayName, true)) }
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
                    IconButton(onClick = { if (!viewModel.navigateBack()) onDismiss() }) { Icon(Icons.Default.ArrowBack, null) }
                    Text("Select Folders", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    TextButton(onClick = { viewModel.toggleSelectAll() }) { Text(if (isAllSelected) "Deselect All" else "Select All") }
                }
                Surface(color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f), shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Text(text = currentPath.absolutePath.replace("/storage/emulated/0", "Internal"), modifier = Modifier.padding(8.dp), style = MaterialTheme.typography.labelSmall)
                }
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(items) { item ->
                        val isSelected = selected.contains(item.uri)
                        Row(modifier = Modifier.fillMaxWidth().clickable { viewModel.navigateTo(java.io.File(item.uri.path ?: "")) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = isSelected, onCheckedChange = { viewModel.toggleFolderSelection(item.uri) })
                            Icon(if (isSelected) Icons.Filled.Folder else Icons.Outlined.Folder, null)
                            Text(item.name, modifier = Modifier.weight(1f).padding(start = 8.dp))
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
fun FolderScreen(folders: List<FolderEntity>, onDeleteFolder: (FolderEntity) -> Unit, onClearAll: () -> Unit, onScan: () -> Unit, isScanning: Boolean, selectedIds: Set<Int>, onToggleSelect: (Int) -> Unit) {
    val grouped = remember(folders) { folders.groupBy { val uri = Uri.parse(it.uriString); if (uri.scheme == "file") java.io.File(uri.path ?: "").parentFile?.name ?: "Root" else "SAF Root" } }
    val expanded = remember { mutableStateMapOf<String, Boolean>() }
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("SOURCES (${folders.size})", fontWeight = FontWeight.Bold, fontSize = 11.sp)
            Row {
                if (folders.isNotEmpty()) TextButton(onClick = onClearAll) { Text("Clear All", color = MaterialTheme.colorScheme.error) }
                if (isScanning) CircularProgressIndicator(modifier = Modifier.size(16.dp)) else if (folders.isNotEmpty()) TextButton(onClick = onScan) { Text("Re-Scan") }
            }
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            grouped.forEach { (parent, parentFolders) ->
                val isExp = expanded[parent] ?: true
                item {
                    Card(onClick = { expanded[parent] = !isExp }, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))) {
                        Row(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                            Icon(Icons.Default.Source, null, tint = MaterialTheme.colorScheme.primary)
                            Text(parent, modifier = Modifier.weight(1f).padding(horizontal = 12.dp), fontWeight = FontWeight.Bold)
                            Icon(if (isExp) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
                        }
                    }
                }
                if (isExp) {
                    items(parentFolders) { f ->
                        val sel = selectedIds.contains(f.id)
                        Card(modifier = Modifier.padding(start = 16.dp).combinedClickable(onClick = { if (selectedIds.isNotEmpty()) onToggleSelect(f.id) }, onLongClick = { onToggleSelect(f.id) }), colors = CardDefaults.cardColors(containerColor = if (sel) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)) {
                            Row(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                if (selectedIds.isNotEmpty()) Checkbox(sel, { onToggleSelect(f.id) }) else Icon(Icons.Default.Folder, null)
                                Text(f.displayName, modifier = Modifier.weight(1f).padding(horizontal = 12.dp))
                                if (selectedIds.isEmpty()) IconButton(onClick = { onDeleteFolder(f) }) { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GalleryScreen(images: List<WallpaperImg>, isScanning: Boolean, selectedUris: Set<String>, onToggleFavorite: (WallpaperImg) -> Unit, onToggleSelect: (String) -> Unit, onFavoriteFolder: (String) -> Unit) {
    var selectedImg by remember { mutableStateOf<WallpaperImg?>(null) }
    val grouped = remember(images) { images.groupBy { it.folderUriString } }
    val expanded = remember { mutableStateMapOf<String, Boolean>() }
    Column(modifier = Modifier.fillMaxSize()) {
        if (isScanning) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            grouped.forEach { (uri, imgs) ->
                val isExp = expanded[uri] ?: false
                val anyFav = imgs.any { it.isFavorite }
                item {
                    val name = remember(uri) { val u = Uri.parse(uri); if (u.scheme == "file") java.io.File(u.path ?: "").name else Uri.decode(uri).split("/").lastOrNull() ?: "Folder" }
                    Card(onClick = { expanded[uri] = !isExp }, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                        Row(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(if (isExp) Icons.Default.FolderOpen else Icons.Default.Folder, null, tint = MaterialTheme.colorScheme.primary)
                            Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                Text(name, fontWeight = FontWeight.Bold, maxLines = 1)
                                Text("${imgs.size} images", style = MaterialTheme.typography.labelSmall)
                            }
                            IconButton(onClick = { onFavoriteFolder(uri) }) { Icon(Icons.Default.Star, null, tint = if (anyFav) Color(0xFFEAB308) else MaterialTheme.colorScheme.outline) }
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
                                Box(modifier = Modifier.weight(1f).aspectRatio(0.85f).clip(RoundedCornerShape(12.dp)).background(if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant).combinedClickable(onClick = { if (selectedUris.isNotEmpty()) onToggleSelect(img.uriString) else selectedImg = img }, onLongClick = { onToggleSelect(img.uriString) })) {
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
    if (selectedImg != null) ImageDetailDialog(selectedImg!!, { selectedImg = null }, { onToggleFavorite(it) })
}

@Composable
fun FavoritesScreen(favorites: List<com.example.data.FavoriteImageEntity>, onRemoveFavorite: (com.example.data.FavoriteImageEntity) -> Unit) {
    var selectedImg by remember { mutableStateOf<com.example.data.FavoriteImageEntity?>(null) }
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("FAVORITES (${favorites.size})", fontWeight = FontWeight.Bold, fontSize = 12.sp)
        if (favorites.isEmpty()) Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No Favorites yet") }
        else LazyVerticalGrid(columns = GridCells.Adaptive(100.dp), modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(favorites) { f ->
                Box(modifier = Modifier.aspectRatio(0.85f).clip(RoundedCornerShape(16.dp)).clickable { selectedImg = f }) {
                    AsyncImage(model = Uri.parse(f.uriString), contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    IconButton(onClick = { onRemoveFavorite(f) }, modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).background(MaterialTheme.colorScheme.surface.copy(alpha = 0.7f), RoundedCornerShape(8.dp)).size(32.dp)) { Icon(Icons.Default.Star, null, tint = Color(0xFFEAB308), modifier = Modifier.size(18.dp)) }
                }
            }
        }
    }
    if (selectedImg != null) ImageDetailDialog(WallpaperImg(selectedImg!!.uriString, selectedImg!!.folderUriString, selectedImg!!.displayName, true), { selectedImg = null }, { onRemoveFavorite(selectedImg!!) })
}

@Composable
fun SettingsScreen(viewModel: HomeViewModel, onSetWallpaperClick: () -> Unit) {
    val interval by viewModel.intervalSeconds.collectAsState()
    val transition by viewModel.transitionType.collectAsState()
    val useFav by viewModel.useFavoritesOnly.collectAsState()
    Column(modifier = Modifier.fillMaxSize().padding(20.dp).verticalScroll(rememberScrollState())) {
        Text("SETTINGS", fontWeight = FontWeight.Bold, fontSize = 12.sp)
        Spacer(modifier = Modifier.height(16.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Use Favorites Only", fontWeight = FontWeight.Bold)
            Switch(useFav, { viewModel.setUseFavoritesOnly(it) })
        }
        Spacer(modifier = Modifier.height(24.dp))
        Text("Rotation Interval: ${formatInterval(interval)}", fontWeight = FontWeight.Bold)
        Slider(value = interval, onValueChange = { viewModel.setIntervalSeconds(it) }, valueRange = 10f..86400f)
        Spacer(modifier = Modifier.height(24.dp))
        Text("Transition Effect", fontWeight = FontWeight.Bold)
        Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { viewModel.setTransitionType("slide") }, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = if (transition == "slide") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)) { Text("Slide") }
            Button(onClick = { viewModel.setTransitionType("fade") }, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = if (transition == "fade") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)) { Text("Fade") }
        }
        Spacer(modifier = Modifier.height(32.dp))
        Divider()
        Spacer(modifier = Modifier.height(16.dp))
        Text("ABOUT", fontWeight = FontWeight.Bold, fontSize = 12.sp)
        Card(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Multi Wallpaper Live Changer", fontWeight = FontWeight.Bold)
                Text("Version 1.0.0", fontSize = 12.sp)
                Spacer(modifier = Modifier.height(8.dp))
                Text("Developer: Migi Gustian", style = MaterialTheme.typography.bodyMedium)
                val context = LocalContext.current
                Text("GitHub: Genesfi/multiwallpaper", color = MaterialTheme.colorScheme.primary, modifier = Modifier.clickable { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/Genesfi/multiwallpaper"))) })
            }
        }
        Spacer(modifier = Modifier.height(24.dp))
        Button(onClick = onSetWallpaperClick, modifier = Modifier.fillMaxWidth()) { Text("Activate Live Wallpaper") }
    }
}

private fun formatInterval(sec: Float): String {
    val s = sec.toInt()
    return when {
        s < 60 -> "Every $s Seconds"
        s < 3600 -> "Every ${s/60} Minutes"
        else -> "Every ${s/3600} Hours"
    }
}

@Composable
fun ImageDetailDialog(img: WallpaperImg, onDismiss: () -> Unit, onToggleFavorite: (WallpaperImg) -> Unit) {
    val context = LocalContext.current
    Dialog(onDismissRequest = onDismiss) {
        Card(shape = RoundedCornerShape(24.dp)) {
            Column(modifier = Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(img.displayName, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                AsyncImage(model = Uri.parse(img.uriString), contentDescription = null, modifier = Modifier.fillMaxWidth().height(300.dp).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceVariant), contentScale = ContentScale.Fit)
                Row(modifier = Modifier.fillMaxWidth().padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = { onToggleFavorite(img) }, modifier = Modifier.weight(1f)) { Icon(if (img.isFavorite) Icons.Filled.Star else Icons.Outlined.StarBorder, null); Text(if (img.isFavorite) "Starred" else "Star") }
                    Button(onClick = { saveImageToGallery(context, Uri.parse(img.uriString), img.displayName) }, modifier = Modifier.weight(1f)) { Icon(Icons.Default.SaveAlt, null); Text("Export") }
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
