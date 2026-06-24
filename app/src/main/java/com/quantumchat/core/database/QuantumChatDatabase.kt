package com.quantumchat.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

/**
 * Encrypted Room Database for local data persistence.
 * Uses SQLCipher via SupportOpenHelperFactory to encrypt the SQLite database file.
 */
@Database(
    entities = [ContactEntity::class, MessageEntity::class, RatchetStateEntity::class, SkippedMessageKeyEntity::class, PendingMessageEntity::class],
    version = 8,
    exportSchema = false
)
abstract class QuantumChatDatabase : RoomDatabase() {

    abstract fun contactDao(): ContactDao
    abstract fun messageDao(): MessageDao
    abstract fun ratchetStateDao(): RatchetStateDao
    abstract fun skippedMessageKeyDao(): SkippedMessageKeyDao
    abstract fun pendingMessageDao(): PendingMessageDao

    companion object {
        const val DATABASE_NAME = "quantum_chat_encrypted.db"

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `skipped_message_keys` (" +
                    "`contactFingerprint` TEXT NOT NULL, " +
                    "`dhPublicKeyB64` TEXT NOT NULL, " +
                    "`messageNumber` INTEGER NOT NULL, " +
                    "`messageKey` BLOB NOT NULL, " +
                    "PRIMARY KEY(`contactFingerprint`, `dhPublicKeyB64`, `messageNumber`))"
                )

                val cursor = db.query("PRAGMA table_info(ratchet_states)")
                val existingColumns = mutableListOf<String>()
                while (cursor.moveToNext()) {
                    val nameIndex = cursor.getColumnIndex("name")
                    if (nameIndex >= 0) {
                        existingColumns.add(cursor.getString(nameIndex))
                    }
                }
                cursor.close()

                if (!existingColumns.contains("previousChainLength")) {
                    db.execSQL("ALTER TABLE ratchet_states ADD COLUMN previousChainLength INTEGER NOT NULL DEFAULT 0")
                }
                if (!existingColumns.contains("localDhPublicKey")) {
                    db.execSQL("ALTER TABLE ratchet_states ADD COLUMN localDhPublicKey BLOB NOT NULL DEFAULT x''")
                }
                if (!existingColumns.contains("localDhPrivateKey")) {
                    db.execSQL("ALTER TABLE ratchet_states ADD COLUMN localDhPrivateKey BLOB NOT NULL DEFAULT x''")
                }
                if (!existingColumns.contains("remoteDhPublicKey")) {
                    db.execSQL("ALTER TABLE ratchet_states ADD COLUMN remoteDhPublicKey BLOB NOT NULL DEFAULT x''")
                }
                if (!existingColumns.contains("isFallback")) {
                    db.execSQL("ALTER TABLE ratchet_states ADD COLUMN isFallback INTEGER NOT NULL DEFAULT 0")
                }
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                val cursor = db.query("PRAGMA table_info(ratchet_states)")
                val existingColumns = mutableListOf<String>()
                while (cursor.moveToNext()) {
                    val nameIndex = cursor.getColumnIndex("name")
                    if (nameIndex >= 0) {
                        existingColumns.add(cursor.getString(nameIndex))
                    }
                }
                cursor.close()

                if (!existingColumns.contains("pendingKyberCiphertext")) {
                    db.execSQL("ALTER TABLE ratchet_states ADD COLUMN pendingKyberCiphertext BLOB")
                }
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE contacts ADD COLUMN onionAddress TEXT")
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS pending_messages (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "contactFingerprint TEXT NOT NULL, " +
                    "encryptedPayload BLOB NOT NULL, " +
                    "createdAt INTEGER NOT NULL, " +
                    "retryCount INTEGER NOT NULL DEFAULT 0, " +
                    "lastAttemptAt INTEGER" +
                    ")"
                )
            }
        }

        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN status TEXT NOT NULL DEFAULT 'SENT'")
                db.execSQL("ALTER TABLE pending_messages ADD COLUMN expiresAt INTEGER NOT NULL DEFAULT 0")
            }
        }


        /**
         * Creates a SupportOpenHelperFactory configured with the given passphrase for SQLCipher database encryption.
         */
        fun getDatabaseFactory(passphrase: ByteArray): SupportOpenHelperFactory {
            return SupportOpenHelperFactory(passphrase)
        }
    }
}
