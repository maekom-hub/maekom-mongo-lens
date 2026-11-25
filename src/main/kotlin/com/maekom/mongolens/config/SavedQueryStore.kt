package com.maekom.mongolens.config

import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

class SavedQueryStore(private val projectBasePath: Path?) {
    private val gson = GsonBuilder().setPrettyPrinting().create()

    private fun storeFile(): Path? {
        val base = projectBasePath ?: return null
        return base.resolve(".idea").resolve("maekom-mongo-queries.json")
    }

    fun loadAll(): List<SavedQuery> {
        val file = storeFile() ?: return emptyList()
        if (!Files.isRegularFile(file)) return emptyList()
        return try {
            val text = Files.readString(file, StandardCharsets.UTF_8)
            val listType = object : TypeToken<List<SavedQuery>>() {}.type
            gson.fromJson<List<SavedQuery>>(text, listType) ?: emptyList()
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun saveAll(items: List<SavedQuery>) {
        val file = storeFile() ?: return
        try {
            Files.createDirectories(file.parent)
            Files.writeString(file, gson.toJson(items), StandardCharsets.UTF_8)
        } catch (_: Throwable) {
            // Ignore IO errors
        }
    }

    fun saveBookmark(query: SavedQuery) {
        val all = loadAll().toMutableList()
        all.removeAll { !it.isHistory && it.id == query.id }
        all.add(0, query.copy(isHistory = false, timestamp = System.currentTimeMillis()))
        saveAll(all)
    }

    fun recordHistory(query: SavedQuery, maxHistory: Int = 50) {
        val all = loadAll().toMutableList()
        val bookmarks = all.filter { !it.isHistory }
        val history = all.filter { it.isHistory }
            .filterNot {
                it.collection == query.collection &&
                    it.mode == query.mode &&
                    it.filter.trim() == query.filter.trim() &&
                    it.projection.trim() == query.projection.trim() &&
                    it.sort.trim() == query.sort.trim() &&
                    it.pipelineJson.trim() == query.pipelineJson.trim() &&
                    it.shellScript.trim() == query.shellScript.trim()
            }
            .toMutableList()

        history.add(0, query.copy(isHistory = true, timestamp = System.currentTimeMillis()))
        val trimmedHistory = history.take(maxHistory)
        saveAll(bookmarks + trimmedHistory)
    }

    fun deleteById(queryId: String) {
        val updated = loadAll().filterNot { it.id == queryId }
        saveAll(updated)
    }

    fun clearHistory(collection: String? = null, mode: String? = null) {
        val updated = loadAll().filterNot { q ->
            q.isHistory &&
                (collection == null || q.collection == collection) &&
                (mode == null || q.mode == mode)
        }
        saveAll(updated)
    }

    fun getBookmarks(collection: String? = null, mode: String? = null): List<SavedQuery> {
        return loadAll().filter { !it.isHistory }
            .filter { collection == null || it.collection == collection }
            .filter { mode == null || it.mode == mode }
    }

    fun getHistory(collection: String? = null, mode: String? = null): List<SavedQuery> {
        return loadAll().filter { it.isHistory }
            .filter { collection == null || it.collection == collection }
            .filter { mode == null || it.mode == mode }
    }
}
