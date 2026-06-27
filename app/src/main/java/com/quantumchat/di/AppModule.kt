package com.quantumchat.di

import com.quantumchat.core.crypto.CryptoManager
import com.quantumchat.core.crypto.CryptoManagerImpl
import com.quantumchat.core.networking.Transport
import com.quantumchat.core.networking.TransportManager
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class AppModule {

    @Binds
    @Singleton
    abstract fun bindCryptoManager(
        cryptoManagerImpl: CryptoManagerImpl
    ): CryptoManager

    @Binds
    @Singleton
    abstract fun bindSessionCrypto(
        simpleSessionCrypto: com.quantumchat.core.crypto.SimpleSessionCrypto
    ): com.quantumchat.core.crypto.SessionCrypto

    @Binds
    @Singleton
    abstract fun bindTransport(
        transportManager: TransportManager
    ): Transport
}
