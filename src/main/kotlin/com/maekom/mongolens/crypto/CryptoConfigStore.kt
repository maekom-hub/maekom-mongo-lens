package com.maekom.mongolens.crypto

import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

class CryptoConfigStore(private val projectBasePath: Path?) {
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val memoryKeyFallback = ConcurrentHashMap<String, String>()

    private fun configFile(): Path? {
        val base = projectBasePath ?: return null
        return base.resolve(".idea").resolve("maekom-mongo-crypto.json")
    }

    private fun loadAllConfigs(): MutableMap<String, ProfileCryptoConfig> {
        val file = configFile() ?: return linkedMapOf()
        if (!Files.isRegularFile(file)) return linkedMapOf()
        return try {
            val text = Files.readString(file, StandardCharsets.UTF_8)
            val type = object : TypeToken<LinkedHashMap<String, ProfileCryptoConfig>>() {}.type
            gson.fromJson<LinkedHashMap<String, ProfileCryptoConfig>>(text, type) ?: linkedMapOf()
        } catch (_: Throwable) {
            linkedMapOf()
        }
    }

    fun getConfig(profileName: String): ProfileCryptoConfig {
        return loadAllConfigs()[profileName] ?: ProfileCryptoConfig(profileName = profileName)
    }

    fun saveConfig(config: ProfileCryptoConfig, secretKey: String) {
        val all = loadAllConfigs()
        all[config.profileName] = config
        val file = configFile()
        if (file != null) {
            try {
                Files.createDirectories(file.parent)
                Files.writeString(file, gson.toJson(all), StandardCharsets.UTF_8)
            } catch (_: Throwable) {
                // Ignore IO error
            }
        }
        saveSecretKey(config.profileName, secretKey)
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
