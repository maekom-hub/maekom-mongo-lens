package com.maekom.mongolens.crypto

import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

class CryptoConfigStore(private val projectBasePath: Path?) {
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val configCache = ConcurrentHashMap<String, ProfileCryptoConfig>()
    private val memoryKeyFallback = ConcurrentHashMap<String, String>()
    private val changeListeners = CopyOnWriteArrayList<() -> Unit>()
    private val knownProfileNames = CopyOnWriteArrayList<String>()

    @Volatile
    private var cacheLoaded = false

    private fun configFile(): Path? {
        val base = projectBasePath ?: return null
        return base.resolve(".idea").resolve("maekom-mongo-crypto.json")
    }

    private fun ensureCacheLoaded() {
        if (cacheLoaded) return
        synchronized(this) {
            if (cacheLoaded) return
            val file = configFile()
            if (file != null && Files.isRegularFile(file)) {
                try {
                    val text = Files.readString(file, StandardCharsets.UTF_8)
                    val type = object : TypeToken<LinkedHashMap<String, ProfileCryptoConfig>>() {}.type
                    val parsed = gson.fromJson<LinkedHashMap<String, ProfileCryptoConfig>>(text, type)
                    if (parsed != null) {
                        @Suppress("USELESS_ELVIS")
                        parsed.forEach { (k, v) ->
                            if (k != null && v != null) {
                                configCache[k] = ProfileCryptoConfig(
                                    profileName = (v.profileName as String?) ?: k,
                                    prefix = (v.prefix as String?) ?: "ENC-",
                                    mode = (v.mode as DecryptMode?) ?: DecryptMode.AES_CBC_IV_PREFIX,
                                    keyEncoding = (v.keyEncoding as KeyEncoding?) ?: KeyEncoding.UTF8,
                                    fixedIv = (v.fixedIv as String?) ?: "",
                                    revealSeconds = if (v.revealSeconds in 5..60) v.revealSeconds else 10
                                )
                            }
                        }
                    }
                } catch (_: Throwable) {
                    // Ignore malformed config file
                }
            }
            cacheLoaded = true
        }
    }

    fun addChangeListener(parentDisposable: Disposable, listener: () -> Unit) {
        changeListeners.add(listener)
        Disposer.register(parentDisposable) {
            changeListeners.remove(listener)
        }
    }

    fun setKnownProfileNames(names: Collection<String>) {
        val cleaned = names.mapNotNull { it.trim().takeIf { s -> s.isNotEmpty() } }.distinct()
        knownProfileNames.clear()
        knownProfileNames.addAll(cleaned)
    }

    fun getAllProfileNames(includeProfile: String? = null): List<String> {
        ensureCacheLoaded()
        val ordered = linkedSetOf<String>()
        knownProfileNames.forEach { if (it.isNotBlank()) ordered.add(it) }
        configCache.keys.forEach { if (it.isNotBlank()) ordered.add(it) }
        memoryKeyFallback.keys.forEach { if (it.isNotBlank()) ordered.add(it) }
        includeProfile?.trim()?.takeIf { it.isNotEmpty() }?.let { ordered.add(it) }
        if (ordered.isEmpty()) ordered.add("default")
        return ordered.toList()
    }

    fun getConfig(profileName: String): ProfileCryptoConfig {
        ensureCacheLoaded()
        return configCache[profileName] ?: ProfileCryptoConfig(profileName = profileName)
    }

    fun saveConfig(config: ProfileCryptoConfig, secretKey: String) {
        ensureCacheLoaded()
        configCache[config.profileName] = config
        val file = configFile()
        if (file != null) {
            try {
                Files.createDirectories(file.parent)
                Files.writeString(file, gson.toJson(LinkedHashMap(configCache)), StandardCharsets.UTF_8)
            } catch (_: Throwable) {
                // Ignore IO error
            }
        }
        saveSecretKey(config.profileName, secretKey)
        changeListeners.forEach { listener ->
            try {
                listener()
            } catch (_: Throwable) {
                // Keep notifying remaining listeners
            }
        }
    }

    fun getSecretKey(profileName: String): String {
        return try {
            PasswordSafe.instance.getPassword(credentialAttributes(profileName))
                ?: memoryKeyFallback[profileName].orEmpty()
        } catch (_: Throwable) {
            memoryKeyFallback[profileName].orEmpty()
        }
    }

    private fun saveSecretKey(profileName: String, secretKey: String) {
        memoryKeyFallback[profileName] = secretKey
        try {
            PasswordSafe.instance.setPassword(credentialAttributes(profileName), secretKey.ifEmpty { null })
        } catch (_: Throwable) {
            // Fallback kept in memoryKeyFallback
        }
    }

    private fun credentialAttributes(profileName: String): CredentialAttributes {
        val projectKey = projectBasePath?.fileName?.toString() ?: "default"
        return CredentialAttributes(
            generateServiceName("MaekomMongoLensCrypto", "$projectKey:$profileName"),
            profileName
        )
    }
}
