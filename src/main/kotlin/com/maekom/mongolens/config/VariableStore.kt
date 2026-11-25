package com.maekom.mongolens.config

import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

class VariableStore(private val projectBasePath: Path?) {
    private val gson = GsonBuilder().setPrettyPrinting().create()

    private fun storeFile(): Path? {
        val base = projectBasePath ?: return null
        return base.resolve(".idea").resolve("maekom-mongo-variables.json")
    }

    fun loadAll(): LinkedHashMap<String, String> {
        val file = storeFile() ?: return linkedMapOf()
        if (!Files.isRegularFile(file)) return linkedMapOf()
        return try {
            val text = Files.readString(file, StandardCharsets.UTF_8)
            val mapType = object : TypeToken<LinkedHashMap<String, String>>() {}.type
            gson.fromJson<LinkedHashMap<String, String>>(text, mapType) ?: linkedMapOf()
        } catch (_: Throwable) {
            linkedMapOf()
        }
    }

    fun saveAll(vars: Map<String, String>) {
        val file = storeFile() ?: return
        try {
            Files.createDirectories(file.parent)
            Files.writeString(file, gson.toJson(vars), StandardCharsets.UTF_8)
        } catch (_: Throwable) {
            // Ignore IO errors
        }
    }
}
