package com.maekom.mongolens.mongo

import org.bson.BsonArray
import org.bson.BsonBoolean
import org.bson.BsonDecimal128
import org.bson.BsonDocument
import org.bson.BsonDouble
import org.bson.BsonInt32
import org.bson.BsonInt64
import org.bson.BsonObjectId
import org.bson.BsonString
import org.bson.BsonValue
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

enum class JoinMatchStrategy {
    LATEST_ONE,
    UNWIND_ALL
}

data class JoinSpec(
    val leftCollection: String,
    val rightCollection: String,
    val localField: String,
    val foreignField: String,
    val matchStrategy: JoinMatchStrategy,
    val allLeftFields: List<String>,
    val allRightFields: List<String>,
    val selectedLeftFields: List<String>,
    val selectedRightFields: List<String>
) {
    fun safeRightPrefix(): String = rightCollection.replace('.', '_')

    /**
     * Collision-safe 1D flat column alias: uses underscore `<targetCollection>_<fieldName>` (never dot `.`)
     * whenever a right-side field collides with any selected left-side field.
     */
    fun rightAliasFor(rightField: String, leftSet: Set<String> = selectedLeftFields.toSet()): String {
        val cleanRight = rightField.replace('.', '_')
        return if (rightField in leftSet) {
            "${safeRightPrefix()}_$cleanRight"
        } else {
            cleanRight
        }
    }

    fun visibleOutputColumns(): List<String> {
        val leftSet = selectedLeftFields.toSet()
        val out = linkedSetOf<String>()
        selectedLeftFields.forEach { out.add(it) }
        selectedRightFields.forEach { out.add(rightAliasFor(it, leftSet)) }
        return out.toList()
    }

    fun allCandidateOutputColumns(): List<String> {
        val leftSet = selectedLeftFields.toSet()
        val allLeftSet = allLeftFields.toSet()
        val out = linkedSetOf<String>()
        allLeftFields.forEach { out.add(it) }
        allRightFields.forEach { rf ->
            val cleanRight = rf.replace('.', '_')
            val colName = if (rf in leftSet || rf in allLeftSet) {
                // If currently displayed unprefixed because left field wasn't selected, keep its current visible name
                if (rf in selectedRightFields && rf !in leftSet) cleanRight else "${safeRightPrefix()}_$cleanRight"
            } else {
                cleanRight
            }
            out.add(colName)
        }
        return out.toList()
    }

    fun updateFromVisibleColumnSelection(chosenColumns: List<String>): JoinSpec {
        val chosenSet = chosenColumns.toSet()
        val prevLeftSet = selectedLeftFields.toSet()

        val newLeft = allLeftFields.filter { lf -> lf in chosenSet }
        val newRight = allRightFields.filter { rf ->
            val cleanRight = rf.replace('.', '_')
            val prefixed = "${safeRightPrefix()}_$cleanRight"
            val prevAlias = rightAliasFor(rf, prevLeftSet)
            when {
                prefixed in chosenSet -> true
                rf !in allLeftFields && cleanRight in chosenSet -> true
                rf in selectedRightFields && prevAlias in chosenSet && rf !in newLeft -> true
                else -> false
            }
        }

        // Guarantee at least 1 output column
        if (newLeft.isEmpty() && newRight.isEmpty()) {
            val fallbackLeft = allLeftFields.firstOrNull()?.let { listOf(it) } ?: listOf("_id")
            return copy(selectedLeftFields = fallbackLeft, selectedRightFields = emptyList())
        }
        return copy(selectedLeftFields = newLeft, selectedRightFields = newRight)
    }

    /**
     * Builds the safe 3-stage Join Aggregation Pipeline:
     * 1. Pre-filter & paging compression ($match -> $sort -> $skip -> $limit)
     * 2. Safe $lookup (LATEST_ONE sub-pipeline with $limit:1 to avoid 16MB BSON limit, or UNWIND_ALL) + $unwind
     * 3. 1D flat $project with underscore aliases on collision
     */
    fun buildPipeline(
        filter: BsonDocument,
        sort: BsonDocument,
        skip: Int,
        limit: Int
    ): List<BsonDocument> {
        val stages = mutableListOf<BsonDocument>()

        // Stage 1: Pre-filter, Sort, Skip, Limit BEFORE $lookup
        if (!filter.isEmpty()) {
            stages.add(BsonDocument("\$match", filter))
        }
        if (!sort.isEmpty()) {
            stages.add(BsonDocument("\$sort", sort))
        }
        if (skip > 0) {
            stages.add(BsonDocument("\$skip", BsonInt32(skip)))
        }
        stages.add(BsonDocument("\$limit", BsonInt32(limit.coerceIn(1, 5000))))

        // Stage 2: $lookup + $unwind (preserveNullAndEmptyArrays = LEFT JOIN)
        when (matchStrategy) {
            JoinMatchStrategy.LATEST_ONE -> {
                val subMatchExpr = BsonDocument(
                    "\$expr",
                    BsonDocument(
                        "\$eq",
                        BsonArray(listOf(BsonString("\$$foreignField"), BsonString("\$\$joinKey")))
                    )
                )
                val subPipeline = BsonArray(
                    listOf(
                        BsonDocument("\$match", subMatchExpr),
                        BsonDocument("\$sort", BsonDocument("_id", BsonInt32(-1))),
                        BsonDocument("\$limit", BsonInt32(1))
                    )
                )
                val lookupDoc = BsonDocument()
                    .append("from", BsonString(rightCollection))
                    .append("let", BsonDocument("joinKey", BsonString("\$$localField")))
                    .append("pipeline", subPipeline)
                    .append("as", BsonString("_joined"))
                stages.add(BsonDocument("\$lookup", lookupDoc))
            }

            JoinMatchStrategy.UNWIND_ALL -> {
                val lookupDoc = BsonDocument()
                    .append("from", BsonString(rightCollection))
                    .append("localField", BsonString(localField))
                    .append("foreignField", BsonString(foreignField))
                    .append("as", BsonString("_joined"))
                stages.add(BsonDocument("\$lookup", lookupDoc))
            }
        }

        stages.add(
            BsonDocument(
                "\$unwind",
                BsonDocument()
                    .append("path", BsonString("\$_joined"))
                    .append("preserveNullAndEmptyArrays", BsonBoolean.TRUE)
            )
        )

        // Stage 3: Flat 1D $project
        val leftSet = selectedLeftFields.toSet()
        val proj = BsonDocument()
        val rightProjectsDirectlyToId = "_id" !in leftSet &&
            selectedRightFields.any { rf -> rightAliasFor(rf, leftSet) == "_id" }
        if ("_id" !in leftSet && !rightProjectsDirectlyToId) {
            proj["_id"] = BsonInt32(0)
        }
        for (lf in selectedLeftFields) {
            proj[lf] = BsonInt32(1)
        }
        for (rf in selectedRightFields) {
            val alias = rightAliasFor(rf, leftSet)
            proj[alias] = BsonString("\$_joined.$rf")
        }
        if (proj.isEmpty() || (proj.size == 1 && proj["_id"] == BsonInt32(0))) {
            proj["_id"] = BsonInt32(1)
        }
        stages.add(BsonDocument("\$project", proj))

        return stages
    }

    fun toMongoshScript(
        filterRaw: String?,
        sortRaw: String?,
        skip: Int,
        limit: Int
    ): String {
        val filterDoc = try {
            MongoShellNormalizer.parseBsonDocument(filterRaw?.trim().orEmpty().ifEmpty { "{}" })
        } catch (_: Throwable) {
            BsonDocument()
        }
        val sortDoc = try {
            MongoShellNormalizer.parseBsonDocument(sortRaw?.trim().orEmpty().ifEmpty { "{}" })
        } catch (_: Throwable) {
            BsonDocument()
        }
        val stages = buildPipeline(filterDoc, sortDoc, skip, limit)
        val prettyArray = stages.joinToString(separator = ",\n", prefix = "[\n", postfix = "\n]") { stage ->
            MongoShellNormalizer.formatPrettyExtended(stage)
                .lines()
                .joinToString("\n") { "  $it" }
        }
        return MongoShellNormalizer.toMongoshAggregateCommand(leftCollection, prettyArray)
    }
}

