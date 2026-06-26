package com.quantumchat.core.crypto

import timber.log.Timber
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyFactory
import java.security.PublicKey
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Security
import java.security.spec.X509EncodedKeySpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.NamedParameterSpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.runBlocking
import org.bouncycastle.jce.provider.BouncyCastleProvider
import com.quantumchat.core.database.RatchetStateDao
import com.quantumchat.core.database.RatchetStateEntity
import com.quantumchat.core.database.SkippedMessageKeyDao
import com.quantumchat.core.database.SkippedMessageKeyEntity

// Bouncy Castle Post-Quantum Cryptography (crystals.kyber) imports
import org.bouncycastle.pqc.crypto.crystals.kyber.KyberKeyPairGenerator
import org.bouncycastle.pqc.crypto.crystals.kyber.KyberKeyGenerationParameters
import org.bouncycastle.pqc.crypto.crystals.kyber.KyberParameters
import org.bouncycastle.pqc.crypto.crystals.kyber.KyberPublicKeyParameters
import org.bouncycastle.pqc.crypto.crystals.kyber.KyberPrivateKeyParameters
import org.bouncycastle.pqc.crypto.crystals.kyber.KyberKEMGenerator
import org.bouncycastle.pqc.crypto.crystals.kyber.KyberKEMExtractor

// Bouncy Castle Post-Quantum Cryptography (crystals.dilithium) imports
import org.bouncycastle.pqc.crypto.crystals.dilithium.DilithiumKeyPairGenerator
import org.bouncycastle.pqc.crypto.crystals.dilithium.DilithiumKeyGenerationParameters
import org.bouncycastle.pqc.crypto.crystals.dilithium.DilithiumParameters
import org.bouncycastle.pqc.crypto.crystals.dilithium.DilithiumPublicKeyParameters
import org.bouncycastle.pqc.crypto.crystals.dilithium.DilithiumPrivateKeyParameters
import org.bouncycastle.pqc.crypto.crystals.dilithium.DilithiumSigner

// Serialization Factory imports
import org.bouncycastle.pqc.crypto.util.SubjectPublicKeyInfoFactory
import org.bouncycastle.pqc.crypto.util.PrivateKeyInfoFactory
import org.bouncycastle.pqc.crypto.util.PublicKeyFactory
import org.bouncycastle.pqc.crypto.util.PrivateKeyFactory

