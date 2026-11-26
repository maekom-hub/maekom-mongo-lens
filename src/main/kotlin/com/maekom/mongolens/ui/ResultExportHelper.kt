package com.maekom.mongolens.ui

import com.intellij.notification.NotificationType
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.project.Project
import com.maekom.mongolens.i18n.I18n
import com.maekom.mongolens.mongo.MongoShellNormalizer
import org.bson.BsonDocument
import java.nio.charset.StandardCharsets
import java.nio.file.Files

object ResultExportHelper {

    fun exportCsv(
        project: Project,
        suggestedBaseName: String,
        columns: List<String>,
        rowCount: Int,
        cellTextProvider: (rowIndex: Int, colIndex: Int) -> String
    ) {
        val saver = FileChooserFactory.getInstance().createSaveFileDialog(
            FileSaverDescriptor(I18n.t("result.export.csv"), "", "csv"),
            project
        )
        val safeName = suggestedBaseName.replace(Regex("[^A-Za-z0-9_.-]"), "_")
        val wrapper = saver.save("$safeName.csv") ?: return
        val path = wrapper.file.toPath()

        try {
            val sb = StringBuilder()
            sb.append(columns.joinToString(",") { escapeCsv(it) }).append('\n')
            for (r in 0 until rowCount) {
                for (c in columns.indices) {
                    if (c > 0) sb.append(',')
                    sb.append(escapeCsv(cellTextProvider(r, c)))
                }
                sb.append('\n')
            }
            Files.writeString(path, sb.toString(), StandardCharsets.UTF_8)
            UiHelpers.notify(project, I18n.t("result.export.success", path.toString()))
        } catch (t: Throwable) {
            UiHelpers.notify(
                project,
                I18n.t("result.export.failed", t.message ?: t.javaClass.simpleName),
                NotificationType.WARNING
            )
        }
    }

    fun exportJson(
        project: Project,
        suggestedBaseName: String,
        documents: List<BsonDocument>
    ) {
        val saver = FileChooserFactory.getInstance().createSaveFileDialog(
            FileSaverDescriptor(I18n.t("result.export.json"), "", "json"),
            project
        )
        val safeName = suggestedBaseName.replace(Regex("[^A-Za-z0-9_.-]"), "_")
        val wrapper = saver.save("$safeName.json") ?: return
        val path = wrapper.file.toPath()

        try {
            val content = if (documents.isEmpty()) {
                "[]"
            } else {
                documents.joinToString(separator = ",\n", prefix = "[\n", postfix = "\n]") { doc ->
                    MongoShellNormalizer.formatPrettyExtended(doc)
                        .lines()
                        .joinToString("\n") { "  $it" }
                }
            }
            Files.writeString(path, content, StandardCharsets.UTF_8)
            UiHelpers.notify(project, I18n.t("result.export.success", path.toString()))
        } catch (t: Throwable) {
            UiHelpers.notify(
                project,
                I18n.t("result.export.failed", t.message ?: t.javaClass.simpleName),
                NotificationType.WARNING
            )
        }
    }

    private fun escapeCsv(value: String): String {
        val needsQuotes = value.contains(',') || value.contains('"') || value.contains('\n') || value.contains('\r')
        if (!needsQuotes) return value
        return "\"${value.replace("\"", "\"\"")}\""
    }
}