object ReferenceJumpHelper {
    data class RankedCollectionItem(
        val name: String,
        val badgeKey: String? // "result.ref.badgeRecent" | "result.ref.badgeSuggested" | null
    )

    private val hex24Regex = Regex("^[0-9a-fA-F]{24}$")
    private val recentMappings = ConcurrentHashMap<String, String>()

    fun recordSelection(database: String, fieldPath: String, targetCollection: String) {
        val leaf = fieldPath.substringAfterLast('.').trim()
        if (leaf.isNotEmpty()) {
            recentMappings["$database:$leaf"] = targetCollection
        }
    }

    fun isReferenceCandidate(fieldPath: String?, value: BsonValue?): Boolean {
        if (fieldPath.isNullOrBlank() || value == null) return false
        return when (value) {
            is BsonObjectId, is BsonString, is BsonInt32, is BsonInt64, is BsonDecimal128, is BsonDouble -> true
            is org.bson.BsonBinary -> true
            else -> false
        }
    }

    fun camelToSnake(input: String): String {
        if (input.isEmpty()) return input
        val sb = StringBuilder(input.length + 4)
        input.forEachIndexed { idx, c ->
            if (c.isUpperCase()) {
                if (idx > 0 && input[idx - 1] != '_' && !input[idx - 1].isUpperCase()) {
                    sb.append('_')
                }
                sb.append(c.lowercaseChar())
            } else {
                sb.append(c)
            }
        }
        return sb.toString()
    }

