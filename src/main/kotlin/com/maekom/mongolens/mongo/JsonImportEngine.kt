package com.maekom.mongolens.mongo

import com.maekom.mongolens.config.MongoConnectionProfile
import com.maekom.mongolens.i18n.I18n
import com.mongodb.client.model.Filters
import com.mongodb.client.model.InsertManyOptions
import com.mongodb.client.model.Projections
import org.bson.Document
import org.bson.json.JsonWriterSettings
import org.bson.types.Binary
import org.bson.types.Decimal128
import org.bson.types.ObjectId
import java.math.BigDecimal
import java.util.Date
import java.util.concurrent.TimeUnit

enum class ImportErrorCategory(val i18nKey: String) {
    SYNTAX_AND_KEY("import.cat.syntaxKey"),
    DUPLICATE_ID("import.cat.duplicateId"),
    TYPE_MISMATCH("import.cat.typeMismatch")
}

data class ImportValidationIssue(
    val category: ImportErrorCategory,
    val docIndex: Int,
    val lineNumber: Int?,
    val fieldPath: String,
    val expected: String,
    val actual: String,
    val detail: String
) {
    fun docLabel(): String = when {
        docIndex > 0 && lineNumber != null -> "#$docIndex (L$lineNumber)"
        docIndex > 0 -> "#$docIndex"
        lineNumber != null -> "L$lineNumber"
        else -> "-"
    }
}

data class ParsedImportDoc(
    val docIndex: Int,
    val lineNumber: Int?,
    val document: Document
)

data class ImportPreflightResult(
    val validDocuments: List<Document>,
    val issues: List<ImportValidationIssue>,
    val sampleCountUsed: Int
) {
    val isValid: Boolean get() = issues.isEmpty() && validDocuments.isNotEmpty()
}

private enum class SchemaBsonKind(val label: String) {
    INT32("Int32"),
    INT64("Int64"),
    DOUBLE("Double"),
    DECIMAL128("Decimal128"),
    STRING("String"),
    BOOLEAN("Boolean"),
    OBJECT_ID("ObjectId"),
    DATE("Date"),
    ARRAY("Array"),
    OBJECT("Object"),
    BINARY("Binary");

    fun isNumeric(): Boolean = this == INT32 || this == INT64 || this == DOUBLE || this == DECIMAL128
}

/**
 * Multi-format JSON importer (single `{...}`, array `[...]`, and NDJSON line-delimited format)
 * with 4-stage atomic Pre-Flight Validation:
 * 1. UTF-8 BOM stripping, syntax validation & DB-specific key constraint checks (AWS DocumentDB vs MongoDB)
 * 2. `_id` duplicate checks (in-batch + existing DB via 500-item chunked `$in`, numeric Int/Long cross-check)
 * 3. Recursive nested schema type compatibility against up to 50 sampled docs + safe numeric widening promotion
 * 4. Atomic abort on any error (zero DB writes) or 500-doc chunked `insertMany` (no `maxTimeMS` for DocumentDB)
 */
object JsonImportEngine {
    const val MAX_IMPORT_FILE_BYTES: Long = 10L * 1024L * 1024L
    private const val CHUNK_SIZE: Int = 500
    private const val SAMPLE_LIMIT: Int = 50
    private const val MAX_ISSUES_COLLECTED: Int = 200
    private val PRETTY_SETTINGS: JsonWriterSettings = JsonWriterSettings.builder().indent(true).build()

    fun isDocumentDbProfile(profile: MongoConnectionProfile): Boolean {
        val uri = profile.effectiveConnectionUri().lowercase()
        return uri.contains("docdb.amazonaws.com") || !profile.effectiveRetryWrites()
    }

