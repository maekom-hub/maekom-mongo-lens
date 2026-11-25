package com.maekom.mongolens.mongo

import com.maekom.mongolens.config.MongoConnectionProfile
import org.bson.BsonArray
import org.bson.BsonBoolean
import org.bson.BsonDocument
import org.bson.BsonInt64
import org.bson.BsonString
import org.bson.BsonValue
import java.util.concurrent.TimeUnit

object MongoShellExecutor {

    private data class ParsedInvocation(
        val collectionName: String,
        val methodName: String,
        val argsRaw: String,
        val chainRaw: String
    )

    fun isWriteOperation(script: String): Boolean {
        val cleaned = stripComments(script)
        return Regex("""\.(insertOne|insertMany|updateOne|updateMany|replaceOne|deleteOne|deleteMany|findOneAndUpdate|findOneAndDelete|drop)\s*\(""")
            .containsMatchIn(cleaned)
    }

    fun execute(
        connectionManager: MongoConnectionManager,
        profile: MongoConnectionProfile,
        database: String,
        defaultCollection: String,
        rawScript: String,
        userVariables: Map<String, String>
    ): QueryExecutionResult {
        val resolvedScript = VariableResolver.resolve(rawScript, userVariables)
        val cleaned = stripComments(resolvedScript).trim().trimEnd(';')
        if (cleaned.isEmpty()) {
            throw IllegalArgumentException("Shell script is empty.")
        }

        val inv = parseInvocation(cleaned, defaultCollection)
        val args = splitTopLevelArgs(inv.argsRaw)
        val client = connectionManager.getOrCreateClient(profile)
        val db = client.getDatabase(database)
        val coll = db.getCollection(inv.collectionName, BsonDocument::class.java)

        val startNs = System.nanoTime()

        return when (inv.methodName) {
            "find" -> {
                val filterRaw = args.getOrNull(0)?.takeIf { it.isNotBlank() } ?: "{}"
                val projRaw = args.getOrNull(1)?.takeIf { it.isNotBlank() } ?: "{}"
                val chains = parseChainedCalls(inv.chainRaw)
                val sortRaw = chains["sort"] ?: "{}"
                val skip = chains["skip"]?.trim()?.toIntOrNull() ?: 0
                val limit = chains["limit"]?.trim()?.toIntOrNull() ?: 50
                connectionManager.executeFind(
                    profile = profile,
                    database = database,
                    collection = inv.collectionName,
                    filterRaw = filterRaw,
                    projectionRaw = projRaw,
                    sortRaw = sortRaw,
                    skip = skip,
                    limit = limit
                )
            }

            "findOne" -> {
                val filterRaw = args.getOrNull(0)?.takeIf { it.isNotBlank() } ?: "{}"
                val projRaw = args.getOrNull(1)?.takeIf { it.isNotBlank() } ?: "{}"
                connectionManager.executeFind(
                    profile = profile,
                    database = database,
                    collection = inv.collectionName,
                    filterRaw = filterRaw,
                    projectionRaw = projRaw,
                    sortRaw = "{}",
                    skip = 0,
                    limit = 1
                )
            }

            "countDocuments" -> {
                val filter = MongoShellNormalizer.parseBsonDocument(args.getOrNull(0) ?: "{}")
                val count = coll.countDocuments(filter)
                val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs)
                val doc = BsonDocument("collection", BsonString(inv.collectionName))
                    .append("count", BsonInt64(count))
                QueryExecutionResult(listOf(doc), 1, false, elapsedMs, IndexScanType.UNKNOWN, null, listOf("collection", "count"))
            }

            "estimatedDocumentCount" -> {
                val count = coll.estimatedDocumentCount()
                val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs)
                val doc = BsonDocument("collection", BsonString(inv.collectionName))
                    .append("estimatedCount", BsonInt64(count))
                QueryExecutionResult(listOf(doc), 1, false, elapsedMs, IndexScanType.UNKNOWN, null, listOf("collection", "estimatedCount"))
            }

