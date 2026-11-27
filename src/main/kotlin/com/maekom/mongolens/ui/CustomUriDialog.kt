package com.maekom.mongolens.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.table.JBTable
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import com.maekom.mongolens.config.ConnectionColorTag
import com.maekom.mongolens.config.MongoConnectionProfile
import com.maekom.mongolens.config.MongoUriHelper
import com.maekom.mongolens.config.ParsedMongoUri
import com.maekom.mongolens.config.ProfileSource
import com.maekom.mongolens.i18n.I18n
import com.maekom.mongolens.mongo.MongoConnectionManager
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicReference
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.DefaultListCellRenderer
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.event.DocumentEvent
import javax.swing.event.TableModelEvent
import javax.swing.table.DefaultTableModel

/**
 * MongoDB Compass-style 4-tab Connection Settings Dialog (`General` / `Authentication` / `TLS/SSL` / `Advanced`)
 * with real-time bidirectional MongoDB URI synchronization, PasswordSafe backing, AWS DocumentDB multi-CA PEM support,
 * environment color tags, and safe read-only YAML inspection + clone-to-Custom workflow.
 */
class CustomUriDialog(
    private val project: Project,
    private val projectBasePath: Path?,
    private val connectionManager: MongoConnectionManager,
    private val existing: MongoConnectionProfile? = null,
    private val onCloneYamlToCustom: ((MongoConnectionProfile) -> Unit)? = null
) : DialogWrapper(project) {

    val isYamlReadOnly: Boolean = existing?.source == ProfileSource.YAML

    private var isSyncing: Boolean = false
    private val testFutureRef = AtomicReference<Future<*>?>(null)

    // --- Top Identity & URI Controls ---
    private val nameField = JBTextField(existing?.displayName ?: "")
    private val colorTagCombo = ComboBox(ConnectionColorTag.values()).apply {
        selectedItem = existing?.colorTag ?: ConnectionColorTag.NONE
        renderer = ColorTagListCellRenderer()
    }
    private val readOnlyCheck = JBCheckBox(
        I18n.t("custom.dialog.readOnly"),
        existing?.readOnly ?: false
    )

    private val uriField = JBTextField().apply {
        font = Font(Font.MONOSPACED, Font.PLAIN, font.size)
        isEditable = false
    }
    private val editUriCheck = JBCheckBox(I18n.t("conn.dialog.editUri"), false)
    private val copyUriBtn = JButton(I18n.t("conn.dialog.copyUri"), AllIcons.Actions.Copy).apply {
        margin = JBUI.insets(2, 8)
    }

    // --- Tab 1: General ---
    private val schemeCombo = ComboBox(arrayOf("mongodb", "mongodb+srv"))
    private val hostsListPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
    }
    private val hostRows = mutableListOf<HostRow>()
    private val addHostBtn = JButton(I18n.t("conn.dialog.addHost"), AllIcons.General.Add).apply {
        margin = JBUI.insets(2, 8)
    }
    private val hostsScrollPane = JBScrollPane(hostsListPanel).apply {
        preferredSize = Dimension(JBUI.scale(480), JBUI.scale(84))
        minimumSize = Dimension(JBUI.scale(320), JBUI.scale(68))
        border = BorderFactory.createLineBorder(JBUI.CurrentTheme.ToolWindow.borderColor())
    }
    private val defaultDbField = JBTextField()
    private val replicaSetField = JBTextField()
    private val directConnectionCheck = JBCheckBox(I18n.t("conn.dialog.directConnection"), false)

    // --- Tab 2: Authentication ---
    private val authMechanismCombo = ComboBox(
        arrayOf("None", "SCRAM-SHA-256", "SCRAM-SHA-1", "MONGODB-X509", "MONGODB-AWS")
    )
    private val usernameField = JBTextField()
    private val passwordField = JBPasswordField()
    private var passwordVisible = false
    private val defaultEchoChar: Char = passwordField.echoChar
    private val togglePasswordBtn = JButton(AllIcons.Actions.ToggleVisibility).apply {
        margin = JBUI.insets(2, 6)
        toolTipText = I18n.t("conn.dialog.togglePassword")
    }
    private val authSourceField = JBTextField().apply {
        emptyText.text = "admin"
    }

    // --- Tab 3: TLS/SSL ---
    private val tlsEnabledCheck = JBCheckBox(I18n.t("conn.dialog.tlsEnable"), false)
    private val caFileField = TextFieldWithBrowseButton().apply {
        (textField as? JBTextField)?.emptyText?.text = I18n.t("conn.dialog.caFileHint")
        addBrowseFolderListener(
            I18n.t("conn.dialog.selectCaFile"),
            I18n.t("conn.dialog.caFileHint"),
            project,
            FileChooserDescriptorFactory.createSingleFileDescriptor()
        )
    }
    private val clientCertField = TextFieldWithBrowseButton().apply {
        (textField as? JBTextField)?.emptyText?.text = I18n.t("conn.dialog.clientCertHint")
        addBrowseFolderListener(
            I18n.t("conn.dialog.selectClientCert"),
            I18n.t("conn.dialog.clientCertHint"),
            project,
            FileChooserDescriptorFactory.createSingleFileDescriptor()
        )
    }
    private val allowInvalidHostnamesCheck = JBCheckBox(I18n.t("conn.dialog.tlsAllowInvalidHostnames"), false)
    private val allowInvalidCertsCheck = JBCheckBox(I18n.t("conn.dialog.tlsAllowInvalidCertificates"), false)

    // --- Tab 4: Advanced ---
    private val readPreferenceCombo = ComboBox(
        arrayOf("default", "primary", "primaryPreferred", "secondary", "secondaryPreferred", "nearest")
    )
    private val retryWritesCheck = JBCheckBox(I18n.t("conn.dialog.retryWrites"), true)
    private val connectTimeoutField = JBTextField().apply { emptyText.text = "10000" }
    private val socketTimeoutField = JBTextField().apply { emptyText.text = "20000" }
    private val appNameField = JBTextField().apply { emptyText.text = "maekom-mongo-lens" }

    private val extraParamsModel = object : DefaultTableModel(
        arrayOf(I18n.t("conn.dialog.paramKey"), I18n.t("conn.dialog.paramValue")),
        0
    ) {
        override fun isCellEditable(row: Int, column: Int): Boolean = !isYamlReadOnly
    }
    private val extraParamsTable = JBTable(extraParamsModel).apply {
        setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        rowHeight = JBUI.scale(24)
        fillsViewportHeight = true
        putClientProperty("terminateEditOnFocusLost", true)
    }
    private val addParamBtn = JButton(I18n.t("conn.dialog.addParam"), AllIcons.General.Add).apply {
        margin = JBUI.insets(2, 8)
    }
    private val removeParamBtn = JButton(I18n.t("conn.dialog.removeParam"), AllIcons.General.Remove).apply {
        margin = JBUI.insets(2, 8)
    }

    // --- Footer Test Controls ---
    private val testButton = JButton(I18n.t("custom.dialog.test"), AllIcons.Actions.Execute)
    private val testStatusLabel = JBLabel(" ")

    private inner class HostRow(initialHost: String) : JPanel(BorderLayout(4, 0)) {
        val field = JBTextField(initialHost)
        val removeBtn = JButton(AllIcons.General.Remove).apply {
            margin = JBUI.insets(1, 5)
            preferredSize = Dimension(JBUI.scale(28), JBUI.scale(26))
        }

        init {
            border = JBUI.Borders.empty(2, 4)
            maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(30))
            alignmentX = JComponent.LEFT_ALIGNMENT
            add(field, BorderLayout.CENTER)
            add(removeBtn, BorderLayout.EAST)

            field.document.addDocumentListener(object : DocumentAdapter() {
                override fun textChanged(e: DocumentEvent) {
                    if (!isSyncing) syncTabsToUri()
                }
            })
            removeBtn.addActionListener {
                if (hostRows.size > 1 && !isYamlReadOnly) {
                    hostRows.remove(this)
                    hostsListPanel.remove(this)
                    updateHostControlsEnabled()
                    hostsListPanel.revalidate()
                    hostsListPanel.repaint()
                    syncTabsToUri()
                }
            }
        }
    }

    private class ColorSwatchIcon(private val color: Color?) : Icon {
        override fun getIconWidth(): Int = JBUI.scale(12)
        override fun getIconHeight(): Int = JBUI.scale(12)
        override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                val size = JBUI.scale(10)
                val oy = y + (iconHeight - size) / 2
                if (color != null) {
                    g2.color = color
                    g2.fillOval(x + 1, oy, size, size)
                } else {
                    g2.color = JBUI.CurrentTheme.ContextHelp.FOREGROUND
                    g2.drawOval(x + 1, oy, size - 1, size - 1)
                }
            } finally {
                g2.dispose()
            }
        }
    }

    private class ColorTagListCellRenderer : DefaultListCellRenderer() {
        override fun getListCellRendererComponent(
            list: JList<*>?,
            value: Any?,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean
        ): Component {
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
            val tag = value as? ConnectionColorTag ?: ConnectionColorTag.NONE
            text = I18n.t(tag.i18nKey)
            icon = ColorSwatchIcon(tag.fgColor())
            return this
        }
    }

    init {
        title = when {
            isYamlReadOnly -> I18n.t("conn.dialog.yamlTitle", existing?.displayName ?: "")
            existing == null -> I18n.t("custom.dialog.title")
            else -> I18n.t("custom.dialog.editTitle")
        }
        setOKButtonText(
            if (isYamlReadOnly) I18n.t("conn.dialog.saveYamlMeta") else I18n.t("edit.dialog.save")
        )
        populateInitialState()
        wireListeners()
        applyYamlReadOnlyLockIfNeeded()
        init()
    }

    override fun isResizable(): Boolean = true

    override fun getDimensionServiceKey(): String = "MaekomMongoLens.ConnectionSettingsDialog"

    private fun populateInitialState() {
        isSyncing = true
        try {
            val initialUri = existing?.effectiveConnectionUri() ?: "mongodb://localhost:27017"
            val parsed = MongoUriHelper.parseUri(initialUri) ?: ParsedMongoUri()

            // Merge profile-level overrides onto parsed URI values
            val effectiveDb = parsed.database.ifBlank { existing?.database?.trim().orEmpty() }
            val effectiveAuthSource = parsed.authSource.ifBlank { existing?.authDatabase?.trim().orEmpty() }
            val effectiveAuthMech = parsed.authMechanism.ifBlank { existing?.authMechanism?.trim().orEmpty() }
            val effectiveReplicaSet = parsed.replicaSet.ifBlank { existing?.replicaSet?.trim().orEmpty() }
            val effectiveDirect = parsed.directConnection || (existing?.directConnection == true && parsed.scheme != "mongodb+srv")
            val effectiveTls = parsed.tls || existing?.effectiveTlsEnabled() == true
            val effectiveAllowHost = parsed.tlsAllowInvalidHostnames || (existing?.tlsAllowInvalidHostnames == true)
            val effectiveAllowCert = parsed.tlsAllowInvalidCertificates || (existing?.tlsAllowInvalidCertificates == true)
            val effectiveReadPref = parsed.readPreference.ifBlank { existing?.readPreference?.trim().orEmpty() }
            val effectiveRetryWrites = existing?.retryWrites ?: parsed.retryWrites
            val effectiveConnTimeout = parsed.connectTimeoutMs.ifBlank { existing?.connectTimeoutMs?.toString().orEmpty() }
            val effectiveSockTimeout = parsed.socketTimeoutMs.ifBlank { existing?.socketTimeoutMs?.toString().orEmpty() }
            val effectiveAppName = parsed.appName.ifBlank { existing?.appName?.trim().orEmpty() }

            val mergedParsed = parsed.copy(
                database = effectiveDb,
                authSource = effectiveAuthSource,
                authMechanism = effectiveAuthMech,
                replicaSet = effectiveReplicaSet,
                directConnection = effectiveDirect,
                tls = effectiveTls,
                tlsAllowInvalidHostnames = effectiveAllowHost,
                tlsAllowInvalidCertificates = effectiveAllowCert,
                readPreference = effectiveReadPref,
                retryWrites = effectiveRetryWrites,
                connectTimeoutMs = effectiveConnTimeout,
                socketTimeoutMs = effectiveSockTimeout,
                appName = effectiveAppName
            )

            applyParsedToTabs(mergedParsed)

            caFileField.text = existing?.tlsCaFilePath.orEmpty()
            clientCertField.text = existing?.tlsClientCertFilePath.orEmpty()

            uriField.text = if (isYamlReadOnly) {
                initialUri
            } else {
                MongoUriHelper.buildUri(mergedParsed)
            }
        } finally {
            isSyncing = false
        }
    }

    private fun applyParsedToTabs(parsed: ParsedMongoUri) {
        schemeCombo.selectedItem = if (parsed.scheme.equals("mongodb+srv", ignoreCase = true)) {
            "mongodb+srv"
        } else {
            "mongodb"
        }

        hostRows.clear()
        hostsListPanel.removeAll()
        val hosts = parsed.hosts.ifEmpty { listOf("localhost:27017") }
        val normalizedHosts = if (isSrvSelected()) {
            listOf(MongoUriHelper.stripPort(hosts.first()))
        } else {
            hosts
        }
        for (h in normalizedHosts) {
            val row = HostRow(h)
            hostRows.add(row)
            hostsListPanel.add(row)
        }
        hostsListPanel.revalidate()
        hostsListPanel.repaint()

        defaultDbField.text = parsed.database
        replicaSetField.text = if (isSrvSelected()) "" else parsed.replicaSet
        directConnectionCheck.isSelected = !isSrvSelected() && parsed.directConnection

        val mechValue = parsed.authMechanism.ifBlank { "None" }
        ensureComboItemSelected(authMechanismCombo, mechValue)
        usernameField.text = parsed.username
        passwordField.text = parsed.password
        authSourceField.text = parsed.authSource

        tlsEnabledCheck.isSelected = parsed.tls ||
            caFileField.text.trim().isNotEmpty() ||
            clientCertField.text.trim().isNotEmpty()
        allowInvalidHostnamesCheck.isSelected = parsed.tlsAllowInvalidHostnames
        allowInvalidCertsCheck.isSelected = parsed.tlsAllowInvalidCertificates

        val prefValue = parsed.readPreference.ifBlank { "default" }
        ensureComboItemSelected(readPreferenceCombo, prefValue)
        retryWritesCheck.isSelected = parsed.retryWrites
        connectTimeoutField.text = parsed.connectTimeoutMs
        socketTimeoutField.text = parsed.socketTimeoutMs
        appNameField.text = parsed.appName

        extraParamsModel.rowCount = 0
        for ((k, v) in parsed.extraParams) {
            extraParamsModel.addRow(arrayOf(k, v))
        }

        updateHostControlsEnabled()
    }

    private fun ensureComboItemSelected(combo: ComboBox<String>, value: String) {
        for (i in 0 until combo.itemCount) {
            if (combo.getItemAt(i).equals(value, ignoreCase = true)) {
                combo.selectedIndex = i
                return
            }
        }
        combo.addItem(value)
        combo.selectedItem = value
    }

    private fun readParsedFromTabs(): ParsedMongoUri {
        val isSrv = isSrvSelected()
        val hosts = hostRows.map { it.field.text.trim() }.filter { it.isNotEmpty() }
            .let { list ->
                if (isSrv) listOf(MongoUriHelper.stripPort(list.firstOrNull() ?: "cluster.example.com"))
                else list.ifEmpty { listOf("localhost:27017") }
            }

        val extra = linkedMapOf<String, String>()
        for (r in 0 until extraParamsModel.rowCount) {
            val k = extraParamsModel.getValueAt(r, 0)?.toString()?.trim().orEmpty()
            val v = extraParamsModel.getValueAt(r, 1)?.toString()?.trim().orEmpty()
            if (k.isNotEmpty()) {
                extra[k] = v
            }
        }

        val authMech = (authMechanismCombo.selectedItem?.toString() ?: "None").let {
            if (it.equals("None", ignoreCase = true)) "" else it
        }
        val readPref = (readPreferenceCombo.selectedItem?.toString() ?: "default").let {
            if (it.equals("default", ignoreCase = true)) "" else it
        }

        return ParsedMongoUri(
            scheme = if (isSrv) "mongodb+srv" else "mongodb",
            hosts = hosts,
            database = defaultDbField.text.trim(),
            username = usernameField.text.trim(),
            password = String(passwordField.password),
            authSource = authSourceField.text.trim(),
            authMechanism = authMech,
            replicaSet = if (isSrv) "" else replicaSetField.text.trim(),
            directConnection = !isSrv && directConnectionCheck.isSelected,
            tls = tlsEnabledCheck.isSelected ||
                caFileField.text.trim().isNotEmpty() ||
                clientCertField.text.trim().isNotEmpty(),
            tlsAllowInvalidHostnames = allowInvalidHostnamesCheck.isSelected,
            tlsAllowInvalidCertificates = allowInvalidCertsCheck.isSelected,
            readPreference = readPref,
            retryWrites = retryWritesCheck.isSelected,
            connectTimeoutMs = connectTimeoutField.text.trim(),
            socketTimeoutMs = socketTimeoutField.text.trim(),
            appName = appNameField.text.trim(),
            extraParams = extra
        )
    }

    private fun syncTabsToUri() {
        if (isSyncing || isYamlReadOnly) return
        isSyncing = true
        try {
            val built = MongoUriHelper.buildUri(readParsedFromTabs())
            if (uriField.text != built) {
                uriField.text = built
            }
        } finally {
            isSyncing = false
        }
    }

    private fun syncUriToTabs() {
        if (isSyncing || isYamlReadOnly) return
        val parsed = MongoUriHelper.parseUri(uriField.text) ?: return
        isSyncing = true
        try {
            applyParsedToTabs(parsed)
        } finally {
            isSyncing = false
        }
    }

    private fun isSrvSelected(): Boolean =
        schemeCombo.selectedItem?.toString().equals("mongodb+srv", ignoreCase = true)

    private fun updateHostControlsEnabled() {
        val isSrv = isSrvSelected()
        val editable = !isYamlReadOnly
        addHostBtn.isEnabled = editable && !isSrv
        replicaSetField.isEnabled = editable && !isSrv
        directConnectionCheck.isEnabled = editable && !isSrv
        hostRows.forEach { row ->
            row.field.isEnabled = editable
            row.removeBtn.isEnabled = editable && !isSrv && hostRows.size > 1
        }
    }

    private fun wireListeners() {
        editUriCheck.addActionListener {
            if (isYamlReadOnly) {
                editUriCheck.isSelected = false
                return@addActionListener
            }
            val directEdit = editUriCheck.isSelected
            uriField.isEditable = directEdit
            if (!directEdit) {
                syncTabsToUri()
            } else {
                uriField.requestFocusInWindow()
            }
        }

        uriField.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) {
                if (!isSyncing && editUriCheck.isSelected && !isYamlReadOnly) {
                    syncUriToTabs()
                }
            }
        })

        copyUriBtn.addActionListener {
            val text = uriField.text?.trim().orEmpty()
            if (text.isNotEmpty()) {
                UiHelpers.copyToClipboard(text)
                UiHelpers.notify(project, I18n.t("conn.dialog.uriCopied"))
            }
        }

        schemeCombo.addActionListener {
            if (isSyncing || isYamlReadOnly) return@addActionListener
            isSyncing = true
            try {
                if (isSrvSelected()) {
                    // Strip :port and keep single host row; clear replicaSet & directConnection
                    while (hostRows.size > 1) {
                        val last = hostRows.removeAt(hostRows.lastIndex)
                        hostsListPanel.remove(last)
                    }
                    hostRows.firstOrNull()?.let { row ->
                        row.field.text = MongoUriHelper.stripPort(row.field.text)
                    }
                    replicaSetField.text = ""
                    directConnectionCheck.isSelected = false
                    hostsListPanel.revalidate()
                    hostsListPanel.repaint()
                }
                updateHostControlsEnabled()
            } finally {
                isSyncing = false
            }
            syncTabsToUri()
        }

        addHostBtn.addActionListener {
            if (isYamlReadOnly || isSrvSelected()) return@addActionListener
            val row = HostRow("localhost:27017")
            hostRows.add(row)
            hostsListPanel.add(row)
            updateHostControlsEnabled()
            hostsListPanel.revalidate()
            hostsListPanel.repaint()
            row.field.requestFocusInWindow()
            syncTabsToUri()
        }

        val docSync = object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) {
                if (!isSyncing) syncTabsToUri()
            }
        }
        defaultDbField.document.addDocumentListener(docSync)
        replicaSetField.document.addDocumentListener(docSync)
        usernameField.document.addDocumentListener(docSync)
        passwordField.document.addDocumentListener(docSync)
        authSourceField.document.addDocumentListener(docSync)
        connectTimeoutField.document.addDocumentListener(docSync)
        socketTimeoutField.document.addDocumentListener(docSync)
        appNameField.document.addDocumentListener(docSync)

        val certDocSync = object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) {
                if (isSyncing || isYamlReadOnly) return
                val hasCert = caFileField.text.trim().isNotEmpty() || clientCertField.text.trim().isNotEmpty()
                if (hasCert && !tlsEnabledCheck.isSelected) {
                    isSyncing = true
                    try {
                        tlsEnabledCheck.isSelected = true
                    } finally {
                        isSyncing = false
                    }
                }
                syncTabsToUri()
            }
        }
        caFileField.textField.document.addDocumentListener(certDocSync)
        clientCertField.textField.document.addDocumentListener(certDocSync)

        directConnectionCheck.addActionListener { if (!isSyncing) syncTabsToUri() }
        authMechanismCombo.addActionListener { if (!isSyncing) syncTabsToUri() }
        tlsEnabledCheck.addActionListener { if (!isSyncing) syncTabsToUri() }
        allowInvalidHostnamesCheck.addActionListener { if (!isSyncing) syncTabsToUri() }
        allowInvalidCertsCheck.addActionListener { if (!isSyncing) syncTabsToUri() }
        readPreferenceCombo.addActionListener { if (!isSyncing) syncTabsToUri() }
        retryWritesCheck.addActionListener { if (!isSyncing) syncTabsToUri() }

        togglePasswordBtn.addActionListener {
            passwordVisible = !passwordVisible
            passwordField.echoChar = if (passwordVisible) 0.toChar() else defaultEchoChar
            togglePasswordBtn.icon = if (passwordVisible) AllIcons.Actions.Show else AllIcons.Actions.ToggleVisibility
        }

        addParamBtn.addActionListener {
            if (isYamlReadOnly) return@addActionListener
            extraParamsModel.addRow(arrayOf("", ""))
            val newRow = extraParamsModel.rowCount - 1
            extraParamsTable.setRowSelectionInterval(newRow, newRow)
            extraParamsTable.editCellAt(newRow, 0)
        }

        removeParamBtn.addActionListener {
            if (isYamlReadOnly) return@addActionListener
            val sel = extraParamsTable.selectedRow
            if (sel in 0 until extraParamsModel.rowCount) {
                if (extraParamsTable.isEditing) {
                    extraParamsTable.cellEditor?.stopCellEditing()
                }
                extraParamsModel.removeRow(sel)
                syncTabsToUri()
            }
        }

        extraParamsModel.addTableModelListener { e: TableModelEvent ->
            if (!isSyncing && (e.type == TableModelEvent.UPDATE || e.type == TableModelEvent.DELETE)) {
                syncTabsToUri()
            }
        }

        testButton.addActionListener { runConnectionTest() }
    }

    private fun applyYamlReadOnlyLockIfNeeded() {
        if (!isYamlReadOnly) return
        nameField.isEditable = false
        editUriCheck.isEnabled = false
        uriField.isEditable = false

        schemeCombo.isEnabled = false
        addHostBtn.isEnabled = false
        hostRows.forEach {
            it.field.isEnabled = false
            it.removeBtn.isEnabled = false
        }
        defaultDbField.isEditable = false
        replicaSetField.isEditable = false
        directConnectionCheck.isEnabled = false

        authMechanismCombo.isEnabled = false
        usernameField.isEditable = false
        passwordField.isEditable = false
        authSourceField.isEditable = false

        tlsEnabledCheck.isEnabled = false
        caFileField.isEnabled = false
        clientCertField.isEnabled = false
        allowInvalidHostnamesCheck.isEnabled = false
        allowInvalidCertsCheck.isEnabled = false

        readPreferenceCombo.isEnabled = false
        retryWritesCheck.isEnabled = false
        connectTimeoutField.isEditable = false
        socketTimeoutField.isEditable = false
        appNameField.isEditable = false
        addParamBtn.isEnabled = false
        removeParamBtn.isEnabled = false
    }

    override fun createCenterPanel(): JComponent {
        val root = JPanel(BorderLayout(0, 8)).apply {
            border = JBUI.Borders.empty(6)
            preferredSize = Dimension(JBUI.scale(680), JBUI.scale(520))
        }

        val topStack = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
        }

        if (isYamlReadOnly) {
            topStack.add(buildYamlBannerPanel())
            topStack.add(Box.createVerticalStrut(JBUI.scale(8)))
        }

        // Identity row: Name + Color Tag + Read-Only checkbox
        val identityRow = JPanel(BorderLayout(8, 0)).apply {
            alignmentX = JComponent.LEFT_ALIGNMENT
            val namePanel = JPanel(BorderLayout(6, 0)).apply {
                add(JBLabel(I18n.t("custom.dialog.name")), BorderLayout.WEST)
                add(nameField, BorderLayout.CENTER)
            }
            val rightMeta = JPanel(FlowLayout(FlowLayout.RIGHT, 8, 0)).apply {
                add(JBLabel(I18n.t("conn.dialog.colorTag")))
                add(colorTagCombo)
                add(readOnlyCheck)
            }
            add(namePanel, BorderLayout.CENTER)
            add(rightMeta, BorderLayout.EAST)
        }
        topStack.add(identityRow)
        topStack.add(Box.createVerticalStrut(JBUI.scale(8)))

        // URI bar row
        val uriHeaderRow = JPanel(BorderLayout(6, 0)).apply {
            alignmentX = JComponent.LEFT_ALIGNMENT
            add(JBLabel(I18n.t("custom.dialog.uri")).apply {
                font = font.deriveFont(Font.BOLD)
            }, BorderLayout.WEST)
            val controls = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0)).apply {
                add(editUriCheck)
                add(copyUriBtn)
            }
            add(controls, BorderLayout.EAST)
        }
        val uriFieldWrapper = JPanel(BorderLayout()).apply {
            alignmentX = JComponent.LEFT_ALIGNMENT
            add(uriField, BorderLayout.CENTER)
        }
        topStack.add(uriHeaderRow)
        topStack.add(Box.createVerticalStrut(JBUI.scale(3)))
        topStack.add(uriFieldWrapper)

        // 4-Tab Pane
        val tabbedPane = JBTabbedPane().apply {
            addTab(I18n.t("conn.tab.general"), buildGeneralTab())
            addTab(I18n.t("conn.tab.auth"), buildAuthTab())
            addTab(I18n.t("conn.tab.tls"), buildTlsTab())
            addTab(I18n.t("conn.tab.advanced"), buildAdvancedTab())
        }

        // Bottom Test Row
        val testRow = JPanel(BorderLayout(8, 0)).apply {
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, JBUI.CurrentTheme.ToolWindow.borderColor()),
                JBUI.Borders.emptyTop(8)
            )
            add(testButton, BorderLayout.WEST)
            add(testStatusLabel, BorderLayout.CENTER)
        }

        root.add(topStack, BorderLayout.NORTH)
        root.add(tabbedPane, BorderLayout.CENTER)
        root.add(testRow, BorderLayout.SOUTH)
        return root
    }

    private fun buildYamlBannerPanel(): JPanel {
        val bannerBg = JBColor(Color(0xEDF5FF), Color(0x233142))
        val bannerBorder = JBColor(Color(0xB8D4F5), Color(0x3B5370))

        val cloneBtn = JButton(I18n.t("conn.dialog.cloneToCustom"), AllIcons.Actions.Copy).apply {
            margin = JBUI.insets(2, 8)
            addActionListener {
                val base = existing ?: return@addActionListener
                val cloned = buildClonedCustomProfile(base)
                onCloneYamlToCustom?.invoke(cloned)
                close(CANCEL_EXIT_CODE)
            }
        }

        val openYamlBtn = JButton(I18n.t("conn.dialog.openYaml"), AllIcons.FileTypes.Yaml).apply {
            margin = JBUI.insets(2, 8)
            isEnabled = !existing?.sourceFilePath.isNullOrBlank()
            addActionListener {
                val path = existing?.sourceFilePath ?: return@addActionListener
                val vf = LocalFileSystem.getInstance().refreshAndFindFileByPath(path)
                if (vf != null) {
                    FileEditorManager.getInstance(project).openFile(vf, true)
                    close(CANCEL_EXIT_CODE)
                } else {
                    UiHelpers.notify(
                        project,
                        I18n.t("conn.dialog.yamlFileNotFound", path),
                        com.intellij.notification.NotificationType.WARNING
                    )
                }
            }
        }

        val infoLabel = JBLabel(I18n.t("conn.dialog.yamlBanner"), AllIcons.General.BalloonInformation, JBLabel.LEFT).apply {
            font = font.deriveFont(Font.PLAIN, font.size2D - 0.5f)
        }
        val btnsPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0)).apply {
            isOpaque = false
            add(cloneBtn)
            add(openYamlBtn)
        }

        return JPanel(BorderLayout(8, 4)).apply {
            alignmentX = JComponent.LEFT_ALIGNMENT
            background = bannerBg
            isOpaque = true
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(bannerBorder),
                JBUI.Borders.empty(6, 8)
            )
            add(infoLabel, BorderLayout.NORTH)
            add(btnsPanel, BorderLayout.SOUTH)
        }
    }

    private fun buildGeneralTab(): JComponent {
        val hostsSection = JPanel(BorderLayout(0, 4)).apply {
            val header = JPanel(BorderLayout()).apply {
                add(JBLabel(I18n.t("conn.dialog.hosts")), BorderLayout.WEST)
                add(addHostBtn, BorderLayout.EAST)
            }
            add(header, BorderLayout.NORTH)
            add(hostsScrollPane, BorderLayout.CENTER)
        }

        return FormBuilder.createFormBuilder()
            .addLabeledComponent(JBLabel(I18n.t("conn.dialog.scheme")), schemeCombo, 4, false)
            .addComponent(hostsSection, 6)
            .addLabeledComponent(JBLabel(I18n.t("custom.dialog.database")), defaultDbField, 6, false)
            .addLabeledComponent(JBLabel(I18n.t("conn.dialog.replicaSet")), replicaSetField, 6, false)
            .addComponent(directConnectionCheck, 6)
            .addComponentFillVertically(JPanel(), 0)
            .panel.apply { border = JBUI.Borders.empty(10) }
    }

    private fun buildAuthTab(): JComponent {
        val passwordRow = JPanel(BorderLayout(4, 0)).apply {
            add(passwordField, BorderLayout.CENTER)
            add(togglePasswordBtn, BorderLayout.EAST)
        }
        return FormBuilder.createFormBuilder()
            .addLabeledComponent(JBLabel(I18n.t("conn.dialog.authMechanism")), authMechanismCombo, 4, false)
            .addLabeledComponent(JBLabel(I18n.t("conn.dialog.username")), usernameField, 6, false)
            .addLabeledComponent(JBLabel(I18n.t("conn.dialog.password")), passwordRow, 6, false)
            .addLabeledComponent(JBLabel(I18n.t("conn.dialog.authSource")), authSourceField, 6, false)
            .addComponent(JBLabel(I18n.t("conn.dialog.passwordSafeHint")).apply {
                foreground = UiHelpers.BSON_NULL_COLOR
                font = font.deriveFont(font.size2D - 1f)
            }, 8)
            .addComponentFillVertically(JPanel(), 0)
            .panel.apply { border = JBUI.Borders.empty(10) }
    }

    private fun buildTlsTab(): JComponent {
        return FormBuilder.createFormBuilder()
            .addComponent(tlsEnabledCheck, 4)
            .addLabeledComponent(JBLabel(I18n.t("conn.dialog.caFile")), caFileField, 6, false)
            .addLabeledComponent(JBLabel(I18n.t("conn.dialog.clientCert")), clientCertField, 6, false)
            .addComponent(allowInvalidHostnamesCheck, 6)
            .addComponent(allowInvalidCertsCheck, 4)
            .addComponent(JBLabel(I18n.t("conn.dialog.tlsDocDbHint")).apply {
                foreground = UiHelpers.BSON_NULL_COLOR
                font = font.deriveFont(font.size2D - 1f)
            }, 8)
            .addComponentFillVertically(JPanel(), 0)
            .panel.apply { border = JBUI.Borders.empty(10) }
    }

    private fun buildAdvancedTab(): JComponent {
        val paramsHeader = JPanel(BorderLayout()).apply {
            add(JBLabel(I18n.t("conn.dialog.extraParams")), BorderLayout.WEST)
            val btns = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply {
                add(addParamBtn)
                add(removeParamBtn)
            }
            add(btns, BorderLayout.EAST)
        }
        val paramsScroll = JBScrollPane(extraParamsTable).apply {
            preferredSize = Dimension(JBUI.scale(480), JBUI.scale(100))
            minimumSize = Dimension(JBUI.scale(320), JBUI.scale(80))
        }
        val paramsSection = JPanel(BorderLayout(0, 4)).apply {
            add(paramsHeader, BorderLayout.NORTH)
            add(paramsScroll, BorderLayout.CENTER)
        }

        return FormBuilder.createFormBuilder()
            .addLabeledComponent(JBLabel(I18n.t("conn.dialog.readPreference")), readPreferenceCombo, 4, false)
            .addComponent(retryWritesCheck, 6)
            .addLabeledComponent(JBLabel(I18n.t("conn.dialog.connectTimeout")), connectTimeoutField, 6, false)
            .addLabeledComponent(JBLabel(I18n.t("conn.dialog.socketTimeout")), socketTimeoutField, 6, false)
            .addLabeledComponent(JBLabel(I18n.t("conn.dialog.appName")), appNameField, 6, false)
            .addComponent(paramsSection, 8)
            .panel.apply { border = JBUI.Borders.empty(10) }
    }

    override fun doValidate(): ValidationInfo? {
        if (isYamlReadOnly) return null

        if (extraParamsTable.isEditing) {
            extraParamsTable.cellEditor?.stopCellEditing()
        }

        if ((nameField.text as String?)?.trim().orEmpty().isEmpty()) {
            return ValidationInfo(I18n.t("custom.dialog.validationName"), nameField)
        }
        val uri = (uriField.text as String?)?.trim().orEmpty()
        if (!uri.startsWith("mongodb://") && !uri.startsWith("mongodb+srv://")) {
            return ValidationInfo(I18n.t("custom.dialog.validationUri"), uriField)
        }
        val ct = connectTimeoutField.text.trim()
        if (ct.isNotEmpty() && (ct.toIntOrNull() == null || ct.toInt() <= 0)) {
            return ValidationInfo(I18n.t("conn.dialog.validationTimeout"), connectTimeoutField)
        }
        val st = socketTimeoutField.text.trim()
        if (st.isNotEmpty() && (st.toIntOrNull() == null || st.toInt() <= 0)) {
            return ValidationInfo(I18n.t("conn.dialog.validationTimeout"), socketTimeoutField)
        }
        return null
    }

    private fun runConnectionTest() {
        if (extraParamsTable.isEditing) {
            extraParamsTable.cellEditor?.stopCellEditing()
        }
        val uri = (uriField.text as String?)?.trim().orEmpty()
        if (!uri.startsWith("mongodb://") && !uri.startsWith("mongodb+srv://")) {
            testStatusLabel.text = I18n.t("custom.dialog.validationUri")
            testStatusLabel.foreground = UiHelpers.COLOR_RED_FG
            return
        }

        val testProfile = if (isYamlReadOnly && existing != null) {
            existing.copy(
                colorTag = selectedColorTag(),
                readOnly = selectedReadOnly()
            )
        } else {
            buildProfile()
        }

        testButton.isEnabled = false
        testButton.text = I18n.t("custom.dialog.testing")
        testStatusLabel.text = I18n.t("custom.dialog.testing")
        testStatusLabel.foreground = UiHelpers.COLOR_BLUE_FG

        testFutureRef.getAndSet(null)?.cancel(true)
        val future = AppExecutorUtil.getAppExecutorService().submit {
            try {
                val ms = connectionManager.testProfileDirect(testProfile)
                ApplicationManager.getApplication().invokeLater({
                    if (isDisposed) return@invokeLater
                    testButton.isEnabled = true
                    testButton.text = I18n.t("custom.dialog.test")
                    testStatusLabel.text = I18n.t("custom.dialog.testSuccess", ms)
                    testStatusLabel.foreground = UiHelpers.COLOR_GREEN_FG
                }, ModalityState.any())
            } catch (t: Throwable) {
                val msg = (t.message ?: t.javaClass.simpleName).lineSequence().firstOrNull()?.take(120).orEmpty()
                ApplicationManager.getApplication().invokeLater({
                    if (isDisposed) return@invokeLater
                    testButton.isEnabled = true
                    testButton.text = I18n.t("custom.dialog.test")
                    testStatusLabel.text = I18n.t("custom.dialog.testFail", msg)
                    testStatusLabel.foreground = UiHelpers.COLOR_RED_FG
                }, ModalityState.any())
            }
        }
        testFutureRef.set(future)
    }

    fun selectedColorTag(): ConnectionColorTag =
        (colorTagCombo.selectedItem as? ConnectionColorTag) ?: ConnectionColorTag.NONE

    fun selectedReadOnly(): Boolean = readOnlyCheck.isSelected

    private fun buildClonedCustomProfile(sourceYaml: MongoConnectionProfile): MongoConnectionProfile {
        val parsed = MongoUriHelper.parseUri(sourceYaml.effectiveConnectionUri()) ?: readParsedFromTabs()
        val clonedName = "${sourceYaml.displayName} (Custom)"
        return MongoConnectionProfile(
            id = "custom:${UUID.randomUUID()}",
            displayName = clonedName,
            profileName = clonedName,
            source = ProfileSource.CUSTOM,
            uri = MongoUriHelper.buildUri(parsed),
            database = parsed.database.takeIf { it.isNotBlank() } ?: sourceYaml.database,
            username = parsed.username.takeIf { it.isNotBlank() } ?: sourceYaml.username,
            password = parsed.password.takeIf { it.isNotEmpty() } ?: sourceYaml.password,
            authDatabase = parsed.authSource.takeIf { it.isNotBlank() } ?: sourceYaml.authDatabase,
            readOnly = selectedReadOnly(),
            colorTag = selectedColorTag(),
            tlsEnabled = parsed.tls || sourceYaml.effectiveTlsEnabled(),
            tlsCaFilePath = sourceYaml.tlsCaFilePath,
            tlsClientCertFilePath = sourceYaml.tlsClientCertFilePath,
            tlsAllowInvalidHostnames = parsed.tlsAllowInvalidHostnames || sourceYaml.tlsAllowInvalidHostnames,
            tlsAllowInvalidCertificates = parsed.tlsAllowInvalidCertificates || sourceYaml.tlsAllowInvalidCertificates,
            connectTimeoutMs = parsed.connectTimeoutMs.toIntOrNull() ?: sourceYaml.connectTimeoutMs,
            socketTimeoutMs = parsed.socketTimeoutMs.toIntOrNull() ?: sourceYaml.socketTimeoutMs,
            retryWrites = parsed.retryWrites,
            authMechanism = parsed.authMechanism.takeIf { it.isNotBlank() } ?: sourceYaml.authMechanism,
            replicaSet = parsed.replicaSet.takeIf { it.isNotBlank() } ?: sourceYaml.replicaSet,
            directConnection = parsed.directConnection,
            readPreference = parsed.readPreference.takeIf { it.isNotBlank() } ?: sourceYaml.readPreference,
            appName = parsed.appName.takeIf { it.isNotBlank() } ?: sourceYaml.appName
        )
    }

    fun buildProfile(): MongoConnectionProfile {
        if (extraParamsTable.isEditing) {
            extraParamsTable.cellEditor?.stopCellEditing()
        }
        val name = (nameField.text as String?)?.trim().orEmpty()
        val parsed = if (editUriCheck.isSelected) {
            MongoUriHelper.parseUri(uriField.text) ?: readParsedFromTabs()
        } else {
            readParsedFromTabs()
        }
        val finalUri = if (editUriCheck.isSelected) {
            uriField.text.trim()
        } else {
            MongoUriHelper.buildUri(parsed)
        }

        val caPath = caFileField.text.trim().takeIf { it.isNotEmpty() }
        val clientPath = clientCertField.text.trim().takeIf { it.isNotEmpty() }
        val effectiveTls = parsed.tls || tlsEnabledCheck.isSelected || caPath != null || clientPath != null

        return MongoConnectionProfile(
            id = existing?.id ?: "custom:${UUID.randomUUID()}",
            displayName = name,
            profileName = name,
            source = ProfileSource.CUSTOM,
            uri = finalUri,
            database = parsed.database.takeIf { it.isNotBlank() },
            username = parsed.username.takeIf { it.isNotBlank() },
            password = parsed.password.takeIf { it.isNotEmpty() },
            authDatabase = parsed.authSource.takeIf { it.isNotBlank() },
            readOnly = readOnlyCheck.isSelected,
            colorTag = selectedColorTag(),
            tlsEnabled = effectiveTls,
            tlsCaFilePath = caPath,
            tlsClientCertFilePath = clientPath,
            tlsAllowInvalidHostnames = parsed.tlsAllowInvalidHostnames || allowInvalidHostnamesCheck.isSelected,
            tlsAllowInvalidCertificates = parsed.tlsAllowInvalidCertificates || allowInvalidCertsCheck.isSelected,
            connectTimeoutMs = parsed.connectTimeoutMs.toIntOrNull(),
            socketTimeoutMs = parsed.socketTimeoutMs.toIntOrNull(),
            retryWrites = parsed.retryWrites,
            authMechanism = parsed.authMechanism.takeIf { it.isNotBlank() },
            replicaSet = parsed.replicaSet.takeIf { it.isNotBlank() },
            directConnection = parsed.directConnection,
            readPreference = parsed.readPreference.takeIf { it.isNotBlank() },
            appName = parsed.appName.takeIf { it.isNotBlank() }
        )
    }

    override fun dispose() {
        testFutureRef.getAndSet(null)?.cancel(true)
        super.dispose()
    }
}
