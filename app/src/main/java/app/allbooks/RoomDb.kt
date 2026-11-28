package app.allbooks

import android.app.DownloadManager
import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

/** Room-сховище завантажень (перший PR: тільки інфраструктура).
 *  Одна таблиця на обидва старі сховища: DM-задачі (метадані для повтору)
 *  і ручні завантаження (стан). Пароль лишається зашифрованим SecretBox. */
@Entity(tableName = "downloads")
data class DownloadEntity(
    @PrimaryKey val id: Long,
    val title: String = "",
    val fileName: String = "",
    // Для DM-задач живий статус береться з DownloadManager при читанні
    // списку; тут — заглушка до переключення списку на Room.
    val status: Int = DownloadManager.STATUS_PENDING,
    val downloaded: Long = 0L,
    val total: Long = -1L,
    val safDocUri: String = "",
    val error: String = "",
    // Метадані для повтору (порожній url — метаданих нема, canRetry = false):
    val url: String = "",
    val folder: String = "",
    val user: String = "",
    val passEncrypted: String = "",
    val kind: String = "dm" // "dm" | "saf" | "okhttp"
) {
    val hasMeta: Boolean get() = url.isNotEmpty()
}

@Dao
interface DownloadDao {
    @Query("SELECT COUNT(*) FROM downloads")
    suspend fun count(): Int

    @Query("SELECT * FROM downloads ORDER BY id DESC")
    fun observeAll(): Flow<List<DownloadEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<DownloadEntity>)
}

@Database(entities = [DownloadEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun downloadDao(): DownloadDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java, "bookmriya.db"
                ).build().also { instance = it }
            }
    }
}
