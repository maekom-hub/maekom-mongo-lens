package com.maekom.mongolens.mongo

import org.bson.BsonArray
import org.bson.BsonDocument
import org.bson.json.JsonMode
import org.bson.json.JsonWriterSettings

object MongoShellNormalizer {
    private val objectIdRegex = Regex("""(?:new\s+)?ObjectId\s*\(\s*["']([0-9a-fA-F]{24})["']\s*\)""")
    private val isoDateRegex = Regex("""(?:new\s+)?ISODate\s*\(\s*["']([^"']+)["']\s*\)""")
    private val newDateStrRegex = Regex("""new\s+Date\s*\(\s*["']([^"']+)["']\s*\)""")
    private val newDateNumRegex = Regex("""new\s+Date\s*\(\s*(-?\d+)\s*\)""")
    private val numberLongRegex = Regex("""NumberLong\s*\(\s*["']?(-?\d+)["']?\s*\)""")
    private val numberIntRegex = Regex("""NumberInt\s*\(\s*["']?(-?\d+)["']?\s*\)""")
    private val numberDecimalRegex = Regex("""NumberDecimal\s*\(\s*["']?(-?\d+(?:\.\d+)?)["']?\s*\)""")
    private val uuidRegex = Regex("""UUID\s*\(\s*["']([^"']+)["']\s*\)""")
    private val hex24Regex = Regex("^[0-9a-fA-F]{24}$")
    private val integerRegex = Regex("^-?(?:0|[1-9]\\d*)$")

    private val extendedPrettySettings: JsonWriterSettings = JsonWriterSettings.builder()
        .outputMode(JsonMode.EXTENDED)
        .indent(true)
        .build()

    private val relaxedCompactSettings: JsonWriterSettings = JsonWriterSettings.builder()
        .outputMode(JsonMode.RELAXED)
        .indent(false)
        .build()

    fun formatPrettyExtended(doc: BsonDocument): String = doc.toJson(extendedPrettySettings)

    fun formatCompact(doc: BsonDocument): String = doc.toJson(relaxedCompactSettings)

    fun parseBsonDocument(raw: String): BsonDocument {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return BsonDocument()
        val normalized = normalizeToExtendedJson(trimmed)
        return BsonDocument.parse(normalized)
    }

    fun parsePipeline(raw: String): List<BsonDocument> {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return emptyList()
        val normalized = normalizeToExtendedJson(trimmed)
        val array = BsonArray.parse(normalized)
        return array.mapIndexed { idx, value ->
            if (!value.isDocument) {
                throw IllegalArgumentException("Stage #${idx + 1} must be a JSON object")
            }
            value.asDocument()
        }
    }

    fun normalizeToExtendedJson(input: String): String {
        val noComments = stripComments(input)
        val withHelpers = replaceShellConstructorsOutsideStrings(noComments)
        val doubleQuoted = convertSingleQuotesToDoubleQuotes(withHelpers)
        val quotedKeys = quoteUnquotedKeys(doubleQuoted)
        return removeTrailingCommas(quotedKeys).trim()
    }

    private fun stripComments(src: String): String {
        val sb = StringBuilder(src.length)
        var i = 0
        var quote: Char? = null
        while (i < src.length) {
            val c = src[i]
            if (quote != null) {
                sb.append(c)
                if (c == '\\' && i + 1 < src.length) {
                    sb.append(src[i + 1])
                    i += 2
                    continue
                }
                if (c == quote) quote = null
                i++
                continue
            }
            if (c == '"' || c == '\'') {
                quote = c
                sb.append(c)
                i++
                continue
            }
            if (c == '/' && i + 1 < src.length && src[i + 1] == '/') {
                i += 2
                while (i < src.length && src[i] != '\n') i++
                continue
            }
            if (c == '/' && i + 1 < src.length && src[i + 1] == '*') {
                i += 2
                while (i + 1 < src.length && !(src[i] == '*' && src[i + 1] == '/')) i++
                i += 2
                continue
            }
            sb.append(c)
            i++
        }
        return sb.toString()
    }

