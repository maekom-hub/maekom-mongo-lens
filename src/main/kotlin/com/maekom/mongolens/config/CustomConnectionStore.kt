package com.maekom.mongolens.config

import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

data class YamlProfileMetaOverride(
    val colorTag: ConnectionColorTag = ConnectionColorTag.NONE,
    val readOnlyOverride: Boolean? = null
)

class CustomConnectionStore(private val projectBasePath: Path?) {
    private val gson = GsonBuilder().setPrettyPrinting().create()

    private fun storeFile(): Path? {
        val base = projectBasePath ?: return null
        return base.resolve(".idea").resolve("maekom-mongo-connections.json")
    }

    private fun yamlMetaFile(): Path? {
        val base = projectBasePath ?: return null
        return base.resolve(".idea").resolve("maekom-mongo-yaml-meta.json")
    }

    fun loadAll(): List<MongoConnectionProfile> {
        val file = storeFile() ?: return emptyList()
        if (!Files.isRegularFile(file)) return emptyList()
        return try {
            val text = Files.readString(file, StandardCharsets.UTF_8)
            val listType = object : TypeToken<List<MongoConnectionProfile>>() {}.type
            val parsed: List<MongoConnectionProfile?>? = gson.fromJson(text, listType)
            @Suppress("USELESS_ELVIS")
            parsed?.filterNotNull()?.map { p ->
                val safeId = (p.id as String?) ?: "custom:${java.util.UUID.randomUUID()}"
                val storedPass = ConnectionPasswordSafe.loadPassword(safeId)
                val effectivePassword = storedPass?.takeIf { it.isNotEmpty() } ?: p.password
                val restoredUri = MongoUriHelper.injectPasswordIntoUri(p.uri, effectivePassword)
                val safeColor = (p.colorTag as ConnectionColorTag?) ?: ConnectionColorTag.NONE
                p.copy(
                    id = safeId,
                    displayName = (p.displayName as String?) ?: "Custom",
                    profileName = (p.profileName as String?) ?: "custom",
                    host = (p.host as String?) ?: "localhost",
                    uri = restoredUri,
                    password = effectivePassword,
                    colorTag = safeColor,
                    source = ProfileSource.CUSTOM
                )
            } ?: emptyList()
        } catch (_: Throwable) {
            emptyList()
        }
    }

    fun saveAll(profiles: List<MongoConnectionProfile>) {
        val file = storeFile() ?: return
        try {
            val sanitized = profiles.map { p ->
                val rawPass = p.password?.takeIf { it.isNotEmpty() }
                    ?: MongoUriHelper.extractPasswordFromUri(p.uri)
                val rawUser = p.username?.takeIf { it.isNotBlank() }
                    ?: MongoUriHelper.parseUri(p.uri)?.username
                val savedToSafe = ConnectionPasswordSafe.savePassword(p.id, rawUser, rawPass)
                if (savedToSafe) {
                    p.copy(
                        uri = MongoUriHelper.stripPasswordFromUri(p.uri),
                        password = null
                    )
                } else {
                    // Fallback to JSON persistence if PasswordSafe is unavailable
                    p
                }
            }
            Files.createDirectories(file.parent)
            Files.writeString(file, gson.toJson(sanitized), StandardCharsets.UTF_8)
        } catch (_: Throwable) {
            // Ignore write failures
        }
    }

    fun upsert(profile: MongoConnectionProfile): List<MongoConnectionProfile> {
        val existing = loadAll().toMutableList()
        val idx = existing.indexOfFirst { it.id == profile.id }
        val normalized = profile.copy(source = ProfileSource.CUSTOM)
        if (idx >= 0) {
            existing[idx] = normalized
        } else {
            existing.add(normalized)
        }
        saveAll(existing)
        return existing
    }

    fun delete(profileId: String): List<MongoConnectionProfile> {
        ConnectionPasswordSafe.removePassword(profileId)
        val updated = loadAll().filterNot { it.id == profileId }
        saveAll(updated)
        return updated
    }

    fun loadYamlMetaOverrides(): Map<String, YamlProfileMetaOverride> {
        val file = yamlMetaFile() ?: return emptyMap()
        if (!Files.isRegularFile(file)) return emptyMap()
        return try {
            val text = Files.readString(file, StandardCharsets.UTF_8)
            val mapType = object : TypeToken<Map<String, YamlProfileMetaOverride>>() {}.type
            val parsed: Map<String, YamlProfileMetaOverride?>? = gson.fromJson(text, mapType)
            parsed?.mapNotNull { (k, v) ->
                if (v == null) null
                else {
                    @Suppress("USELESS_ELVIS")
                    val safeColor = (v.colorTag as ConnectionColorTag?) ?: ConnectionColorTag.NONE
                    k to v.copy(colorTag = safeColor)
                }
            }?.toMap() ?: emptyMap()
        } catch (_: Throwable) {
            emptyMap()
        }
    }

    fun saveYamlMetaOverride(profileId: String, colorTag: ConnectionColorTag, readOnlyOverride: Boolean) {
        val file = yamlMetaFile() ?: return
        try {
            val map = loadYamlMetaOverrides().toMutableMap()
            map[profileId] = YamlProfileMetaOverride(colorTag = colorTag, readOnlyOverride = readOnlyOverride)
            Files.createDirectories(file.parent)
            Files.writeString(file, gson.toJson(map), StandardCharsets.UTF_8)
        } catch (_: Throwable) {
            // Ignore write failures
        }
    }
}