    /**
     * Parses single JSON object, JSON array (`[...]`), or NDJSON (`{...}\n{...}`) using standard `DocumentCodec`
     * so Extended JSON (`$oid`, `$date`, `$numberLong`, `$numberDecimal`) decodes cleanly into typed BSON objects.
     */
    fun parseJsonInput(rawText: String): Pair<List<ParsedImportDoc>, List<ImportValidationIssue>> {
        val noBom = rawText.trimStart('\uFEFF')
        val cleaned = noBom.trim()
        if (cleaned.isEmpty()) {
            return emptyList<ParsedImportDoc>() to listOf(
                ImportValidationIssue(
                    category = ImportErrorCategory.SYNTAX_AND_KEY,
                    docIndex = 0,
                    lineNumber = 1,
                    fieldPath = "$",
                    expected = "JSON ({...} / [...] / NDJSON)",
                    actual = "(empty)",
                    detail = I18n.t("import.err.emptyInput")
                )
            )
        }

        // Case 1: JSON Array `[ ... ]` parsed through DocumentCodec wrapper for consistent Document tree
        if (cleaned.startsWith("[")) {
            return try {
                val wrapper = Document.parse("{\"items\": $cleaned}")
                val rawItems = wrapper["items"] as? List<*>
                if (rawItems.isNullOrEmpty()) {
                    return emptyList<ParsedImportDoc>() to listOf(
                        ImportValidationIssue(
                            category = ImportErrorCategory.SYNTAX_AND_KEY,
                            docIndex = 0,
                            lineNumber = 1,
                            fieldPath = "$",
                            expected = "Non-empty JSON Object Array",
                            actual = "[]",
                            detail = I18n.t("import.err.emptyArray")
                        )
                    )
                }
                val docs = mutableListOf<ParsedImportDoc>()
                val issues = mutableListOf<ImportValidationIssue>()
                rawItems.forEachIndexed { idx, item ->
                    if (item is Document) {
                        docs.add(ParsedImportDoc(docIndex = idx + 1, lineNumber = null, document = item))
                    } else {
                        issues.add(
                            ImportValidationIssue(
                                category = ImportErrorCategory.SYNTAX_AND_KEY,
                                docIndex = idx + 1,
                                lineNumber = null,
                                fieldPath = "[$idx]",
                                expected = "JSON Object ({...})",
                                actual = previewValue(item),
                                detail = I18n.t("import.err.arrayNonObject", idx + 1)
                            )
                        )
                    }
                }
                docs to issues
            } catch (t: Throwable) {
                emptyList<ParsedImportDoc>() to listOf(
                    ImportValidationIssue(
                        category = ImportErrorCategory.SYNTAX_AND_KEY,
                        docIndex = 1,
                        lineNumber = extractLineFromParseError(cleaned, t),
                        fieldPath = "$",
                        expected = "Valid JSON Array ([...])",
                        actual = cleaned.take(80),
                        detail = t.message ?: t.javaClass.simpleName
                    )
                )
            }
        }

        // Case 2: Single JSON Object `{ ... }` OR NDJSON (line-delimited `{ ... }\n{ ... }`)
        if (cleaned.startsWith("{")) {
            val singleAttempt = runCatching { Document.parse(cleaned) }
            if (singleAttempt.isSuccess) {
                return listOf(ParsedImportDoc(1, 1, singleAttempt.getOrThrow())) to emptyList()
            }

            // Detect NDJSON: multiple non-blank lines where at least 2 lines begin with '{'
            val rawLines = noBom.split(Regex("\\r?\\n"))
            val nonBlankLines = rawLines.mapIndexedNotNull { idx, line ->
                val trimmedLine = line.trim()
                if (trimmedLine.isEmpty()) null else (idx + 1 to trimmedLine)
            }
            val braceLineCount = nonBlankLines.count { it.second.startsWith("{") }
            if (nonBlankLines.size >= 2 && braceLineCount >= 2) {
                val docs = mutableListOf<ParsedImportDoc>()
                val issues = mutableListOf<ImportValidationIssue>()
                nonBlankLines.forEachIndexed { idx, (lineNum, trimmedLine) ->
                    val docNum = idx + 1
                    try {
                        val doc = Document.parse(trimmedLine)
                        docs.add(ParsedImportDoc(docIndex = docNum, lineNumber = lineNum, document = doc))
                    } catch (t: Throwable) {
                        if (issues.size < MAX_ISSUES_COLLECTED) {
                            issues.add(
                                ImportValidationIssue(
                                    category = ImportErrorCategory.SYNTAX_AND_KEY,
                                    docIndex = docNum,
                                    lineNumber = lineNum,
                                    fieldPath = "$",
                                    expected = "Valid JSON Object (NDJSON line)",
                                    actual = trimmedLine.take(80),
                                    detail = t.message ?: t.javaClass.simpleName
                                )
                            )
                        }
                    }
                }
                return docs to issues
            }

            val err = singleAttempt.exceptionOrNull()!!
            return emptyList<ParsedImportDoc>() to listOf(
                ImportValidationIssue(
                    category = ImportErrorCategory.SYNTAX_AND_KEY,
                    docIndex = 1,
                    lineNumber = extractLineFromParseError(cleaned, err),
                    fieldPath = "$",
                    expected = "Valid JSON Object ({...})",
                    actual = cleaned.take(80),
                    detail = err.message ?: err.javaClass.simpleName
                )
            )
        }

        return emptyList<ParsedImportDoc>() to listOf(
            ImportValidationIssue(
                category = ImportErrorCategory.SYNTAX_AND_KEY,
                docIndex = 1,
                lineNumber = 1,
                fieldPath = "$",
                expected = "JSON Object ({...}) or Array ([...])",
                actual = cleaned.take(60),
                detail = I18n.t("import.err.mustStartWithBraceOrBracket")
            )
        )
    }