    private fun replaceShellConstructorsOutsideStrings(src: String): String {
        // Process only non-string segments so string values containing "ObjectId(...)" remain untouched
        val sb = StringBuilder(src.length)
        val outside = StringBuilder()
        var i = 0
        var quote: Char? = null

        fun flushOutside() {
            if (outside.isEmpty()) return
            var chunk = outside.toString()
            chunk = objectIdRegex.replace(chunk) { """{"${'$'}oid": "${it.groupValues[1]}"}""" }
            chunk = isoDateRegex.replace(chunk) { """{"${'$'}date": "${it.groupValues[1]}"}""" }
            chunk = newDateStrRegex.replace(chunk) { """{"${'$'}date": "${it.groupValues[1]}"}""" }
            chunk = newDateNumRegex.replace(chunk) { """{"${'$'}date": {"${'$'}numberLong": "${it.groupValues[1]}"}}""" }
            chunk = numberLongRegex.replace(chunk) { """{"${'$'}numberLong": "${it.groupValues[1]}"}""" }
            chunk = numberIntRegex.replace(chunk) { it.groupValues[1] }
            chunk = numberDecimalRegex.replace(chunk) { """{"${'$'}numberDecimal": "${it.groupValues[1]}"}""" }
            chunk = uuidRegex.replace(chunk) { """{"${'$'}uuid": "${it.groupValues[1]}"}""" }
            sb.append(chunk)
            outside.setLength(0)
        }

        // Wait: shell constructors themselves contain quotes for their argument, e.g., ObjectId("...").
        // So first detect constructors directly when outside a string!
        while (i < src.length) {
            val c = src[i]
            if (quote == null) {
                val match = matchHelperAt(src, i)
                if (match != null) {
                    flushOutside()
                    sb.append(match.second)
                    i = match.first
                    continue
                }
                if (c == '"' || c == '\'') {
                    flushOutside()
                    quote = c
                    sb.append(c)
                    i++
                    continue
                }
                outside.append(c)
                i++
            } else {
                sb.append(c)
                if (c == '\\' && i + 1 < src.length) {
                    sb.append(src[i + 1])
                    i += 2
                    continue
                }
                if (c == quote) quote = null
                i++
            }
        }
        flushOutside()
        return sb.toString()
    }

    private fun matchHelperAt(src: String, pos: Int): Pair<Int, String>? {
        val c = src[pos]
        if (c != 'O' && c != 'I' && c != 'N' && c != 'U' && c != 'n') return null
        val helpers: List<Pair<Regex, (MatchResult) -> String>> = listOf(
            objectIdRegex to { m -> """{"${'$'}oid": "${m.groupValues[1]}"}""" },
            isoDateRegex to { m -> """{"${'$'}date": "${m.groupValues[1]}"}""" },
            newDateStrRegex to { m -> """{"${'$'}date": "${m.groupValues[1]}"}""" },
            newDateNumRegex to { m -> """{"${'$'}date": {"${'$'}numberLong": "${m.groupValues[1]}"}}""" },
            numberLongRegex to { m -> """{"${'$'}numberLong": "${m.groupValues[1]}"}""" },
            numberIntRegex to { m -> m.groupValues[1] },
            numberDecimalRegex to { m -> """{"${'$'}numberDecimal": "${m.groupValues[1]}"}""" },
            uuidRegex to { m -> """{"${'$'}uuid": "${m.groupValues[1]}"}""" }
        )
        for ((regex, replacer) in helpers) {
            val m = regex.matchAt(src, pos)
            if (m != null) {
                return (m.range.last + 1) to replacer(m)
            }
        }
        return null
    }

    private fun convertSingleQuotesToDoubleQuotes(src: String): String {
        val sb = StringBuilder(src.length)
        var i = 0
        var inDouble = false
        var inSingle = false
        while (i < src.length) {
            val c = src[i]
            if (inDouble) {
                sb.append(c)
                if (c == '\\' && i + 1 < src.length) {
                    sb.append(src[i + 1])
                    i += 2
                    continue
                }
                if (c == '"') inDouble = false
                i++
            } else if (inSingle) {
                if (c == '\\' && i + 1 < src.length) {
                    val next = src[i + 1]
                    if (next == '\'') {
                        sb.append('\'')
                    } else {
                        sb.append('\\').append(next)
                    }
                    i += 2
                    continue
                }
                if (c == '\'') {
                    sb.append('"')
                    inSingle = false
                } else if (c == '"') {
                    sb.append("\\\"")
                } else {
                    sb.append(c)
                }
                i++
            } else {
                if (c == '"') {
                    inDouble = true
                    sb.append(c)
                } else if (c == '\'') {
                    inSingle = true
                    sb.append('"')
                } else {
                    sb.append(c)
                }
                i++
            }
        }
        return sb.toString()
    }

