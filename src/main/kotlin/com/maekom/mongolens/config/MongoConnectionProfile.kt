package com.maekom.mongolens.config

import com.intellij.ui.JBColor
import com.maekom.mongolens.mongo.MongoSslHelper
import java.awt.Color
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.nio.file.Path

enum class ProfileSource {
    YAML,
    CUSTOM
}

enum class ConnectionColorTag(
    val lightBgHex: Int?,
    val darkBgHex: Int?,
    val lightFgHex: Int?,
    val darkFgHex: Int?,
    val i18nKey: String
) {
    NONE(null, null, null, null, "conn.color.none"),
    GREEN(0xE6F7ED, 0x1B3B2B, 0x0D7A3E, 0x52D98B, "conn.color.green"),
    ORANGE(0xFFF3E0, 0x4A2E14, 0xB85C00, 0xFFB74D, "conn.color.orange"),
    RED(0xFDECEC, 0x4A1D21, 0xC62828, 0xFF7B86, "conn.color.red");

    fun bgColor(): Color? = if (lightBgHex != null && darkBgHex != null) JBColor(Color(lightBgHex), Color(darkBgHex)) else null
    fun fgColor(): Color? = if (lightFgHex != null && darkFgHex != null) JBColor(Color(lightFgHex), Color(darkFgHex)) else null
}