    /**
     * Formats valid single/array/NDJSON input into cleanly indented JSON.
     */
    fun formatPrettyJson(rawText: String): Result<String> {
        val (docs, issues) = parseJsonInput(rawText)
        if (issues.isNotEmpty()) {
            val first = issues.first()
            return Result.failure(IllegalArgumentException("${first.docLabel()}: ${first.detail}"))
        }
        return runCatching {
            if (docs.size == 1) {
                docs.first().document.toJson(PRETTY_SETTINGS)
            } else {
                val sb = StringBuilder("[\n")
                docs.forEachIndexed { idx, entry ->
                    val indented = entry.document.toJson(PRETTY_SETTINGS)
                        .lines()
                        .joinToString("\n") { "  $it" }
                    sb.append(indented)
                    if (idx < docs.lastIndex) sb.append(",\n") else sb.append("\n")
                }
                sb.append("]")
                sb.toString()
            }
        }
    }

    /**
     * Executes full 4-stage Pre-Flight Validation against the target collection without writing to DB.
     */
    fun runPreflightValidation(
        connectionManager: MongoConnectionManager,
        profile: MongoConnectionProfile,
        database: String,
        collection: String,
        rawText: String,
        stripIds: Boolean
    ): ImportPreflightResult {
        val (parsedDocs, parseIssues) = parseJsonInput(rawText)
        val issues = mutableListOf<ImportValidationIssue>()
        issues.addAll(parseIssues)

        if (parsedDocs.isEmpty() || issues.isNotEmpty()) {
            return ImportPreflightResult(emptyList(), issues, sampleCountUsed = 0)
        }

        // Option: strip existing `_id` so MongoDB auto-generates fresh ObjectIds
        if (stripIds) {
            parsedDocs.forEach { it.document.remove("_id") }
        }

        val isDocDb = isDocumentDbProfile(profile)

        // Stage 1: DB-specific server key constraints
        for (entry in parsedDocs) {
            validateKeysRecursively(entry, entry.document, "", isTopLevel = true, isDocDb = isDocDb, out = issues)
            if (issues.size >= MAX_ISSUES_COLLECTED) break
        }

        val rawColl = connectionManager.getRawDocumentCollection(profile, database, collection)

        // Stage 2: `_id` duplicate checks (in-batch + existing DB with Int/Long cross-check)
        if (!stripIds && issues.size < MAX_ISSUES_COLLECTED) {
            validateDuplicateIds(rawColl, parsedDocs, issues)
        }

        // Stage 3: Nested schema type compatibility against up to 50 sampled existing documents
        val sampleDocs = rawColl.find()
            .limit(SAMPLE_LIMIT)
            .batchSize(SAMPLE_LIMIT)
            .maxTime(10, TimeUnit.SECONDS)
            .into(mutableListOf())

        val schemaMap = linkedMapOf<String, LinkedHashSet<SchemaBsonKind>>()
        if (sampleDocs.isNotEmpty()) {
            for (sample in sampleDocs) {
                collectSchemaRecursively(sample, "", schemaMap)
            }
            for (entry in parsedDocs) {
                if (issues.size >= MAX_ISSUES_COLLECTED) break
                validateTypesAgainstSchemaRecursively(entry, entry.document, "", schemaMap, issues)
            }
        }

        if (issues.isNotEmpty()) {
            return ImportPreflightResult(emptyList(), issues, sampleCountUsed = sampleDocs.size)
        }

        // Stage 4 (Pre-write): Safe numeric widening promotion matching existing schema types
        val finalDocs = parsedDocs.map { entry ->
            if (schemaMap.isNotEmpty()) {
                promoteNumericTypesRecursively(entry.document, "", schemaMap)
            }
            entry.document
        }

        return ImportPreflightResult(
            validDocuments = finalDocs,
            issues = emptyList(),
            sampleCountUsed = sampleDocs.size
        )
    }

