package com.github.pandeiro.tempore.slsk.core.gateway

import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.security.MessageDigest
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import okhttp3.OkHttpClient

/**
 * TLS identity rules for the slskd client.
 *
 * Strict pin mode (PR review issue #1): when a pin is configured, the TLS identity of
 * slskd is *only* the pin. There is no fallback to system CAs and no independent hostname
 * check: any non-matching — or expired/invalid — chain fails the handshake instead of
 * silently degrading to "whatever certificate the system trusts", which would have sent
 * the API key to a MITM holding a valid certificate for *any* hostname. The hostname
 * verifier is bypassed on this pinned client only, because the pin *is* the identity and
 * the configured address may be a LAN IP with a self-signed leaf.
 *
 * Without a pin the builder must not call [apply] at all: the platform default trust and
 * OkHttp's default (real) hostname verification stay in effect.
 */
internal object SlskTls {

    fun applyStrictPinning(builder: OkHttpClient.Builder, pinnedCertSha256: String) {
        val context = SSLContext.getInstance("TLS")
        context.init(null, arrayOf<TrustManager>(strictPinTrustManager(pinnedCertSha256)), SecureRandom())
        builder.sslSocketFactory(context.socketFactory, trustManager(pinnedCertSha256))
        builder.hostnameVerifier(HostnameVerifier { _, _ -> true })
    }

    fun trustManager(pinnedCertSha256: String): X509TrustManager = strictPinTrustManager(pinnedCertSha256)

    private fun strictPinTrustManager(pinnedCertSha256: String): X509TrustManager {
        val normalized = pinnedCertSha256.lowercase().replace(":", "")
        val system = defaultTrustManager()
        return object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = system.acceptedIssuers

            // The client never presents a client certificate; passthrough if ever asked.
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
                system.checkClientTrusted(chain, authType)

            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                checkPinned(chain, normalized)
            }
        }
    }

    /** Validity first (a pin never overrides expiry), then an exact fingerprint match. */
    internal fun checkPinned(chain: Array<X509Certificate>, normalizedPin: String) {
        if (chain.isEmpty()) throw CertificateException("empty server certificate chain")
        try {
            for (cert in chain) cert.checkValidity()
        } catch (e: CertificateException) {
            throw CertificateException("pinned certificate not currently valid: ${e.message}", e)
        }
        for (cert in chain) {
            val digest = MessageDigest.getInstance("SHA-256").digest(cert.encoded)
            if (digest.joinToString("") { "%02x".format(it) } == normalizedPin) return
        }
        throw CertificateException(
            "server certificate does not match the pinned fingerprint — " +
                "rejecting handshake instead of sending the API key"
        )
    }

    private fun defaultTrustManager(): X509TrustManager {
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(null as java.security.KeyStore?)
        return factory.trustManagers.filterIsInstance<X509TrustManager>().first()
    }
}