    fun snakeToCamel(input: String): String {
        if (!input.contains('_') || input.startsWith("_")) return input
        val parts = input.split('_').filter { it.isNotEmpty() }
        if (parts.isEmpty()) return input
        return buildString {
            append(parts.first().lowercase(Locale.ROOT))
            for (i in 1 until parts.size) {
                val p = parts[i].lowercase(Locale.ROOT)
                append(p.replaceFirstChar { it.uppercaseChar() })
            }
        }
    }

    private fun extractStem(fieldName: String): String {
        val leaf = fieldName.substringAfterLast('.').trim().trimStart('_')
        val snake = camelToSnake(leaf)
        val stripped = snake
            .removeSuffix("_id")
            .removeSuffix("_ids")
            .removeSuffix("_no")
            .removeSuffix("_key")
            .removeSuffix("_code")
            .removeSuffix("_seq")
            .trim('_')
        return stripped.lowercase(Locale.ROOT)
    }

    fun rankCollections(
        database: String,
        currentCollection: String,
        fieldPath: String?,
        allCollections: List<String>
    ): List<RankedCollectionItem> {
        val leaf = fieldPath?.substringAfterLast('.')?.trim().orEmpty()
        val recent = if (leaf.isNotEmpty()) recentMappings["$database:$leaf"] else null
        val stem = if (leaf.isNotEmpty()) extractStem(leaf) else ""
        val currentStem = extractStem(currentCollection)

        val candidateNames = if (stem.isNotEmpty()) {
            setOf(
                stem,
                "${stem}s",
                "${stem}es",
                "${stem}_hst",
                "${stem}_history",
                "${stem}_tb",
                "tb_$stem",
                snakeToCamel(stem),
                "${snakeToCamel(stem)}s"
            )
        } else {
            emptySet()
        }

        val recentList = mutableListOf<RankedCollectionItem>()
        val suggestedList = mutableListOf<RankedCollectionItem>()
        val restList = mutableListOf<RankedCollectionItem>()

        for (coll in allCollections.distinct()) {
            val lower = coll.lowercase(Locale.ROOT)
            when {
                recent != null && coll == recent -> {
                    recentList.add(RankedCollectionItem(coll, "result.ref.badgeRecent"))
                }
                coll != currentCollection && (
                    lower in candidateNames ||
                        (stem.length >= 3 && lower.contains(stem)) ||
                        (currentStem.length >= 3 && (lower.contains(currentStem) || (lower.endsWith("_hst") && currentCollection.endsWith("_hst"))))
                    ) -> {
                    suggestedList.add(RankedCollectionItem(coll, "result.ref.badgeSuggested"))
                }
                else -> {
                    restList.add(RankedCollectionItem(coll, null))
                }
            }
        }

        return recentList + suggestedList.sortedBy { it.name } + restList.sortedBy { it.name }
    }

