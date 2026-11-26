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
import java.util.concurrent.atomic.AtomicBoolean
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
    private val diffBaselineState = DiffBaselineState()
    private val connectionManager = MongoConnectionManager(project)

    @Volatile
    private var discoveredProfiles: List<MongoConnectionProfile> = emptyList()

    @Volatile
    private var selectedProfileFilterId: String? = null

    @Volatile
    private var availableUpdate: ReleaseInfo? = null

    private val isCheckingUpdate = AtomicBoolean(false)

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
            diffBaselineState = diffBaselineState,
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
            onSyncYaml = { reloadProfilesAsync() },
            onProfileFilterChanged = { profileId, selectedProfile ->
                selectedProfileFilterId = profileId
                if (selectedProfile != null && connectionManager.isEffectivelyReadOnly(selectedProfile)) {
                    setStatus(I18n.t("header.status.readOnly"))
                }
            },
            onAddCustomUri = { openCustomUriDialog(null) },
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
     * Simplified native ToolWindow title bar actions: ONLY 2 items:
     * 1. Version & Connection Status ("v0.2.2 · 연결됨 (126ms)")
     * 2. [⚙ 설정 / Settings] popup group (Decrypt Config, Check/Install Update, Language Switch)
     */
    fun createTitleActions(): List<AnAction> {
        // 1. Version & Connection Status Action
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

        // 2. [⚙ 설정 / Settings] Popup Group
        val settingsGroup = object : DefaultActionGroup(), DumbAware, CustomComponentAction {
            init {
                isPopup = true
                templatePresentation.isPopupGroup = true
                templatePresentation.isPerformGroup = false
                templatePresentation.icon = AllIcons.General.Settings
                templatePresentation.text = I18n.t("header.settings")
            }

            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

            override fun createCustomComponent(presentation: Presentation, place: String): JComponent {
                return ActionButtonWithText(this, presentation, place, ActionToolbar.DEFAULT_MINIMUM_BUTTON_SIZE)
            }

            override fun update(e: AnActionEvent) {
                e.presentation.isPopupGroup = true
                e.presentation.isPerformGroup = false
                e.presentation.icon = AllIcons.General.Settings
                val pending = availableUpdate
                e.presentation.text = if (pending != null) {
                    "${I18n.t("header.settings")} (⬆)"
                } else {
                    I18n.t("header.settings")
                }
                e.presentation.description = I18n.t("header.settingsTooltip")
            }

            override fun getChildren(e: AnActionEvent?): Array<AnAction> {
                val decryptItem = object : AnAction(
                    I18n.t("header.settings.decrypt"),
                    I18n.t("header.cryptoConfigTooltip"),
                    AllIcons.Nodes.Padlock
                ), DumbAware {
                    override fun actionPerformed(ev: AnActionEvent) {
                        openDecryptConfigDialog()
                    }
                }

                val pending = availableUpdate
                val updateLabel = when {
                    isCheckingUpdate.get() -> I18n.t("header.update.checking")
                    pending != null -> I18n.t("header.settings.updateInstall", pending.tagName)
                    else -> I18n.t("header.settings.updateCheck")
                }
                val updateItem = object : AnAction(
                    updateLabel,
                    if (pending != null) I18n.t("header.update.availableTooltip", pending.tagName) else I18n.t("header.update.tooltip"),
                    AllIcons.Actions.Download
                ), DumbAware {
                    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
                    override fun update(ev: AnActionEvent) {
                        ev.presentation.isEnabled = !isCheckingUpdate.get()
                    }
                    override fun actionPerformed(ev: AnActionEvent) {
                        val rel = availableUpdate
                        if (rel != null) {
                            PluginUpdateChecker.promptAndInstallUpdate(project, rel)
                        } else {
                            triggerUpdateCheck(silent = false)
                        }
                    }
                }

                val langItem = object : AnAction(
                    I18n.t("header.settings.langSwitch"),
                    I18n.t("header.lang.toggleTooltip"),
                    AllIcons.General.Web
                ), DumbAware {
                    override fun actionPerformed(ev: AnActionEvent) {
                        I18n.toggle()
                        refreshTitleToolbar()
                    }
                }

                return arrayOf(decryptItem, updateItem, langItem)
            }
        }

        return listOf(versionStatusAction, settingsGroup)
    }

    fun openDecryptConfigDialog() {
        val targetProfileName = tabManager.activeTabProfileName()
            ?: discoveredProfiles.firstOrNull { it.id == selectedProfileFilterId }?.profileName
            ?: discoveredProfiles.firstOrNull()?.profileName
            ?: "default"
        val allProfileNames = discoveredProfiles.map { it.profileName }.distinct()
        DecryptConfigDialog(
            project = project,
            projectBasePath = projectBasePath,
            cryptoStore = cryptoStore,
            initialProfileName = targetProfileName,
            availableProfiles = allProfileNames
        ).show()
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
                    cryptoStore.setKnownProfileNames(merged.map { it.profileName })
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
        if (!isCheckingUpdate.compareAndSet(false, true)) return
        if (!silent) {
            refreshTitleToolbar()
        }
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = PluginUpdateChecker.fetchLatestRelease()
            ApplicationManager.getApplication().invokeLater {
                isCheckingUpdate.set(false)
                if (project.isDisposed) return@invokeLater
                result.onSuccess { release ->
                    availableUpdate = release
                    refreshTitleToolbar()
                    if (!silent) {
                        if (release != null) {
                            // One-click flow: immediately prompt for download & install instead of asking user to re-open menu
                            PluginUpdateChecker.promptAndInstallUpdate(project, release)
                        } else {
                            UiHelpers.notify(
                                project,
                                I18n.t("header.update.upToDate", "v${PluginUpdateChecker.currentVersionString()}")
                            )
                        }
                    }
                }.onFailure { err ->
                    refreshTitleToolbar()
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
