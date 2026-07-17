package app.clearspace.network.storage.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import net.sqlcipher.database.SupportFactory
import app.clearspace.network.storage.db.dao.ContentDao
import app.clearspace.network.storage.db.dao.GroupDao
import app.clearspace.network.storage.db.dao.MessageDao
import app.clearspace.network.storage.db.dao.PeerDao
import app.clearspace.network.storage.db.dao.SecurityLogDao
import app.clearspace.network.storage.db.dao.RelayPacketDao
import app.clearspace.network.storage.db.entity.ContentUnit
import app.clearspace.network.storage.db.entity.ContentFtsEntity
import app.clearspace.network.storage.db.entity.KnownPeer
import app.clearspace.network.storage.db.entity.Message
import app.clearspace.network.storage.db.entity.SecurityLogEntry
import app.clearspace.network.storage.db.entity.RelayPacket
import app.clearspace.network.storage.db.entity.TrustGroup
import app.clearspace.network.storage.db.entity.GroupMember
import app.clearspace.network.storage.db.entity.BlockedAuthor
import app.clearspace.network.storage.db.dao.BlockedAuthorDao
import app.clearspace.network.storage.db.dao.BlacklistDao
import app.clearspace.network.storage.db.entity.BlacklistEntry
import app.clearspace.network.storage.db.entity.SpaceEntity
import app.clearspace.network.storage.db.entity.ModeratorEntity
import app.clearspace.network.storage.db.dao.SpaceDao

@Database(
    entities = [
        ContentUnit::class,
        KnownPeer::class,
        Message::class,
        SecurityLogEntry::class,
        ContentFtsEntity::class,
        RelayPacket::class,
        TrustGroup::class,
        GroupMember::class,
        BlockedAuthor::class,
        BlacklistEntry::class,
        SpaceEntity::class,
        ModeratorEntity::class
    ],
    version = 19,
    exportSchema = false
)
@androidx.room.TypeConverters(Converters::class)
abstract class AetherDatabase : RoomDatabase() {
    abstract fun contentDao(): ContentDao
    abstract fun peerDao(): PeerDao
    abstract fun messageDao(): MessageDao
    abstract fun securityLogDao(): SecurityLogDao
    abstract fun relayPacketDao(): RelayPacketDao
    abstract fun groupDao(): GroupDao
    abstract fun blockedAuthorDao(): BlockedAuthorDao
    abstract fun blacklistDao(): BlacklistDao
    abstract fun spaceDao(): SpaceDao

