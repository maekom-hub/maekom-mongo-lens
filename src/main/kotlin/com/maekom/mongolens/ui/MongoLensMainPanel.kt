package com.maekom.mongolens.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.util.Disposer
import com.intellij.ui.JBSplitter
import com.intellij.ui.components.JBLabel
import com.intellij.ui.tabs.JBTabs
import com.intellij.ui.tabs.JBTabsFactory
import com.intellij.util.ui.JBUI
import com.maekom.mongolens.config.CustomConnectionStore
import com.maekom.mongolens.config.MongoConnectionProfile
import com.maekom.mongolens.config.SavedQueryStore
import com.maekom.mongolens.config.SpringYamlConfigParser
import com.maekom.mongolens.config.VariableStore
import com.maekom.mongolens.config.YamlConfigWatcher
import com.maekom.mongolens.i18n.I18n
import com.maekom.mongolens.mongo.MongoConnectionManager
import com.maekom.mongolens.update.PluginUpdateChecker
import com.maekom.mongolens.update.ReleaseInfo
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.GridBagLayout
import java.nio.file.Path
import java.nio.file.Paths
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingUtilities

class MongoLensMainPanel(
    private val project: Project,
    parentDisposable: Disposable
) : JPanel(BorderLayout()), Disposable {

    private sealed class ProfileDropdownItem {
        object AllProfiles : ProfileDropdownItem() {
            override fun toString(): String = I18n.t("header.allProfiles")
        }
        data class SingleProfile(val profile: MongoConnectionProfile) : ProfileDropdownItem() {
            override fun toString(): String =
                if (profile.readOnly) "${profile.displayName} 🔒" else profile.displayName
        }
        object AddCustomUri : ProfileDropdownItem() {
            override fun toString(): String = I18n.t("header.addCustomUri")
        }
    }

    private val projectBasePath: Path? = project.basePath?.let { Paths.get(it) }
    private val yamlParser = SpringYamlConfigParser()
    private val customStore = CustomConnectionStore(projectBasePath)
    private val savedQueryStore = SavedQueryStore(projectBasePath)
    private val variableStore = VariableStore(projectBasePath)
    private val connectionManager = MongoConnectionManager(project)

    private var discoveredProfiles: List<MongoConnectionProfile> = emptyList()
    private var selectedProfileFilterId: String? = null
    private var suppressDropdownEvent = false
    private var availableUpdate: ReleaseInfo? = null

    // Global 1-row header controls
    private val titleLabel = JBLabel(I18n.t("app.title")).apply {
        font = font.deriveFont(Font.BOLD, (font.size + 1).toFloat())
    }
    private val versionBadge = UiHelpers.createBadgeLabel(
        "v${PluginUpdateChecker.currentVersionString()}",
        UiHelpers.COLOR_BLUE_BG,
        UiHelpers.COLOR_BLUE_FG
    )
    private val profileComboModel = DefaultComboBoxModel<ProfileDropdownItem>()
    private val profileCombo = ComboBox(profileComboModel).apply {
        preferredSize = Dimension(190, 26)
    }
    private val syncYamlBtn = JButton(I18n.t("header.syncYaml")).apply {
        margin = JBUI.insets(1, 8)
        toolTipText = I18n.t("header.syncYamlTooltip")
    }

    private val statusLabel = JBLabel(I18n.t("header.status.idle")).apply {
        border = JBUI.Borders.empty(0, 6)
    }
    private val updateBtn = JButton(I18n.t("header.update.check")).apply {
        margin = JBUI.insets(1, 8)
        toolTipText = I18n.t("header.update.tooltip")
    }
    private val langToggleBtn = JButton(I18n.getLang().code).apply {
        margin = JBUI.insets(1, 8)
        toolTipText = I18n.t("header.lang.toggleTooltip")
    }

    private val tabs: JBTabs = JBTabsFactory.createTabs(project, this)
    private val rightCardLayout = CardLayout()
    private val rightPanel = JPanel(rightCardLayout)
    private val welcomeTitleLabel = JBLabel(I18n.t("workspace.welcome.title")).apply {
        font = font.deriveFont(Font.BOLD, 16f)
        alignmentX = JComponent.CENTER_ALIGNMENT
    }
    private val welcomeDescLabel = JBLabel(formatHtmlCenter(I18n.t("workspace.welcome.desc"))).apply {
        foreground = UiHelpers.BSON_NULL_COLOR
        alignmentX = JComponent.CENTER_ALIGNMENT
    }

    // Left tree & Right multi-tab manager
    private lateinit var connectionsTree: ConnectionsTreePanel
    private val tabManager: WorkspaceTabManager

    init {
        Disposer.register(parentDisposable, this)

        tabManager = WorkspaceTabManager(
            project = project,
            tabs = tabs,
            parentDisposable = this,
            connectionManager = connectionManager,
            savedQueryStore = savedQueryStore,
            variableStore = variableStore,
            onStatusUpdate = { text, isError -> setStatus(text, isError) },
            onReadOnlyStateChanged = { connectionsTree.repaintTree() },
            onTabCountChanged = { count ->
                rightCardLayout.show(rightPanel, if (count > 0) "TABS" else "WELCOME")
            }
        )

        connectionsTree = ConnectionsTreePanel(
            project = project,
            connectionManager = connectionManager,
            parentDisposable = this,
            onStatusUpdate = { text, isError -> setStatus(text, isError) },
            onOpenFindTab = { profile, db, coll, forceNew ->
                if (forceNew) {
                    tabManager.openNewClonedTab(profile, db, coll, CollectionWorkspaceTab.WorkspaceMode.FIND)
                } else {
                    tabManager.openOrFocusCollectionTab(profile, db, coll)
                }
            },
            onOpenAggTab = { profile, db, coll ->
                tabManager.openNewClonedTab(profile, db, coll, CollectionWorkspaceTab.WorkspaceMode.AGGREGATION)
            },
            onOpenShellTab = { profile, db, coll ->
                tabManager.openNewClonedTab(profile, db, coll, CollectionWorkspaceTab.WorkspaceMode.SHELL)
            },
            onEditCustomProfile = { existing -> openCustomUriDialog(existing) },
            onDeleteCustomProfile = { existing ->
                connectionManager.closeClient(existing.id)
                customStore.delete(existing.id)
                reloadProfilesAsync()
            }
        )

        buildGlobalHeader()
        buildBody()

        // Watch Spring YAML & .env changes automatically
        YamlConfigWatcher(project, this) {
            reloadProfilesAsync()
        }

        I18n.addListener(this) { refreshTexts() }

        // Initial scan & one-time silent GitHub release check (asynchronous, non-blocking)
        reloadProfilesAsync()
        triggerUpdateCheck(silent = true)
    }

    private fun buildGlobalHeader() {
        val header = JPanel(BorderLayout(8, 0)).apply {
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, JBUI.CurrentTheme.ToolWindow.borderColor()),
                JBUI.Borders.empty(3, 8)
            )
        }

        val left = JPanel(FlowLayout(FlowLayout.LEFT, 6, 1)).apply {
            add(titleLabel)
            add(versionBadge)
            add(profileCombo)
            add(syncYamlBtn)
        }

        val right = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 1)).apply {
            add(statusLabel)
            add(updateBtn)
            add(langToggleBtn)
        }

        header.add(left, BorderLayout.WEST)
        header.add(right, BorderLayout.EAST)
        add(header, BorderLayout.NORTH)

        profileCombo.addActionListener {
            if (suppressDropdownEvent) return@addActionListener
            when (val selected = profileCombo.selectedItem as? ProfileDropdownItem) {
                is ProfileDropdownItem.AllProfiles -> {
                    selectedProfileFilterId = null
                    connectionsTree.setProfileFilter(null)
                }
                is ProfileDropdownItem.SingleProfile -> {
                    selectedProfileFilterId = selected.profile.id
                    connectionsTree.setProfileFilter(selected.profile.id)
                    if (connectionManager.isEffectivelyReadOnly(selected.profile)) {
                        setStatus(I18n.t("header.status.readOnly"), false)
                    }
                }
                is ProfileDropdownItem.AddCustomUri -> {
                    openCustomUriDialog(null)
                    restoreDropdownSelection()
                }
                null -> Unit
            }
        }

        syncYamlBtn.addActionListener { reloadProfilesAsync() }

        updateBtn.addActionListener {
            val pending = availableUpdate
            if (pending != null) {
                PluginUpdateChecker.promptAndInstallUpdate(project, pending)
            } else {
                triggerUpdateCheck(silent = false)
            }
        }

        langToggleBtn.addActionListener {
            I18n.toggle()
        }
    }

    private fun buildBody() {
        val welcomeInner = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(welcomeTitleLabel)
            add(Box.createVerticalStrut(10))
            add(welcomeDescLabel)
        }
        val welcomePanel = JPanel(GridBagLayout()).apply {
            add(welcomeInner)
        }

        rightPanel.add(welcomePanel, "WELCOME")
        rightPanel.add(tabs.component, "TABS")
        rightCardLayout.show(rightPanel, "WELCOME")

        val splitter = JBSplitter(false, 0.22f).apply {
            firstComponent = connectionsTree
            secondComponent = rightPanel
        }
        add(splitter, BorderLayout.CENTER)
    }

    private fun reloadProfilesAsync() {
        object : Task.Backgroundable(project, "Scanning Spring YAML MongoDB configs...", true) {
            override fun run(indicator: ProgressIndicator) {
                val yamlProfiles = yamlParser.scanProject(projectBasePath)
                val customProfiles = customStore.loadAll()
                val merged = yamlProfiles + customProfiles

                ApplicationManager.getApplication().invokeLater {
                    if (project.isDisposed) return@invokeLater
                    discoveredProfiles = merged
                    if (selectedProfileFilterId != null && merged.none { it.id == selectedProfileFilterId }) {
                        selectedProfileFilterId = null
                    }
                    rebuildProfileDropdown()
                    connectionsTree.setProfiles(merged, selectedProfileFilterId)
                }
            }
        }.queue()
    }

    private fun rebuildProfileDropdown() {
        suppressDropdownEvent = true
        try {
            profileComboModel.removeAllElements()
            profileComboModel.addElement(ProfileDropdownItem.AllProfiles)
            discoveredProfiles.forEach { p ->
                profileComboModel.addElement(ProfileDropdownItem.SingleProfile(p))
            }
            profileComboModel.addElement(ProfileDropdownItem.AddCustomUri)
            restoreDropdownSelection()
        } finally {
            suppressDropdownEvent = false
        }
    }

    private fun restoreDropdownSelection() {
        suppressDropdownEvent = true
        try {
            if (selectedProfileFilterId == null) {
                profileCombo.selectedIndex = 0
            } else {
                for (i in 0 until profileComboModel.size) {
                    val item = profileComboModel.getElementAt(i)
                    if (item is ProfileDropdownItem.SingleProfile && item.profile.id == selectedProfileFilterId) {
                        profileCombo.selectedIndex = i
                        return
                    }
                }
                profileCombo.selectedIndex = 0
            }
        } finally {
            suppressDropdownEvent = false
        }
    }

    private fun openCustomUriDialog(existing: MongoConnectionProfile?) {
        val dlg = CustomUriDialog(project, connectionManager, existing)
        if (dlg.showAndGet()) {
            val saved = dlg.buildProfile()
            customStore.upsert(saved)
            connectionManager.closeClient(saved.id)
            reloadProfilesAsync()
        }
    }

    private fun triggerUpdateCheck(silent: Boolean) {
        if (!silent) {
            updateBtn.isEnabled = false
            updateBtn.text = I18n.t("header.update.checking")
        }
        PluginUpdateChecker.checkLatestReleaseAsync(project, silent) { result ->
            updateBtn.isEnabled = true
            result.onSuccess { release ->
                if (release != null) {
                    availableUpdate = release
                    applyUpdateAvailableButtonStyle(release)
                } else {
                    availableUpdate = null
                    applyDefaultUpdateButtonStyle()
                    if (!silent) {
                        UiHelpers.notify(
                            project,
                            I18n.t("header.update.upToDate", "v${PluginUpdateChecker.currentVersionString()}")
                        )
                    }
                }
            }.onFailure { err ->
                if (availableUpdate == null) {
                    applyDefaultUpdateButtonStyle()
                }
                if (!silent) {
                    UiHelpers.notify(
                        project,
                        I18n.t("header.update.failed", err.message ?: err.javaClass.simpleName),
                        com.intellij.notification.NotificationType.WARNING
                    )
                }
            }
        }
    }

    private fun applyUpdateAvailableButtonStyle(release: ReleaseInfo) {
        updateBtn.text = I18n.t("header.update.available", release.tagName)
        updateBtn.toolTipText = I18n.t("header.update.availableTooltip", release.tagName)
        updateBtn.background = UiHelpers.COLOR_GREEN_BG
        updateBtn.foreground = UiHelpers.COLOR_GREEN_FG
        updateBtn.font = updateBtn.font.deriveFont(Font.BOLD)
        updateBtn.isOpaque = true
    }

    private fun applyDefaultUpdateButtonStyle() {
        updateBtn.text = I18n.t("header.update.check")
        updateBtn.toolTipText = I18n.t("header.update.tooltip")
        updateBtn.background = null
        updateBtn.foreground = null
        updateBtn.font = updateBtn.font.deriveFont(Font.PLAIN)
        updateBtn.isOpaque = false
    }

    private fun setStatus(text: String, isError: Boolean) {
        SwingUtilities.invokeLater {
            statusLabel.text = text
            statusLabel.foreground = if (isError) UiHelpers.COLOR_RED_FG else null
        }
    }

    private fun formatHtmlCenter(text: String): String {
        return "<html><div style='text-align:center;'>${text.replace("\n", "<br/>")}</div></html>"
    }

    private fun refreshTexts() {
        SwingUtilities.invokeLater {
            titleLabel.text = I18n.t("app.title")
            syncYamlBtn.text = I18n.t("header.syncYaml")
            syncYamlBtn.toolTipText = I18n.t("header.syncYamlTooltip")
            langToggleBtn.text = I18n.getLang().code
            langToggleBtn.toolTipText = I18n.t("header.lang.toggleTooltip")
            welcomeTitleLabel.text = I18n.t("workspace.welcome.title")
            welcomeDescLabel.text = formatHtmlCenter(I18n.t("workspace.welcome.desc"))

            val pending = availableUpdate
            if (pending != null) {
                applyUpdateAvailableButtonStyle(pending)
            } else {
                applyDefaultUpdateButtonStyle()
            }

            if (statusLabel.text == "대기 중" || statusLabel.text == "Idle") {
                statusLabel.text = I18n.t("header.status.idle")
            }
            profileCombo.repaint()
        }
    }

    override fun dispose() {
        tabManager.clearRegistry()
    }
}
