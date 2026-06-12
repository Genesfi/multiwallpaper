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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Environment.isExternalStorageManager()
            } else {
                true // Handled by standard permissions
            }
        )
    }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            hasStoragePermission = Environment.isExternalStorageManager()
        }
    }

    if (!hasStoragePermission && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
                Icon(Icons.Default.Storage, contentDescription = null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.height(16.dp))
                Text("Izin Akses File Diperlukan", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Text(
                    "Agar bisa membaca folder secara massal dan tembus sub-folder, aplikasi butuh izin akses semua file.",
                    textAlign = TextAlign.Center,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(24.dp))
                Button(onClick = {
                    val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    launcher.launch(intent)
                }) {
                    Text("Berikan Izin Sekarang")
                }
            }
        }
    } else {
        content()
    }
}

enum class NavigationTab {
    FOLDERS, GALLERY, FAVORITES, SETTINGS
}

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
    
    val context = LocalContext.current

    // Document tree launcher (Single select fallback)
    val folderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            viewModel.addFolders(listOf(uri))
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(top = 8.dp)
                    ) {
                        Text(
                            "Multi Wallpaper",
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 20.sp,
                            letterSpacing = (-0.5).sp
                        )
                        Text(
                            "System Active",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                },
                navigationIcon = {
                    if (selectedFolderIds.isNotEmpty() && currentTab == NavigationTab.FOLDERS) {
                        IconButton(onClick = { viewModel.clearFolderIdSelection() }) {
                            Icon(Icons.Default.Close, contentDescription = "Cancel Selection")
                        }
                    } else {
                        IconButton(onClick = { triggerLiveWallpaperSelection(context) }) {
                            Icon(
                                imageVector = Icons.Default.Wallpaper,
                                contentDescription = "Apply Wallpaper",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(26.dp)
                            )
                        }
                    }
                },
                actions = {
                    if (selectedFolderIds.isNotEmpty() && currentTab == NavigationTab.FOLDERS) {
                        IconButton(onClick = { viewModel.deleteSelectedFolders() }) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete Selected", tint = MaterialTheme.colorScheme.error)
                        }
                    } else {
                        IconButton(
                            modifier = Modifier.testTag("refresh_action"),
                            onClick = { viewModel.scanFolders() }
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Sync Folders",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = 0.dp
            ) {
                NavigationBarItem(
                    selected = currentTab == NavigationTab.FOLDERS,
                    onClick = { currentTab = NavigationTab.FOLDERS },
                    icon = {
                        Icon(
                            imageVector = if (currentTab == NavigationTab.FOLDERS) Icons.Filled.FolderOpen else Icons.Outlined.Folder,
                            contentDescription = "Folder Saya"
                        )
                    },
                    label = { Text("Directory", fontSize = 11.sp, fontWeight = FontWeight.SemiBold) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        selectedTextColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                )

                NavigationBarItem(
                    selected = currentTab == NavigationTab.GALLERY,
                    onClick = { currentTab = NavigationTab.GALLERY },
                    icon = {
                        Icon(
                            imageVector = if (currentTab == NavigationTab.GALLERY) Icons.Filled.Image else Icons.Outlined.Image,
                            contentDescription = "Galeri Lokal"
                        )
                    },
                    label = { Text("Gallery", fontSize = 11.sp, fontWeight = FontWeight.SemiBold) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        selectedTextColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                )

                NavigationBarItem(
                    selected = currentTab == NavigationTab.FAVORITES,
                    onClick = { currentTab = NavigationTab.FAVORITES },
                    icon = {
                        Icon(
                            imageVector = if (currentTab == NavigationTab.FAVORITES) Icons.Filled.Star else Icons.Outlined.StarBorder,
                            contentDescription = "Favorit"
                        )
                    },
                    label = { Text("Favorites", fontSize = 11.sp, fontWeight = FontWeight.SemiBold) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        selectedTextColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                )

                NavigationBarItem(
                    selected = currentTab == NavigationTab.SETTINGS,
                    onClick = { currentTab = NavigationTab.SETTINGS },
                    icon = {
                        Icon(
                            imageVector = if (currentTab == NavigationTab.SETTINGS) Icons.Filled.Settings else Icons.Outlined.Settings,
                            contentDescription = "Pengaturan"
                        )
                    },
                    label = { Text("Settings", fontSize = 11.sp, fontWeight = FontWeight.SemiBold) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        selectedTextColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                )
            }
        },
        floatingActionButton = {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // Main Setup FAB
                SmallFloatingActionButton(
                    onClick = { triggerLiveWallpaperSelection(context) },
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.AutoFixHigh, contentDescription = "Quick Setup")
                }

                if (currentTab == NavigationTab.FOLDERS) {
                    // Combine folder actions into a cleaner UI
                    FloatingActionButton(
                        modifier = Modifier.testTag("add_folder_fab"),
                        onClick = { 
                            viewModel.refreshCurrentPath()
                            showMultiSelectDialog = true 
                        },
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                        shape = RoundedCornerShape(16.dp),
                        elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 2.dp)
                    ) {
                        Row(modifier = Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(imageVector = Icons.Default.Checklist, contentDescription = "Mark Folders")
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Mark Folders", fontWeight = FontWeight.Bold)
                        }
                    }
                    
                    SmallFloatingActionButton(
                        onClick = { folderLauncher.launch(null) },
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.CreateNewFolder, contentDescription = "Add Single Folder")
                    }
                }
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(MaterialTheme.colorScheme.background)
        ) {
            AnimatedContent(
                targetState = currentTab,
                transitionSpec = {
                    fadeIn() togetherWith fadeOut()
                },
                label = "TabTransition"
            ) { tab ->
                when (tab) {
                    NavigationTab.FOLDERS -> FolderScreen(
                        folders = folders,
                        onDeleteFolder = { viewModel.deleteFolder(it) },
                        onClearAll = { viewModel.clearAllFolders() },
                        onScan = { viewModel.scanFolders() },
                        isScanning = isScanning,
                        onAddClick = { folderLauncher.launch(null) },
                        selectedIds = selectedFolderIds,
                        onToggleSelect = { viewModel.toggleFolderIdSelection(it) }
                    )
                    NavigationTab.GALLERY -> GalleryScreen(
                        images = scannedImages,
                        isScanning = isScanning,
                        onToggleFavorite = { viewModel.toggleFavorite(it) }
                    )
                    NavigationTab.FAVORITES -> FavoritesScreen(
                        favorites = favorites,
                        onRemoveFavorite = { fav ->
                            // Map Favorite to WallpaperImg to utilize same model if cached or just wrap
                            viewModel.toggleFavorite(
                                WallpaperImg(
                                    uriString = fav.uriString,
                                    folderUriString = fav.folderUriString,
                                    displayName = fav.displayName,
                                    isFavorite = true
                                )
                            )
                        }
                    )
                    NavigationTab.SETTINGS -> SettingsScreen(
                        viewModel = viewModel,
                        onSetWallpaperClick = { triggerLiveWallpaperSelection(context) }
                    )
                }
            }

            if (showMultiSelectDialog) {
                MultiFolderSelectDialog(
                    viewModel = viewModel,
                    onDismiss = { showMultiSelectDialog = false }
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
        Card(
            modifier = Modifier.fillMaxWidth().height(600.dp),
            shape = RoundedCornerShape(24.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    IconButton(onClick = { if (!viewModel.navigateBack()) onDismiss() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                    Text(
                        "Pilih Folder",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { viewModel.toggleSelectAll() }) {
                        Text(
                            text = if (isAllSelected) "Batal Semua" else "Pilih Semua",
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                ) {
                    Text(
                        text = currentPath.absolutePath.replace("/storage/emulated/0", "Internal Storage"),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
                
                Divider(modifier = Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
                
                if (items.isEmpty()) {
                    Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.FolderOff, contentDescription = null, tint = Color.LightGray, modifier = Modifier.size(48.dp))
                            Text("Tidak ada sub-folder", color = Color.Gray, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                } else {
                    LazyColumn(modifier = Modifier.weight(1f)) {
                        items(items) { item ->
                            val isSelected = selected.contains(item.uri)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { viewModel.navigateTo(java.io.File(item.uri.path ?: "")) }
                                    .padding(vertical = 4.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = isSelected,
                                    onCheckedChange = { viewModel.toggleFolderSelection(item.uri) }
                                )
                                Icon(
                                    imageVector = if (isSelected) Icons.Filled.Folder else Icons.Outlined.Folder,
                                    contentDescription = null,
                                    tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(28.dp)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    item.name,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                )
                                Icon(
                                    Icons.Default.ChevronRight,
                                    contentDescription = "Open",
                                    tint = MaterialTheme.colorScheme.outline,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }

                Divider(modifier = Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "${selected.size} folder dipilih",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                    Row {
                        TextButton(onClick = onDismiss) { Text("Batal") }
                        Button(
                            modifier = Modifier.padding(start = 8.dp),
                            enabled = selected.isNotEmpty(),
                            shape = RoundedCornerShape(12.dp),
                            onClick = {
                                viewModel.confirmMultiSelect()
                                onDismiss()
                            }
                        ) {
                            Text("Tambah")
                        }
                    }
                }
            }
        }
    }
}

private fun triggerLiveWallpaperSelection(context: Context) {
    try {
        val intent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).apply {
            putExtra(
                WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                ComponentName(context, MultiWallpaperLiveService::class.java)
            )
        }
        context.startActivity(intent)
    } catch (e: Exception) {
        try {
            // Fallback for some systems
            val intent = Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER)
            context.startActivity(intent)
        } catch (e2: Exception) {
            Toast.makeText(context, "Gagal membuka setelan Live Wallpaper: ${e2.message}", Toast.LENGTH_LONG).show()
        }
    }
}

// ---------------------- SCREENS ----------------------

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FolderScreen(
    folders: List<FolderEntity>,
    onDeleteFolder: (FolderEntity) -> Unit,
    onClearAll: () -> Unit,
    onScan: () -> Unit,
    isScanning: Boolean,
    onAddClick: () -> Unit,
    selectedIds: Set<Int>,
    onToggleSelect: (Int) -> Unit
) {
    // Grouping logic: group by parent directory name for better organization
    val groupedFolders = remember(folders) {
        folders.groupBy { 
            val uri = Uri.parse(it.uriString)
            if (uri.scheme == "file") {
                val file = java.io.File(uri.path ?: "")
                file.parentFile?.name ?: "Root"
            } else {
                val decoded = Uri.decode(it.uriString)
                val parts = decoded.split("/")
                if (parts.size > 1) parts[parts.size - 2] else "Root"
            }
        }
    }
    
    val expandedParents = remember { mutableStateMapOf<String, Boolean>() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        Spacer(modifier = Modifier.height(16.dp))
        // Explanatory banner with dynamic colors for Dark Mode
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            shape = RoundedCornerShape(24.dp)
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.AutoAwesomeMotion,
                        contentDescription = "Rotation Status",
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Auto-Rotation Support",
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        fontSize = 14.sp
                    )
                    Text(
                        "Add folders to rotate wallpapers automatically.",
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f),
                        fontSize = 11.sp,
                        lineHeight = 14.sp
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "WALLPAPER SOURCES (${folders.size})",
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                letterSpacing = 0.5.sp
            )
            Row {
                if (folders.isNotEmpty()) {
                    TextButton(
                        onClick = onClearAll,
                        modifier = Modifier.height(32.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp)
                    ) {
                        Text("Clear All", color = MaterialTheme.colorScheme.error, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
                
                if (isScanning) {
                    CircularProgressIndicator(
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp).align(Alignment.CenterVertically)
                    )
                } else if (folders.isNotEmpty()) {
                    TextButton(
                        onClick = onScan,
                        modifier = Modifier.height(32.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp)
                    ) {
                        Icon(Icons.Default.Refresh, null, modifier = Modifier.size(14.dp), MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Re-Scan", color = MaterialTheme.colorScheme.primary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        if (folders.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.clickable { onAddClick() }
                ) {
                    Icon(
                        imageVector = Icons.Default.FolderZip,
                        contentDescription = "Empty",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.size(80.dp)
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    Text(
                        "No Wallpaper Folders Added",
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 16.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "Tap here or use the '+ Add Folder' button to choose folders from local storage.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        fontSize = 12.sp
                    )
                }
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                groupedFolders.forEach { (parentName, parentFolders) ->
                    val isExpanded = expandedParents[parentName] ?: (groupedFolders.size == 1)
                    
                    item(key = parentName) {
                        Card(
                            onClick = { expandedParents[parentName] = !isExpanded },
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Source, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = parentName,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp
                                    )
                                    Text(
                                        text = "${parentFolders.size} active sub-folders",
                                        fontSize = 10.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Icon(
                                    imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                    contentDescription = null
                                )
                            }
                        }
                    }

                    if (isExpanded) {
                        items(parentFolders, key = { it.id }) { folder ->
                            val isSelected = selectedIds.contains(folder.id)
                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
                                ),
                                shape = RoundedCornerShape(16.dp),
                                border = BorderStroke(1.dp, if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 16.dp)
                                    .combinedClickable(
                                        onClick = { if (selectedIds.isNotEmpty()) onToggleSelect(folder.id) },
                                        onLongClick = { onToggleSelect(folder.id) }
                                    ),
                                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    if (selectedIds.isNotEmpty()) {
                                        Checkbox(
                                            checked = isSelected,
                                            onCheckedChange = { onToggleSelect(folder.id) },
                                            modifier = Modifier.size(24.dp)
                                        )
                                        Spacer(modifier = Modifier.width(12.dp))
                                    } else {
                                        Box(
                                            modifier = Modifier.size(36.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(Icons.Default.Folder, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                                        }
                                        Spacer(modifier = Modifier.width(12.dp))
                                    }
                                    
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = folder.displayName,
                                            fontWeight = FontWeight.SemiBold,
                                            fontSize = 13.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        val displayPath = remember(folder.uriString) {
                                            val uri = Uri.parse(folder.uriString)
                                            if (uri.scheme == "file") {
                                                uri.path?.replace("/storage/emulated/0", "Internal") ?: folder.uriString
                                            } else {
                                                Uri.decode(folder.uriString).split(":").lastOrNull() ?: folder.uriString
                                            }
                                        }
                                        Text(
                                            text = displayPath,
                                            fontSize = 10.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    
                                    if (selectedIds.isEmpty()) {
                                        IconButton(onClick = { onDeleteFolder(folder) }) {
                                            Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun GalleryScreen(
    images: List<WallpaperImg>,
    isScanning: Boolean,
    onToggleFavorite: (WallpaperImg) -> Unit
) {
    var selectedImg by remember { mutableStateOf<WallpaperImg?>(null) }
    
    // Group images by folder for better organization
    val groupedImages = remember(images) {
        images.groupBy { it.folderUriString }
    }
    
    val expandedFolders = remember { mutableStateMapOf<String, Boolean>() }

    Column(
        modifier = Modifier
            .fillMaxSize()
    ) {
        if (isScanning) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.primary
            )
        }

        if (images.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.PhotoLibrary,
                        contentDescription = "Empty Images",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.size(64.dp)
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    Text(
                        "No Images Cataloged",
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 15.sp
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                groupedImages.forEach { (folderUri, folderImages) ->
                    val isExpanded = expandedFolders[folderUri] ?: false
                    
                    item(key = folderUri) {
                        val folderName = remember(folderUri) {
                            val uri = Uri.parse(folderUri)
                            if (uri.scheme == "file") {
                                java.io.File(uri.path ?: "").name
                            } else {
                                val decoded = Uri.decode(folderUri)
                                decoded.split("/").lastOrNull() ?: "Folder"
                            }
                        }
                        
                        Card(
                            onClick = { expandedFolders[folderUri] = !isExpanded },
                            colors = CardDefaults.cardColors(
                                containerColor = if (isExpanded) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f) else MaterialTheme.colorScheme.surface
                            ),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = if (isExpanded) Icons.Default.FolderOpen else Icons.Default.Folder,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = folderName,
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "${folderImages.size} images",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Icon(
                                    imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    if (isExpanded) {
                        val chunks = folderImages.chunked(3)
                        items(chunks.size) { index ->
                            val rowImages = chunks[index]
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.padding(top = 4.dp, bottom = 4.dp)
                            ) {
                                rowImages.forEach { wallpaper ->
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .aspectRatio(0.85f)
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(MaterialTheme.colorScheme.surfaceVariant)
                                            .clickable { selectedImg = wallpaper }
                                    ) {
                                        AsyncImage(
                                            model = Uri.parse(wallpaper.uriString),
                                            contentDescription = null,
                                            modifier = Modifier.fillMaxSize(),
                                            contentScale = ContentScale.Crop
                                        )
                                        if (wallpaper.isFavorite) {
                                            Icon(
                                                imageVector = Icons.Default.Star,
                                                contentDescription = null,
                                                tint = Color.Yellow,
                                                modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(16.dp)
                                            )
                                        }
                                    }
                                }
                                // Fill empty spots if row is not full
                                repeat(3 - rowImages.size) {
                                    Spacer(modifier = Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (selectedImg != null) {
        ImageDetailDialog(
            img = selectedImg!!,
            onDismiss = { selectedImg = null },
            onToggleFavorite = {
                onToggleFavorite(it)
                selectedImg = selectedImg?.copy(isFavorite = !selectedImg!!.isFavorite)
            }
        )
    }
}

@Composable
fun FavoritesScreen(
    favorites: List<com.example.data.FavoriteImageEntity>,
    onRemoveFavorite: (com.example.data.FavoriteImageEntity) -> Unit
) {
    var selectedImg by remember { mutableStateOf<com.example.data.FavoriteImageEntity?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(
            "FAVORITE IMAGES (${favorites.size})",
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            letterSpacing = 1.sp,
            modifier = Modifier.padding(bottom = 12.dp)
        )

        if (favorites.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Stars,
                        contentDescription = "No Favorites",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.size(64.dp)
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    Text(
                        "No Favorites Starred",
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 15.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "Star your favorite background wallpapers from the Gallery tab to group them together.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center,
                        lineHeight = 16.sp
                    )
                }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 100.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(favorites) { fav ->
                    Box(
                        modifier = Modifier
                            .aspectRatio(0.85f)
                            .clip(RoundedCornerShape(16.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .clickable { selectedImg = fav }
                    ) {
                        AsyncImage(
                            model = Uri.parse(fav.uriString),
                            contentDescription = fav.displayName,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(8.dp)
                                .size(32.dp)
                                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.9f), RoundedCornerShape(10.dp))
                                .clickable { onRemoveFavorite(fav) },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Star,
                                contentDescription = "Unstar",
                                tint = Color(0xFFEAB308),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .background(Color.Black.copy(alpha = 0.4f))
                                .padding(horizontal = 8.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = fav.displayName,
                                color = Color.White,
                                fontSize = 10.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }
        }
    }

    if (selectedImg != null) {
        val mappedWallpaper = WallpaperImg(
            uriString = selectedImg!!.uriString,
            folderUriString = selectedImg!!.folderUriString,
            displayName = selectedImg!!.displayName,
            isFavorite = true
        )
        ImageDetailDialog(
            img = mappedWallpaper,
            onDismiss = { selectedImg = null },
            onToggleFavorite = {
                onRemoveFavorite(selectedImg!!)
                selectedImg = null
            }
        )
    }
}

@Composable
fun SettingsScreen(
    viewModel: HomeViewModel,
    onSetWallpaperClick: () -> Unit
) {
    val intervalMinutes by viewModel.intervalMinutes.collectAsState()
    val useFavoritesOnly by viewModel.useFavoritesOnly.collectAsState()
    val transitionType by viewModel.transitionType.collectAsState()
    val context = LocalContext.current

    val intervals = listOf(
        0.1f to "6-Second Rotation (Dev Log)",
        0.5f to "30 Seconds",
        1.0f to "1 Minute",
        5.0f to "5 Minutes",
        15.0f to "15 Minutes",
        30.0f to "30 Minutes",
        60.0f to "1 Hour",
        720.0f to "12 Hours",
        1440.0f to "24 Hours"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp)
            .background(Color.Transparent)
    ) {
        Text(
            "TRANSITION EFFECT",
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            letterSpacing = 1.2.sp,
            modifier = Modifier.padding(bottom = 12.dp)
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            listOf("slide" to "Slide Mode", "fade" to "Fade Mode").forEach { (type, label) ->
                val selected = transitionType == type
                Button(
                    onClick = { viewModel.setTransitionType(type) },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(label, fontWeight = FontWeight.Bold)
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Use Favorites Only",
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 14.sp
                )
                Text(
                    "Rotate wallpaper pages exclusively from starred list",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp
                )
            }
            Switch(
                modifier = Modifier.testTag("favorite_switch"),
                checked = useFavoritesOnly,
                onCheckedChange = { viewModel.setUseFavoritesOnly(it) }
            )
        }

        Divider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(vertical = 8.dp))

        Text(
            "UPDATE INTERVAL",
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp,
            letterSpacing = 1.sp,
            modifier = Modifier.padding(top = 8.dp)
        )
        Text(
            "Configure how frequently wallpaper images alternate",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 11.sp,
            modifier = Modifier.padding(bottom = 12.dp)
        )

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(intervals) { (minutes, label) ->
                val isSelected = intervalMinutes == minutes
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)
                        .border(
                            width = 1.dp,
                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                            shape = RoundedCornerShape(12.dp)
                        )
                        .clickable { viewModel.setIntervalMinutes(minutes) }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = label,
                        color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                        fontSize = 13.sp
                    )
                    RadioButton(
                        selected = isSelected,
                        onClick = { viewModel.setIntervalMinutes(minutes) }
                    )
                }
            }
        }
    }
}

// ---------------------- DIALOGS ----------------------

@Composable
fun ImageDetailDialog(
    img: WallpaperImg,
    onDismiss: () -> Unit,
    onToggleFavorite: (WallpaperImg) -> Unit
) {
    val context = LocalContext.current
    Dialog(onDismissRequest = onDismiss) {
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(24.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentHeight()
                .padding(4.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = img.displayName,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(bottom = 14.dp)
                )

                AsyncImage(
                    model = Uri.parse(img.uriString),
                    contentDescription = img.displayName,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(300.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentScale = ContentScale.Fit
                )

                Spacer(modifier = Modifier.height(20.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = {
                            onToggleFavorite(img)
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (img.isFavorite) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = if (img.isFavorite) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSecondaryContainer
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("dialog_favorite_button"),
                        shape = RoundedCornerShape(12.dp),
                        elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp),
                        border = BorderStroke(1.dp, if (img.isFavorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
                    ) {
                        Icon(
                            imageVector = if (img.isFavorite) Icons.Filled.Star else Icons.Outlined.StarBorder,
                            contentDescription = "Star toggle",
                            tint = if (img.isFavorite) Color(0xFFEAB308) else MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(if (img.isFavorite) "Starred" else "Star", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    }

                    Button(
                        onClick = {
                            saveImageToGallery(context, Uri.parse(img.uriString), img.displayName)
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("dialog_save_gallery_button"),
                        shape = RoundedCornerShape(12.dp),
                        elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp)
                    ) {
                        Icon(imageVector = Icons.Default.SaveAlt, contentDescription = "Save Action", modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Export App", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Dismiss Dialog", color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

private fun saveImageToGallery(context: Context, imageUri: Uri, displayName: String) {
    try {
        val resolver = context.contentResolver
        val nameWithMime = if (displayName.contains(".")) {
            displayName
        } else {
            "$displayName.jpg"
        }
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "MW_$nameWithMime")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/MultiWallpaper")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }

        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }

        val uri = resolver.insert(collection, contentValues)
        if (uri != null) {
            resolver.openInputStream(imageUri)?.use { input ->
                resolver.openOutputStream(uri)?.use { output ->
                    input.copyTo(output)
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentValues.clear()
                contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(uri, contentValues, null, null)
            }
            Toast.makeText(context, "Selesai! Disimpan di Galeri Ponsel (Pictures/MultiWallpaper)", Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(context, "Gagal menambahkan ke media resolver", Toast.LENGTH_SHORT).show()
        }
    } catch (e: Exception) {
        e.printStackTrace()
        Toast.makeText(context, "Simpan Galeri Gagal: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}
