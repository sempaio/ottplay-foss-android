package play.ott.nativeapp.core

import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.PublicKey
import java.security.interfaces.DSAParams
import java.security.interfaces.DSAPublicKey
import java.security.interfaces.ECPublicKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.DSAParameterSpec
import java.security.spec.ECFieldFp
import java.security.spec.ECParameterSpec
import java.security.spec.EllipticCurve
import javax.net.ssl.SSLPeerUnverifiedException
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TlsCertificateKeyPolicyTest {
    @Test fun `RSA strength uses exact modulus bits and a positive odd exponent`() {
        val generator = KeyPairGenerator.getInstance("RSA")
        generator.initialize(2048)
        val strong = generator.generateKeyPair().public as RSAPublicKey
        assertTrue(TlsCertificateKeyPolicy.isStrongKey(strong))
        for ((bits, exponent) in listOf(2047 to 65537L, 1024 to 65537L, 2048 to 0L, 2048 to 1L, 2048 to 2L, 2048 to -3L)) {
            val key = object : RSAPublicKey by strong {
                override fun getModulus() = BigInteger.ONE.shiftLeft(bits - 1).add(BigInteger.ONE)
                override fun getPublicExponent() = BigInteger.valueOf(exponent)
            }
            assertFalse(TlsCertificateKeyPolicy.isStrongKey(key), "bits=$bits exponent=$exponent")
        }
    }

    @Test fun `EC validates both field and subgroup order`() {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(256)
        val strong = generator.generateKeyPair().public as ECPublicKey
        assertTrue(TlsCertificateKeyPolicy.isStrongKey(strong))
        for ((fieldBits, orderBits) in listOf(223 to 256, 256 to 223)) {
            val params = strong.params
            val curve = EllipticCurve(ECFieldFp(BigInteger.ONE.shiftLeft(fieldBits - 1).add(BigInteger.ONE)), BigInteger.ONE, BigInteger.ONE)
            val key = object : ECPublicKey by strong {
                override fun getParams() = ECParameterSpec(curve, params.generator, BigInteger.ONE.shiftLeft(orderBits - 1), 1)
            }
            assertFalse(TlsCertificateKeyPolicy.isStrongKey(key), "field=$fieldBits order=$orderBits")
        }
    }

    @Test fun `DSA checks both prime and subgroup sizes`() {
        val generator = KeyPairGenerator.getInstance("DSA")
        generator.initialize(2048)
        val strong = generator.generateKeyPair().public as DSAPublicKey
        assertTrue(TlsCertificateKeyPolicy.isStrongKey(strong))
        for ((pBits, qBits) in listOf(1024 to 224, 2048 to 160)) {
            val key = object : DSAPublicKey by strong {
                override fun getParams(): DSAParams = DSAParameterSpec(BigInteger.ONE.shiftLeft(pBits - 1), BigInteger.ONE.shiftLeft(qBits - 1), BigInteger.TWO)
            }
            assertFalse(TlsCertificateKeyPolicy.isStrongKey(key), "p=$pBits q=$qBits")
        }
    }

    @Test fun `named JCA decoders distinguish real Ed25519 and Ed448 from algorithm labels`() {
        // Actual JDK 17 provider keys commonly report the generic algorithm name EdDSA.
        for (name in listOf("Ed25519", "Ed448")) {
            val actual = KeyPairGenerator.getInstance(name).generateKeyPair().public
            assertTrue(TlsCertificateKeyPolicy.isStrongKey(actual), "$name provider key")
            assertTrue(TlsCertificateKeyPolicy.isStrongKey(renamed(actual, name)), "$name named key")
            assertTrue(TlsCertificateKeyPolicy.isStrongKey(renamed(actual, "EdDSA")), "$name generic key")
            val other = if (name == "Ed25519") "Ed448" else "Ed25519"
            assertFalse(TlsCertificateKeyPolicy.isStrongKey(renamed(actual, other)), "$name bytes must not decode as $other")
        }
        val rsa = KeyPairGenerator.getInstance("RSA").generateKeyPair().public
        assertFalse(TlsCertificateKeyPolicy.isStrongKey(renamed(rsa, "EdDSA")))
        assertFalse(TlsCertificateKeyPolicy.isStrongKey(renamed(rsa, "unknown")))
        val malformed = object : PublicKey by renamed(rsa, "EdDSA") {
            override fun getEncoded() = byteArrayOf(1, 2, 3)
        }
        assertFalse(TlsCertificateKeyPolicy.isStrongKey(malformed))
    }

    @Test fun `an absent verified chain fails closed`() {
        assertFailsWith<SSLPeerUnverifiedException> { TlsCertificateKeyPolicy.requireStrongChain(emptyList()) }
    }

    private fun renamed(actual: PublicKey, name: String): PublicKey = object : PublicKey by actual {
        override fun getAlgorithm() = name
    }
}
