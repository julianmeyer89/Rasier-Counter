package de.rasier.speicher.server

import org.bouncycastle.cert.X509CertificateHolder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.openssl.PEMKeyPair
import org.bouncycastle.openssl.PEMParser
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter
import org.bouncycastle.pkcs.PKCS8EncryptedPrivateKeyInfo
import java.io.File
import java.io.FileReader
import java.security.KeyStore
import java.security.PrivateKey
import java.security.PrivateKey as JPrivateKey
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.TrustManagerFactory
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo

/**
 * Builds the mutual-TLS material for the Marstek cloud broker from the PEM files that ship inside
 * the public hame-relay Docker image (ca.crt, client cert, client key). Handles both PKCS#1 and
 * PKCS#8 keys, RSA or EC, via BouncyCastle.
 */
object TlsFactory {
    private val converter = JcaPEMKeyConverter()
    private val certConverter = JcaX509CertificateConverter()

    data class Material(val keyManagerFactory: KeyManagerFactory, val trustManagerFactory: TrustManagerFactory)

    /**
     * Builds the TLS material. The broker (AWS IoT) presents a publicly trusted server certificate,
     * so server verification uses the JVM's default trust store (public CAs). We authenticate to the
     * broker with the Marstek client certificate.
     */
    @Suppress("UNUSED_PARAMETER")
    fun load(caFile: File, certFile: File, keyFile: File): Material {
        val clientCerts = readCertificates(certFile)
        val privateKey = readPrivateKey(keyFile)
        require(clientCerts.isNotEmpty()) { "Kein Client-Zertifikat in ${certFile.name}" }

        // Default trust managers: the normal set of public root CAs (includes Amazon's roots).
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(null as KeyStore?) }

        // Only the client certificate chain belongs here. Marstek's CA (caCerts) is a different,
        // unrelated chain, so appending it makes KeyStore reject the entry as "chain is not valid".
        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setKeyEntry("client", privateKey, CHARS, clientCerts.toTypedArray())
        }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(keyStore, CHARS) }

        return Material(kmf, tmf)
    }

    private fun readCertificates(file: File): List<X509Certificate> {
        val out = mutableListOf<X509Certificate>()
        PEMParser(FileReader(file)).use { parser ->
            while (true) {
                val obj = parser.readObject() ?: break
                if (obj is X509CertificateHolder) out += certConverter.getCertificate(obj)
            }
        }
        return out
    }

    private fun readPrivateKey(file: File): PrivateKey {
        PEMParser(FileReader(file)).use { parser ->
            while (true) {
                val obj = parser.readObject() ?: break
                val key: JPrivateKey? = when (obj) {
                    is PEMKeyPair -> converter.getKeyPair(obj).private
                    is PrivateKeyInfo -> converter.getPrivateKey(obj)
                    is PKCS8EncryptedPrivateKeyInfo -> throw IllegalArgumentException("Verschlüsselter Schlüssel wird nicht unterstützt")
                    else -> null
                }
                if (key != null) return key
            }
        }
        throw IllegalArgumentException("Kein privater Schlüssel in ${file.name}")
    }

    private val CHARS = CharArray(0)
}
