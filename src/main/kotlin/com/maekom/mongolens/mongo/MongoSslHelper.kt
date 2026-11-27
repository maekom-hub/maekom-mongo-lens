package com.maekom.mongolens.mongo

import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.security.KeyFactory
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import javax.net.ssl.KeyManager
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Builds custom TLS/SSL `SSLContext` instances for MongoDB & AWS DocumentDB:
 * - Multi-cert CA PEM bundles (e.g. AWS DocumentDB `global-bundle.pem`) loaded via `generateCertificates(inputStream)`
 *   with unique aliases `ca-cert-0`, `ca-cert-1`, ...
 * - CA JKS / PKCS12 stores
 * - Client X.509 certificates (`.p12` / `.pfx` / `.jks` or combined `.pem` with PKCS#8 or PKCS#1 RSA private key)
 * - Optional `tlsAllowInvalidCertificates` permissive TrustManager
 */
object MongoSslHelper {

    fun resolveCertPath(rawPath: String?, projectBasePath: Path?): Path? {
        val trimmed = rawPath?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        val expanded = if (trimmed.startsWith("~/") || trimmed.startsWith("~\\")) {
            val home = System.getProperty("user.home").orEmpty()
            home + trimmed.substring(1)
        } else {
            trimmed
        }
        val path = Paths.get(expanded)
        return if (path.isAbsolute || projectBasePath == null) {
            path.normalize()
        } else {
            projectBasePath.resolve(path).normalize()
        }
    }

    fun needsCustomSslContext(
        caFilePath: String?,
        clientCertFilePath: String?,
        allowInvalidCerts: Boolean
    ): Boolean {
        return !caFilePath.isNullOrBlank() || !clientCertFilePath.isNullOrBlank() || allowInvalidCerts
    }

    fun buildSslContext(
        caFilePath: String?,
        clientCertFilePath: String?,
        allowInvalidCerts: Boolean,
        projectBasePath: Path?
    ): SSLContext {
        val trustManagers: Array<TrustManager>? = when {
            allowInvalidCerts -> arrayOf(createTrustAllManager())
            !caFilePath.isNullOrBlank() -> {
                val resolvedCa = resolveCertPath(caFilePath, projectBasePath)
                    ?: throw IllegalArgumentException("Invalid CA certificate path: $caFilePath")
                if (!Files.isRegularFile(resolvedCa)) {
                    throw IllegalArgumentException("CA certificate file not found: $resolvedCa")
                }
                buildTrustManagersFromFile(resolvedCa)
            }
            else -> null
        }

        val keyManagers: Array<KeyManager>? = if (!clientCertFilePath.isNullOrBlank()) {
            val resolvedClient = resolveCertPath(clientCertFilePath, projectBasePath)
                ?: throw IllegalArgumentException("Invalid client certificate path: $clientCertFilePath")
            if (!Files.isRegularFile(resolvedClient)) {
                throw IllegalArgumentException("Client certificate file not found: $resolvedClient")
            }
            buildKeyManagersFromFile(resolvedClient)
        } else {
            null
        }

        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(keyManagers, trustManagers, SecureRandom())
        return sslContext
    }

    private fun buildTrustManagersFromFile(caFile: Path): Array<TrustManager> {
        val lowerName = caFile.fileName.toString().lowercase()
        val keyStore = when {
            lowerName.endsWith(".jks") -> loadKeyStore(caFile, "JKS")
            lowerName.endsWith(".p12") || lowerName.endsWith(".pfx") -> loadKeyStore(caFile, "PKCS12")
            else -> loadMultiPemCaKeyStore(caFile)
        }
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(keyStore)
        return tmf.trustManagers
    }

    private fun loadMultiPemCaKeyStore(pemFile: Path): KeyStore {
        val cf = CertificateFactory.getInstance("X.509")
        val certs: Collection<Certificate> = Files.newInputStream(pemFile).use { input ->
            cf.generateCertificates(input)
        }
        if (certs.isEmpty()) {
            throw IllegalArgumentException("No X.509 certificates found in CA file: $pemFile")
        }
        val ks = KeyStore.getInstance(KeyStore.getDefaultType())
        ks.load(null, null)
        certs.forEachIndexed { index, cert ->
            ks.setCertificateEntry("ca-cert-$index", cert)
        }
        return ks
    }

    private fun buildKeyManagersFromFile(clientFile: Path): Array<KeyManager> {
        val lowerName = clientFile.fileName.toString().lowercase()
        val keyStore = when {
            lowerName.endsWith(".p12") || lowerName.endsWith(".pfx") -> loadKeyStore(clientFile, "PKCS12")
            lowerName.endsWith(".jks") -> loadKeyStore(clientFile, "JKS")
            else -> loadCombinedPemClientKeyStore(clientFile)
        }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(keyStore, charArrayOf())
        return kmf.keyManagers
    }

    private fun loadKeyStore(file: Path, type: String): KeyStore {
        val ks = KeyStore.getInstance(type)
        Files.newInputStream(file).use { input ->
            ks.load(input, charArrayOf())
        }
        return ks
    }

    private fun loadCombinedPemClientKeyStore(pemFile: Path): KeyStore {
        val pemText = Files.readString(pemFile, StandardCharsets.UTF_8)
        val cf = CertificateFactory.getInstance("X.509")
        val certRegex = Regex("-----BEGIN CERTIFICATE-----[\\s\\S]*?-----END CERTIFICATE-----")
        val certChain = certRegex.findAll(pemText).mapNotNull { m ->
            cf.generateCertificate(ByteArrayInputStream(m.value.toByteArray(StandardCharsets.UTF_8)))
        }.toList()

        if (certChain.isEmpty()) {
            throw IllegalArgumentException("No certificate block found in client PEM file: $pemFile")
        }

        val privateKey = parsePemPrivateKey(pemText)
            ?: throw IllegalArgumentException("No unencrypted PKCS#8 or RSA private key block found in client PEM file: $pemFile")

        val ks = KeyStore.getInstance("PKCS12")
        ks.load(null, charArrayOf())
        ks.setKeyEntry("client-key", privateKey, charArrayOf(), certChain.toTypedArray())
        return ks
    }

    private fun parsePemPrivateKey(pemText: String): PrivateKey? {
        val pkcs8Regex = Regex("-----BEGIN PRIVATE KEY-----([\\s\\S]*?)-----END PRIVATE KEY-----")
        pkcs8Regex.find(pemText)?.let { match ->
            val der = Base64.getMimeDecoder().decode(match.groupValues[1].trim())
            return decodePkcs8PrivateKey(der)
        }

        val rsaRegex = Regex("-----BEGIN RSA PRIVATE KEY-----([\\s\\S]*?)-----END RSA PRIVATE KEY-----")
        rsaRegex.find(pemText)?.let { match ->
            val pkcs1Der = Base64.getMimeDecoder().decode(match.groupValues[1].trim())
            val pkcs8Der = wrapPkcs1RsaKeyInPkcs8(pkcs1Der)
            return KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(pkcs8Der))
        }

        return null
    }

    private fun decodePkcs8PrivateKey(der: ByteArray): PrivateKey {
        val spec = PKCS8EncodedKeySpec(der)
        val algorithms = listOf("RSA", "EC", "Ed25519")
        var lastErr: Throwable? = null
        for (alg in algorithms) {
            try {
                return KeyFactory.getInstance(alg).generatePrivate(spec)
            } catch (t: Throwable) {
                lastErr = t
            }
        }
        throw IllegalArgumentException("Unsupported private key algorithm in PEM", lastErr)
    }

    /**
     * Wraps a PKCS#1 RSAPrivateKey DER byte array in a PKCS#8 PrivateKeyInfo ASN.1 SEQUENCE (pure JDK, zero extra deps).
     */
    private fun wrapPkcs1RsaKeyInPkcs8(pkcs1Bytes: ByteArray): ByteArray {
        val rsaAlgId = byteArrayOf(
            0x30, 0x0D, // SEQUENCE (13 bytes)
            0x06, 0x09, 0x2A, 0x86.toByte(), 0x48, 0x86.toByte(), 0xF7.toByte(), 0x0D, 0x01, 0x01, 0x01, // OID 1.2.840.113549.1.1.1 (rsaEncryption)
            0x05, 0x00 // NULL
        )
        val version = byteArrayOf(0x02, 0x01, 0x00) // INTEGER 0
        val octetHeader = derTagAndLength(0x04, pkcs1Bytes.size)
        val innerLen = version.size + rsaAlgId.size + octetHeader.size + pkcs1Bytes.size
        val seqHeader = derTagAndLength(0x30, innerLen)

        val out = ByteArray(seqHeader.size + innerLen)
        var pos = 0
        System.arraycopy(seqHeader, 0, out, pos, seqHeader.size); pos += seqHeader.size
        System.arraycopy(version, 0, out, pos, version.size); pos += version.size
        System.arraycopy(rsaAlgId, 0, out, pos, rsaAlgId.size); pos += rsaAlgId.size
        System.arraycopy(octetHeader, 0, out, pos, octetHeader.size); pos += octetHeader.size
        System.arraycopy(pkcs1Bytes, 0, out, pos, pkcs1Bytes.size)
        return out
    }

    private fun derTagAndLength(tag: Int, length: Int): ByteArray {
        return when {
            length < 128 -> byteArrayOf(tag.toByte(), length.toByte())
            length < 256 -> byteArrayOf(tag.toByte(), 0x81.toByte(), length.toByte())
            else -> byteArrayOf(tag.toByte(), 0x82.toByte(), (length shr 8).toByte(), (length and 0xFF).toByte())
        }
    }

    private fun createTrustAllManager(): X509TrustManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
}
