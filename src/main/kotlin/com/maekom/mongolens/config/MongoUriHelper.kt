package com.maekom.mongolens.config

import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

data class ParsedMongoUri(
    val scheme: String = "mongodb", // "mongodb" or "mongodb+srv"
    val hosts: List<String> = listOf("localhost:27017"),
    val database: String = "",
    val username: String = "",
    val password: String = "",
    val authSource: String = "",
    val authMechanism: String = "",
    val replicaSet: String = "",
    val directConnection: Boolean = false,
    val tls: Boolean = false,
    val tlsAllowInvalidHostnames: Boolean = false,
    val tlsAllowInvalidCertificates: Boolean = false,
    val readPreference: String = "",
    val retryWrites: Boolean = true,
    val connectTimeoutMs: String = "",
    val socketTimeoutMs: String = "",
    val appName: String = "",
    val extraParams: LinkedHashMap<String, String> = linkedMapOf()
)

object MongoUriHelper {
    private val KNOWN_QUERY_KEYS = setOf(
        "authsource",
        "authmechanism",
        "replicaset",
        "directconnection",
        "tls",
        "ssl",
        "tlsallowinvalidhostnames",
        "sslinvalidhostnameallowed",
        "tlsallowinvalidcertificates",
        "tlsinsecure",
        "readpreference",
        "retrywrites",
        "connecttimeoutms",
        "sockettimeoutms",
        "appname"
    )

    fun encodeComponent(raw: String): String {
        return URLEncoder.encode(raw, StandardCharsets.UTF_8)
            .replace("+", "%20")
    }

    /**
     * Safely decodes percent-encoded URI segments without turning literal `+` in passwords into spaces.
     */
    fun decodeComponent(encoded: String): String {
        return try {
            URLDecoder.decode(encoded.replace("+", "%2B"), StandardCharsets.UTF_8)
        } catch (_: Throwable) {
            encoded
        }
    }