    /**
     * Executes chunked `insertMany` (`chunked(500)`, `ordered = true`, NO `maxTimeMS` for DocumentDB compatibility).
     */
    fun executeChunkedImport(
        connectionManager: MongoConnectionManager,
        profile: MongoConnectionProfile,
        database: String,
        collection: String,
        documents: List<Document>
    ): Int {
        check(!connectionManager.isEffectivelyReadOnly(profile)) {
            "Write blocked: profile '${profile.displayName}' is READ-ONLY"
        }
        val rawColl = connectionManager.getRawDocumentCollection(profile, database, collection)
        val options = InsertManyOptions().ordered(true)
        var inserted = 0
        for (chunk in documents.chunked(CHUNK_SIZE)) {
            rawColl.insertMany(chunk, options)
            inserted += chunk.size
        }
        connectionManager.invalidateMetaCacheForCollection(profile.id, database, collection)
        return inserted
    }

    private fun validateKeysRecursively(
        entry: ParsedImportDoc,
        doc: Document,
        prefix: String,
        isTopLevel: Boolean,
        isDocDb: Boolean,
        out: MutableList<ImportValidationIssue>
    ) {
        for ((k, v) in doc) {
            if (out.size >= MAX_ISSUES_COLLECTED) return
            val path = if (prefix.isEmpty()) k else "$prefix.$k"
            if (k.isEmpty() || k.indexOf('\u0000') >= 0) {
                out.add(
                    ImportValidationIssue(
                        category = ImportErrorCategory.SYNTAX_AND_KEY,
                        docIndex = entry.docIndex,
                        lineNumber = entry.lineNumber,
                        fieldPath = path.ifEmpty { "(empty key)" },
                        expected = I18n.t("import.rule.validFieldName"),
                        actual = k,
                        detail = I18n.t("import.err.invalidKeyChar")
                    )
                )
            }
            if (isTopLevel && k.startsWith("$")) {
                out.add(
                    ImportValidationIssue(
                        category = ImportErrorCategory.SYNTAX_AND_KEY,
                        docIndex = entry.docIndex,
                        lineNumber = entry.lineNumber,
                        fieldPath = k,
                        expected = I18n.t("import.rule.noDollarTopKey"),
                        actual = k,
                        detail = I18n.t("import.err.topLevelDollarKey", k)
                    )
                )
            }
            if (isTopLevel && k == "_id" && (v is List<*> || v is Array<*>)) {
                out.add(
                    ImportValidationIssue(
                        category = ImportErrorCategory.SYNTAX_AND_KEY,
                        docIndex = entry.docIndex,
                        lineNumber = entry.lineNumber,
                        fieldPath = "_id",
                        expected = I18n.t("import.rule.idNotArray"),
                        actual = previewValue(v),
                        detail = I18n.t("import.err.idCannotBeArray")
                    )
                )
            }
            if (isDocDb && k.contains('.')) {
                out.add(
                    ImportValidationIssue(
                        category = ImportErrorCategory.SYNTAX_AND_KEY,
                        docIndex = entry.docIndex,
                        lineNumber = entry.lineNumber,
                        fieldPath = path,
                        expected = I18n.t("import.rule.docDbNoDotKey"),
                        actual = k,
                        detail = I18n.t("import.err.docDbDotKey", path)
                    )
                )
            }
            when (v) {
                is Document -> validateKeysRecursively(entry, v, path, isTopLevel = false, isDocDb = isDocDb, out = out)
                is List<*> -> v.forEachIndexed { idx, elem ->
                    if (elem is Document) {
                        validateKeysRecursively(entry, elem, "$path.$idx", isTopLevel = false, isDocDb = isDocDb, out = out)
                    }
                }
            }
        }
    }

