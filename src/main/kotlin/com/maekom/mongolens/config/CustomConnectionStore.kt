package com.maekom.mongolens.config

import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

class CustomConnectionStore(private val projectBasePath: Path?) {
    private val gson = GsonBuilder().setPrettyPrinting().create()

    private fun storeFile(): Path? {
        val base = projectBasePath ?: return null
        return base.resolve(".idea").resolve("maekom-mongo-connections.json")
    }

    fun loadAll(): List<MongoConnectionProfile> {
        val file = storeFile() ?: return emptyList()
        if (!Files.isRegularFile(file)) return emptyList()
        return try {
            val text = Files.readString(file, StandardCharsets.UTF_8)
            val listType = object : TypeToken<List<MongoConnectionProfile>>() {}.type
            val parsed: List<MongoConnectionProfile>? = gson.fromJson(text, listType)
            parsed?.map { it.copy(source = ProfileSource.CUSTOM) } ?: emptyList()
        } catch (_: Throwable) {
            emptyList()
        }
    }

    fun saveAll(profiles: List<MongoConnectionProfile>) {
        val file = storeFile() ?: return
        try {
            Files.createDirectories(file.parent)
            Files.writeString(file, gson.toJson(profiles), StandardCharsets.UTF_8)
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
        val updated = loadAll().filterNot { it.id == profileId }
        saveAll(updated)
        return updated
    }
}