data class MongoConnectionProfile(
    val id: String,
    val displayName: String,
    val profileName: String,
    val source: ProfileSource,
    val uri: String? = null,
    val host: String = "localhost",
    val port: Int = 27017,
    val database: String? = null,
    val username: String? = null,
    val password: String? = null,
    val authDatabase: String? = null,
    val sourceFilePath: String? = null,
    val moduleName: String? = null,
    val readOnly: Boolean = isProductionProfile(profileName, sourceFilePath),
    val colorTag: ConnectionColorTag = ConnectionColorTag.NONE,
    val tlsEnabled: Boolean = false,
    val tlsCaFilePath: String? = null,
    val tlsClientCertFilePath: String? = null,
    val tlsAllowInvalidHostnames: Boolean = false,
    val tlsAllowInvalidCertificates: Boolean = false,
    val connectTimeoutMs: Int? = null,
    val socketTimeoutMs: Int? = null,
    val retryWrites: Boolean? = null,
    val authMechanism: String? = null,
    val replicaSet: String? = null,
    val directConnection: Boolean = false,
    val readPreference: String? = null,
    val appName: String? = null
) {
    fun effectiveConnectionUri(): String {
        val trimmedUri = uri?.trim()?.takeIf { it.isNotEmpty() }
        if (trimmedUri != null) {
            var resolved = trimmedUri
            if (!authDatabase.isNullOrBlank() && !resolved.contains("authSource=", ignoreCase = true)) {
                val sep = if (resolved.contains("?")) "&" else "?"
                val encodedAuth = URLEncoder.encode(authDatabase.trim(), StandardCharsets.UTF_8)
                resolved = "$resolved${sep}authSource=$encodedAuth"
            }
            if (tlsEnabled && !resolved.contains("tls=", ignoreCase = true) && !resolved.contains("ssl=", ignoreCase = true)) {
                val sep = if (resolved.contains("?")) "&" else "?"
                resolved = "$resolved${sep}tls=true"
            }
            return resolved
        }

        val parsed = ParsedMongoUri(
            scheme = "mongodb",
            hosts = listOf("${host.ifBlank { "localhost" }}:$port"),
            database = database?.trim().orEmpty(),
            username = username?.trim().orEmpty(),
            password = password.orEmpty(),
            authSource = authDatabase?.trim().orEmpty(),
            authMechanism = authMechanism?.trim().orEmpty(),
            replicaSet = replicaSet?.trim().orEmpty(),
            directConnection = directConnection,
            tls = tlsEnabled || !tlsCaFilePath.isNullOrBlank() || !tlsClientCertFilePath.isNullOrBlank(),
            tlsAllowInvalidHostnames = tlsAllowInvalidHostnames,
            tlsAllowInvalidCertificates = tlsAllowInvalidCertificates,
            readPreference = readPreference?.trim().orEmpty(),
            retryWrites = retryWrites ?: true,
            connectTimeoutMs = connectTimeoutMs?.toString().orEmpty(),
            socketTimeoutMs = socketTimeoutMs?.toString().orEmpty(),
            appName = appName?.trim().orEmpty()
        )
        return MongoUriHelper.buildUri(parsed)
    }

    fun extractDefaultDatabase(): String? {
        if (!database.isNullOrBlank()) return database.trim()
        return MongoUriHelper.parseUri(uri)?.database?.takeIf { it.isNotBlank() }
    }

    fun maskedLocation(): String = MongoUriHelper.maskPasswordInUri(effectiveConnectionUri())

    fun effectiveTlsEnabled(): Boolean {
        if (tlsEnabled || !tlsCaFilePath.isNullOrBlank() || !tlsClientCertFilePath.isNullOrBlank()) return true
        return MongoUriHelper.parseUri(effectiveConnectionUri())?.tls == true
    }

    fun effectiveAllowInvalidHostnames(): Boolean {
        if (tlsAllowInvalidHostnames) return true
        return MongoUriHelper.parseUri(effectiveConnectionUri())?.tlsAllowInvalidHostnames == true
    }

    fun effectiveAllowInvalidCertificates(): Boolean {
        if (tlsAllowInvalidCertificates) return true
        return MongoUriHelper.parseUri(effectiveConnectionUri())?.tlsAllowInvalidCertificates == true
    }

    fun effectiveRetryWrites(): Boolean {
        if (retryWrites != null) return retryWrites
        return MongoUriHelper.parseUri(effectiveConnectionUri())?.retryWrites ?: true
    }

    /**
     * Fingerprint covering URI + TLS certificate files + timeout options so any edit immediately invalidates cached MongoClient.
     */
    fun connectionFingerprint(): String {
        return listOf(
            effectiveConnectionUri(),
            effectiveTlsEnabled().toString(),
            tlsCaFilePath.orEmpty(),
            tlsClientCertFilePath.orEmpty(),
            effectiveAllowInvalidHostnames().toString(),
            effectiveAllowInvalidCertificates().toString(),
            connectTimeoutMs?.toString().orEmpty(),
            socketTimeoutMs?.toString().orEmpty()
        ).joinToString("|")
    }

    /**
     * Builds a ready-to-run `mongosh` CLI command including TLS/CA/Client cert & DocumentDB flags.
     */
    fun buildMongoshCliCommand(projectBasePath: Path?): String {
        val fullUri = effectiveConnectionUri()
        val sb = StringBuilder("mongosh \"").append(fullUri.replace("\"", "\\\"")).append("\"")
        val hasCa = !tlsCaFilePath.isNullOrBlank()
        val hasClientCert = !tlsClientCertFilePath.isNullOrBlank()
        if (effectiveTlsEnabled() || hasCa || hasClientCert) {
            sb.append(" --tls")
        }
        if (hasCa) {
            val resolved = MongoSslHelper.resolveCertPath(tlsCaFilePath, projectBasePath)?.toString() ?: tlsCaFilePath!!.trim()
            sb.append(" --tlsCAFile \"").append(resolved.replace("\"", "\\\"")).append("\"")
        }
        if (hasClientCert) {
            val resolved = MongoSslHelper.resolveCertPath(tlsClientCertFilePath, projectBasePath)?.toString() ?: tlsClientCertFilePath!!.trim()
            sb.append(" --tlsCertificateKeyFile \"").append(resolved.replace("\"", "\\\"")).append("\"")
        }
        if (effectiveAllowInvalidHostnames()) {
            sb.append(" --tlsAllowInvalidHostnames")
        }
        if (effectiveAllowInvalidCertificates()) {
            sb.append(" --tlsAllowInvalidCertificates")
        }
        if (!effectiveRetryWrites()) {
            sb.append(" --retryWrites=false")
        }
        return sb.toString()
    }

    companion object {
        fun isProductionProfile(profileName: String?, sourcePath: String?): Boolean {
            val p = (profileName ?: "").lowercase()
            val f = (sourcePath ?: "").substringAfterLast('/').substringAfterLast('\\').lowercase()
            return p.contains("prod") || p.contains("prd") || f.contains("prod") || f.contains("prd")
        }
    }
}

data class SavedQuery(
    val id: String,
    val name: String,
    val profileId: String,
    val database: String,
    val collection: String,
    val mode: String, // "FIND", "AGGREGATION", or "SHELL"
    val filter: String = "{}",
    val projection: String = "{}",
    val sort: String = "{}",
    val limit: Int = 50,
    val skip: Int = 0,
    val pipelineJson: String = "[]",
    val shellScript: String = "",
    val isHistory: Boolean = false,
    val timestamp: Long = System.currentTimeMillis()
)
