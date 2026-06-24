package com.quantumchat.core.crypto

import org.junit.Test
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertArrayEquals
import org.bouncycastle.pqc.crypto.crystals.kyber.KyberKeyPairGenerator
import org.bouncycastle.pqc.crypto.crystals.kyber.KyberKeyGenerationParameters
import org.bouncycastle.pqc.crypto.crystals.kyber.KyberParameters
import org.bouncycastle.pqc.crypto.crystals.kyber.KyberPublicKeyParameters
import org.bouncycastle.pqc.crypto.crystals.kyber.KyberPrivateKeyParameters
import org.bouncycastle.pqc.crypto.crystals.kyber.KyberKEMGenerator
import org.bouncycastle.pqc.crypto.crystals.kyber.KyberKEMExtractor
import org.bouncycastle.pqc.crypto.util.SubjectPublicKeyInfoFactory
import org.bouncycastle.pqc.crypto.util.PrivateKeyInfoFactory
import org.bouncycastle.pqc.crypto.util.PublicKeyFactory
import org.bouncycastle.pqc.crypto.util.PrivateKeyFactory
import java.security.SecureRandom

class MldsaTest {
    @Test
    fun testKyberSerialization() {
        val generator = KyberKeyPairGenerator()
        generator.init(KyberKeyGenerationParameters(SecureRandom(), KyberParameters.kyber768))
        val keyPair = generator.generateKeyPair()
        
        val pub = keyPair.public as KyberPublicKeyParameters
        val priv = keyPair.private as KyberPrivateKeyParameters
        
        // Serialize
        val pubInfo = SubjectPublicKeyInfoFactory.createSubjectPublicKeyInfo(pub)
        val pubEncoded = pubInfo.encoded
        
        val privInfo = PrivateKeyInfoFactory.createPrivateKeyInfo(priv)
        val privEncoded = privInfo.encoded
        
        // Deserialize
        val pubRestored = PublicKeyFactory.createKey(pubEncoded) as KyberPublicKeyParameters
        val privRestored = PrivateKeyFactory.createKey(privEncoded) as KyberPrivateKeyParameters
        
        assertArrayEquals(pub.encoded, pubRestored.encoded)
        assertArrayEquals(priv.encoded, privRestored.encoded)
        
        // Test KEM with restored keys
        val generatorKEM = KyberKEMGenerator(SecureRandom())
        val encap = generatorKEM.generateEncapsulated(pubRestored)
        val sharedSecret1 = encap.secret
        val ciphertext = encap.encapsulation
        
        val extractorKEM = KyberKEMExtractor(privRestored)
        val sharedSecret2 = extractorKEM.extractSecret(ciphertext)
        
        assertArrayEquals(sharedSecret1, sharedSecret2)
    }
}
