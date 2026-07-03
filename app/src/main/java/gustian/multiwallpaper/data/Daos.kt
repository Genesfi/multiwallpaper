package gustian.multiwallpaper.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface FolderDao {
    @Query("SELECT * FROM folders WHERE target = :target ORDER BY addedTime DESC")
    fun getAllFolders(target: String): Flow<List<FolderEntity>>

    @Query("SELECT * FROM folders WHERE target = :target")
    fun getAllFoldersSync(target: String): List<FolderEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFolder(folder: FolderEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFolders(folders: List<FolderEntity>)

    @Query("DELETE FROM folders WHERE target = :target")
    suspend fun deleteAllFolders(target: String)

    @Delete
    suspend fun deleteFolder(folder: FolderEntity)

    @Query("DELETE FROM folders WHERE id = :id")
    suspend fun deleteFolderById(id: Int)
}

@Dao
interface FavoriteDao {
    @Query("SELECT * FROM favorites WHERE target = :target ORDER BY addedTime DESC")
    fun getAllFavorites(target: String): Flow<List<FavoriteImageEntity>>

    @Query("SELECT * FROM favorites WHERE target = :target")
    fun getAllFavoritesSync(target: String): List<FavoriteImageEntity>

    @Query("SELECT COUNT(*) FROM favorites WHERE target = :target")
    suspend fun getFavoriteCount(target: String): Int

    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE uriString = :uriString AND target = :target)")
    suspend fun isFavoriteSync(uriString: String, target: String): Boolean

    @Query("SELECT uriString FROM favorites WHERE target = :target AND uriString NOT IN (SELECT uriString FROM rotation_history WHERE target = :target) ORDER BY RANDOM() LIMIT 1")
    suspend fun getRandomFavoriteUriExcludingHistorySubquery(target: String): String?

    @Query("SELECT uriString FROM favorites WHERE target = :target AND uriString NOT IN (SELECT uriString FROM rotation_history WHERE target = :target) ORDER BY folderUriString ASC, displayName ASC LIMIT 1")
    suspend fun getOrderedFavoriteUriExcludingHistorySubquery(target: String): String?

    @Query("SELECT uriString FROM favorites WHERE target = :target AND uriString NOT IN (SELECT uriString FROM rotation_history WHERE target = :target) ORDER BY RANDOM() LIMIT :limit")
    suspend fun getRandomFavoriteUrisExcludingHistory(target: String, limit: Int): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFavorite(favorite: FavoriteImageEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFavorites(favorites: List<FavoriteImageEntity>)

    @Delete
    suspend fun deleteFavorite(favorite: FavoriteImageEntity)

    @Query("DELETE FROM favorites WHERE uriString = :uriString AND target = :target")
    suspend fun deleteFavoriteByUri(uriString: String, target: String)

    @Query("DELETE FROM favorites WHERE folderUriString = :folderUriString AND target = :target")
    suspend fun deleteFavoritesByFolderUri(folderUriString: String, target: String)

    @Query("DELETE FROM favorites WHERE target = :target")
    suspend fun deleteAllFavorites(target: String)

    @Query("DELETE FROM favorites WHERE uriString = :uriString AND target = :target")
    suspend fun deleteFavoriteByUriSync(uriString: String, target: String)
}

@Dao
interface ScannedImageDao {
    @Query("SELECT * FROM scanned_images WHERE target = :target")
    fun getAllImages(target: String): Flow<List<ScannedImageEntity>>

    @Query("SELECT * FROM scanned_images WHERE target = :target")
    fun getAllImagesSync(target: String): List<ScannedImageEntity>

    @Query("SELECT COUNT(*) FROM scanned_images WHERE target = :target")
    suspend fun getImageCount(target: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertImages(images: List<ScannedImageEntity>)

    @Query("DELETE FROM scanned_images WHERE target = :target")
    suspend fun deleteAllImages(target: String)

    @Query("DELETE FROM scanned_images WHERE uriString = :uriString AND target = :target")
    suspend fun deleteImageByUriSync(uriString: String, target: String)

    @Query("SELECT uriString FROM scanned_images WHERE target = :target AND uriString NOT IN (SELECT uriString FROM rotation_history WHERE target = :target) ORDER BY RANDOM() LIMIT 1")
    suspend fun getRandomUriExcludingHistorySubquery(target: String): String?

    @Query("SELECT uriString FROM scanned_images WHERE target = :target AND uriString NOT IN (SELECT uriString FROM rotation_history WHERE target = :target) ORDER BY folderUriString ASC, displayName ASC LIMIT 1")
    suspend fun getOrderedUriExcludingHistorySubquery(target: String): String?

    @Query("SELECT uriString FROM scanned_images WHERE target = :target AND uriString NOT IN (SELECT uriString FROM rotation_history WHERE target = :target) ORDER BY RANDOM() LIMIT :limit")
    suspend fun getRandomUrisExcludingHistory(target: String, limit: Int): List<String>
}

@Dao
interface PresetDao {
    @Query("SELECT * FROM presets WHERE target = :target ORDER BY createdTime DESC")
    fun getAllPresets(target: String): Flow<List<PresetEntity>>

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

    @Query("DELETE FROM blacklisted_images")
    suspend fun deleteAllBlacklisted()
}

@Dao
interface RotationHistoryDao {
    @Query("SELECT uriString FROM rotation_history WHERE target = :target ORDER BY id DESC")
    fun getAllHistory(target: String): Flow<List<String>>

    @Query("SELECT uriString FROM rotation_history WHERE target = :target ORDER BY id DESC LIMIT :limit OFFSET :offset")
    suspend fun getHistoryPaged(target: String, limit: Int, offset: Int): List<String>

    @Query("SELECT COUNT(*) FROM rotation_history WHERE target = :target")
    fun getHistoryCount(target: String): Flow<Int>

    @Query("SELECT COUNT(*) FROM rotation_history WHERE target = :target")
    suspend fun getHistoryCountSync(target: String): Int

    @Query("SELECT uriString FROM rotation_history WHERE target = :target ORDER BY id DESC")
    suspend fun getAllHistorySync(target: String): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHistory(item: RotationHistoryEntity)

    @Query("DELETE FROM rotation_history WHERE uriString = :uriString AND target = :target")
    suspend fun deleteHistoryByUri(uriString: String, target: String)

    @Query("DELETE FROM rotation_history WHERE uriString IN (:uris) AND target = :target")
    suspend fun deleteMultipleHistoryByUri(uris: List<String>, target: String)

    @Query("DELETE FROM rotation_history WHERE target = :target AND id NOT IN (SELECT id FROM rotation_history WHERE target = :target ORDER BY id DESC LIMIT :limit)")
    suspend fun trimHistory(target: String, limit: Int)

    @Query("DELETE FROM rotation_history WHERE target = :target")
    suspend fun clearHistory(target: String)
}

@Dao
interface CustomPaletteDao {
    @Query("SELECT * FROM custom_palettes WHERE type = :type ORDER BY createdTime DESC")
    fun getPalettesByType(type: String): Flow<List<CustomPaletteEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPalette(palette: CustomPaletteEntity)

    @Delete
    suspend fun deletePalette(palette: CustomPaletteEntity)
}

@Dao
interface ScheduleDao {
    @Query("SELECT * FROM schedules WHERE target = :target ORDER BY startTime ASC")
    fun getAllSchedules(target: String): Flow<List<ScheduleEntity>>

    @Query("SELECT * FROM schedules WHERE target = :target AND isEnabled = 1")
    fun getEnabledSchedulesSync(target: String): List<ScheduleEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSchedule(schedule: ScheduleEntity)

    @Update
    suspend fun updateSchedule(schedule: ScheduleEntity)

    @Delete
    suspend fun deleteSchedule(schedule: ScheduleEntity)

    @Query("SELECT * FROM schedules WHERE id = :id")
    suspend fun getScheduleById(id: Int): ScheduleEntity?
}
