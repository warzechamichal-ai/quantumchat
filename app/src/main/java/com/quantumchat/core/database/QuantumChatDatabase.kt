package com.quantumchat.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

/**
 * Encrypted Room Database for local data persistence.
 * Uses SQLCipher via SupportOpenHelperFactory to encrypt the SQLite database file.
 */
@Database(
    entities = [ContactEntity::class, MessageEntity::class],
    version = 1,
    exportSchema = false
)
abstract class QuantumChatDatabase : RoomDatabase() {

    abstract fun contactDao(): ContactDao
    abstract fun messageDao(): MessageDao

    companion object {
        const val DATABASE_NAME = "quantum_chat_encrypted.db"

        /**
         * Creates a SupportOpenHelperFactory configured with the given passphrase for SQLCipher database encryption.
         */
        fun getDatabaseFactory(passphrase: ByteArray): SupportOpenHelperFactory {
            return SupportOpenHelperFactory(passphrase)
        }
    }
}