    /**
     * Parses a MongoDB URI (`mongodb://` or `mongodb+srv://`) into structured fields,
     * preserving ALL unrecognized query parameters in `extraParams`.
     * Returns `null` safely if the URI is incomplete/malformed during live typing.
     */
    fun parseUri(rawUri: String?): ParsedMongoUri? {
        val trimmed = rawUri?.trim().orEmpty()
        val scheme: String
        val rest: String
        when {
            trimmed.startsWith("mongodb+srv://", ignoreCase = true) -> {
                scheme = "mongodb+srv"
                rest = trimmed.substring("mongodb+srv://".length)
            }
            trimmed.startsWith("mongodb://", ignoreCase = true) -> {
                scheme = "mongodb"
                rest = trimmed.substring("mongodb://".length)
            }
            else -> return null
        }

        if (rest.isBlank()) return null

        val querySplitIdx = rest.indexOf('?')
        val authorityAndPath = if (querySplitIdx >= 0) rest.substring(0, querySplitIdx) else rest
        val queryStr = if (querySplitIdx >= 0) rest.substring(querySplitIdx + 1) else ""

        // Split authority and /database
        val slashIdx = authorityAndPath.indexOf('/')
        val authority = if (slashIdx >= 0) authorityAndPath.substring(0, slashIdx) else authorityAndPath
        val database = if (slashIdx >= 0) decodeComponent(authorityAndPath.substring(slashIdx + 1).trim()) else ""

        // Split credentials and hosts at the LAST '@' (safe even if user typed unencoded '@' while editing)
        val atIdx = authority.lastIndexOf('@')
        val userInfo = if (atIdx >= 0) authority.substring(0, atIdx) else ""
        val hostsPart = if (atIdx >= 0) authority.substring(atIdx + 1) else authority

        if (hostsPart.isBlank()) return null

        val username: String
        val password: String
        if (userInfo.isNotEmpty()) {
            val colonIdx = userInfo.indexOf(':')
            if (colonIdx >= 0) {
                username = decodeComponent(userInfo.substring(0, colonIdx))
                password = decodeComponent(userInfo.substring(colonIdx + 1))
            } else {
                username = decodeComponent(userInfo)
                password = ""
            }
        } else {
            username = ""
            password = ""
        }

        val rawHosts = hostsPart.split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (rawHosts.isEmpty()) return null

        val normalizedHosts = if (scheme == "mongodb+srv") {
            listOf(stripPort(rawHosts.first()))
        } else {
            rawHosts
        }

        var authSource = ""
        var authMechanism = ""
        var replicaSet = ""
        var directConnection = false
        var tls = false
        var tlsAllowInvalidHostnames = false
        var tlsAllowInvalidCertificates = false
        var readPreference = ""
        var retryWrites = true
        var connectTimeoutMs = ""
        var socketTimeoutMs = ""
        var appName = ""
        val extraParams = linkedMapOf<String, String>()

        if (queryStr.isNotBlank()) {
            for (pair in queryStr.split('&')) {
                if (pair.isBlank()) continue
                val eqIdx = pair.indexOf('=')
                val key = if (eqIdx >= 0) decodeComponent(pair.substring(0, eqIdx).trim()) else decodeComponent(pair.trim())
                val value = if (eqIdx >= 0) decodeComponent(pair.substring(eqIdx + 1).trim()) else ""
                if (key.isEmpty()) continue

                when (key.lowercase()) {
                    "authsource" -> authSource = value
                    "authmechanism" -> authMechanism = value
                    "replicaset" -> if (scheme != "mongodb+srv") replicaSet = value
                    "directconnection" -> if (scheme != "mongodb+srv") directConnection = value.equals("true", ignoreCase = true)
                    "tls", "ssl" -> tls = value.equals("true", ignoreCase = true)
                    "tlsallowinvalidhostnames", "sslinvalidhostnameallowed" ->
                        tlsAllowInvalidHostnames = value.equals("true", ignoreCase = true)
                    "tlsallowinvalidcertificates" ->
                        tlsAllowInvalidCertificates = value.equals("true", ignoreCase = true)
                    "tlsinsecure" -> {
                        if (value.equals("true", ignoreCase = true)) {
                            tlsAllowInvalidHostnames = true
                            tlsAllowInvalidCertificates = true
                        }
                    }
                    "readpreference" -> readPreference = value
                    "retrywrites" -> retryWrites = !value.equals("false", ignoreCase = true)
                    "connecttimeoutms" -> connectTimeoutMs = value
                    "sockettimeoutms" -> socketTimeoutMs = value
                    "appname" -> appName = value
                    else -> extraParams[key] = value
                }
            }
        }

        return ParsedMongoUri(
            scheme = scheme,
            hosts = normalizedHosts,
            database = database,
            username = username,
            password = password,
            authSource = authSource,
            authMechanism = authMechanism,
            replicaSet = replicaSet,
            directConnection = directConnection,
            tls = tls,
            tlsAllowInvalidHostnames = tlsAllowInvalidHostnames,
            tlsAllowInvalidCertificates = tlsAllowInvalidCertificates,
            readPreference = readPreference,
            retryWrites = retryWrites,
            connectTimeoutMs = connectTimeoutMs,
            socketTimeoutMs = socketTimeoutMs,
            appName = appName,
            extraParams = extraParams
        )
    }

