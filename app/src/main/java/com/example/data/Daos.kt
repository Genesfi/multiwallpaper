package com.example.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface FolderDao {
    @Query("SELECT * FROM folders ORDER BY addedTime DESC")
    fun getAllFolders(): Flow<List<FolderEntity>>

    @Query("SELECT * FROM folders")
    fun getAllFoldersSync(): List<FolderEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFolder(folder: FolderEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFolders(folders: List<FolderEntity>)

    @Query("DELETE FROM folders")
    suspend fun deleteAllFolders()

    @Delete
    suspend fun deleteFolder(folder: FolderEntity)

    @Query("DELETE FROM folders WHERE id = :id")
    suspend fun deleteFolderById(id: Int)
}

@Dao
interface FavoriteDao {
    @Query("SELECT * FROM favorites ORDER BY addedTime DESC")
    fun getAllFavorites(): Flow<List<FavoriteImageEntity>>

    @Query("SELECT * FROM favorites")
    fun getAllFavoritesSync(): List<FavoriteImageEntity>

    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE uriString = :uriString)")
    suspend fun isFavoriteSync(uriString: String): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE uriString = :uriString)")
    fun isFavoriteFlow(uriString: String): Flow<Boolean>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFavorite(favorite: FavoriteImageEntity)

    @Delete
    suspend fun deleteFavorite(favorite: FavoriteImageEntity)

    @Query("DELETE FROM favorites WHERE uriString = :uriString")
    suspend fun deleteFavoriteByUri(uriString: String)
}

@Dao
interface PresetDao {
    @Query("SELECT * FROM presets ORDER BY createdTime DESC")
    fun getAllPresets(): Flow<List<PresetEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPreset(preset: PresetEntity)

    @Delete
    suspend fun deletePreset(preset: PresetEntity)

    @Update
    suspend fun updatePreset(preset: PresetEntity)
}
