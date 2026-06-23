package com.quantumchat.di

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.quantumchat.core.data.ContactRepository
import com.quantumchat.core.data.ContactRepositoryImpl
import com.quantumchat.core.database.ContactDao
import com.quantumchat.core.database.MessageDao
import com.quantumchat.core.database.QuantumChatDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import timber.log.Timber
import javax.inject.Singleton

/**
 * Hilt module that provides encrypted local database instances and their respective DAOs.
 */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(
        @ApplicationContext context: Context
    ): QuantumChatDatabase {
        Timber.d("Initializing SQLCipher encrypted Room database...")

        try {
            // Load native SQLCipher libraries
            System.loadLibrary("sqlcipher")
            Timber.i("Native SQLCipher libraries loaded successfully.")
        } catch (e: UnsatisfiedLinkError) {
            Timber.e(e, "Failed to load native SQLCipher libraries.")
        }

        // Passphrase for DB encryption
        val passphrase = "QC-DATABASE-SECURE-KEY-1024".toByteArray(Charsets.UTF_8)
        val factory = QuantumChatDatabase.getDatabaseFactory(passphrase)

        return Room.databaseBuilder(
            context,
            QuantumChatDatabase::class.java,
            QuantumChatDatabase.DATABASE_NAME
        )
        .openHelperFactory(factory)
        .addCallback(object : RoomDatabase.Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                super.onCreate(db)
                Timber.d("onCreate database callback: Inserting mock contacts...")
                db.execSQL(
                    "INSERT INTO contacts (id, name, publicKeyFingerprint, isOnline) " +
                    "VALUES ('1', 'Alice (Security Lead)', 'QC-PQ-A1B2-C3D4', 1)"
                )
                db.execSQL(
                    "INSERT INTO contacts (id, name, publicKeyFingerprint, isOnline) " +
                    "VALUES ('2', 'Bob (Quantum Cryptographer)', 'QC-PQ-E5F6-G7H8', 0)"
                )
                db.execSQL(
                    "INSERT INTO contacts (id, name, publicKeyFingerprint, isOnline) " +
                    "VALUES ('3', 'Charlie (Validator)', 'QC-PQ-I9J0-K1L2', 1)"
                )
            }
        })
        .fallbackToDestructiveMigration(true) // Clean migration for development setups
        .build()
    }

    @Provides
    fun provideContactDao(database: QuantumChatDatabase): ContactDao {
        return database.contactDao()
    }

    @Provides
    fun provideMessageDao(database: QuantumChatDatabase): MessageDao {
        return database.messageDao()
    }

    @Provides
    @Singleton
    fun provideContactRepository(contactDao: ContactDao): ContactRepository {
        return ContactRepositoryImpl(contactDao)
    }
}
