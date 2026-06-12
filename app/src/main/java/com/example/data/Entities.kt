package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "folders")
data class FolderEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val uriString: String,
    val displayName: String,
    val addedTime: Long = System.currentTimeMillis()
)

@Entity(tableName = "favorites")
data class FavoriteImageEntity(
    @PrimaryKey val uriString: String,
    val folderUriString: String,
    val displayName: String,
    val addedTime: Long = System.currentTimeMillis()
)

@Entity(tableName = "presets")
data class PresetEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    val thumbnailUri: String?,
    val folderUris: List<String>,
    val favoriteData: String, // Store full FavoriteImageEntity list as JSON string
    val createdTime: Long = System.currentTimeMillis()
)