    /**
     * Resolves the optimal target query field (`same` -> `snake_case` -> `camelCase` -> `_id`)
     * and applies automatic ObjectId <-> 24-hex String coercion to maximize match accuracy.
     */
    fun buildReferenceFilterQuery(
        sourceFieldPath: String,
        sourceValue: BsonValue,
        targetTopFields: List<String>,
        targetFieldTypes: Map<String, SmartInValueType>
    ): String {
        val leaf = sourceFieldPath.substringAfterLast('.').trim()
        val snake = camelToSnake(leaf)
        val camel = snakeToCamel(leaf)
        val fieldSet = targetTopFields.toSet()

        val chosenField = when {
            leaf.isNotEmpty() && leaf != "_id" && leaf in fieldSet -> leaf
            snake.isNotEmpty() && snake != "_id" && snake in fieldSet -> snake
            camel.isNotEmpty() && camel != "_id" && camel in fieldSet -> camel
            else -> "_id"
        }

        val targetType = targetFieldTypes[chosenField]
        val preferObjectId = when {
            targetType == SmartInValueType.OBJECT_ID -> true
            targetType == SmartInValueType.STRING -> false
            chosenField == "_id" -> true
            else -> sourceValue is BsonObjectId
        }
        val preferString = targetType == SmartInValueType.STRING

        val formattedValue = formatCoercedQueryLiteral(sourceValue, preferObjectId, preferString)
        val escapedField = MongoShellNormalizer.escapeJsonString(chosenField)
        return """{ "$escapedField": $formattedValue }"""
    }

    private fun formatCoercedQueryLiteral(
        value: BsonValue,
        preferObjectId: Boolean,
        preferString: Boolean
    ): String {
        return when (value) {
            is BsonObjectId -> {
                val hex = value.value.toHexString()
                if (preferString) {
                    "\"$hex\""
                } else {
                    "ObjectId(\"$hex\")"
                }
            }
            is BsonString -> {
                val raw = value.value.trim()
                if (preferObjectId && hex24Regex.matches(raw)) {
                    "ObjectId(\"$raw\")"
                } else {
                    "\"${MongoShellNormalizer.escapeJsonString(value.value)}\""
                }
            }
            is BsonInt32 -> value.value.toString()
            is BsonInt64 -> value.value.toString()
            is BsonDouble -> value.value.toString()
            is BsonDecimal128 -> "NumberDecimal(\"${value.value}\")"
            is org.bson.BsonBinary -> {
                val sub = value.type
                if ((sub == org.bson.BsonBinarySubType.UUID_STANDARD.value ||
                        sub == org.bson.BsonBinarySubType.UUID_LEGACY.value) &&
                    value.data.size == 16
                ) {
                    "UUID(\"${value.asUuid()}\")"
                } else {
                    "\"${MongoShellNormalizer.escapeJsonString(value.toString())}\""
                }
            }
            else -> "\"${MongoShellNormalizer.escapeJsonString(value.toString())}\""
        }
    }
}
