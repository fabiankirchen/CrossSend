package com.crosssend.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.crosssend.app.data.model.DocumentType
import com.crosssend.app.data.model.RenderMode
import com.crosssend.app.data.model.SyncStatus

class Converters {
    @TypeConverter
    fun fromDocumentType(value: DocumentType): String = value.name

    @TypeConverter
    fun toDocumentType(value: String): DocumentType = DocumentType.valueOf(value)

    @TypeConverter
    fun fromSyncStatus(value: SyncStatus): String = value.name

    @TypeConverter
    fun toSyncStatus(value: String): SyncStatus = SyncStatus.valueOf(value)

    @TypeConverter
    fun fromRenderMode(value: RenderMode): String = value.name

    @TypeConverter
    fun toRenderMode(value: String): RenderMode = try {
        RenderMode.valueOf(value)
    } catch (_: Exception) {
        RenderMode.READER
    }
}

@Database(
    entities = [
        DocumentEntity::class,
        FeedEntity::class,
        FeedArticleEntity::class,
        DomainPreferenceEntity::class
    ],
    version = 4,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun documentDao(): DocumentDao
    abstract fun feedDao(): FeedDao
    abstract fun domainPreferenceDao(): DomainPreferenceDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `feeds` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `title` TEXT NOT NULL,
                        `url` TEXT NOT NULL,
                        `description` TEXT,
                        `iconUrl` TEXT,
                        `addedAt` INTEGER NOT NULL DEFAULT 0,
                        `lastFetchedAt` INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent()
                )
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `documents` ADD COLUMN `archivedAt` INTEGER")
                db.execSQL("ALTER TABLE `documents` ADD COLUMN `excerpt` TEXT")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `feed_articles` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `feedId` INTEGER NOT NULL,
                        `link` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `author` TEXT,
                        `pubDate` TEXT,
                        `publishedTimestamp` INTEGER NOT NULL DEFAULT 0,
                        `description` TEXT,
                        `contentHtml` TEXT NOT NULL,
                        `imageUrlsJoined` TEXT NOT NULL DEFAULT '',
                        `wordCount` INTEGER NOT NULL DEFAULT 0,
                        `readingTimeMinutes` INTEGER NOT NULL DEFAULT 1,
                        `fetchedAt` INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_feed_articles_feedId_link` ON `feed_articles` (`feedId`, `link`)"
                )
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `documents` ADD COLUMN `renderMode` TEXT NOT NULL DEFAULT 'READER'")
                db.execSQL("ALTER TABLE `documents` ADD COLUMN `pendingDeleteRemotePath` TEXT")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `domain_preferences` (
                        `host` TEXT NOT NULL PRIMARY KEY,
                        `renderMode` TEXT NOT NULL,
                        `updatedAt` INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent()
                )
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "crosssend_database"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
