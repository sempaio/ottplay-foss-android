package play.ott.nativeapp.core

import java.math.BigInteger
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.PublicKey
import java.security.cert.Certificate
import java.security.cert.X509Certificate
import java.security.interfaces.DSAPublicKey
import java.security.interfaces.ECPublicKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.X509EncodedKeySpec
import javax.net.ssl.SSLPeerUnverifiedException
import okhttp3.Interceptor
import okhttp3.Response

/** Validate the platform-verified chain before sending an origin HTTP request. */
internal object TlsCertificateKeyPolicy : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.url.isHttps) {
            val certificates = chain.connection()?.handshake()?.peerCertificates.orEmpty()
            requireStrongChain(certificates)
        }
        return chain.proceed(request)
    }

    internal fun requireStrongChain(certificates: List<Certificate>) {
        if (certificates.isEmpty()) throw SSLPeerUnverifiedException("Missing verified TLS certificate chain")
        certificates.forEachIndexed { index, certificate ->
            val key = (certificate as? X509Certificate)?.publicKey
            if (key == null || !isStrongKey(key)) {
                throw SSLPeerUnverifiedException("TLS certificate ${index + 1} uses an unsupported or undersized key")
            }
        }
    }

    internal fun isStrongKey(key: PublicKey): Boolean = when (key) {
        is RSAPublicKey -> key.modulus.signum() > 0 && key.modulus.bitLength() >= 2048 &&
            key.modulus.testBit(0) && key.publicExponent >= BigInteger.valueOf(3) && key.publicExponent.testBit(0)
        is ECPublicKey -> key.params?.let { it.order.signum() > 0 && it.order.bitLength() >= 224 && it.curve.field.fieldSize >= 224 } == true
        is DSAPublicKey -> key.params?.let { it.p.signum() > 0 && it.q.signum() > 0 && it.p.bitLength() >= 2048 && it.q.bitLength() >= 224 } == true
        else -> strongEdwardsKey(key)
    }

    private fun strongEdwardsKey(key: PublicKey): Boolean {
        val algorithms = when (key.algorithm) {
            "Ed25519" -> listOf("Ed25519")
            "Ed448" -> listOf("Ed448")
            "EdDSA" -> listOf("Ed25519", "Ed448")
            else -> return false
        }
        val encoded = key.encoded ?: return false
        // Named JCA decoders validate the actual key form, including for generic EdDSA keys.
        return algorithms.any { algorithm ->
            try {
                KeyFactory.getInstance(algorithm).generatePublic(X509EncodedKeySpec(encoded))
                true
            } catch (_: GeneralSecurityException) {
                false
            }
        }
    }
}