    private fun validateDuplicateIds(
        rawColl: com.mongodb.client.MongoCollection<Document>,
        parsedDocs: List<ParsedImportDoc>,
        out: MutableList<ImportValidationIssue>
    ) {
        val firstSeenByKey = linkedMapOf<String, Int>()
        val queryCandidates = linkedSetOf<Any>()
        val docsWithId = mutableListOf<Triple<ParsedImportDoc, Any, String>>()

        for (entry in parsedDocs) {
            if (!entry.document.containsKey("_id")) continue
            val rawId = entry.document["_id"] ?: continue
            val canonKey = canonicalIdKey(rawId)
            docsWithId.add(Triple(entry, rawId, canonKey))

            val firstIdx = firstSeenByKey.putIfAbsent(canonKey, entry.docIndex)
            if (firstIdx != null) {
                out.add(
                    ImportValidationIssue(
                        category = ImportErrorCategory.DUPLICATE_ID,
                        docIndex = entry.docIndex,
                        lineNumber = entry.lineNumber,
                        fieldPath = "_id",
                        expected = I18n.t("import.rule.uniqueId"),
                        actual = previewValue(rawId),
                        detail = I18n.t("import.err.duplicateIdInBatch", firstIdx, previewValue(rawId))
                    )
                )
            } else {
                addIdQueryCandidates(rawId, queryCandidates)
            }
        }

        if (queryCandidates.isEmpty()) return

        val existingDbKeys = hashSetOf<String>()
        for (chunk in queryCandidates.toList().chunked(CHUNK_SIZE)) {
            rawColl.find(Filters.`in`("_id", chunk))
                .projection(Projections.include("_id"))
                .maxTime(10, TimeUnit.SECONDS)
                .forEach { foundDoc ->
                    val foundId = foundDoc["_id"]
                    if (foundId != null) {
                        existingDbKeys.add(canonicalIdKey(foundId))
                    }
                }
        }

        if (existingDbKeys.isEmpty()) return
        for ((entry, rawId, canonKey) in docsWithId) {
            if (out.size >= MAX_ISSUES_COLLECTED) break
            if (canonKey in existingDbKeys) {
                out.add(
                    ImportValidationIssue(
                        category = ImportErrorCategory.DUPLICATE_ID,
                        docIndex = entry.docIndex,
                        lineNumber = entry.lineNumber,
                        fieldPath = "_id",
                        expected = I18n.t("import.rule.uniqueIdInDb"),
                        actual = previewValue(rawId),
                        detail = I18n.t("import.err.duplicateIdInDb", previewValue(rawId))
                    )
                )
            }
        }
    }

