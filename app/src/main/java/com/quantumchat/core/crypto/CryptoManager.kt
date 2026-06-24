package com.quantumchat.core.crypto

/**
 * Zestaw zweryfikowanych danych kontaktu uzyskany na podstawie kodu QR.
 */
data class VerifiedContactData(
    val name: String,
    val publicKeyFingerprint: String,
    val onionAddress: String? = null
)

/**
 * Centralny interfejs odpowiedzialny za wszystkie operacje kryptograficzne w aplikacji.
 *
 * Filozofia bezpieczeństwa:
 * - Hybrydowa kryptografia (X25519 + ML-KEM) dla odporności na komputery kwantowe
 * - Mechanizm Double Ratchet (docelowo) dla forward secrecy
 * - Weryfikacja tożsamości out-of-band przez QR Code (najwyższy poziom zaufania)
 */
interface CryptoManager {

    // region === Zarządzanie kluczami tożsamości ===

    /**
     * Generuje parę kluczy tożsamości (identity key pair).
     * Powinno zawierać zarówno klucz klasyczny (X25519), jak i post-kwantowy (ML-KEM + ML-DSA).
     */
    fun generateLocalIdentityKeyPair(): Boolean

    /**
     * Zwraca fingerprint publicznego klucza tożsamości urządzenia.
     */
    fun getLocalIdentityFingerprint(): String

    // endregion

    // region === Nawiązywanie sesji ===

    /**
     * Nawiązuje bezpieczną sesję z kontaktem.
     */
    fun establishSecureSession(contactFingerprint: String, isNewSession: Boolean = false): Boolean

    /**
     * Wykonuje hybrydowe uzgadnianie kluczy (X25519 + ML-KEM).
     * Łączy klasyczny shared secret z sekretem ML-KEM za pomocą HKDF-SHA256.
     * Zwraca parę: (wynikowy rootKey, opcjonalny kyberCiphertext do wysłania).
     */
    fun performHybridKeyAgreement(
        x25519SharedSecret: ByteArray,
        contactFingerprint: String,
        incomingKyberCiphertext: ByteArray? = null
    ): Pair<ByteArray, ByteArray?>


    // endregion

    // region === Szyfrowanie i deszyfrowanie wiadomości ===

    /**
     * Szyfruje wiadomość dla konkretnego kontaktu.
     */
    fun encryptMessage(plainText: ByteArray, contactId: String): ByteArray

    /**
     * Deszyfruje wiadomość od konkretnego kontaktu.
     */
    fun decryptMessage(cipherText: ByteArray, contactId: String): ByteArray

    // endregion

    // region === Podpisywanie wiadomości ===

    /**
     * Podpisuje wiadomość kluczem prywatnym urządzenia.
     */
    fun signMessage(message: ByteArray): ByteArray

    /**
     * Weryfikuje podpis wiadomości od kontaktu.
     */
    fun verifyMessageSignature(
        message: ByteArray,
        signature: ByteArray,
        contactFingerprint: String
    ): Boolean

    // endregion

    // region === Weryfikacja tożsamości przez QR Code ===

    /**
     * Generuje zawartość QR kodu do weryfikacji tożsamości.
     */
    fun generateIdentityQRContent(): String

    /**
     * Weryfikuje tożsamość kontaktu na podstawie zeskanowanego kodu QR.
     */
    fun verifyContactWithQR(scannedQrContent: String): Boolean

    /**
     * Wyodrębnia dane kontaktu ze zeskanowanego kodu QR.
     * Zwraca dane kontaktu, jeśli weryfikacja przebiegła pomyślnie, w przeciwnym razie null.
     */
    fun extractContactFromQR(scannedQrContent: String): VerifiedContactData?

    /**
     * Generuje unikalny fingerprint klucza publicznego dla nowego kontaktu.
     */
    fun generateContactFingerprint(): String

    /**
     * Wypisuje i usuwa stan sesji (RatchetState) z pamięci oraz bazy danych.
     */
    fun deleteSession(contactFingerprint: String): Boolean

    // endregion
}
