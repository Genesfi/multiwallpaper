package gustian.multiwallpaper.data

import android.content.Context
import androidx.room.*
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory

class Converters {
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val type = Types.newParameterizedType(List::class.java, String::class.java)
    private val adapter = moshi.adapter<List<String>>(type)

    @TypeConverter
    fun fromString(value: String): List<String>? {
        return adapter.fromJson(value)
    }

    @TypeConverter
    fun fromList(list: List<String>): String {
        return adapter.toJson(list)
    }
}

@Database(entities = [FolderEntity::class, FavoriteImageEntity::class, PresetEntity::class, ScannedImageEntity::class, BlacklistedImageEntity::class, RotationHistoryEntity::class, ScheduleEntity::class, CustomPaletteEntity::class], version = 18, exportSchema = false)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun folderDao(): FolderDao
    abstract fun favoriteDao(): FavoriteDao
    abstract fun presetDao(): PresetDao
    abstract fun scannedImageDao(): ScannedImageDao
    abstract fun blacklistedDao(): BlacklistedDao
    abstract fun rotationHistoryDao(): RotationHistoryDao
    abstract fun scheduleDao(): ScheduleDao
    abstract fun customPaletteDao(): CustomPaletteDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "multi_wallpaper_database"
                )
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}