    companion object {
        val MIGRATION_18_19 = object : Migration(18, 19) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS `federated_spaces` (
                        `spaceId` TEXT NOT NULL, 
                        `name` TEXT NOT NULL, 
                        `description` TEXT NOT NULL, 
                        `isJoined` INTEGER NOT NULL, 
                        `creatorAlias` TEXT NOT NULL, 
                        `rules` TEXT NOT NULL, 
                        `createdAt` INTEGER NOT NULL, 
                        PRIMARY KEY(`spaceId`)
                    )
                """)
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS `space_moderators` (
                        `spaceId` TEXT NOT NULL, 
                        `moderatorPubKey` TEXT NOT NULL, 
                        `addedAt` INTEGER NOT NULL, 
                        PRIMARY KEY(`spaceId`, `moderatorPubKey`)
                    )
                """)
                database.execSQL("ALTER TABLE `content_units` ADD COLUMN `spaceId` TEXT")
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS `content_units_fts` USING FTS4(`title`, `body`, `categoryFlags`, `authorAlias`, content=`content_units`)")
                database.execSQL("INSERT INTO content_units_fts(content_units_fts, docid, `title`, `body`, `categoryFlags`, `authorAlias`) SELECT 'rebuild', rowid, `title`, `body`, `categoryFlags`, `authorAlias` FROM content_units")
            }
        }

        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE vault_media ADD COLUMN lastPlaybackPosition INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE vault_media ADD COLUMN playbackSpeed REAL NOT NULL DEFAULT 1.0")
            }
        }

        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("CREATE TABLE IF NOT EXISTS `shopping_items` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `isChecked` INTEGER NOT NULL, `reminderTimeMs` INTEGER, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))")
            }
        }

        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE `shopping_items` ADD COLUMN `quantity` INTEGER NOT NULL DEFAULT 1")
                database.execSQL("ALTER TABLE `shopping_items` ADD COLUMN `reminderTime` INTEGER")
            }
        }

        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS `recipes` (
                        `id` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `description` TEXT NOT NULL,
                        `ingredients` TEXT NOT NULL,
                        `steps` TEXT NOT NULL,
                        `prepTimeMinutes` INTEGER NOT NULL,
                        `cookTimeMinutes` INTEGER NOT NULL,
                        `isFavorite` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                """)
            }
        }

        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS `relay_packets` (
                        `packetId` TEXT NOT NULL,
                        `hashedRecipientId` TEXT NOT NULL,
                        `senderAlias` TEXT,
                        `encryptedPayload` BLOB NOT NULL,
                        `timestamp` INTEGER NOT NULL,
                        `ttlExpiration` INTEGER NOT NULL,
                        PRIMARY KEY(`packetId`)
                    )
                """)
            }
        }

        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS `trust_groups` (
                        `groupId` TEXT NOT NULL,
                        `name` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        PRIMARY KEY(`groupId`)
                    )
                """)
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS `group_members` (
                        `groupId` TEXT NOT NULL,
                        `publicKeyBase64` TEXT NOT NULL,
                        PRIMARY KEY(`groupId`, `publicKeyBase64`),
                        FOREIGN KEY(`groupId`) REFERENCES `trust_groups`(`groupId`) ON UPDATE NO ACTION ON DELETE CASCADE ,
                        FOREIGN KEY(`publicKeyBase64`) REFERENCES `known_peers`(`publicKeyBase64`) ON UPDATE NO ACTION ON DELETE CASCADE 
                    )
                """)
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_group_members_groupId` ON `group_members` (`groupId`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_group_members_publicKeyBase64` ON `group_members` (`publicKeyBase64`)")
                
                database.execSQL("ALTER TABLE `known_peers` ADD COLUMN `encryptionPublicKeyBase64` TEXT")
                database.execSQL("ALTER TABLE `content_units` ADD COLUMN `recipientKeyMapJson` TEXT")
            }
        }

        // FIX C3: Add Ed25519 authorship signature fields to content_units
        val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE `content_units` ADD COLUMN `authorSignatureBase64` TEXT")
                database.execSQL("ALTER TABLE `content_units` ADD COLUMN `authorPublicKeyBase64` TEXT")
            }
        }

        val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("CREATE TABLE IF NOT EXISTS `blocked_authors` (`authorId` TEXT NOT NULL, `blockedAt` INTEGER NOT NULL, PRIMARY KEY(`authorId`))")
            }
        }
        
        val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE `content_units` ADD COLUMN `reportTokens` TEXT NOT NULL DEFAULT '[]'")
            }
        }

        val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("CREATE TABLE IF NOT EXISTS `cached_blacklist` (`hash` TEXT NOT NULL, `timestamp` INTEGER NOT NULL, PRIMARY KEY(`hash`))")
            }
        }

        val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("DROP TABLE IF EXISTS `shopping_items`")
                database.execSQL("DROP TABLE IF EXISTS `recipes`")
            }
        }

        @Volatile
        private var INSTANCE_PRIVATE: AetherDatabase? = null

        @Volatile
        private var INSTANCE_SHARED: AetherDatabase? = null

        fun getPrivateDatabase(context: Context, passhprase: ByteArray): AetherDatabase {
            return INSTANCE_PRIVATE ?: synchronized(this) {
                val factory = SupportFactory(passhprase, null, false)
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AetherDatabase::class.java,
                    "aether_private.db"
                )
                .openHelperFactory(factory)
                .addMigrations(
                    MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10,
                    MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17, MIGRATION_17_18, MIGRATION_18_19
                )
                // FIX MC1: Removed fallbackToDestructiveMigration(). A failed migration now
                // surfaces an exception that must be caught and shown to the user, rather than
                // silently wiping the encrypted data vault.
                .build()
                INSTANCE_PRIVATE = instance
                instance
            }
        }

        fun getSharedDatabase(context: Context, passhprase: ByteArray): AetherDatabase {
            return INSTANCE_SHARED ?: synchronized(this) {
                val factory = SupportFactory(passhprase, null, false)
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AetherDatabase::class.java,
                    "aether_shared.db"
                )
                .openHelperFactory(factory)
                .addMigrations(
                    MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10,
                    MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17, MIGRATION_17_18, MIGRATION_18_19
                )
                // FIX MC1: Removed fallbackToDestructiveMigration(). Same reason as private DB.
                .build()
                INSTANCE_SHARED = instance
                instance
            }
        }

        fun getPrivateDatabase(): AetherDatabase {
            return INSTANCE_PRIVATE ?: throw IllegalStateException("Private database not initialized")
        }

        fun getSharedDatabase(): AetherDatabase {
            return INSTANCE_SHARED ?: throw IllegalStateException("Shared database not initialized")
        }

        fun wipeAll(context: Context) {
            INSTANCE_PRIVATE?.close()
            INSTANCE_SHARED?.close()
            INSTANCE_PRIVATE = null
            INSTANCE_SHARED = null
            context.deleteDatabase("aether_private.db")
            context.deleteDatabase("aether_shared.db")
            val prefs = context.getSharedPreferences("aether_settings", android.content.Context.MODE_PRIVATE)
            prefs.edit().clear().apply()
        }
    }
}