            "distinct" -> {
                val fieldArg = args.getOrNull(0)?.trim()?.trim('"', '\'')
                    ?: throw IllegalArgumentException("distinct() requires a field name as first argument.")
                val filter = MongoShellNormalizer.parseBsonDocument(args.getOrNull(1) ?: "{}")
                val values = coll.distinct(fieldArg, filter, BsonValue::class.java).into(mutableListOf())
                val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs)
                val arr = BsonArray(values)
                val doc = BsonDocument("field", BsonString(fieldArg))
                    .append("count", BsonInt64(values.size.toLong()))
                    .append("values", arr)
                QueryExecutionResult(listOf(doc), 1, false, elapsedMs, IndexScanType.UNKNOWN, null, listOf("field", "count", "values"))
            }

            "aggregate" -> {
                val pipelineRaw = args.getOrNull(0)?.takeIf { it.isNotBlank() } ?: "[]"
                val pipeline = MongoShellNormalizer.parsePipeline(pipelineRaw)
                connectionManager.executeAggregate(profile, database, inv.collectionName, pipeline)
            }

            "updateOne", "updateMany" -> {
                check(!connectionManager.isEffectivelyReadOnly(profile)) {
                    "Write blocked: profile '${profile.displayName}' is READ-ONLY"
                }
                val filter = MongoShellNormalizer.parseBsonDocument(args.getOrNull(0) ?: throw IllegalArgumentException("${inv.methodName} requires filter and update arguments."))
                val update = MongoShellNormalizer.parseBsonDocument(args.getOrNull(1) ?: throw IllegalArgumentException("${inv.methodName} requires update document as 2nd argument."))
                val res = if (inv.methodName == "updateOne") {
                    coll.updateOne(filter, update)
                } else {
                    coll.updateMany(filter, update)
                }
                val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs)
                val doc = BsonDocument("acknowledged", BsonBoolean(res.wasAcknowledged()))
                    .append("matchedCount", BsonInt64(res.matchedCount))
                    .append("modifiedCount", BsonInt64(res.modifiedCount))
                QueryExecutionResult(listOf(doc), 1, false, elapsedMs, IndexScanType.UNKNOWN, null, listOf("acknowledged", "matchedCount", "modifiedCount"))
            }

            "deleteOne", "deleteMany" -> {
                check(!connectionManager.isEffectivelyReadOnly(profile)) {
                    "Write blocked: profile '${profile.displayName}' is READ-ONLY"
                }
                val filter = MongoShellNormalizer.parseBsonDocument(args.getOrNull(0) ?: throw IllegalArgumentException("${inv.methodName} requires a filter document."))
                val res = if (inv.methodName == "deleteOne") {
                    coll.deleteOne(filter)
                } else {
                    coll.deleteMany(filter)
                }
                val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs)
                val doc = BsonDocument("acknowledged", BsonBoolean(res.wasAcknowledged()))
                    .append("deletedCount", BsonInt64(res.deletedCount))
                QueryExecutionResult(listOf(doc), 1, false, elapsedMs, IndexScanType.UNKNOWN, null, listOf("acknowledged", "deletedCount"))
            }

            "insertOne" -> {
                check(!connectionManager.isEffectivelyReadOnly(profile)) {
                    "Write blocked: profile '${profile.displayName}' is READ-ONLY"
                }
                val toInsert = MongoShellNormalizer.parseBsonDocument(args.getOrNull(0) ?: throw IllegalArgumentException("insertOne requires a document."))
                val res = coll.insertOne(toInsert)
                val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs)
                val doc = BsonDocument("acknowledged", BsonBoolean(res.wasAcknowledged()))
                res.insertedId?.let { doc.append("insertedId", it) }
                QueryExecutionResult(listOf(doc), 1, false, elapsedMs, IndexScanType.UNKNOWN, null, listOf("acknowledged", "insertedId"))
            }

            else -> throw IllegalArgumentException("Unsupported mongosh method: '${inv.methodName}'")
        }
    }

    private fun parseInvocation(script: String, defaultCollection: String): ParsedInvocation {
        val getCollRegex = Regex("""^db\.getCollection\(\s*["']([^"']+)["']\s*\)\.(\w+)\s*\(""")
        val directCollRegex = Regex("""^db\.([A-Za-z0-9_$-]+)\.(\w+)\s*\(""")

        val matchGet = getCollRegex.find(script)
        val (collection, method, openParenIndex) = if (matchGet != null) {
            Triple(matchGet.groupValues[1], matchGet.groupValues[2], matchGet.range.last)
        } else {
            val matchDirect = directCollRegex.find(script)
                ?: throw IllegalArgumentException(
                    "Expected syntax: db.<collection>.<method>(...) or db.getCollection('<collection>').<method>(...)"
                )
            Triple(matchDirect.groupValues[1].ifBlank { defaultCollection }, matchDirect.groupValues[2], matchDirect.range.last)
        }

        val closeParenIndex = findMatchingParen(script, openParenIndex)
        if (closeParenIndex < 0) {
            throw IllegalArgumentException("Unclosed '(' in $method(...)")
        }

        val argsRaw = script.substring(openParenIndex + 1, closeParenIndex)
        val chainRaw = script.substring(closeParenIndex + 1).trim()
        return ParsedInvocation(collection, method, argsRaw, chainRaw)
    }

    private fun parseChainedCalls(chainRaw: String): Map<String, String> {
        val result = linkedMapOf<String, String>()
        var idx = 0
        val regex = Regex("""\.\s*(\w+)\s*\(""")
        while (idx < chainRaw.length) {
            val m = regex.find(chainRaw, idx) ?: break
            val method = m.groupValues[1]
            val openParen = m.range.last
            val closeParen = findMatchingParen(chainRaw, openParen)
            if (closeParen < 0) break
            result[method] = chainRaw.substring(openParen + 1, closeParen).trim()
            idx = closeParen + 1
        }
        return result
    }

    private fun findMatchingParen(src: String, openIdx: Int): Int {
        var depth = 0
        var quote: Char? = null
        var i = openIdx
        while (i < src.length) {
            val c = src[i]
            if (quote != null) {
                if (c == '\\') {
                    i += 2
                    continue
                }
                if (c == quote) quote = null
            } else {
                when (c) {
                    '"', '\'' -> quote = c
                    '(' -> depth++
                    ')' -> {
                        depth--
                        if (depth == 0) return i
                    }
                }
            }
            i++
        }
        return -1
    }

    private fun splitTopLevelArgs(argsRaw: String): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var parenDepth = 0
        var braceDepth = 0
        var bracketDepth = 0
        var quote: Char? = null
        var i = 0

        while (i < argsRaw.length) {
            val c = argsRaw[i]
            if (quote != null) {
                current.append(c)
                if (c == '\\' && i + 1 < argsRaw.length) {
                    current.append(argsRaw[i + 1])
                    i += 2
                    continue
                }
                if (c == quote) quote = null
                i++
                continue
            }

            when (c) {
                '"', '\'' -> {
                    quote = c
                    current.append(c)
                }
                '(' -> { parenDepth++; current.append(c) }
                ')' -> { parenDepth--; current.append(c) }
                '{' -> { braceDepth++; current.append(c) }
                '}' -> { braceDepth--; current.append(c) }
                '[' -> { bracketDepth++; current.append(c) }
                ']' -> { bracketDepth--; current.append(c) }
                ',' -> {
                    if (parenDepth == 0 && braceDepth == 0 && bracketDepth == 0) {
                        result.add(current.toString().trim())
                        current.setLength(0)
                    } else {
                        current.append(c)
                    }
                }
                else -> current.append(c)
            }
            i++
        }

        val last = current.toString().trim()
        if (last.isNotEmpty() || result.isNotEmpty()) {
            result.add(last)
        }
        return result
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
}
