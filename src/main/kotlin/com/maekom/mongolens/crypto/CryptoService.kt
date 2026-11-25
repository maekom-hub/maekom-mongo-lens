package com.maekom.mongolens.crypto

import com.maekom.mongolens.config.EnvResolver
import com.maekom.mongolens.i18n.I18n
import org.bson.BsonArray
import org.bson.BsonDocument
import org.bson.BsonString
import org.bson.BsonValue
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

enum class DecryptMode(val labelKey: String) {
    AES_CBC_IV_PREFIX("crypto.dialog.mode.cbcPrefix"),
    AES_CBC_FIXED_IV("crypto.dialog.mode.cbcFixed"),
    AES_ECB("crypto.dialog.mode.ecb");

    override fun toString(): String = I18n.t(labelKey)
}

enum class KeyEncoding(val label: String) {
    UTF8("UTF-8"),
    BASE64("Base64"),
    HEX("Hex");

    override fun toString(): String = label
}

data class ProfileCryptoConfig(
    val profileName: String,
    val prefix: String = "ENC-",
    val mode: DecryptMode = DecryptMode.AES_CBC_IV_PREFIX,
    val keyEncoding: KeyEncoding = KeyEncoding.UTF8,
    val fixedIv: String = "",
    val revealSeconds: Int = 10
) {
    fun clampedRevealSeconds(): Int = revealSeconds.coerceIn(5, 60)
}

data class YamlKeyCandidate(
    val sourceFileName: String,
    val yamlPath: String,
    val resolvedValue: String
) {
    override fun toString(): String {
        val masked = if (resolvedValue.length > 8) {
            resolvedValue.take(4) + "****" + resolvedValue.takeLast(2)
        } else {
            "****"
        }
        return "$sourceFileName → $yamlPath ($masked)"
    }
}

object CryptoService {

    data class RecursiveDecryptResult(
        val value: BsonValue,
        val decryptedCount: Int,
        val failedCount: Int,
        val firstError: Throwable?
    )

    fun isEncryptedCandidate(value: String, config: ProfileCryptoConfig): Boolean {
        val prefix = config.prefix
        if (prefix.isEmpty()) return false
        return value.startsWith(prefix) && value.length > prefix.length + 4
    }

    fun containsEncryptedCandidate(value: BsonValue, config: ProfileCryptoConfig): Boolean {
        if (config.prefix.isEmpty()) return false
        return when (value) {
            is BsonString -> isEncryptedCandidate(value.value, config)
            is BsonDocument -> value.values.any { containsEncryptedCandidate(it, config) }
            is BsonArray -> value.values.any { containsEncryptedCandidate(it, config) }
            else -> false
        }
    }

    fun decryptBsonValueRecursively(
        value: BsonValue,
        config: ProfileCryptoConfig,
        secretKey: String
    ): RecursiveDecryptResult {
        return when (value) {
            is BsonString -> {
                if (!isEncryptedCandidate(value.value, config)) {
                    RecursiveDecryptResult(value, 0, 0, null)
                } else {
                    val outcome = runCatching { decrypt(value.value, config, secretKey) }
                    outcome.fold(
                        onSuccess = { plain -> RecursiveDecryptResult(BsonString(plain), 1, 0, null) },
                        onFailure = { err -> RecursiveDecryptResult(value, 0, 1, err) }
                    )
                }
            }

            is BsonDocument -> {
                var decryptedCount = 0
                var failedCount = 0
                var firstError: Throwable? = null
                val rebuilt = BsonDocument()
                for ((k, v) in value) {
                    val child = decryptBsonValueRecursively(v, config, secretKey)
                    decryptedCount += child.decryptedCount
                    failedCount += child.failedCount
                    if (firstError == null && child.firstError != null) {
                        firstError = child.firstError
                    }
                    rebuilt[k] = child.value
                }
                RecursiveDecryptResult(
                    value = if (decryptedCount > 0) rebuilt else value,
                    decryptedCount = decryptedCount,
                    failedCount = failedCount,
                    firstError = firstError
                )
            }

            is BsonArray -> {
                var decryptedCount = 0
                var failedCount = 0
                var firstError: Throwable? = null
                val rebuilt = BsonArray()
                for (elem in value) {
                    val child = decryptBsonValueRecursively(elem, config, secretKey)
                    decryptedCount += child.decryptedCount
                    failedCount += child.failedCount
                    if (firstError == null && child.firstError != null) {
                        firstError = child.firstError
                    }
                    rebuilt.add(child.value)
                }
                RecursiveDecryptResult(
                    value = if (decryptedCount > 0) rebuilt else value,
                    decryptedCount = decryptedCount,
                    failedCount = failedCount,
                    firstError = firstError
                )
            }

            else -> RecursiveDecryptResult(value, 0, 0, null)
        }
    }

    fun decodeKeyBytes(rawKey: String, encoding: KeyEncoding): ByteArray {
        val trimmed = rawKey.trim()
        if (trimmed.isEmpty()) {
            throw IllegalArgumentException("Secret Key is empty.")
        }
        val bytes = when (encoding) {
            KeyEncoding.UTF8 -> trimmed.toByteArray(StandardCharsets.UTF_8)
            KeyEncoding.BASE64 -> Base64.getDecoder().decode(trimmed)
            KeyEncoding.HEX -> decodeHex(trimmed)
        }
        if (bytes.size != 16 && bytes.size != 24 && bytes.size != 32) {
            throw IllegalArgumentException(I18n.t("crypto.dialog.keyLengthError", bytes.size))
        }
        return bytes
    }