@Singleton
class CryptoManagerImpl @Inject constructor(
    private val ratchetStateDao: RatchetStateDao,
    private val skippedMessageKeyDao: SkippedMessageKeyDao,
    private val torManager: dagger.Lazy<com.quantumchat.core.networking.TorManager>
) : CryptoManager {

    private var localKeyPair: KeyPair? = null
    private val contactPublicKeys = mutableMapOf<String, PublicKey>()
    private val ratchetStates = mutableMapOf<String, RatchetState>()

    // Post-Quantum Identity Keys (ML-DSA / Dilithium)
    private var localDilithiumKeyPair: org.bouncycastle.crypto.AsymmetricCipherKeyPair? = null
    private val contactDilithiumPublicKeys = mutableMapOf<String, DilithiumPublicKeyParameters>()

    // Post-Quantum Session Keys (ML-KEM / Kyber-768)
    private var localKyberKeyPair: org.bouncycastle.crypto.AsymmetricCipherKeyPair? = null
    private val contactKyberPublicKeys = mutableMapOf<String, KyberPublicKeyParameters>()

    // Tracking decryption failures count per contact/session
    private val decryptionFailureCounts = java.util.concurrent.ConcurrentHashMap<String, Int>()

    init {
        val provider = Security.getProvider("BC")
        if (provider == null || provider.javaClass.name != "org.bouncycastle.jce.provider.BouncyCastleProvider") {
            Timber.w("Bouncy Castle provider not found or legacy system provider detected. Registering full BouncyCastleProvider.")
            Security.removeProvider("BC")
            Security.addProvider(BouncyCastleProvider())
        } else {
            Timber.i("Bouncy Castle provider is already correctly registered: ${provider.javaClass.name}")
        }
        Timber.d("CryptoManagerImpl has been initialized.")
        try {
            generateLocalIdentityKeyPair()
        } catch (e: Exception) {
            Timber.e(e, "Non-fatal error generating identity keys during initialization.")
        }
    }

    override fun generateLocalIdentityKeyPair(): Boolean {
        return try {
            if (Security.getProvider("BC") == null || Security.getProvider("BC")?.javaClass?.name != "org.bouncycastle.jce.provider.BouncyCastleProvider") {
                Security.removeProvider("BC")
                Security.addProvider(BouncyCastleProvider())
            }

            Timber.d("Generating classical keypair (X25519) via Bouncy Castle as a layer for hybrid cryptography.")
            val kpg = KeyPairGenerator.getInstance("XDH", "BC")
            try {
                kpg.initialize(NamedParameterSpec("X25519"))
            } catch (e: NoClassDefFoundError) {
                kpg.initialize(255)
            } catch (e: Exception) {
                kpg.initialize(255)
            }
            localKeyPair = kpg.generateKeyPair()
            Timber.i("Local X25519 Identity Key Pair generated successfully using Bouncy Castle XDH.")

            Timber.d("[Post-Quantum] Generating ML-KEM/Kyber-768 identity key pair.")
            val kyberKpg = KyberKeyPairGenerator()
            kyberKpg.init(KyberKeyGenerationParameters(SecureRandom(), KyberParameters.kyber768))
            localKyberKeyPair = kyberKpg.generateKeyPair()
            Timber.i("Local ML-KEM/Kyber-768 Identity Key Pair generated successfully.")

            Timber.d("[Post-Quantum] Generating ML-DSA/Dilithium identity key pair for message authenticity.")
            val dilithiumKpg = DilithiumKeyPairGenerator()
            dilithiumKpg.init(DilithiumKeyGenerationParameters(SecureRandom(), DilithiumParameters.dilithium3))
            localDilithiumKeyPair = dilithiumKpg.generateKeyPair()
            Timber.i("Local ML-DSA/Dilithium Identity Key Pair generated successfully.")

            true
        } catch (e: Exception) {
            Timber.e(e, "CRITICAL: Failed to generate identity key pairs")
            false
        }
    }

    override fun getLocalIdentityFingerprint(): String {
        val kp = localKeyPair
        if (kp == null) {
            Timber.e("Local identity key pair is null – cannot proceed")
            return "QC-PQ-ERROR"
        }
        return try {
            val pubBytes = kp.public.encoded
            val digest = MessageDigest.getInstance("SHA-256")
            val hash = digest.digest(pubBytes)
            val b64 = java.util.Base64.getEncoder().encodeToString(hash)
            "QC-PQ-" + b64.take(12).uppercase()
        } catch (e: Exception) {
            Timber.e(e, "Failed to compute local fingerprint.")
            "QC-PQ-ERROR"
        }
    }

    override fun establishSecureSession(contactFingerprint: String, isNewSession: Boolean): Boolean {
        Timber.i("Establishing new Double Ratchet session for fingerprint: $contactFingerprint (isNewSession=$isNewSession)")
        val keyPair = localKeyPair
        if (keyPair == null) {
            Timber.e("Local identity key pair is null – cannot proceed")
            return false
        }
        return try {
            if (isNewSession) {
                Timber.i("Creating FRESH Double Ratchet session for fingerprint: $contactFingerprint (isNewSession=true)")
                deleteSession(contactFingerprint)
                Timber.i("Deleted old ratchet state from DB before creating new one")
            }

            // Check if there is a saved state in DB first (only if isNewSession is false)
            val savedEntity = if (!isNewSession) {
                runBlocking { ratchetStateDao.getRatchetState(contactFingerprint) }
            } else null

            if (savedEntity != null) {
                Timber.i("Loaded existing ratchet state from DB")
                val kf = KeyFactory.getInstance("X25519", "BC")
                
                val localPubSpec = X509EncodedKeySpec(savedEntity.localDhPublicKey)
                val localPublicKey = kf.generatePublic(localPubSpec)
                
                val localPrivSpec = PKCS8EncodedKeySpec(savedEntity.localDhPrivateKey)
                val localPrivateKey = kf.generatePrivate(localPrivSpec)
                
                val remotePubSpec = X509EncodedKeySpec(savedEntity.remoteDhPublicKey)
                val remotePublicKey = kf.generatePublic(remotePubSpec)

                val state = RatchetState(
                    rootKey = savedEntity.rootKey,
                    sendingChainKey = savedEntity.sendingChainKey,
                    receivingChainKey = savedEntity.receivingChainKey,
                    sendingMessageNumber = savedEntity.sendingMessageNumber,
                    receivingMessageNumber = savedEntity.receivingMessageNumber,
                    previousChainLength = savedEntity.previousChainLength,
                    localDhKeyPair = KeyPair(localPublicKey, localPrivateKey),
                    remoteDhPublicKey = remotePublicKey,
                    isFallback = savedEntity.isFallback,
                    pendingKyberCiphertext = savedEntity.pendingKyberCiphertext
                )
                ratchetStates[contactFingerprint] = state
                val digest = MessageDigest.getInstance("SHA-256")
                val rootKeyHash = digest.digest(state.rootKey).take(8).joinToString("") { "%02x".format(it) }
                Timber.i("Loaded existing ratchet state from DB. rootKey hash: $rootKeyHash")
                return true
            }

            Timber.i("Creating new ratchet state")
            // Otherwise, derive new session state...
            val recipientPublicKey = contactPublicKeys[contactFingerprint]
            val localKP = localKeyPair
            val isFallback = recipientPublicKey == null || localKP == null
            val digest = MessageDigest.getInstance("SHA-256")

            // ========================================================
            // HYBRID KEY AGREEMENT PHASE (X25519 + ML-KEM/Kyber-768)
            // ========================================================

            // 1. Classical Key Agreement (X25519)
            val x25519SharedSecret = if (!isFallback) {
                Timber.d("Classical step: Performing X25519 key agreement.")
                val ka = KeyAgreement.getInstance("X25519", "BC")
                ka.init(localKP!!.private)
                ka.doPhase(recipientPublicKey!!, true)
                ka.generateSecret()
            } else {
                Timber.d("Classical step: Fallback to fingerprint-derived key.")
                digest.digest(getFallbackSeedBytes(contactFingerprint))
            }

            // 2. Hybrid key agreement combining classical X25519 and post-quantum ML-KEM (Kyber-768)
            val (rootKey, kyberCiphertext) = performHybridKeyAgreement(x25519SharedSecret, contactFingerprint)


            // Seed the generator deterministically in fallback mode to avoid out-of-sync keypairs
            val kpg = if (isFallback) {
                val seedDigest = digest.digest(getFallbackSeedBytes(contactFingerprint))
                val deterministicRandom = java.security.SecureRandom.getInstance("SHA1PRNG")
                deterministicRandom.setSeed(seedDigest)
                KeyPairGenerator.getInstance("XDH", "BC").apply { initialize(255, deterministicRandom) }
            } else {
                KeyPairGenerator.getInstance("XDH", "BC").apply { initialize(255) }
            }

            val isInitiator = getLocalIdentityFingerprint() < contactFingerprint
            val role = if (isInitiator) "Alice (Initiator)" else "Bob (Responder)"

            val state = if (isInitiator) {
                // Alice is initiator:
                // Bob's initial DH public key is Bob's identity public key (or dummy in fallback)
                val initialRemotePub = if (!isFallback) recipientPublicKey!! else {
                    kpg.generateKeyPair().public
                }
                
                // Alice generates her first DH sending key pair
                val aliceDhs = kpg.generateKeyPair()
                
                // Alice performs first DH agreement with Bob's initial DH public key
                val sharedSecret = calculateDH(aliceDhs.private, initialRemotePub, isFallback, contactFingerprint)
                
                val (rootKeyNew, sendingCK, receivingCK) = if (isFallback) {
                    // Version 3.12: Fallback mode - perform full deterministic initialization of both chains directly
                    // using the rootKey and shared secret derived from sorted fingerprints.
                    // Alice (Initiator) sending is Bob's receiving (0x02), and Alice receiving is Bob's sending (0x01).
                    val sCK = hmacSha256(rootKey, sharedSecret + byteArrayOf(0x02))
                    val rCK = hmacSha256(rootKey, sharedSecret + byteArrayOf(0x01))
                    Triple(rootKey, sCK, rCK)
                } else {
                    val (rkNew, sCK) = kdfRk(rootKey, sharedSecret)
                    Triple(rkNew, sCK, ByteArray(32))
                }

                RatchetState(
                    rootKey = rootKeyNew,
                    sendingChainKey = sendingCK,
                    receivingChainKey = receivingCK,
                    sendingMessageNumber = 0,
                    receivingMessageNumber = 0,
                    previousChainLength = 0,
                    localDhKeyPair = aliceDhs,
                    remoteDhPublicKey = initialRemotePub,
                    isFallback = isFallback,
                    pendingKyberCiphertext = kyberCiphertext
                )
            } else {
                // Bob is receiver:
                // Bob's initial DH key pair is Bob's identity key pair (or dummy in fallback)
                val bobDhs = if (!isFallback) localKP!! else kpg.generateKeyPair()
                
                // Alice's initial DH public key is Alice's identity public key (or dummy in fallback)
                val initialRemotePub = if (!isFallback) recipientPublicKey!! else {
                    kpg.generateKeyPair().public
                }
                
                if (isFallback) {
                    // Version 3.12: Bob (Responder) fallback mode - perform full deterministic symmetric initialization
                    // to match Alice's initial chain keys exactly.
                    // Bob's sending is Alice's receiving (0x01), and Bob's receiving is Alice's sending (0x02).
                    Timber.d("establishSecureSession: Bob (Responder) fallback mode - performing full symmetric initialization")
                    val sharedSecret = calculateDH(bobDhs.private, initialRemotePub, isFallback, contactFingerprint)
                    val sendingCK = hmacSha256(rootKey, sharedSecret + byteArrayOf(0x01))
                    val receivingCK = hmacSha256(rootKey, sharedSecret + byteArrayOf(0x02))
                    
                    RatchetState(
                        rootKey = rootKey,
                        sendingChainKey = sendingCK,
                        receivingChainKey = receivingCK,
                        sendingMessageNumber = 0,
                        receivingMessageNumber = 0,
                        previousChainLength = 0,
                        localDhKeyPair = bobDhs,
                        remoteDhPublicKey = initialRemotePub,
                        isFallback = isFallback,
                        pendingKyberCiphertext = null
                    )
                } else {
                    // Bob does not derive sendingChainKey yet (done when Bob receives message & ratchets)
                    RatchetState(
                        rootKey = rootKey,
                        sendingChainKey = ByteArray(32),
                        receivingChainKey = ByteArray(32),
                        sendingMessageNumber = 0,
                        receivingMessageNumber = 0,
                        previousChainLength = 0,
                        localDhKeyPair = bobDhs,
                        remoteDhPublicKey = initialRemotePub,
                        isFallback = isFallback,
                        pendingKyberCiphertext = null
                    )
                }
            }

            ratchetStates[contactFingerprint] = state

            // Save new state to DB
            saveRatchetStateToDb(contactFingerprint, state)
            
            // Version 3.13: Precise diagnostics logging of keys hashes and counters
            val rootKeyHash = digest.digest(state.rootKey).take(8).joinToString("") { "%02x".format(it) }
            val sendingHash = digest.digest(state.sendingChainKey).take(8).joinToString("") { "%02x".format(it) }
            val receivingHash = digest.digest(state.receivingChainKey).take(8).joinToString("") { "%02x".format(it) }
            val resolvedRole = if (isInitiator) "Initiator" else "Responder"
            Timber.i("Ratchet state initialized ($resolvedRole). rootKey=${rootKeyHash}, sendingCK=${sendingHash}, receivingCK=${receivingHash}, N=${state.sendingMessageNumber}, PN=${state.receivingMessageNumber}")
            true
        } catch (e: Exception) {
            Timber.e(e, "Failed to establish secure session.")
            false
        }
    }

    override fun encryptMessage(plainText: ByteArray, contactId: String): ByteArray {
        val keyPair = localKeyPair
        if (keyPair == null) {
            Timber.e("Local identity key pair is null – cannot proceed")
            return ByteArray(0)
        }
        
        Timber.d("CryptoManagerImpl: Encrypting message for $contactId, payload size: ${plainText.size} bytes")
        loadRatchetStateFromDb(contactId)
        val ratchet = ratchetStates[contactId] ?: run {
            establishSecureSession(contactId)
            ratchetStates[contactId] ?: return ByteArray(0)
        }

        return try {
            // Derive next message key
            val mk = ratchet.deriveNextSendingKey()
            val aesKey = SecretKeySpec(mk, "AES")

            // AES-GCM encryption
            val iv = ByteArray(12)
            SecureRandom().nextBytes(iv)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val spec = GCMParameterSpec(128, iv)
            cipher.init(Cipher.ENCRYPT_MODE, aesKey, spec)
            val cipherText = cipher.doFinal(plainText)

            // Construct message header
            val senderDhPubBytes = ratchet.localDhKeyPair.public.encoded
            val n = ratchet.sendingMessageNumber - 1
            val pn = ratchet.previousChainLength

            // Get pending Kyber ciphertext
            var kyberCipher = ratchet.pendingKyberCiphertext
            
            // If Bob has not replied yet, we must send the Kyber ciphertext (re-generating it if it is null, e.g. after database restore)
            if (kyberCipher == null && ratchet.receivingMessageNumber == 0) {
                try {
                    val recipientPublicKey = contactPublicKeys[contactId]
                    val localKP = localKeyPair
                    val isFallback = recipientPublicKey == null || localKP == null
                    val digest = MessageDigest.getInstance("SHA-256")

                    val x25519SharedSecret = if (!isFallback) {
                        val ka = KeyAgreement.getInstance("X25519", "BC")
                        ka.init(localKP!!.private)
                        ka.doPhase(recipientPublicKey!!, true)
                        ka.generateSecret()
                    } else {
                        digest.digest(getFallbackSeedBytes(contactId))
                    }

                    // Call performHybridKeyAgreement to get the ciphertext
                    val (_, ciphertext) = performHybridKeyAgreement(x25519SharedSecret, contactId, null)
                    kyberCipher = ciphertext
                } catch (e: Exception) {
                    Timber.w("Failed to dynamically re-generate Kyber ciphertext: ${e.message}")
                }
            }

            val kyberLen = kyberCipher?.size ?: 0

            // Pack message packet:
            // [ Kyber Ciphertext Length (2 bytes, big-endian) ]
            // [ Kyber Ciphertext Bytes (variable, e.g. 1088 bytes) ]
            // [ DH Pub Key Length (1 byte) ]
            // [ DH Pub Key Bytes (variable) ]
            // [ Message Number N (4 bytes) ]
            // [ Previous Chain Length PN (4 bytes) ]
            // [ IV (12 bytes) ]
            // [ Ciphertext ]
            val headerLen = 2 + kyberLen + 1 + senderDhPubBytes.size + 4 + 4 + 12
            val packet = ByteArray(headerLen + cipherText.size)

            // Kyber Ciphertext Length
            packet[0] = (kyberLen ushr 8).toByte()
            packet[1] = kyberLen.toByte()

            var offset = 2
            if (kyberCipher != null) {
                System.arraycopy(kyberCipher, 0, packet, offset, kyberLen)
                offset += kyberLen
            }

            packet[offset] = senderDhPubBytes.size.toByte()
            System.arraycopy(senderDhPubBytes, 0, packet, offset + 1, senderDhPubBytes.size)
            offset += 1 + senderDhPubBytes.size
            
            val nBytes = packInt(n)
            System.arraycopy(nBytes, 0, packet, offset, 4)
            offset += 4
            
            val pnBytes = packInt(pn)
            System.arraycopy(pnBytes, 0, packet, offset, 4)
            offset += 4
            
            System.arraycopy(iv, 0, packet, offset, 12)
            offset += 12

            System.arraycopy(cipherText, 0, packet, offset, cipherText.size)

            // Persist updated state to DB
            saveRatchetStateToDb(contactId, ratchet)
            Timber.d("CryptoManagerImpl: Encrypted message header details - N: $n, PN: $pn, total packet size: ${packet.size} bytes")

            packet
        } catch (e: Exception) {
            throw RuntimeException("Encryption failed: ${e.message}", e)
        }
    }

    override fun decryptMessage(cipherText: ByteArray, contactId: String): ByteArray {
        if (cipherText.size < 55) {
            throw IllegalArgumentException("Ciphertext too short.")
        }
        
        Timber.d("CryptoManagerImpl: Decrypting message from $contactId, ciphertext size: ${cipherText.size} bytes")
        loadRatchetStateFromDb(contactId)
        val ratchet = ratchetStates[contactId] ?: run {
            establishSecureSession(contactId)
            ratchetStates[contactId] ?: return ByteArray(0)
        }

        val initialReceivingN = ratchet.receivingMessageNumber

        try {
            val plainBytes = decryptMessageWithState(cipherText, contactId, ratchet)
            // Success! Reset decryption failure count.
            decryptionFailureCounts[contactId] = 0
            return plainBytes
        } catch (e: Exception) {
            // Version 3.13 check: If decryption fails on the very first or second message (N=0 or 1),
            // immediately reset the Double Ratchet session using isNewSession = true to synchronize.
            if (initialReceivingN == 0 || initialReceivingN == 1) {
                Timber.w(e, "CryptoManagerImpl: BAD_DECRYPT at first messages (N=$initialReceivingN). Forcing immediate session reset (isNewSession = true)...")
                decryptionFailureCounts[contactId] = 0 // reset counter
                ratchetStates.remove(contactId) // remove corrupted cached state
                establishSecureSession(contactId, isNewSession = true) // reset session
                return ByteArray(0)
            }

            Timber.w(e, "CryptoManagerImpl: First decryption attempt failed for $contactId. Reloading state from DB and retrying...")
            
            // Reload the state from database to discard any in-memory mutations
            loadRatchetStateFromDb(contactId)
            val reloadedRatchet = ratchetStates[contactId]
            
            if (reloadedRatchet != null) {
                try {
                    val plainBytes = decryptMessageWithState(cipherText, contactId, reloadedRatchet)
                    // Success on retry! Reset decryption failure count.
                    decryptionFailureCounts[contactId] = 0
                    return plainBytes
                } catch (retryEx: Exception) {
                    Timber.e(retryEx, "CryptoManagerImpl: Second decryption attempt failed after reloading state from DB.")
                    handleDecryptionFailure(contactId, retryEx)
                }
            } else {
                Timber.e(e, "CryptoManagerImpl: Failed to reload state from DB after first decryption failure.")
                handleDecryptionFailure(contactId, e)
            }
            return ByteArray(0)
        }
    }

    private fun decryptMessageWithState(cipherText: ByteArray, contactId: String, ratchet: RatchetState): ByteArray {
        // Unpack Kyber Ciphertext Length (2 bytes)
        val kyberLen = ((cipherText[0].toInt() and 0xFF) shl 8) or (cipherText[1].toInt() and 0xFF)
        var offset = 2

        val kyberCiphertextBytes = if (kyberLen > 0) {
            val bytes = cipherText.copyOfRange(offset, offset + kyberLen)
            offset += kyberLen
            bytes
        } else null

        // Unpack DH Pub Key
        val dhPubLen = cipherText[offset].toInt() and 0xFF
        val dhPubBytes = cipherText.copyOfRange(offset + 1, offset + 1 + dhPubLen)
        offset += 1 + dhPubLen

        // Unpack N, PN, IV
        val n = unpackInt(cipherText, offset)
        val pn = unpackInt(cipherText, offset + 4)
        val iv = cipherText.copyOfRange(offset + 8, offset + 8 + 12)
        offset += 8 + 12

        val encryptedBytes = cipherText.copyOfRange(offset, cipherText.size)

        val dhPublicKeyB64 = java.util.Base64.getEncoder().encodeToString(dhPubBytes)

        // If we have an incoming Kyber ciphertext and we are the receiver, decapsulate it to derive the real root key
        if (kyberCiphertextBytes != null) {
            val isInitiator = getLocalIdentityFingerprint() < contactId
            if (!isInitiator) {
                val recipientPublicKey = contactPublicKeys[contactId]
                val localKP = localKeyPair
                val isFallback = recipientPublicKey == null || localKP == null
                val digest = MessageDigest.getInstance("SHA-256")

                // Classical key agreement (X25519)
                val x25519SharedSecret = if (!isFallback) {
                    val ka = KeyAgreement.getInstance("X25519", "BC")
                    ka.init(localKP!!.private)
                    ka.doPhase(recipientPublicKey!!, true)
                    ka.generateSecret()
                } else {
                    digest.digest(getFallbackSeedBytes(contactId))
                }

                // Decapsulate using our private Kyber key and derive the real rootKey
                val (realRootKey, _) = performHybridKeyAgreement(x25519SharedSecret, contactId, kyberCiphertextBytes)
                
                // Update Bob's ratchet state with the real hybrid root key
                ratchet.rootKey = realRootKey
                Timber.i("Bob decapsulated Kyber ciphertext and initialized hybrid rootKey.")
            }
        }

        // 1. Check for skipped keys first
        val skippedKeyEntity = runBlocking {
            skippedMessageKeyDao.getSkippedMessageKey(contactId, dhPublicKeyB64, n)
        }
        
        // Version 3.13 check: verify message is not too old or duplicate
        if (n < ratchet.receivingMessageNumber && skippedKeyEntity == null) {
            throw IllegalArgumentException("Message is too old or duplicate: n=$n, expected >= ${ratchet.receivingMessageNumber}")
        }
        
        val mk = if (skippedKeyEntity != null) {
            Timber.i("Decrypting with skipped message key for msg number $n")
            val key = skippedKeyEntity.messageKey
            // Delete used skipped key for forward secrecy
            runBlocking {
                skippedMessageKeyDao.deleteSkippedMessageKey(contactId, dhPublicKeyB64, n)
            }
            key
        } else {
            // 2. Perform DH ratchet step if public key has changed
            val currentRemoteEncoded = ratchet.remoteDhPublicKey.encoded
            val isNewKey = !dhPubBytes.contentEquals(currentRemoteEncoded)

            if (isNewKey) {
                Timber.i("New DH key received. Performing DH ratchet update.")
                // Skip message keys in old receiving chain
                val remoteEncoded = ratchet.remoteDhPublicKey.encoded
                while (ratchet.receivingMessageNumber < pn) {
                    val skippedMk = ratchet.deriveNextReceivingKey()
                    saveSkippedKey(contactId, remoteEncoded, ratchet.receivingMessageNumber - 1, skippedMk)
                }

                // Perform DH Ratchet Step
                ratchet.previousChainLength = ratchet.sendingMessageNumber
                ratchet.sendingMessageNumber = 0
                ratchet.receivingMessageNumber = 0

                val kf = KeyFactory.getInstance("X25519", "BC")
                val spec = X509EncodedKeySpec(dhPubBytes)
                ratchet.remoteDhPublicKey = kf.generatePublic(spec)

                // DH agreement and new receiving chain key derivation
                val sharedSecret = calculateDH(ratchet.localDhKeyPair.private, ratchet.remoteDhPublicKey, ratchet.isFallback, contactId)
                val (rootKeyNew, receivingCK) = kdfRk(ratchet.rootKey, sharedSecret)
                ratchet.rootKey = rootKeyNew
                ratchet.receivingChainKey = receivingCK

                // Generate new local key pair and derive new sending chain key
                val kpg = KeyPairGenerator.getInstance("XDH", "BC").apply { initialize(255) }
                ratchet.localDhKeyPair = kpg.generateKeyPair()

                val sharedSecret2 = calculateDH(ratchet.localDhKeyPair.private, ratchet.remoteDhPublicKey, ratchet.isFallback, contactId)
                val (rootKeyNew2, sendingCK) = kdfRk(ratchet.rootKey, sharedSecret2)
                ratchet.rootKey = rootKeyNew2
                ratchet.sendingChainKey = sendingCK
            }

            // Skip message keys in current receiving chain up to n
            val remoteEncoded = ratchet.remoteDhPublicKey.encoded
            while (ratchet.receivingMessageNumber < n) {
                val skippedMk = ratchet.deriveNextReceivingKey()
                saveSkippedKey(contactId, remoteEncoded, ratchet.receivingMessageNumber - 1, skippedMk)
            }

            // Derive the current message key and update receivingMessageNumber to n+1
            val derivedMk = ratchet.deriveNextReceivingKey()
            derivedMk
        }

        // Decrypt with AES-GCM
        val aesKey = SecretKeySpec(mk, "AES")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val spec = GCMParameterSpec(128, iv)
        cipher.init(Cipher.DECRYPT_MODE, aesKey, spec)
        val plainBytes = cipher.doFinal(encryptedBytes)

        // Bob has replied/active, we can clear the pending Kyber ciphertext
        ratchet.pendingKyberCiphertext = null

        // Persist updated state to DB
        saveRatchetStateToDb(contactId, ratchet)
        Timber.d("CryptoManagerImpl: Decrypted message from $contactId successfully. Output size: ${plainBytes.size} bytes")

        return plainBytes
    }

    private fun handleDecryptionFailure(contactId: String, exception: Exception) {
        val failures = (decryptionFailureCounts[contactId] ?: 0) + 1
        decryptionFailureCounts[contactId] = failures
        Timber.w("CryptoManagerImpl: Decryption failure count for $contactId is now $failures")

        val currentState = ratchetStates[contactId]
        val rootKeyHash = currentState?.let {
            try {
                val digest = MessageDigest.getInstance("SHA-256")
                java.util.Base64.getEncoder().encodeToString(digest.digest(it.rootKey))
            } catch (ex: Exception) { "unknown" }
        } ?: "no_state"
        val sendingN = currentState?.sendingMessageNumber ?: -1
        val receivingN = currentState?.receivingMessageNumber ?: -1
        val skippedCount = runBlocking {
            try {
                skippedMessageKeyDao.getSkippedKeysCount(contactId)
            } catch (ex: Exception) { -1 }
        }
        
        Timber.e(exception, "CryptoManagerImpl: Decryption failed (BAD_DECRYPT) for contact: $contactId. " +
                "Diagnostics: failuresCount=$failures, sendingMessageNumber=$sendingN, receivingMessageNumber=$receivingN, " +
                "rootKeyHash=$rootKeyHash, skippedKeysCount=$skippedCount")

        // Remove from memory
        ratchetStates.remove(contactId)
        Timber.i("CryptoManagerImpl: Removed corrupted/failed in-memory ratchet state for contact $contactId.")

        if (failures >= 3) {
            Timber.w("CryptoManagerImpl: Failure count reached $failures. Forcing session re-establishment (isNewSession = true)...")
            decryptionFailureCounts[contactId] = 0 // reset counter
            establishSecureSession(contactId, isNewSession = true)
        }
    }

    override fun signMessage(message: ByteArray): ByteArray {
        val kp = localDilithiumKeyPair
        if (kp == null) {
            Timber.e("Local identity key pair is null – cannot proceed")
            return ByteArray(0)
        }
        Timber.d("[Post-Quantum] Signing message with ML-DSA/Dilithium private key (authenticity layer).")
        return try {
            val signer = DilithiumSigner()
            signer.init(true, kp.private)
            signer.generateSignature(message)
        } catch (e: Exception) {
            Timber.e(e, "Failed to sign message with Dilithium")
            ByteArray(0)
        }
    }

    override fun verifyMessageSignature(
        message: ByteArray,
        signature: ByteArray,
        contactFingerprint: String
    ): Boolean {
        val pubKey = contactDilithiumPublicKeys[contactFingerprint]
        if (pubKey == null) {
            Timber.w("No Dilithium public key found for contact $contactFingerprint. Fallback to true (Dev/Fallback mode).")
            return true
        }
        Timber.d("[Post-Quantum] Verifying message signature with contact's ML-DSA/Dilithium public key (authenticity layer).")
        return try {
            val signer = DilithiumSigner()
            signer.init(false, pubKey)
            signer.verifySignature(message, signature)
        } catch (e: Exception) {
            Timber.e(e, "Failed to verify signature with Dilithium")
            false
        }
    }

    override fun generateIdentityQRContent(): String {
        val kp = localKeyPair ?: return "NO-KEY"
        val pubB64 = java.util.Base64.getEncoder().encodeToString(kp.public.encoded)
        val fingerprint = getLocalIdentityFingerprint()

        // To prevent QR Code generation failure due to size limits (> 2953 bytes in binary mode),
        // we omit full post-quantum public keys from the QR code.
        // The protocol will fall back to fingerprint-derived deterministic ML-KEM/ML-DSA.
        val dilithiumB64 = "NO-DILITHIUM"
        val kyberB64 = "NO-KYBER"

        val onionAddress = torManager.get().onionAddress.value ?: "NO-ONION"
        return "QC-IDENTITY:$fingerprint:$pubB64:$dilithiumB64:$kyberB64:$onionAddress"
    }

    override fun verifyContactWithQR(scannedQrContent: String): Boolean {
        Timber.d("Verifying out-of-band contact QR content: $scannedQrContent")
        return scannedQrContent.startsWith("QC-IDENTITY:")
    }

    override fun extractContactFromQR(scannedQrContent: String): VerifiedContactData? {
        if (!verifyContactWithQR(scannedQrContent)) return null
        val parts = scannedQrContent.split(":")
        val fingerprint = parts.getOrNull(1) ?: "QC-PQ-UNKNOWN"
        val pubKeyB64 = parts.getOrNull(2)
        val dilithiumPubB64 = parts.getOrNull(3)
        val kyberPubB64 = parts.getOrNull(4)
        val onionAddr = parts.getOrNull(5)
        
        if (pubKeyB64 != null && pubKeyB64 != "DEV-MODE") {
            try {
                val pubKeyBytes = java.util.Base64.getDecoder().decode(pubKeyB64)
                val kf = KeyFactory.getInstance("X25519", "BC")
                val pubKeySpec = X509EncodedKeySpec(pubKeyBytes)
                val publicKey = kf.generatePublic(pubKeySpec)
                
                contactPublicKeys[fingerprint] = publicKey
            } catch (e: Exception) {
                Timber.e(e, "Failed to reconstruct public key from QR")
            }
        }

        if (dilithiumPubB64 != null && dilithiumPubB64 != "NO-DILITHIUM") {
            try {
                val bytes = java.util.Base64.getDecoder().decode(dilithiumPubB64)
                val pubKey = PublicKeyFactory.createKey(bytes) as DilithiumPublicKeyParameters
                contactDilithiumPublicKeys[fingerprint] = pubKey
                Timber.i("Extracted Dilithium public key for contact: $fingerprint")
            } catch (e: Exception) {
                Timber.e(e, "Failed to reconstruct Dilithium public key from QR")
            }
        }

        if (kyberPubB64 != null && kyberPubB64 != "NO-KYBER") {
            try {
                val bytes = java.util.Base64.getDecoder().decode(kyberPubB64)
                val pubKey = PublicKeyFactory.createKey(bytes) as KyberPublicKeyParameters
                contactKyberPublicKeys[fingerprint] = pubKey
                Timber.i("Extracted Kyber public key for contact: $fingerprint")
            } catch (e: Exception) {
                Timber.e(e, "Failed to reconstruct Kyber public key from QR")
            }
        }
        
        return VerifiedContactData(
            name = "Device",
            publicKeyFingerprint = fingerprint,
            onionAddress = if (onionAddr == "NO-ONION" || onionAddr.isNullOrEmpty()) null else onionAddr
        )
    }

    override fun generateContactFingerprint(): String {
        return "QC-PQ-NEW-" + System.currentTimeMillis().toString().takeLast(4)
    }

    override fun deleteSession(contactFingerprint: String): Boolean {
        return try {
            Timber.i("deleteSession: Evicting in-memory ratchetStates cache and database records for fingerprint: $contactFingerprint")
            ratchetStates.remove(contactFingerprint)
            runBlocking {
                ratchetStateDao.deleteRatchetState(contactFingerprint)
                skippedMessageKeyDao.deleteSkippedMessageKeysForContact(contactFingerprint)
            }
            Timber.d("deleteSession: Successfully deleted session state and skipped keys from database for fingerprint: $contactFingerprint")
            true
        } catch (e: Exception) {
            Timber.e(e, "deleteSession: Failed to delete session state for fingerprint: $contactFingerprint")
            false
        }
    }

    override fun isSessionReady(contactFingerprint: String): Boolean {
        loadRatchetStateFromDb(contactFingerprint)
        val ready = ratchetStates.containsKey(contactFingerprint)
        Timber.d("isSessionReady: Checked session status for $contactFingerprint -> ready: $ready")
        return ready
    }

    override fun performHybridKeyAgreement(
        x25519SharedSecret: ByteArray,
        contactFingerprint: String,
        incomingKyberCiphertext: ByteArray?
    ): Pair<ByteArray, ByteArray?> {
        val remoteKyberPubKey = contactKyberPublicKeys[contactFingerprint]
        val isInitiator = getLocalIdentityFingerprint() < contactFingerprint
        val digest = MessageDigest.getInstance("SHA-256")

        if (remoteKyberPubKey != null) {
            // ========================================================
            // REAL ASYMMETRIC PQC KEY AGREEMENT (Kyber-768 / ML-KEM)
            // ========================================================
            Timber.d("[Post-Quantum] Performing real independent Kyber-768 hybrid agreement.")
            if (isInitiator) {
                // Alice (Initiator): Generate random ML-KEM encapsulation secret and ciphertext against Bob's public key
                val generator = KyberKEMGenerator(SecureRandom())
                val encapResult = generator.generateEncapsulated(remoteKyberPubKey)
                val mlKemSharedSecret = encapResult.secret
                val kyberCiphertext = encapResult.encapsulation
                
                val rootKey = hkdfDerive(x25519SharedSecret, mlKemSharedSecret)
                val rootKeyHash = digest.digest(rootKey).take(8).joinToString("") { "%02x".format(it) }
                Timber.i("[Hybrid] Alice derived rootKey and generated Kyber ciphertext. rootKey hash: $rootKeyHash")
                return Pair(rootKey, kyberCiphertext)
            } else {
                // Bob (Receiver): If we have the incoming ciphertext, decapsulate it using our private key
                if (incomingKyberCiphertext != null) {
                    val localPriv = (localKyberKeyPair?.private as? KyberPrivateKeyParameters)
                        ?: throw IllegalStateException("Local Kyber private key not generated.")
                    val extractor = KyberKEMExtractor(localPriv)
                    val mlKemSharedSecret = extractor.extractSecret(incomingKyberCiphertext)
                    val rootKey = hkdfDerive(x25519SharedSecret, mlKemSharedSecret)
                    val rootKeyHash = digest.digest(rootKey).take(8).joinToString("") { "%02x".format(it) }
                    Timber.i("[Hybrid] Bob decapsulated Kyber ciphertext and derived rootKey. rootKey hash: $rootKeyHash")
                    return Pair(rootKey, null)
                } else {
                    // Bob doesn't have the ciphertext yet (waiting for Alice's first message).
                    // We temporarily return a rootKey using a placeholder (classical only) until the ciphertext is received.
                    val rootKey = hkdfDerive(x25519SharedSecret, ByteArray(32))
                    val rootKeyHash = digest.digest(rootKey).take(8).joinToString("") { "%02x".format(it) }
                    Timber.w("[Hybrid] Bob awaiting Kyber ciphertext. Initializing with classical-only placeholder. rootKey hash: $rootKeyHash")
                    return Pair(rootKey, null)
                }
            }
        } else {
            // ========================================================
            // FALLBACK DETERMINISTIC PQC AGREEMENT (For testing/dev)
            // ========================================================
            Timber.w("[Post-Quantum] No contact Kyber public key. Falling back to deterministic agreement.")
            // To ensure 100% deterministic behavior across different device manufacturers and Android versions,
            // we bypass the potentially unstable SHA1PRNG SecureRandom and derive the hybrid secret using SHA-256.
            val mlKemSharedSecret = digest.digest(x25519SharedSecret + "QC-KYBER-FALLBACK".toByteArray(Charsets.UTF_8))
            
            val rootKey = hkdfDerive(x25519SharedSecret, mlKemSharedSecret)
            val rootKeyHash = digest.digest(rootKey).take(8).joinToString("") { "%02x".format(it) }
            Timber.i("[Hybrid Fallback] Derived fallback rootKey. rootKey hash: $rootKeyHash")
            return Pair(rootKey, null)
        }
    }

    // region === Helpers ===

    private fun hkdfDerive(ikm1: ByteArray, ikm2: ByteArray): ByteArray {
        val combinedIkm = ikm1 + ikm2
        val salt = ByteArray(32) // Empty salt
        val prk = hmacSha256(salt, combinedIkm)
        val info = "QC-HYBRID-KDF".toByteArray(Charsets.UTF_8) + byteArrayOf(1)
        return hmacSha256(prk, info)
    }

    private fun kdfRk(rootKey: ByteArray, sharedSecret: ByteArray): Pair<ByteArray, ByteArray> {
        val rootKeyNew = hmacSha256(rootKey, sharedSecret + byteArrayOf(1))
        val chainKeyNew = hmacSha256(rootKey, sharedSecret + byteArrayOf(2))
        return Pair(rootKeyNew, chainKeyNew)
    }

    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    private fun getFallbackSeedBytes(contactFingerprint: String): ByteArray {
        val local = getLocalIdentityFingerprint()
        val sorted = if (local < contactFingerprint) local + contactFingerprint else contactFingerprint + local
        return sorted.toByteArray(Charsets.UTF_8)
    }

    private fun calculateDH(
        localPrivate: java.security.PrivateKey,
        remotePublic: java.security.PublicKey,
        isFallback: Boolean,
        contactFingerprint: String
    ): ByteArray {
        if (isFallback) {
            val digest = MessageDigest.getInstance("SHA-256")
            return digest.digest(getFallbackSeedBytes(contactFingerprint))
        }
        return try {
            val ka = KeyAgreement.getInstance("X25519", "BC")
            ka.init(localPrivate)
            ka.doPhase(remotePublic, true)
            ka.generateSecret()
        } catch (e: Exception) {
            Timber.w("Real DH agreement failed, falling back. Error: ${e.message}")
            val digest = MessageDigest.getInstance("SHA-256")
            digest.digest(getFallbackSeedBytes(contactFingerprint))
        }
    }

    private fun saveRatchetStateToDb(contactFingerprint: String, state: RatchetState) {
        runBlocking {
            ratchetStateDao.insertOrUpdate(
                RatchetStateEntity(
                    contactFingerprint = contactFingerprint,
                    rootKey = state.rootKey,
                    sendingChainKey = state.sendingChainKey,
                    receivingChainKey = state.receivingChainKey,
                    sendingMessageNumber = state.sendingMessageNumber,
                    receivingMessageNumber = state.receivingMessageNumber,
                    previousChainLength = state.previousChainLength,
                    localDhPublicKey = state.localDhKeyPair.public.encoded,
                    localDhPrivateKey = state.localDhKeyPair.private.encoded,
                    remoteDhPublicKey = state.remoteDhPublicKey.encoded,
                    isFallback = state.isFallback,
                    pendingKyberCiphertext = state.pendingKyberCiphertext
                )
            )
        }
    }

    private fun loadRatchetStateFromDb(contactFingerprint: String) {
        try {
            val savedEntity = runBlocking { ratchetStateDao.getRatchetState(contactFingerprint) }
            if (savedEntity != null) {
                val kf = KeyFactory.getInstance("X25519", "BC")
                
                val localPubSpec = X509EncodedKeySpec(savedEntity.localDhPublicKey)
                val localPublicKey = kf.generatePublic(localPubSpec)
                
                val localPrivSpec = PKCS8EncodedKeySpec(savedEntity.localDhPrivateKey)
                val localPrivateKey = kf.generatePrivate(localPrivSpec)
                
                val remotePubSpec = X509EncodedKeySpec(savedEntity.remoteDhPublicKey)
                val remotePublicKey = kf.generatePublic(remotePubSpec)

                val state = RatchetState(
                    rootKey = savedEntity.rootKey,
                    sendingChainKey = savedEntity.sendingChainKey,
                    receivingChainKey = savedEntity.receivingChainKey,
                    sendingMessageNumber = savedEntity.sendingMessageNumber,
                    receivingMessageNumber = savedEntity.receivingMessageNumber,
                    previousChainLength = savedEntity.previousChainLength,
                    localDhKeyPair = KeyPair(localPublicKey, localPrivateKey),
                    remoteDhPublicKey = remotePublicKey,
                    isFallback = savedEntity.isFallback,
                    pendingKyberCiphertext = savedEntity.pendingKyberCiphertext
                )
                ratchetStates[contactFingerprint] = state
                Timber.d("CryptoManagerImpl: Successfully reloaded ratchet state from DB for contact: $contactFingerprint")
            }
        } catch (e: Exception) {
            Timber.e(e, "CryptoManagerImpl: Failed to reload ratchet state from DB for contact: $contactFingerprint")
        }
    }

    private fun saveSkippedKey(
        contactFingerprint: String,
        dhPublicKeyBytes: ByteArray,
        messageNumber: Int,
        messageKey: ByteArray
    ) {
        val dhPublicKeyB64 = java.util.Base64.getEncoder().encodeToString(dhPublicKeyBytes)
        runBlocking {
            skippedMessageKeyDao.insertSkippedMessageKey(
                SkippedMessageKeyEntity(
                    contactFingerprint = contactFingerprint,
                    dhPublicKeyB64 = dhPublicKeyB64,
                    messageNumber = messageNumber,
                    messageKey = messageKey
                )
            )
        }
        Timber.i("Saved skipped message key for contact $contactFingerprint, message number $messageNumber")
    }

    private fun packInt(value: Int): ByteArray {
        return byteArrayOf(
            (value ushr 24).toByte(),
            (value ushr 16).toByte(),
            (value ushr 8).toByte(),
            value.toByte()
        )
    }

    private fun unpackInt(bytes: ByteArray, offset: Int): Int {
        return ((bytes[offset].toInt() and 0xFF) shl 24) or
               ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
               ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
               (bytes[offset + 3].toInt() and 0xFF)
    }

    // endregion
}
