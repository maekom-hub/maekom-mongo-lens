package com.maekom.mongolens.ui

import com.intellij.icons.AllIcons
import com.intellij.ide.ActivityTracker
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.actionSystem.ex.CustomComponentAction
import com.intellij.openapi.actionSystem.impl.ActionButtonWithText
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.JBSplitter
import com.intellij.ui.components.JBLabel
import com.intellij.ui.tabs.JBTabs
import com.intellij.ui.tabs.JBTabsFactory
import com.maekom.mongolens.config.CustomConnectionStore
import com.maekom.mongolens.config.MongoConnectionProfile
import com.maekom.mongolens.config.SavedQueryStore
import com.maekom.mongolens.config.SpringYamlConfigParser
import com.maekom.mongolens.config.VariableStore
import com.maekom.mongolens.config.YamlConfigWatcher
import com.maekom.mongolens.crypto.CryptoConfigStore
import com.maekom.mongolens.i18n.I18n
import com.maekom.mongolens.mongo.MongoConnectionManager
import com.maekom.mongolens.update.PluginUpdateChecker
import com.maekom.mongolens.update.ReleaseInfo
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Font
import java.awt.GridBagLayout
import java.nio.file.Path
import java.nio.file.Paths
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingUtilities

class MongoLensMainPanel(
    private val project: Project,
    parentDisposable: Disposable
) : JPanel(BorderLayout()), Disposable {

    private val projectBasePath: Path? = project.basePath?.let { Paths.get(it) }
    private val yamlParser = SpringYamlConfigParser()
    private val customStore = CustomConnectionStore(projectBasePath)
    private val savedQueryStore = SavedQueryStore(projectBasePath)
    private val variableStore = VariableStore(projectBasePath)
    private val cryptoStore = CryptoConfigStore(projectBasePath)
    private val connectionManager = MongoConnectionManager(project)

    @Volatile
    private var discoveredProfiles: List<MongoConnectionProfile> = emptyList()

    @Volatile
    private var selectedProfileFilterId: String? = null

    @Volatile
    private var availableUpdate: ReleaseInfo? = null

    @Volatile
    private var checkingUpdate: Boolean = false

    @Volatile
    private var currentStatusText: String = I18n.t("header.status.idle")

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

    private lateinit var connectionsTree: ConnectionsTreePanel
    private val tabManager: WorkspaceTabManager

    init {
        Disposer.register(parentDisposable, this)

        tabManager = WorkspaceTabManager(
            project = project,
            projectBasePath = projectBasePath,
            tabs = tabs,
            parentDisposable = this,
            connectionManager = connectionManager,
            savedQueryStore = savedQueryStore,
            variableStore = variableStore,
            cryptoStore = cryptoStore,
            onStatusUpdate = { text, _ -> setStatus(text) },
            onReadOnlyStateChanged = { connectionsTree.repaintTree() },
            onTabCountChanged = { count ->
                rightCardLayout.show(rightPanel, if (count > 0) "TABS" else "WELCOME")
            }
        )

        connectionsTree = ConnectionsTreePanel(
            project = project,
            connectionManager = connectionManager,
            parentDisposable = this,
            onStatusUpdate = { text, _ -> setStatus(text) },
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

        // Body starts immediately with Connections Tree | Collection Workspace Tabs (NO duplicate top header bar)
        buildBody()

        YamlConfigWatcher(project, this) {
            reloadProfilesAsync()
        }

        I18n.addListener(this) { refreshTexts() }

        reloadProfilesAsync()
        triggerUpdateCheck(silent = true)
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

    /**
     * Builds the lightweight native ToolWindowEx title actions integrated into IntelliJ's
     * single native ToolWindow title bar (100% IntelliJ New UI compatible).
     */
    fun createTitleActions(): List<AnAction> {
        // 1. Version & Connection Status indicator
        val versionStatusAction = object : AnAction(), DumbAware, CustomComponentAction {
            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

            override fun createCustomComponent(presentation: Presentation, place: String): JComponent {
                return ActionButtonWithText(this, presentation, place, ActionToolbar.DEFAULT_MINIMUM_BUTTON_SIZE)
            }

            override fun update(e: AnActionEvent) {
                val ver = PluginUpdateChecker.currentVersionString()
                val text = "v$ver · $currentStatusText"
                e.presentation.text = text
                e.presentation.description = "${I18n.t("app.title")} $text"
            }

            override fun actionPerformed(e: AnActionEvent) {
                val ver = PluginUpdateChecker.currentVersionString()
                UiHelpers.notify(project, "${I18n.t("app.title")} v$ver · $currentStatusText")
            }
        }

        // 2. Profile Selector Dropdown (DefaultActionGroup, isPopup = true)
        val profileSelectorGroup = object : DefaultActionGroup(), DumbAware, CustomComponentAction {
            init {
                isPopup = true
                templatePresentation.icon = AllIcons.Nodes.DataTables
            }

            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

            override fun createCustomComponent(presentation: Presentation, place: String): JComponent {
                return ActionButtonWithText(this, presentation, place, ActionToolbar.DEFAULT_MINIMUM_BUTTON_SIZE)
            }

            override fun update(e: AnActionEvent) {
                val selectedId = selectedProfileFilterId
                val label = if (selectedId == null) {
                    I18n.t("header.allProfiles")
                } else {
                    val found = discoveredProfiles.firstOrNull { it.id == selectedId }
                    if (found != null) {
                        if (found.readOnly) "${found.displayName} 🔒" else found.displayName
                    } else {
                        I18n.t("header.allProfiles")
                    }
                }
                e.presentation.text = label
                e.presentation.icon = AllIcons.Nodes.DataTables
            }

            override fun getChildren(e: AnActionEvent?): Array<AnAction> {
                val actions = mutableListOf<AnAction>()
                val currentFilterId = selectedProfileFilterId

                actions.add(object : AnAction(
                    I18n.t("header.allProfiles"),
                    null,
                    if (currentFilterId == null) AllIcons.Actions.Checked else null
                ), DumbAware {
                    override fun actionPerformed(ev: AnActionEvent) {
                        selectedProfileFilterId = null
                        connectionsTree.setProfileFilter(null)
                        refreshTitleToolbar()
                    }
                })

                val snapshot = discoveredProfiles
                if (snapshot.isNotEmpty()) {
                    actions.add(Separator.getInstance())
                    for (profile in snapshot) {
                        val itemText = if (profile.readOnly) "${profile.displayName} 🔒" else profile.displayName
                        val isSelected = currentFilterId == profile.id
                        actions.add(object : AnAction(
                            itemText,
                            profile.maskedLocation(),
                            if (isSelected) AllIcons.Actions.Checked else null
                        ), DumbAware {
                            override fun actionPerformed(ev: AnActionEvent) {
                                selectedProfileFilterId = profile.id
                                connectionsTree.setProfileFilter(profile.id)
                                if (connectionManager.isEffectivelyReadOnly(profile)) {
                                    setStatus(I18n.t("header.status.readOnly"))
                                }
                                refreshTitleToolbar()
                            }
                        })
                    }
                }

                actions.add(Separator.getInstance())
                actions.add(object : AnAction(
                    I18n.t("header.addCustomUri"),
                    null,
                    AllIcons.General.Add
                ), DumbAware {
                    override fun actionPerformed(ev: AnActionEvent) {
                        openCustomUriDialog(null)
                    }
                })

                return actions.toTypedArray()
            }
        }

        // 3. YAML Sync Action (AllIcons.Actions.Refresh)
        val syncYamlAction = object : AnAction(AllIcons.Actions.Refresh), DumbAware {
            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

            override fun update(e: AnActionEvent) {
                e.presentation.text = I18n.t("header.syncYaml").removePrefix("↻ ").trim()
                e.presentation.description = I18n.t("header.syncYamlTooltip")
                e.presentation.icon = AllIcons.Actions.Refresh
            }

            override fun actionPerformed(e: AnActionEvent) {
                reloadProfilesAsync()
            }
        }

        // 4. Decrypt Config Action (AllIcons.Nodes.Padlock)
        val decryptConfigAction = object : AnAction(AllIcons.Nodes.Padlock), DumbAware {
            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

            override fun update(e: AnActionEvent) {
                e.presentation.text = I18n.t("header.cryptoConfig").removePrefix("🔐 ").trim()
                e.presentation.description = I18n.t("header.cryptoConfigTooltip")
                e.presentation.icon = AllIcons.Nodes.Padlock
            }

            override fun actionPerformed(e: AnActionEvent) {
                openDecryptConfigDialog()
            }
        }

        // 5. Update Check / Install Action (AllIcons.Actions.Download)
        val updateAction = object : AnAction(AllIcons.Actions.Download), DumbAware, CustomComponentAction {
            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

            override fun createCustomComponent(presentation: Presentation, place: String): JComponent {
                return ActionButtonWithText(this, presentation, place, ActionToolbar.DEFAULT_MINIMUM_BUTTON_SIZE)
            }

            override fun update(e: AnActionEvent) {
                val pending = availableUpdate
                e.presentation.isEnabled = !checkingUpdate
                e.presentation.icon = AllIcons.Actions.Download
                when {
                    checkingUpdate -> {
                        e.presentation.text = I18n.t("header.update.checking")
                        e.presentation.description = I18n.t("header.update.checking")
                    }
                    pending != null -> {
                        e.presentation.text = "⬆ ${pending.tagName}"
                        e.presentation.description = I18n.t("header.update.availableTooltip", pending.tagName)
                    }
                    else -> {
                        e.presentation.text = I18n.t("header.update.check").removePrefix("↻ ").trim()
                        e.presentation.description = I18n.t("header.update.tooltip")
                    }
                }
            }

            override fun actionPerformed(e: AnActionEvent) {
                val pending = availableUpdate
                if (pending != null) {
                    PluginUpdateChecker.promptAndInstallUpdate(project, pending)
                } else {
                    triggerUpdateCheck(silent = false)
                }
            }
        }

        // 6. KO / EN Language Toggle Action
        val langToggleAction = object : AnAction(), DumbAware, CustomComponentAction {
            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

            override fun createCustomComponent(presentation: Presentation, place: String): JComponent {
                return ActionButtonWithText(this, presentation, place, ActionToolbar.DEFAULT_MINIMUM_BUTTON_SIZE)
            }

            override fun update(e: AnActionEvent) {
                e.presentation.text = I18n.getLang().code
                e.presentation.description = I18n.t("header.lang.toggleTooltip")
            }

            override fun actionPerformed(e: AnActionEvent) {
                I18n.toggle()
                refreshTitleToolbar()
            }
        }

        return listOf(
            versionStatusAction,
            profileSelectorGroup,
            syncYamlAction,
            decryptConfigAction,
            updateAction,
            langToggleAction
        )
    }

    fun openDecryptConfigDialog() {
        val targetProfileName = tabManager.activeTabProfileName()
            ?: discoveredProfiles.firstOrNull { it.id == selectedProfileFilterId }?.profileName
            ?: discoveredProfiles.firstOrNull()?.profileName
            ?: "default"
        DecryptConfigDialog(project, projectBasePath, cryptoStore, targetProfileName).show()
    }

    fun reloadProfilesAsync() {
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
                    connectionsTree.setProfiles(merged, selectedProfileFilterId)
                    refreshTitleToolbar()
                }
            }
        }.queue()
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
            checkingUpdate = true
            refreshTitleToolbar()
        }
        PluginUpdateChecker.checkLatestReleaseAsync(project, silent) { result ->
            checkingUpdate = false
            result.onSuccess { release ->
                availableUpdate = release
                if (release == null && !silent) {
                    UiHelpers.notify(
                        project,
                        I18n.t("header.update.upToDate", "v${PluginUpdateChecker.currentVersionString()}")
                    )
                }
            }.onFailure { err ->
                if (!silent) {
                    UiHelpers.notify(
                        project,
                        I18n.t("header.update.failed", err.message ?: err.javaClass.simpleName),
                        com.intellij.notification.NotificationType.WARNING
                    )
                }
            }
            refreshTitleToolbar()
        }
    }

    private fun setStatus(text: String) {
        currentStatusText = text
        refreshTitleToolbar()
    }

    private fun refreshTitleToolbar() {
        ApplicationManager.getApplication().invokeLater {
            if (!project.isDisposed) {
                ActivityTracker.getInstance().inc()
            }
        }
    }

    private fun formatHtmlCenter(text: String): String {
        return "<html><div style='text-align:center;'>${text.replace("\n", "<br/>")}</div></html>"
    }

    private fun refreshTexts() {
        SwingUtilities.invokeLater {
            welcomeTitleLabel.text = I18n.t("workspace.welcome.title")
            welcomeDescLabel.text = formatHtmlCenter(I18n.t("workspace.welcome.desc"))
            if (currentStatusText == "대기 중" || currentStatusText == "Idle") {
                currentStatusText = I18n.t("header.status.idle")
            }
            ActivityTracker.getInstance().inc()
        }
    }

    override fun dispose() {
        tabManager.clearRegistry()
    }
}
