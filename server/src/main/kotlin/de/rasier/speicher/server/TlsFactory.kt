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

    fun load(caFile: File, certFile: File, keyFile: File): Material {
        val caCerts = readCertificates(caFile)
        val clientCerts = readCertificates(certFile)
        val privateKey = readPrivateKey(keyFile)
        require(caCerts.isNotEmpty()) { "Keine CA in ${caFile.name}" }
        require(clientCerts.isNotEmpty()) { "Kein Client-Zertifikat in ${certFile.name}" }

        val trustStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            caCerts.forEachIndexed { i, c -> setCertificateEntry("ca$i", c) }
        }
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(trustStore) }

        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setKeyEntry("client", privateKey, CHARS, (clientCerts + caCerts).toTypedArray())
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
