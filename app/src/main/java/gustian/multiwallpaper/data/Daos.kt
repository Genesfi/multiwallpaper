package gustian.multiwallpaper.data

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

    @Query("SELECT COUNT(*) FROM favorites")
    suspend fun getFavoriteCount(): Int

    @Query("SELECT uriString FROM favorites LIMIT 1 OFFSET :position")
    suspend fun getUriAtPosition(position: Int): String?

    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE uriString = :uriString)")
    suspend fun isFavoriteSync(uriString: String): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE uriString = :uriString)")
    fun isFavoriteFlow(uriString: String): Flow<Boolean>

    @Query("SELECT uriString FROM favorites WHERE uriString NOT IN (:history) ORDER BY RANDOM() LIMIT 1")
    suspend fun getRandomFavoriteUriExcludingHistory(history: List<String>): String?

    @Query("SELECT uriString FROM favorites WHERE uriString NOT IN (SELECT uriString FROM rotation_history) ORDER BY RANDOM() LIMIT 1")
    suspend fun getRandomFavoriteUriExcludingHistorySubquery(): String?

    @Query("SELECT uriString FROM favorites WHERE uriString NOT IN (:history) ORDER BY folderUriString ASC, displayName ASC LIMIT 1")
    suspend fun getOrderedFavoriteUriExcludingHistory(history: List<String>): String?

    @Query("SELECT uriString FROM favorites WHERE uriString NOT IN (SELECT uriString FROM rotation_history) ORDER BY folderUriString ASC, displayName ASC LIMIT 1")
    suspend fun getOrderedFavoriteUriExcludingHistorySubquery(): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFavorite(favorite: FavoriteImageEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFavorites(favorites: List<FavoriteImageEntity>)

    @Delete
    suspend fun deleteFavorite(favorite: FavoriteImageEntity)

    @Query("DELETE FROM favorites WHERE uriString = :uriString")
    suspend fun deleteFavoriteByUri(uriString: String)

    @Query("DELETE FROM favorites WHERE folderUriString = :folderUriString")
    suspend fun deleteFavoritesByFolderUri(folderUriString: String)

    @Query("DELETE FROM favorites")
    suspend fun deleteAllFavorites()

    @Query("DELETE FROM favorites WHERE uriString = :uriString")
    suspend fun deleteFavoriteByUriSync(uriString: String)
}

@Dao
interface ScannedImageDao {
    @Query("SELECT * FROM scanned_images")
    fun getAllImages(): Flow<List<ScannedImageEntity>>

    @Query("SELECT * FROM scanned_images")
    fun getAllImagesSync(): List<ScannedImageEntity>

    @Query("SELECT COUNT(*) FROM scanned_images")
    suspend fun getImageCount(): Int

    @Query("SELECT uriString FROM scanned_images LIMIT 1 OFFSET :position")
    suspend fun getUriAtPosition(position: Int): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertImages(images: List<ScannedImageEntity>)

    @Query("DELETE FROM scanned_images")
    suspend fun deleteAllImages()

    @Query("DELETE FROM scanned_images WHERE folderUriString = :folderUriString")
    suspend fun deleteImagesByFolderUri(folderUriString: String)

    @Query("DELETE FROM scanned_images WHERE uriString = :uriString")
    suspend fun deleteImageByUriSync(uriString: String)

    @Query("SELECT uriString FROM scanned_images WHERE uriString NOT IN (:history) ORDER BY RANDOM() LIMIT 1")
    suspend fun getRandomUriExcludingHistory(history: List<String>): String?

    @Query("SELECT uriString FROM scanned_images WHERE uriString NOT IN (SELECT uriString FROM rotation_history) ORDER BY RANDOM() LIMIT 1")
    suspend fun getRandomUriExcludingHistorySubquery(): String?

    @Query("SELECT uriString FROM scanned_images WHERE uriString NOT IN (:history) ORDER BY folderUriString ASC, displayName ASC LIMIT 1")
    suspend fun getOrderedUriExcludingHistory(history: List<String>): String?

    @Query("SELECT uriString FROM scanned_images WHERE uriString NOT IN (SELECT uriString FROM rotation_history) ORDER BY folderUriString ASC, displayName ASC LIMIT 1")
    suspend fun getOrderedUriExcludingHistorySubquery(): String?
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

@Dao
interface BlacklistedDao {
    @Query("SELECT * FROM blacklisted_images ORDER BY addedTime DESC")
    fun getAllBlacklisted(): Flow<List<BlacklistedImageEntity>>

    @Query("SELECT * FROM blacklisted_images")
    suspend fun getAllBlacklistedSync(): List<BlacklistedImageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBlacklist(image: BlacklistedImageEntity)

    @Delete
    suspend fun deleteBlacklist(image: BlacklistedImageEntity)

    @Query("DELETE FROM blacklisted_images WHERE uriString = :uriString")
    suspend fun deleteBlacklistByUri(uriString: String)

    @Query("SELECT EXISTS(SELECT 1 FROM blacklisted_images WHERE uriString = :uriString)")
    suspend fun isBlacklistedSync(uriString: String): Boolean
}

@Dao
interface RotationHistoryDao {
    @Query("SELECT uriString FROM rotation_history ORDER BY id DESC")
    fun getAllHistory(): Flow<List<String>>

    @Query("SELECT uriString FROM rotation_history ORDER BY id DESC LIMIT :limit OFFSET :offset")
    suspend fun getHistoryPaged(limit: Int, offset: Int): List<String>

    @Query("SELECT COUNT(*) FROM rotation_history")
    fun getHistoryCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM rotation_history")
    suspend fun getHistoryCountSync(): Int

    @Query("SELECT uriString FROM rotation_history ORDER BY id DESC")
    suspend fun getAllHistorySync(): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHistory(item: RotationHistoryEntity)

    @Query("DELETE FROM rotation_history WHERE uriString = :uriString")
    suspend fun deleteHistoryByUri(uriString: String)

    @Query("DELETE FROM rotation_history WHERE uriString IN (:uris)")
    suspend fun deleteMultipleHistoryByUri(uris: List<String>)

    @Query("DELETE FROM rotation_history WHERE id NOT IN (SELECT id FROM rotation_history ORDER BY id DESC LIMIT :limit)")
    suspend fun trimHistory(limit: Int)

    @Query("DELETE FROM rotation_history")
    suspend fun clearHistory()
}