    private fun canonicalIdKey(id: Any): String = when (id) {
        is Int -> "INT:${id.toLong()}"
        is Long -> "INT:$id"
        is Double -> {
            if (id.isFinite() && id % 1.0 == 0.0 && id >= Long.MIN_VALUE.toDouble() && id <= Long.MAX_VALUE.toDouble()) {
                "INT:${id.toLong()}"
            } else {
                "DBL:$id"
            }
        }
        is Decimal128 -> {
            val bd = id.bigDecimalValue().stripTrailingZeros()
            if (bd.scale() <= 0) "INT:${bd.toPlainString()}" else "DEC:${bd.toPlainString()}"
        }
        is ObjectId -> "OID:${id.toHexString()}"
        is Date -> "DATE:${id.time}"
        is String -> "STR:$id"
        else -> "${id.javaClass.name}:$id"
    }

    private fun addIdQueryCandidates(rawId: Any, out: MutableSet<Any>) {
        out.add(rawId)
        when (rawId) {
            is Int -> out.add(rawId.toLong())
            is Long -> {
                if (rawId in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
                    out.add(rawId.toInt())
                }
            }
            is Decimal128 -> {
                val bd = rawId.bigDecimalValue().stripTrailingZeros()
                if (bd.scale() <= 0) {
                    runCatching {
                        val l = bd.longValueExact()
                        out.add(l)
                        if (l in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) out.add(l.toInt())
                    }
                }
            }
        }
    }

    private fun collectSchemaRecursively(
        doc: Document,
        prefix: String,
        schemaMap: MutableMap<String, LinkedHashSet<SchemaBsonKind>>
    ) {
        for ((k, v) in doc) {
            if (v == null) continue
            val path = if (prefix.isEmpty()) k else "$prefix.$k"
            val kind = inferKind(v) ?: continue
            schemaMap.computeIfAbsent(path) { linkedSetOf() }.add(kind)
            if (v is Document) {
                collectSchemaRecursively(v, path, schemaMap)
            }
        }
    }

    private fun validateTypesAgainstSchemaRecursively(
        entry: ParsedImportDoc,
        doc: Document,
        prefix: String,
        schemaMap: Map<String, Set<SchemaBsonKind>>,
        out: MutableList<ImportValidationIssue>
    ) {
        for ((k, v) in doc) {
            if (out.size >= MAX_ISSUES_COLLECTED) return
            if (v == null) continue // null is always allowed
            val path = if (prefix.isEmpty()) k else "$prefix.$k"
            val expectedKinds = schemaMap[path]
            val actualKind = inferKind(v)

            if (!expectedKinds.isNullOrEmpty() && actualKind != null) {
                if (!isKindCompatible(actualKind, expectedKinds)) {
                    out.add(
                        ImportValidationIssue(
                            category = ImportErrorCategory.TYPE_MISMATCH,
                            docIndex = entry.docIndex,
                            lineNumber = entry.lineNumber,
                            fieldPath = path,
                            expected = expectedKinds.joinToString(" | ") { it.label },
                            actual = "${actualKind.label} (${previewValue(v)})",
                            detail = I18n.t(
                                "import.err.typeMismatch",
                                path,
                                expectedKinds.joinToString("/") { it.label },
                                actualKind.label,
                                previewValue(v)
                            )
                        )
                    )
                    continue
                }
            }

            if (v is Document) {
                validateTypesAgainstSchemaRecursively(entry, v, path, schemaMap, out)
            }
        }
    }