    fun decrypt(encryptedInput: String, config: ProfileCryptoConfig, secretKey: String): String {
        val keyBytes = decodeKeyBytes(secretKey, config.keyEncoding)
        val keySpec = SecretKeySpec(keyBytes, "AES")

        var payload = encryptedInput.trim()
        if (config.prefix.isNotEmpty() && payload.startsWith(config.prefix)) {
            payload = payload.removePrefix(config.prefix)
            if (config.prefix.endsWith("(") && payload.endsWith(")")) {
                payload = payload.dropLast(1)
            }
        }

        val decoded = Base64.getDecoder().decode(payload.trim())

        val decryptedBytes = when (config.mode) {
            DecryptMode.AES_CBC_IV_PREFIX -> {
                if (decoded.size <= 16) {
                    throw IllegalArgumentException("Ciphertext too short to contain 16-byte IV prefix.")
                }
                val iv = decoded.copyOfRange(0, 16)
                val cipherBytes = decoded.copyOfRange(16, decoded.size)
                val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
                cipher.init(Cipher.DECRYPT_MODE, keySpec, IvParameterSpec(iv))
                cipher.doFinal(cipherBytes)
            }

            DecryptMode.AES_CBC_FIXED_IV -> {
                val ivBytes = decodeIvBytes(config.fixedIv, config.keyEncoding)
                val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
                cipher.init(Cipher.DECRYPT_MODE, keySpec, IvParameterSpec(ivBytes))
                cipher.doFinal(decoded)
            }

            DecryptMode.AES_ECB -> {
                val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
                cipher.init(Cipher.DECRYPT_MODE, keySpec)
                cipher.doFinal(decoded)
            }
        }

        return String(decryptedBytes, StandardCharsets.UTF_8)
    }

    private fun decodeIvBytes(rawIv: String, encoding: KeyEncoding): ByteArray {
        val trimmed = rawIv.trim()
        val bytes = when (encoding) {
            KeyEncoding.UTF8 -> trimmed.toByteArray(StandardCharsets.UTF_8)
            KeyEncoding.BASE64 -> Base64.getDecoder().decode(trimmed)
            KeyEncoding.HEX -> decodeHex(trimmed)
        }
        if (bytes.size != 16) {
            throw IllegalArgumentException("AES/CBC Fixed IV must be exactly 16 bytes (got ${bytes.size}).")
        }
        return bytes
    }

    private fun decodeHex(hex: String): ByteArray {
        val clean = hex.replace(" ", "").removePrefix("0x").removePrefix("0X")
        if (clean.length % 2 != 0) {
            throw IllegalArgumentException("Invalid Hex string length.")
        }
        return ByteArray(clean.length / 2) { i ->
            clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    fun scanYamlForSecretKeyCandidates(projectBasePath: Path?): List<YamlKeyCandidate> {
        if (projectBasePath == null || !Files.isDirectory(projectBasePath)) return emptyList()
        val envResolver = EnvResolver(projectBasePath)
        val candidates = mutableListOf<YamlKeyCandidate>()
        val ignoredDirs = setOf(".git", ".gradle", ".idea", ".intellijPlatform", "build", "out", "target", "node_modules")

        try {
            Files.walkFileTree(projectBasePath, object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    val name = dir.fileName?.toString() ?: return FileVisitResult.CONTINUE
                    if (dir != projectBasePath && name in ignoredDirs) return FileVisitResult.SKIP_SUBTREE
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    val norm = file.toString().replace('\\', '/')
                    if (!norm.contains("/src/main/resources/")) return FileVisitResult.CONTINUE
                    val fname = file.fileName.toString().lowercase()
                    if (!(fname == "application.yml" || fname == "application.yaml" ||
                            (fname.startsWith("application-") && (fname.endsWith(".yml") || fname.endsWith(".yaml"))))
                    ) {
                        return FileVisitResult.CONTINUE
                    }

                    try {
                        val content = Files.readString(file, StandardCharsets.UTF_8)
                        val yaml = Yaml(SafeConstructor(LoaderOptions()))
                        for (doc in yaml.loadAll(content)) {
                            val map = doc as? Map<*, *> ?: continue
                            collectKeyCandidates(map, "", file.fileName.toString(), envResolver, candidates)
                        }
                    } catch (_: Throwable) {
                        // Skip malformed YAML
                    }
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: IOException?): FileVisitResult = FileVisitResult.CONTINUE
            })
        } catch (_: Throwable) {
            // Ignore walk errors
        }

        return candidates.distinctBy { "${it.yamlPath}:${it.resolvedValue}" }
    }

    private fun collectKeyCandidates(
        map: Map<*, *>,
        prefix: String,
        fileName: String,
        envResolver: EnvResolver,
        out: MutableList<YamlKeyCandidate>
    ) {
        for ((k, v) in map) {
            val keyStr = k?.toString()?.trim() ?: continue
            val fullPath = if (prefix.isEmpty()) keyStr else "$prefix.$keyStr"
            if (v is Map<*, *>) {
                collectKeyCandidates(v, fullPath, fileName, envResolver, out)
            } else if (v != null && isSecretKeyPath(fullPath)) {
                val resolved = envResolver.resolve(v.toString())?.trim().orEmpty()
                if (resolved.isNotEmpty() && !resolved.startsWith("\${")) {
                    out.add(YamlKeyCandidate(fileName, fullPath, resolved))
                }
            }
        }
    }

    private fun isSecretKeyPath(path: String): Boolean {
        val lower = path.lowercase()
        return lower.contains("secret-key") ||
            lower.contains("secretkey") ||
            lower.contains("aes.key") ||
            lower.contains("aes.secret") ||
            lower.contains("encrypt.key") ||
            lower.contains("crypto.key") ||
            lower.contains("cipher.key") ||
            lower.contains("encryption.key")
    }
}
