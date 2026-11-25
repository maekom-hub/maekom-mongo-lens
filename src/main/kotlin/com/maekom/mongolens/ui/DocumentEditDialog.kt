package com.maekom.mongolens.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.util.ui.JBUI
import com.maekom.mongolens.i18n.I18n
import com.maekom.mongolens.mongo.MongoShellNormalizer
import org.bson.BsonDocument
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.JComponent
import javax.swing.JPanel

class DocumentEditDialog(
    project: Project,
    originalDoc: BsonDocument
) : DialogWrapper(project) {

    private val editor = MongoJsonEditor(
        projectRef = project,
        initialText = MongoShellNormalizer.formatPrettyExtended(originalDoc),
        multiLine = true
    )

    var parsedDocument: BsonDocument? = null
        private set

    init {
        title = I18n.t("result.doc.editTitle")
        init()
    }

    override fun createCenterPanel(): JComponent {
        return JPanel(BorderLayout()).apply {
            preferredSize = Dimension(680, 480)
            border = JBUI.Borders.empty(4)
            add(editor, BorderLayout.CENTER)
        }
    }

    override fun doValidate(): ValidationInfo? {
        return try {
            parsedDocument = MongoShellNormalizer.parseBsonDocument(editor.text)
            null
        } catch (t: Throwable) {
            ValidationInfo(t.message ?: "Invalid BSON/JSON document", editor)
        }
    }
}
