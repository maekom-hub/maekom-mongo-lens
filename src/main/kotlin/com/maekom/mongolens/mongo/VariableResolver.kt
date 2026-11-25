package com.maekom.mongolens.mongo

import org.bson.types.ObjectId
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

object VariableResolver {
    private val hex24Regex = Regex("^[0-9a-fA-F]{24}$")
    private val numberRegex = Regex("^-?(?:0|[1-9]\\d*)(?:\\.\\d+)?$")
    private val varTokenRegex = Regex("""\{\{\s*([$\w.-]+)\s*}}""")

    val BUILTIN_DYNAMIC_VARS: List<Pair<String, String>> = listOf(
        "\$now" to "Current UTC ISODate timestamp",
        "\$today" to "Today 00:00:00.000Z ISODate timestamp",
        "\$objectId" to "Generated 24-char ObjectId"
    )

    fun resolve(script: String, userVariables: Map<String, String>): String {
        if (!script.contains("{{")) return script

        val sb = StringBuilder(script.length + 32)
        var i = 0
        var inDoubleQuote = false
        var inSingleQuote = false

        while (i < script.length) {
            val c = script[i]
            if (inDoubleQuote) {
                if (c == '\\' && i + 1 < script.length) {
                    sb.append(c).append(script[i + 1])
                    i += 2
                    continue
                }
                if (c == '"') {
                    inDoubleQuote = false
                    sb.append(c)
                    i++
                    continue
                }
                val match = varTokenRegex.matchAt(script, i)
                if (match != null) {
                    val varName = match.groupValues[1]
                    sb.append(resolveInsideQuotes(varName, userVariables, match.value))
                    i = match.range.last + 1
                    continue
                }
                sb.append(c)
                i++
            } else if (inSingleQuote) {
                if (c == '\\' && i + 1 < script.length) {
                    sb.append(c).append(script[i + 1])
                    i += 2
                    continue
                }
                if (c == '\'') {
                    inSingleQuote = false
                    sb.append(c)
                    i++
                    continue
                }
                val match = varTokenRegex.matchAt(script, i)
                if (match != null) {
                    val varName = match.groupValues[1]
                    sb.append(resolveInsideQuotes(varName, userVariables, match.value))
                    i = match.range.last + 1
                    continue
                }
                sb.append(c)
                i++
            } else {
                if (c == '"') {
                    inDoubleQuote = true
                    sb.append(c)
                    i++
                    continue
                }
                if (c == '\'') {
                    inSingleQuote = true
                    sb.append(c)
                    i++
                    continue
                }
                val match = varTokenRegex.matchAt(script, i)
                if (match != null) {
                    val varName = match.groupValues[1]
                    sb.append(resolveOutsideQuotes(varName, userVariables, match.value))
                    i = match.range.last + 1
                    continue
                }
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }

    private fun resolveInsideQuotes(
        varName: String,
        userVariables: Map<String, String>,
        originalToken: String
    ): String {
        val rawValue = when (varName) {
            "\$now" -> DateTimeFormatter.ISO_INSTANT.format(Instant.now())
            "\$today" -> DateTimeFormatter.ISO_INSTANT.format(LocalDate.now(ZoneOffset.UTC).atStartOfDay().toInstant(ZoneOffset.UTC))
            "\$objectId" -> ObjectId().toHexString()
            else -> userVariables[varName] ?: return originalToken
        }
        return MongoShellNormalizer.escapeJsonString(rawValue)
    }

    private fun resolveOutsideQuotes(
        varName: String,
        userVariables: Map<String, String>,
        originalToken: String
    ): String {
        return when (varName) {
            "\$now" -> {
                val iso = DateTimeFormatter.ISO_INSTANT.format(Instant.now())
                """ISODate("$iso")"""
            }
            "\$today" -> {
                val iso = DateTimeFormatter.ISO_INSTANT.format(LocalDate.now(ZoneOffset.UTC).atStartOfDay().toInstant(ZoneOffset.UTC))
                """ISODate("$iso")"""
            }
            "\$objectId" -> {
                """ObjectId("${ObjectId().toHexString()}")"""
            }
            else -> {
                val raw = userVariables[varName] ?: return originalToken
                formatTypedLiteral(raw)
            }
        }
    }

    private fun formatTypedLiteral(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return "\"\""
        if (trimmed == "true" || trimmed == "false" || trimmed == "null") return trimmed
        if (hex24Regex.matches(trimmed)) return """ObjectId("$trimmed")"""
        if (numberRegex.matches(trimmed)) return trimmed
        if ((trimmed.startsWith("{") && trimmed.endsWith("}")) ||
            (trimmed.startsWith("[") && trimmed.endsWith("]")) ||
            trimmed.startsWith("ObjectId(") ||
            trimmed.startsWith("ISODate(") ||
            trimmed.startsWith("NumberLong(") ||
            trimmed.startsWith("NumberDecimal(")
        ) {
            return trimmed
        }
        return "\"${MongoShellNormalizer.escapeJsonString(raw)}\""
    }
}
