package com.maekom.mongolens.config

import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

class SavedQueryStore(private val projectBasePath: Path?) {
    private val gson = GsonBuilder().setPrettyPrinting().create()

    private fun storeFile(): Path? {
        val base = projectBasePath ?: return null
        return base.resolve(".idea").resolve("maekom-mongo-queries.json")
    }

    /**
     * Gson bypasses Kotlin constructors & defaults when deserializing older JSON files
     * (e.g., files written before `shellScript` was added), leaving non-null Kotlin String fields as null.
     * Normalize every field defensively so .trim() NEVER throws NullPointerException.
     */
    @Suppress("USELESS_ELVIS", "UNNECESSARY_SAFE_CALL")
    private fun sanitize(q: SavedQuery): SavedQuery {
        return SavedQuery(
            id = (q.id as String?)?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString(),
            name = (q.name as String?) ?: "",
            profileId = (q.profileId as String?) ?: "",
            database = (q.database as String?) ?: "",
            collection = (q.collection as String?) ?: "",
            mode = (q.mode as String?)?.takeIf { it.isNotBlank() } ?: "FIND",
            filter = (q.filter as String?) ?: "{}",
            projection = (q.projection as String?) ?: "{}",
            sort = (q.sort as String?) ?: "{}",
            limit = if (q.limit > 0) q.limit else 50,
            skip = q.skip.coerceAtLeast(0),
            pipelineJson = (q.pipelineJson as String?) ?: "[]",
            shellScript = (q.shellScript as String?) ?: "",
            isHistory = q.isHistory,
            timestamp = q.timestamp
        )
    }

    private fun safeTrim(value: String?): String = value?.trim().orEmpty()

    fun loadAll(): List<SavedQuery> {
        val file = storeFile() ?: return emptyList()
        if (!Files.isRegularFile(file)) return emptyList()
        return try {
            val text = Files.readString(file, StandardCharsets.UTF_8)
            val listType = object : TypeToken<List<SavedQuery>>() {}.type
            val rawList = gson.fromJson<List<SavedQuery?>>(text, listType) ?: emptyList()
            rawList.filterNotNull().map { sanitize(it) }
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
        val clean = sanitize(query)
        val all = loadAll().toMutableList()
        all.removeAll { !it.isHistory && it.id == clean.id }
        all.add(0, clean.copy(isHistory = false, timestamp = System.currentTimeMillis()))
        saveAll(all)
    }

    fun recordHistory(query: SavedQuery, maxHistory: Int = 50) {
        val clean = sanitize(query)
        val all = loadAll().toMutableList()
        val bookmarks = all.filter { !it.isHistory }
        val history = all.filter { it.isHistory }
            .filterNot {
                it.collection == clean.collection &&
                    it.mode == clean.mode &&
                    safeTrim(it.filter) == safeTrim(clean.filter) &&
                    safeTrim(it.projection) == safeTrim(clean.projection) &&
                    safeTrim(it.sort) == safeTrim(clean.sort) &&
                    safeTrim(it.pipelineJson) == safeTrim(clean.pipelineJson) &&
                    safeTrim(it.shellScript) == safeTrim(clean.shellScript)
            }
            .toMutableList()

        history.add(0, clean.copy(isHistory = true, timestamp = System.currentTimeMillis()))
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