    private fun isKindCompatible(actual: SchemaBsonKind, expected: Set<SchemaBsonKind>): Boolean {
        if (actual in expected) return true
        // Numeric widening compatibility: Int32 <-> Int64, integer -> Double/Decimal128, Double -> Decimal128
        if ((actual == SchemaBsonKind.INT32 || actual == SchemaBsonKind.INT64) && expected.any { it.isNumeric() }) {
            return true
        }
        if (actual == SchemaBsonKind.DOUBLE && SchemaBsonKind.DECIMAL128 in expected) {
            return true
        }
        return false
    }

    private fun promoteNumericTypesRecursively(
        doc: Document,
        prefix: String,
        schemaMap: Map<String, Set<SchemaBsonKind>>
    ) {
        for (k in doc.keys.toList()) {
            val v = doc[k] ?: continue
            val path = if (prefix.isEmpty()) k else "$prefix.$k"
            val expectedKinds = schemaMap[path]
            if (!expectedKinds.isNullOrEmpty() && v is Number) {
                val promoted = promoteNumberIfNeeded(v, expectedKinds)
                if (promoted !== v) {
                    doc[k] = promoted
                }
            } else if (v is Document) {
                promoteNumericTypesRecursively(v, path, schemaMap)
            }
        }
    }

    private fun promoteNumberIfNeeded(num: Number, expected: Set<SchemaBsonKind>): Any {
        return when {
            SchemaBsonKind.DECIMAL128 in expected && num !is Decimal128 -> {
                runCatching {
                    when (num) {
                        is Double -> Decimal128(BigDecimal.valueOf(num))
                        else -> Decimal128(BigDecimal(num.toString()))
                    }
                }.getOrElse { num }
            }
            SchemaBsonKind.DOUBLE in expected && (num is Int || num is Long) -> num.toDouble()
            SchemaBsonKind.INT64 in expected && num is Int -> num.toLong()
            SchemaBsonKind.INT32 in expected && num is Long -> {
                // Only downcast to Int32 if within Int32 bounds; preserve Long without truncation otherwise
                if (num in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) num.toInt() else num
            }
            else -> num
        }
    }

    private fun inferKind(v: Any): SchemaBsonKind? = when (v) {
        is Int -> SchemaBsonKind.INT32
        is Long -> SchemaBsonKind.INT64
        is Double, is Float -> SchemaBsonKind.DOUBLE
        is Decimal128, is BigDecimal -> SchemaBsonKind.DECIMAL128
        is String -> SchemaBsonKind.STRING
        is Boolean -> SchemaBsonKind.BOOLEAN
        is ObjectId -> SchemaBsonKind.OBJECT_ID
        is Date -> SchemaBsonKind.DATE
        is List<*>, is Array<*> -> SchemaBsonKind.ARRAY
        is Document -> SchemaBsonKind.OBJECT
        is Binary, is ByteArray -> SchemaBsonKind.BINARY
        else -> null
    }

    private fun previewValue(v: Any?): String {
        val raw = when (v) {
            null -> "null"
            is String -> "\"$v\""
            is ObjectId -> "ObjectId(\"${v.toHexString()}\")"
            is Date -> "ISODate(\"${v.toInstant()}\")"
            is List<*> -> "[${v.size} items]"
            is Document -> "{${v.size} fields}"
            else -> v.toString()
        }
        return if (raw.length > 64) "${raw.take(61)}..." else raw
    }

    private fun extractLineFromParseError(text: String, t: Throwable): Int? {
        val msg = t.message ?: return 1
        val posMatch = Regex("position\\s+(\\d+)", RegexOption.IGNORE_CASE).find(msg)
        val pos = posMatch?.groupValues?.getOrNull(1)?.toIntOrNull() ?: return 1
        val bounded = pos.coerceIn(0, text.length)
        return text.substring(0, bounded).count { it == '\n' } + 1
    }
}
