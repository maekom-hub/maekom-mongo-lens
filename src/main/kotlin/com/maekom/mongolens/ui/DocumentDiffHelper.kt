package com.maekom.mongolens.ui

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.maekom.mongolens.crypto.CryptoService
import com.maekom.mongolens.crypto.ProfileCryptoConfig
import com.maekom.mongolens.i18n.I18n
import com.maekom.mongolens.mongo.MongoShellNormalizer
import org.bson.BsonArray
import org.bson.BsonDocument
import org.bson.BsonString
import org.bson.BsonValue
import java.util.TreeMap
import java.util.concurrent.CopyOnWriteArrayList
import javax.swing.SwingUtilities

data class BaselineSnapshot(
    val fullLabel: String,
    val shortId: String,
    val canonicalDoc: BsonDocument
)

/**
 * Project-scoped Diff Baseline holder shared across all workspace tabs so a document pinned
 * in one tab/collection/profile can be compared against any document in the same or another tab.
 */
class DiffBaselineState {
    @Volatile
    var current: BaselineSnapshot? = null
        private set

    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    fun addListener(parentDisposable: Disposable, listener: () -> Unit) {
        listeners.add(listener)
        Disposer.register(parentDisposable) {
            listeners.remove(listener)
        }
    }

    fun pin(fullLabel: String, shortId: String, doc: BsonDocument) {
        current = BaselineSnapshot(
            fullLabel = fullLabel,
            shortId = shortId,
            canonicalDoc = DocumentDiffHelper.deepCopy(doc)
        )
        notifyListeners()
    }

    fun clear() {
        if (current == null) return
        current = null
        notifyListeners()
    }

    private fun notifyListeners() {
        val run = Runnable {
            listeners.forEach { l ->
                try {
                    l()
                } catch (_: Throwable) {
                }
            }
        }
        if (SwingUtilities.isEventDispatchThread()) {
            run.run()
        } else {
            SwingUtilities.invokeLater(run)
        }
    }
}

object DocumentDiffHelper {

    fun deepCopy(doc: BsonDocument): BsonDocument {
        return try {
            BsonDocument.parse(MongoShellNormalizer.formatPrettyExtended(doc))
        } catch (_: Throwable) {
            doc.clone()
        }
    }

    /**
     * Resolves an isolated snapshot of a document for Diff comparison:
     * - If Table batch decryption is active, recursively decrypts all matching fields.
     * - If individual fields are currently revealed in Tree view (`perFieldPlaintexts`), overlays them.
     */
    fun resolveDocumentForDiff(
        rawDoc: BsonDocument,
        isBatchDecrypted: Boolean,
        cryptoConfig: ProfileCryptoConfig,
        secretKey: String,
        perFieldPlaintexts: Map<String, String>
    ): BsonDocument {
        var working = deepCopy(rawDoc)
        if (isBatchDecrypted && secretKey.isNotBlank()) {
            val res = CryptoService.decryptBsonValueRecursively(working, cryptoConfig, secretKey)
            if (res.value is BsonDocument) {
                working = res.value
            }
        }
        if (perFieldPlaintexts.isNotEmpty()) {
            for ((path, plain) in perFieldPlaintexts) {
                applyFieldPathPlaintext(working, path, plain)
            }
        }
        return working
    }

    private fun applyFieldPathPlaintext(root: BsonDocument, fieldPath: String, plaintext: String) {
        val segments = fieldPath.split('.').filter { it.isNotEmpty() }
        if (segments.isEmpty()) return
        var current: BsonValue = root
        for (i in 0 until segments.lastIndex) {
            val seg = segments[i]
            current = if (seg.startsWith("[") && seg.endsWith("]")) {
                val idx = seg.substring(1, seg.length - 1).toIntOrNull() ?: return
                val arr = current as? BsonArray ?: return
                if (idx !in 0 until arr.size) return
                arr[idx]
            } else {
                val obj = current as? BsonDocument ?: return
                obj[seg] ?: return
            }
        }
        val last = segments.last()
        if (last.startsWith("[") && last.endsWith("]")) {
            val idx = last.substring(1, last.length - 1).toIntOrNull() ?: return
            val arr = current as? BsonArray ?: return
            if (idx in 0 until arr.size) {
                arr[idx] = BsonString(plaintext)
            }
        } else {
            val obj = current as? BsonDocument ?: return
            if (obj.containsKey(last)) {
                obj[last] = BsonString(plaintext)
            }
        }
    }

    /**
     * Recursively sorts all nested BsonDocument keys alphabetically via TreeMap so field ordering
     * differences never produce false-positive diffs, then pretty-prints as Extended JSON.
     */
    fun canonicalSortAndPrettyPrint(doc: BsonDocument): String {
        val sorted = sortBsonValue(doc) as BsonDocument
        return MongoShellNormalizer.formatPrettyExtended(sorted)
    }

    private fun sortBsonValue(value: BsonValue): BsonValue {
        return when (value) {
            is BsonDocument -> {
                val tree = TreeMap<String, BsonValue>()
                for ((k, v) in value) {
                    tree[k] = sortBsonValue(v)
                }
                val sortedDoc = BsonDocument()
                for ((k, v) in tree) {
                    sortedDoc[k] = v
                }
                sortedDoc
            }
            is BsonArray -> {
                val sortedArr = BsonArray()
                for (elem in value) {
                    sortedArr.add(sortBsonValue(elem))
                }
                sortedArr
            }
            else -> value
        }
    }

    fun showDiff(
        project: Project,
        contextTitle: String,
        leftTitle: String,
        leftDoc: BsonDocument,
        rightTitle: String,
        rightDoc: BsonDocument
    ) {
        val leftJson = canonicalSortAndPrettyPrint(leftDoc)
        val rightJson = canonicalSortAndPrettyPrint(rightDoc)
        val jsonFileType = MongoJsonEditor.resolveJsonFileType()
        val contentFactory = DiffContentFactory.getInstance()
        val leftContent = contentFactory.create(project, leftJson, jsonFileType)
        val rightContent = contentFactory.create(project, rightJson, jsonFileType)

        val request = SimpleDiffRequest(
            I18n.t("diff.windowTitle", contextTitle),
            leftContent,
            rightContent,
            leftTitle,
            rightTitle
        )
        DiffManager.getInstance().showDiff(project, request)
    }
}
