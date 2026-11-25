package com.maekom.mongolens.config

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

class EnvResolver(projectBasePath: Path?) {
    private val dotEnvMap: Map<String, String> = loadDotEnv(projectBasePath)

    fun resolve(raw: String?): String? {
        if (raw == null) return null
        if (!raw.contains("\${")) return raw

        var result: String = raw
        var guard = 0
        while (result.contains("\${") && guard < 10) {
            guard++
            val resolvedOnce = resolveSinglePass(result)
            if (resolvedOnce == result) break
            result = resolvedOnce
        }
        return result
    }

    private fun resolveSinglePass(input: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < input.length) {
            if (input[i] == '$' && i + 1 < input.length && input[i + 1] == '{') {
                val endIdx = findMatchingBrace(input, i + 2)
                if (endIdx > i + 2) {
                    val expr = input.substring(i + 2, endIdx)
                    sb.append(evaluateExpression(expr))
                    i = endIdx + 1
                    continue
                }
            }
            sb.append(input[i])
            i++
        }
        return sb.toString()
    }

    private fun findMatchingBrace(s: String, start: Int): Int {
        var depth = 1
        var idx = start
        while (idx < s.length) {
            if (s[idx] == '$' && idx + 1 < s.length && s[idx + 1] == '{') {
                depth++
                idx += 2
                continue
            }
            if (s[idx] == '}') {
                depth--
                if (depth == 0) return idx
            }
            idx++
        }
        return -1
    }

    private fun evaluateExpression(expr: String): String {
        val colonIdx = expr.indexOf(':')
        if (colonIdx >= 0) {
            val varName = expr.substring(0, colonIdx).trim()
            val defaultVal = expr.substring(colonIdx + 1)
            // Prioritize default value after ':' per specification; fallback to .env / System.getenv if empty
            if (defaultVal.isNotEmpty()) {
                return defaultVal
            }
            return lookupEnv(varName) ?: ""
        }
        val varName = expr.trim()
        return lookupEnv(varName) ?: "\${$expr}"
    }

    private fun lookupEnv(varName: String): String? {
        return dotEnvMap[varName] ?: System.getenv(varName)
    }

    companion object {
        fun loadDotEnv(projectBasePath: Path?): Map<String, String> {
            if (projectBasePath == null) return emptyMap()
            val envFile = projectBasePath.resolve(".env")
            if (!Files.isRegularFile(envFile)) return emptyMap()

            val map = linkedMapOf<String, String>()
            try {
                Files.readAllLines(envFile, StandardCharsets.UTF_8).forEach { rawLine ->
                    val line = rawLine.trim()
                    if (line.isEmpty() || line.startsWith("#")) return@forEach
                    val clean = if (line.startsWith("export ")) line.removePrefix("export ").trim() else line
                    val eqIdx = clean.indexOf('=')
                    if (eqIdx <= 0) return@forEach
                    val key = clean.substring(0, eqIdx).trim()
                    var value = clean.substring(eqIdx + 1).trim()
                    if ((value.startsWith("\"") && value.endsWith("\"") && value.length >= 2) ||
                        (value.startsWith("'") && value.endsWith("'") && value.length >= 2)
                    ) {
                        value = value.substring(1, value.length - 1)
                    }
                    if (key.isNotEmpty()) {
                        map[key] = value
                    }
                }
            } catch (_: Throwable) {
                // Ignore unreadable .env
            }
            return map
        }
    }
}