    /**
     * Assembles a valid RFC-3986 MongoDB connection string from structured options,
     * applying URL encoding to credentials, `mongodb+srv` port/flag rules, and preserving `extraParams`.
     */
    fun buildUri(parsed: ParsedMongoUri): String {
        val isSrv = parsed.scheme.equals("mongodb+srv", ignoreCase = true)
        val scheme = if (isSrv) "mongodb+srv" else "mongodb"
        val sb = StringBuilder(scheme).append("://")

        val user = parsed.username.trim()
        val pass = parsed.password
        if (user.isNotEmpty()) {
            sb.append(encodeComponent(user))
            if (pass.isNotEmpty()) {
                sb.append(':').append(encodeComponent(pass))
            }
            sb.append('@')
        }

        val cleanHosts = parsed.hosts.map { it.trim() }.filter { it.isNotEmpty() }
            .ifEmpty { listOf(if (isSrv) "cluster.example.com" else "localhost:27017") }
        val hostsText = if (isSrv) {
            stripPort(cleanHosts.first())
        } else {
            cleanHosts.joinToString(",")
        }
        sb.append(hostsText)

        val queryPairs = linkedMapOf<String, String>()
        if (parsed.authSource.isNotBlank()) {
            queryPairs["authSource"] = parsed.authSource.trim()
        }
        if (parsed.authMechanism.isNotBlank() && !parsed.authMechanism.equals("None", ignoreCase = true)) {
            queryPairs["authMechanism"] = parsed.authMechanism.trim()
        }
        if (!isSrv) {
            if (parsed.replicaSet.isNotBlank()) {
                queryPairs["replicaSet"] = parsed.replicaSet.trim()
            }
            if (parsed.directConnection) {
                queryPairs["directConnection"] = "true"
            }
        }
        if (parsed.tls) {
            queryPairs["tls"] = "true"
        }
        if (parsed.tlsAllowInvalidHostnames) {
            queryPairs["tlsAllowInvalidHostnames"] = "true"
        }
        if (parsed.tlsAllowInvalidCertificates) {
            queryPairs["tlsAllowInvalidCertificates"] = "true"
        }
        if (parsed.readPreference.isNotBlank() && !parsed.readPreference.equals("default", ignoreCase = true)) {
            queryPairs["readPreference"] = parsed.readPreference.trim()
        }
        if (!parsed.retryWrites) {
            queryPairs["retryWrites"] = "false"
        }
        if (parsed.connectTimeoutMs.isNotBlank()) {
            queryPairs["connectTimeoutMS"] = parsed.connectTimeoutMs.trim()
        }
        if (parsed.socketTimeoutMs.isNotBlank()) {
            queryPairs["socketTimeoutMS"] = parsed.socketTimeoutMs.trim()
        }
        if (parsed.appName.isNotBlank()) {
            queryPairs["appName"] = parsed.appName.trim()
        }
        for ((k, v) in parsed.extraParams) {
            val cleanK = k.trim()
            if (cleanK.isNotEmpty() && cleanK.lowercase() !in KNOWN_QUERY_KEYS) {
                queryPairs[cleanK] = v.trim()
            }
        }

        val db = parsed.database.trim()
        if (db.isNotEmpty()) {
            sb.append('/').append(encodeComponent(db))
        } else if (queryPairs.isNotEmpty()) {
            sb.append('/')
        }

        if (queryPairs.isNotEmpty()) {
            sb.append('?')
            sb.append(queryPairs.entries.joinToString("&") { (k, v) ->
                "${encodeComponent(k)}=${encodeComponent(v)}"
            })
        }

        return sb.toString()
    }

    fun stripPort(hostEntry: String): String {
        val h = hostEntry.trim()
        if (h.startsWith("[")) {
            val closeIdx = h.indexOf(']')
            return if (closeIdx > 0) h.substring(0, closeIdx + 1) else h
        }
        return h.substringBefore(':')
    }

    fun maskPasswordInUri(rawUri: String): String {
        val parsed = parseUri(rawUri)
        if (parsed != null && parsed.username.isNotEmpty() && parsed.password.isNotEmpty()) {
            val rebuilt = buildUri(parsed.copy(password = "___MASK_PLACEHOLDER___"))
            return rebuilt.replace("___MASK_PLACEHOLDER___", "***")
        }
        return rawUri.replace(Regex("(mongodb(?:\\+srv)?://[^:@/]+):([^@/]+)@"), "$1:***@")
    }

    fun extractPasswordFromUri(rawUri: String?): String? {
        val parsed = parseUri(rawUri) ?: return null
        return parsed.password.takeIf { it.isNotEmpty() }
    }

    fun stripPasswordFromUri(rawUri: String?): String? {
        if (rawUri.isNullOrBlank()) return rawUri
        val parsed = parseUri(rawUri) ?: return rawUri
        if (parsed.password.isEmpty()) return rawUri
        return buildUri(parsed.copy(password = ""))
    }

    fun injectPasswordIntoUri(rawUri: String?, password: String?): String? {
        if (rawUri.isNullOrBlank() || password.isNullOrEmpty()) return rawUri
        val parsed = parseUri(rawUri) ?: return rawUri
        if (parsed.username.isEmpty() || parsed.password.isNotEmpty()) return rawUri
        return buildUri(parsed.copy(password = password))
    }
}
