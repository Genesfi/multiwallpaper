package gustian.multiwallpaper.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.*

@Entity(tableName = "folders")
data class FolderEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val uriString: String,
    val displayName: String,
    val target: String = "HOME", // "HOME" or "LOCK"
    val addedTime: Long = System.currentTimeMillis()
)

@Entity(tableName = "favorites", primaryKeys = ["uriString", "target"])
data class FavoriteImageEntity(
    val uriString: String,
    val folderUriString: String,
    val displayName: String,
    val target: String = "HOME", // "HOME" or "LOCK"
    val addedTime: Long = System.currentTimeMillis()
)

@Entity(tableName = "scanned_images", primaryKeys = ["uriString", "target"])
data class ScannedImageEntity(
    val uriString: String,
    val folderUriString: String,
    val displayName: String,
    val target: String = "HOME", // "HOME" or "LOCK"
    val lastScanned: Long = System.currentTimeMillis()
)

@Entity(tableName = "presets")
data class PresetEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    val thumbnailUri: String?,
    val folderUris: List<String>,
    val favoriteData: String, // Store full FavoriteImageEntity list as JSON string
    val blacklistData: String? = null, // Store full BlacklistedImageEntity list as JSON string
    val target: String = "HOME", // "HOME" or "LOCK"
    val createdTime: Long = System.currentTimeMillis()
)

@Entity(tableName = "blacklisted_images")
data class BlacklistedImageEntity(
    @PrimaryKey val uriString: String,
    val folderUriString: String,
    val displayName: String,
    val addedTime: Long = System.currentTimeMillis()
)

@Entity(tableName = "rotation_history", indices = [Index(value = ["timestamp"]), Index(value = ["id"]), Index(value = ["target"])])
data class RotationHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uriString: String,
    val target: String = "HOME", // "HOME" or "LOCK"
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "schedules")
data class ScheduleEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    val startTime: String, // HH:mm format
    val endTime: String,   // HH:mm format
    val target: String = "HOME", // "HOME" or "LOCK"
    val isEnabled: Boolean = true,
    val presetId: Int? = null,
    val blurEnabled: Boolean? = null,
    val blurRadius: Float? = null,
    val dimEnabled: Boolean? = null,
    val dimIntensity: Float? = null,
    val lightModeEnabled: Boolean? = null,
    val filterType: String? = null,
    val filterColor1: Int? = null,
    val filterColor2: Int? = null,
    val filterColor3: Int? = null,
    val selectedDays: String = "1,2,3,4,5,6,7", // Comma-separated days 1-7 (Sun-Sat)
    val createdTime: Long = System.currentTimeMillis()
)

@Entity(tableName = "custom_palettes")
data class CustomPaletteEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    val color1: Int,
    val color2: Int,
    val color3: Int? = null,
    val type: String, // "DUOTONE" or "TRITONE"
    val createdTime: Long = System.currentTimeMillis()
)
