package com.maekom.mongolens.config

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

enum class ProfileSource {
    YAML,
    CUSTOM
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
    val readOnly: Boolean = isProductionProfile(profileName, sourceFilePath)
) {
    fun effectiveConnectionUri(): String {
        val trimmedUri = uri?.trim()?.takeIf { it.isNotEmpty() }
        if (trimmedUri != null) {
            if (!authDatabase.isNullOrBlank() && !trimmedUri.contains("authSource=", ignoreCase = true)) {
                val sep = if (trimmedUri.contains("?")) "&" else "?"
                val encodedAuth = URLEncoder.encode(authDatabase.trim(), StandardCharsets.UTF_8)
                return "$trimmedUri${sep}authSource=$encodedAuth"
            }
            return trimmedUri
        }

        val sb = StringBuilder("mongodb://")
        if (!username.isNullOrBlank()) {
            sb.append(URLEncoder.encode(username, StandardCharsets.UTF_8))
            if (!password.isNullOrEmpty()) {
                sb.append(":").append(URLEncoder.encode(password, StandardCharsets.UTF_8))
            }
            sb.append("@")
        }
        sb.append(host.ifBlank { "localhost" }).append(":").append(port)
        val db = database?.trim()?.takeIf { it.isNotEmpty() }
        if (db != null) {
            sb.append("/").append(db)
        }
        if (!authDatabase.isNullOrBlank()) {
            sb.append("?authSource=").append(URLEncoder.encode(authDatabase.trim(), StandardCharsets.UTF_8))
        }
        return sb.toString()
    }

    fun extractDefaultDatabase(): String? {
        if (!database.isNullOrBlank()) return database.trim()
        val u = uri?.trim() ?: return null
        val withoutScheme = u.removePrefix("mongodb+srv://").removePrefix("mongodb://")
        val slashIdx = withoutScheme.indexOf('/')
        if (slashIdx < 0) return null
        val afterSlash = withoutScheme.substring(slashIdx + 1)
        val dbPart = afterSlash.substringBefore('?').trim()
        return dbPart.takeIf { it.isNotEmpty() }
    }

    fun maskedLocation(): String {
        val raw = effectiveConnectionUri()
        return raw.replace(Regex("(mongodb(?:\\+srv)?://[^:@/]+):([^@/]+)@"), "$1:***@")
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
