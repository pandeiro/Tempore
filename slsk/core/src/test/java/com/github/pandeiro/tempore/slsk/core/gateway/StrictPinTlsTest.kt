@file:Suppress("DEPRECATION")
package com.github.pandeiro.tempore.slsk.core.gateway

import java.security.MessageDigest
import java.security.cert.CertificateException
import java.io.IOException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Verifies the STRICT pin mode (PR review issue #1). The old behavior fell back to
 * system-trust on pin mismatch and disabled hostname verification entirely, so a MITM on
 * the network path with *any* CA-signed certificate could capture the API key even though
 * a pin was configured. These tests hold against a live TLS endpoint:
 *
 * 1. the pinned leaf is accepted with no hostname check (the LAN-IP slskd form);
 * 2. a different, CA-signed certificate must fail the handshake (strict — no system fallback);
 * 3. the check helper itself rejects a valid, well-formed but unpinned chain.
 */
class StrictPinTlsTest {

    private val server = MockWebServer()

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun sha256(cert: java.security.cert.X509Certificate): String =
        MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString("") { "%02x".format(it) }

    /** Serves a real TLS endpoint presenting `leaf` (with `chain` as intermediates). */
    private fun startHttps(leaf: HeldCertificate, vararg chain: HeldCertificate): MockWebServer {
        val serverCerts = HandshakeCertificates.Builder()
            .heldCertificate(leaf, *chain.map { it.certificate }.toTypedArray())
            .build()
        server.useHttps(serverCerts.sslSocketFactory(), false)
        server.enqueue(
            MockResponse().setBody(
                """{"version":{"full":"0.26.1.0"},"server":{"isConnected":true,"isLoggedIn":true}}"""
            )
        )
        server.start()
        return server
    }

    private fun pinnedClient(pinSha256: String): OkHttpClient {
        val builder = OkHttpClient.Builder()
        SlskTls.applyStrictPinning(builder, pinSha256)
        return builder.build()
    }

    @Test
    fun `pinned matching leaf is accepted without hostname verification`() {
        val leaf = HeldCertificate.Builder()
            .addSubjectAlternativeName("attacker.example") // hostname deliberately unrelated
            .build()
        val url = startHttps(leaf).url("/").toString()

        val response = pinnedClient(sha256(leaf.certificate))
            .newCall(Request.Builder().url(url).build()).execute()

        assertEquals(200, response.code)
        assertTrue(response.body!!.string().contains("0.26.1.0"))
        response.close()
    }

    @Test
    fun `mismatched pin on a different CA-signed chain rejects the handshake`() {
        val ca = HeldCertificate.Builder().certificateAuthority(1).build()
        val signedLeaf = HeldCertificate.Builder()
            .addSubjectAlternativeName("attacker.example")
            .signedBy(ca)
            .certificateAuthority(0)
            .build()
        val url = startHttps(signedLeaf, ca).url("/").toString()

        // Pin the fingerprint of an unrelated certificate: the served chain is a valid,
        // CA-signed x509 — the retired code fell back to system trust right here and a
        // MITM with any Let's Encrypt certificate captured the API key.
        val unrelated = HeldCertificate.Builder().build()
        try {
            pinnedClient(sha256(unrelated.certificate))
                .newCall(Request.Builder().url(url).build()).execute().use { }
            fail("expected the handshake to be rejected on pin mismatch")
        } catch (expected: SSLHandshakeException) {
        } catch (expected: SSLPeerUnverifiedException) {
        } catch (expected: IOException) {
            // OkHttp folds certain TLS failures into plain IOException; all mean a rejected handshake.
        }
    }

    @Test
    fun `strict manager rejects a valid chain whose pin does not match`() {
        val leaf = HeldCertificate.Builder()
            .addSubjectAlternativeName("attacker.example")
            .build()
        val tm = SlskTls.trustManager(sha256(leaf.certificate))
        val unrelated = HeldCertificate.Builder().build()
        try {
            tm.checkServerTrusted(arrayOf(unrelated()), "generic")
            fail("expected a pin-mismatch CertificateException")
        } catch (expected: CertificateException) {
            assertTrue(expected.message!!.contains("does not match the pinned"))
        }
    }

    private fun unrelated(): java.security.cert.X509Certificate {
        val held = HeldCertificate.Builder().build()
        return java.security.cert.CertificateFactory.getInstance("X.509")
            .generateCertificate(java.io.ByteArrayInputStream(held.certificate.encoded)) as java.security.cert.X509Certificate
    }
}