    private fun quoteUnquotedKeys(src: String): String {
        val sb = StringBuilder(src.length + 16)
        var i = 0
        var inDouble = false

        while (i < src.length) {
            val c = src[i]
            if (inDouble) {
                sb.append(c)
                if (c == '\\' && i + 1 < src.length) {
                    sb.append(src[i + 1])
                    i += 2
                    continue
                }
                if (c == '"') inDouble = false
                i++
                continue
            }

            if (c == '"') {
                inDouble = true
                sb.append(c)
                i++
                continue
            }

            // Detect unquoted identifier key right after '{' or ',' (with optional whitespace)
            if (isKeyStartChar(c) && isPrecededByObjectKeyDelimiter(sb)) {
                val start = i
                while (i < src.length && isKeyPartChar(src[i])) i++
                val token = src.substring(start, i)
                var j = i
                while (j < src.length && src[j].isWhitespace()) j++
                if (j < src.length && src[j] == ':') {
                    sb.append('"').append(token).append('"')
                    continue
                } else {
                    sb.append(token)
                    continue
                }
            }

            sb.append(c)
            i++
        }
        return sb.toString()
    }

    private fun isKeyStartChar(c: Char): Boolean = c.isLetter() || c == '_' || c == '$'

    private fun isKeyPartChar(c: Char): Boolean = c.isLetterOrDigit() || c == '_' || c == '$' || c == '.' || c == '-'

    private fun isPrecededByObjectKeyDelimiter(sb: StringBuilder): Boolean {
        for (idx in sb.length - 1 downTo 0) {
            val ch = sb[idx]
            if (ch.isWhitespace()) continue
            return ch == '{' || ch == ','
        }
        return false
    }

    private fun removeTrailingCommas(src: String): String {
        val sb = StringBuilder(src.length)
        var i = 0
        var inDouble = false
        while (i < src.length) {
            val c = src[i]
            if (inDouble) {
                sb.append(c)
                if (c == '\\' && i + 1 < src.length) {
                    sb.append(src[i + 1])
                    i += 2
                    continue
                }
                if (c == '"') inDouble = false
                i++
                continue
            }
            if (c == '"') {
                inDouble = true
                sb.append(c)
                i++
                continue
            }
            if (c == ',') {
                var j = i + 1
                while (j < src.length && src[j].isWhitespace()) j++
                if (j < src.length && (src[j] == '}' || src[j] == ']')) {
                    i++
                    continue
                }
            }
            sb.append(c)
            i++
        }
        return sb.toString()
    }

    fun buildSmartInQuery(rawInput: String, fieldName: String = "_id"): String {
        val cleanField = fieldName.trim().ifEmpty { "_id" }
        val tokens = rawInput
            .split(Regex("[\\r\\n,;\\t\\s]+"))
            .map { it.trim().trim('"', '\'') }
            .filter { it.isNotEmpty() }
            .distinct()

        if (tokens.isEmpty()) {
            return """{ "${escapeJsonString(cleanField)}": { "${'$'}in": [] } }"""
        }

        val items = tokens.map { token ->
            when {
                hex24Regex.matches(token) -> """{ "${'$'}oid": "$token" }"""
                integerRegex.matches(token) && token.toLongOrNull() != null -> token
                else -> "\"${escapeJsonString(token)}\""
            }
        }

        val joined = if (items.size <= 3) {
            items.joinToString(", ")
        } else {
            "\n    " + items.joinToString(",\n    ") + "\n  "
        }

        return "{\n  \"${escapeJsonString(cleanField)}\": { \"\$in\": [$joined] }\n}"
    }

    fun escapeJsonString(s: String): String {
        return buildString(s.length) {
            for (c in s) {
                when (c) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> append(c)
                }
            }
        }
    }

    fun toMongoshFindCommand(
        collection: String,
        filter: String,
        projection: String,
        sort: String,
        skip: Int,
        limit: Int
    ): String {
        val f = filter.trim().ifEmpty { "{}" }
        val p = projection.trim()
        val s = sort.trim()

        val sb = StringBuilder()
        sb.append("db.getCollection('${collection.replace("'", "\\'")}').find(")
        sb.append(f)
        if (p.isNotEmpty() && p != "{}") {
            sb.append(", ").append(p)
        }
        sb.append(")")
        if (s.isNotEmpty() && s != "{}") {
            sb.append(".sort(").append(s).append(")")
        }
        if (skip > 0) {
            sb.append(".skip(").append(skip).append(")")
        }
        if (limit > 0) {
            sb.append(".limit(").append(limit).append(")")
        }
        return sb.toString()
    }

    fun toMongoshAggregateCommand(collection: String, pipelineJson: String): String {
        val p = pipelineJson.trim().ifEmpty { "[]" }
        return "db.getCollection('${collection.replace("'", "\\'")}').aggregate($p)"
    }
}
