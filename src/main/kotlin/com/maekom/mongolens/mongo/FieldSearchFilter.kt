package com.maekom.mongolens.mongo

/**
 * Pure Kotlin (ZERO IntelliJ UI dependencies) case-insensitive field search filter
 * ranking prefix matches (`startsWith`) ahead of substring matches (`contains`).
 */
object FieldSearchFilter {

    /**
     * Filters string field candidates by case-insensitive substring match:
     * - Blank query returns `masterFields` unchanged.
     * - Items starting with `query` (case-insensitive) appear first (preserving relative order),
     *   followed by remaining items containing `query` (preserving relative order).
     */
    fun filter(masterFields: List<String>, query: String): List<String> {
        return filterItems(masterFields, query) { it }
    }

    /**
     * Generic candidate filter supporting custom primary & display labels (e.g. `SourceKeyOption`).
     * An item is considered a prefix match if EITHER `primaryKey(item)` OR `displayLabel(item)` starts with `query`.
     */
    fun <T> filterItems(
        masterItems: List<T>,
        query: String,
        primaryKey: (T) -> String = { it.toString() },
        displayLabel: (T) -> String = { it.toString() }
    ): List<T> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return masterItems

        val prefixMatches = mutableListOf<T>()
        val substringMatches = mutableListOf<T>()

        for (item in masterItems) {
            val pk = primaryKey(item)
            val label = displayLabel(item)
            when {
                pk.startsWith(trimmed, ignoreCase = true) || label.startsWith(trimmed, ignoreCase = true) -> {
                    prefixMatches.add(item)
                }
                pk.contains(trimmed, ignoreCase = true) || label.contains(trimmed, ignoreCase = true) -> {
                    substringMatches.add(item)
                }
            }
        }
        return prefixMatches + substringMatches
    }
}
