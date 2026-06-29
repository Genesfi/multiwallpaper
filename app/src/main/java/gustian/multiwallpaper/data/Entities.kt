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

@Entity(
    tableName = "rotation_history",
    indices = [
        Index(value = ["timestamp"]),
        Index(value = ["id"]),
        Index(value = ["target"])
    ]
)
data class RotationHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uriString: String,
    val target: String = "HOME", // "HOME" or "LOCK"
    val timestamp: Long = System.currentTimeMillis()
)

