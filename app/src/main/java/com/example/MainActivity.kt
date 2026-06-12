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

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                MainLayout()
            }
        }
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
    
    val folders by viewModel.folders.collectAsState()
    val favorites by viewModel.favorites.collectAsState()
    val scannedImages by viewModel.scannedImages.collectAsState()
    val isScanning by viewModel.isScanning.collectAsState()
    val context = LocalContext.current

    // Document tree launcher
    val folderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            viewModel.addFolder(uri)
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
                    IconButton(onClick = { triggerLiveWallpaperSelection(context) }) {
                        Icon(
                            imageVector = Icons.Default.Wallpaper,
                            contentDescription = "Apply Wallpaper",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(26.dp)
                        )
                    }
                },
                actions = {
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
                // Main Setup FAB - always visible or specific to some tabs
                SmallFloatingActionButton(
                    onClick = { triggerLiveWallpaperSelection(context) },
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.AutoFixHigh, contentDescription = "Quick Setup")
                }

                if (currentTab == NavigationTab.FOLDERS) {
                    ExtendedFloatingActionButton(
                        modifier = Modifier.testTag("add_folder_fab"),
                        onClick = { folderLauncher.launch(null) },
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                        shape = RoundedCornerShape(16.dp),
                        elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 2.dp)
                    ) {
                        Icon(imageVector = Icons.Default.AddCircle, contentDescription = "Add Folder")
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Add Folder", fontWeight = FontWeight.Bold)
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
                        onScan = { viewModel.scanFolders() },
                        isScanning = isScanning,
                        onAddClick = { folderLauncher.launch(null) }
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

@Composable
fun FolderScreen(
    folders: List<FolderEntity>,
    onDeleteFolder: (FolderEntity) -> Unit,
    onScan: () -> Unit,
    isScanning: Boolean,
    onAddClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Explanatory banner matching design spec HTML
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFFD9E2FF)),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 20.dp),
            shape = RoundedCornerShape(24.dp)
        ) {
            Row(
                modifier = Modifier.padding(20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .background(Color(0xFF005AC1), RoundedCornerShape(16.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.AutoAwesomeMotion,
                        contentDescription = "Rotation Status",
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Auto-Rotation Support",
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF001D3E),
                        fontSize = 15.sp
                    )
                    Text(
                        "Add device directories using the button below. Image files (.jpg, .png, .webp) will be cataloged safely offline.",
                        color = Color(0xFF001D3E).copy(alpha = 0.75f),
                        fontSize = 12.sp,
                        lineHeight = 16.sp
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "WALLPAPER SOURCES (${folders.size})",
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                color = Color(0xFF43474E),
                letterSpacing = 1.sp
            )
            if (isScanning) {
                CircularProgressIndicator(
                    strokeWidth = 2.dp,
                    color = Color(0xFF005AC1),
                    modifier = Modifier.size(16.dp)
                )
            } else if (folders.isNotEmpty()) {
                TextButton(
                    onClick = onScan,
                    modifier = Modifier.height(32.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = Color(0xFF005AC1)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Re-Scan", color = Color(0xFF005AC1), fontSize = 13.sp, fontWeight = FontWeight.Bold)
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
                        tint = Color(0xFF43474E).copy(alpha = 0.5f),
                        modifier = Modifier.size(80.dp)
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    Text(
                        "No Wallpaper Folders Added",
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF1A1C1E),
                        fontSize = 16.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "Tap here or use the '+ Add Folder' button to choose folders from local storage.",
                        color = Color(0xFF73777F),
                        textAlign = TextAlign.Center,
                        fontSize = 12.sp
                    )
                }
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(folders) { folder ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color.White),
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(1.dp, Color(0xFFDEE2E6)),
                        modifier = Modifier.fillMaxWidth(),
                        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(40.dp)
                                        .background(Color(0xFFF3F4F9), RoundedCornerShape(10.dp)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Folder,
                                        contentDescription = "Folder",
                                        tint = Color(0xFF43474E),
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(14.dp))
                                Column {
                                    Text(
                                        text = folder.displayName,
                                        fontWeight = FontWeight.SemiBold,
                                        color = Color(0xFF1A1C1E),
                                        fontSize = 14.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = Uri.parse(folder.uriString).path ?: folder.uriString,
                                        color = Color(0xFF73777F),
                                        fontSize = 11.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            IconButton(
                                onClick = { onDeleteFolder(folder) }
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = "Hapus Folder",
                                    tint = Color(0xFFBA1A1A)
                                )
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
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                groupedImages.forEach { (folderUri, folderImages) ->
                    item {
                        val folderName = folderImages.firstOrNull()?.folderUriString?.split("/")?.lastOrNull() ?: "Folder"
                        Text(
                            text = folderName.uppercase(),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 1.2.sp,
                            modifier = Modifier.padding(bottom = 12.dp)
                        )

                        // Display images in a grid-like manner within the column
                        val chunks = folderImages.chunked(3)
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            chunks.forEach { rowImages ->
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
            color = Color(0xFF43474E),
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
                        tint = Color(0xFF43474E).copy(alpha = 0.5f),
                        modifier = Modifier.size(64.dp)
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    Text(
                        "No Favorites Starred",
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF1A1C1E),
                        fontSize = 15.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "Star your favorite background wallpapers from the Gallery tab to group them together.",
                        color = Color(0xFF73777F),
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
                            .background(Color(0xFFE2E8F0))
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
                                .background(Color.White.copy(alpha = 0.9f), RoundedCornerShape(10.dp))
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
            colors = CardDefaults.cardColors(containerColor = Color.White),
            shape = RoundedCornerShape(24.dp),
            border = BorderStroke(1.dp, Color(0xFFDEE2E6)),
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
                    color = Color(0xFF1A1C1E),
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
                        .background(Color(0xFFE2E8F0)),
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
                            containerColor = if (img.isFavorite) Color(0xFFD9E2FF) else Color(0xFFF3F4F9),
                            contentColor = if (img.isFavorite) Color(0xFF001D3E) else Color(0xFF43474E)
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("dialog_favorite_button"),
                        shape = RoundedCornerShape(12.dp),
                        elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp),
                        border = BorderStroke(1.dp, if (img.isFavorite) Color(0xFF005AC1) else Color(0xFFDEE2E6))
                    ) {
                        Icon(
                            imageVector = if (img.isFavorite) Icons.Filled.Star else Icons.Outlined.StarBorder,
                            contentDescription = "Star toggle",
                            tint = if (img.isFavorite) Color(0xFFEAB308) else Color(0xFF43474E),
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
                            containerColor = Color(0xFF005AC1),
                            contentColor = Color.White
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
                    Text("Dismiss Dialog", color = Color(0xFF73777F), fontWeight = FontWeight.SemiBold)
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
