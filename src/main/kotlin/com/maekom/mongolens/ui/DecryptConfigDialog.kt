package com.maekom.mongolens.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import com.maekom.mongolens.crypto.CryptoConfigStore
import com.maekom.mongolens.crypto.CryptoService
import com.maekom.mongolens.crypto.DecryptMode
import com.maekom.mongolens.crypto.KeyEncoding
import com.maekom.mongolens.crypto.ProfileCryptoConfig
import com.maekom.mongolens.i18n.I18n
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.nio.file.Path
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JSpinner
import javax.swing.JToggleButton
import javax.swing.SpinnerNumberModel

class DecryptConfigDialog(
    private val project: Project,
    private val projectBasePath: Path?,
    private val cryptoStore: CryptoConfigStore,
    initialProfileName: String,
    availableProfiles: List<String> = emptyList()
) : DialogWrapper(project) {

    private val profileOptions: List<String> = buildList {
        availableProfiles.forEach { name ->
            val trimmed = name.trim()
            if (trimmed.isNotEmpty()) add(trimmed)
        }
        addAll(cryptoStore.getAllProfileNames(initialProfileName))
        val initTrimmed = initialProfileName.trim()
        if (initTrimmed.isNotEmpty()) add(initTrimmed)
    }.distinct().ifEmpty { listOf("default") }

    private var selectedProfileName: String =
        initialProfileName.trim().takeIf { it in profileOptions } ?: profileOptions.first()

    private val drafts = linkedMapOf<String, Pair<ProfileCryptoConfig, String>>()
    private var loadingProfileFields = false

    private val profileCombo = ComboBox(profileOptions.toTypedArray()).apply {
        selectedItem = selectedProfileName
    }

    private val initialConfig = cryptoStore.getConfig(selectedProfileName)
    private val initialSecret = cryptoStore.getSecretKey(selectedProfileName)

    private val prefixField = JBTextField(initialConfig.prefix)
    private val modeCombo = ComboBox(DecryptMode.values()).apply {
        selectedItem = initialConfig.mode
    }
    private val encodingCombo = ComboBox(KeyEncoding.values()).apply {
        selectedItem = initialConfig.keyEncoding
    }
    private val secretKeyField = JBPasswordField().apply {
        text = initialSecret
    }
    private val defaultEchoChar: Char = secretKeyField.echoChar
    private val eyeToggleBtn = JToggleButton("👁", false).apply {
        margin = JBUI.insets(1, 5)
        preferredSize = Dimension(34, 26)
    }
    private val scanYamlBtn = JButton(I18n.t("crypto.dialog.scanYaml")).apply {
        margin = JBUI.insets(2, 8)
    }
    private val fixedIvField = JBTextField(initialConfig.fixedIv).apply {
        isEnabled = initialConfig.mode == DecryptMode.AES_CBC_FIXED_IV
    }
    private val revealSecondsSpinner = JSpinner(
        SpinnerNumberModel(initialConfig.clampedRevealSeconds(), 5, 60, 1)
    ).apply {
        preferredSize = Dimension(72, 26)
    }

    // Live Decryption Test area
    private val testInputField = JBTextField("").apply {
        emptyText.text = I18n.t("crypto.dialog.testInput")
    }
    private val testBtn = JButton(I18n.t("crypto.dialog.testBtn")).apply {
        margin = JBUI.insets(2, 8)
    }
    private val testResultLabel = JBLabel(" ").apply {
        font = Font(Font.MONOSPACED, Font.BOLD, 11)
    }

    init {
        title = "${I18n.t("crypto.dialog.title")} — $selectedProfileName"
        wireListeners()
        init()
    }

    private fun wireListeners() {
        profileCombo.addActionListener {
            if (loadingProfileFields) return@addActionListener
            val nextProfile = profileCombo.selectedItem?.toString()?.trim().orEmpty()
            if (nextProfile.isEmpty() || nextProfile == selectedProfileName) return@addActionListener
            // Preserve current unsaved edits for the previous profile
            drafts[selectedProfileName] = buildConfig() to String(secretKeyField.password)
            selectedProfileName = nextProfile
            loadProfileIntoFields(selectedProfileName)
        }

        eyeToggleBtn.addActionListener {
            secretKeyField.echoChar = if (eyeToggleBtn.isSelected) 0.toChar() else defaultEchoChar
        }

        modeCombo.addActionListener {
            fixedIvField.isEnabled = (modeCombo.selectedItem as? DecryptMode) == DecryptMode.AES_CBC_FIXED_IV
        }

        scanYamlBtn.addActionListener {
            val candidates = CryptoService.scanYamlForSecretKeyCandidates(projectBasePath)
            if (candidates.isEmpty()) {
                Messages.showInfoMessage(project, I18n.t("crypto.dialog.scanYamlEmpty"), I18n.t("crypto.dialog.scanYaml"))
                return@addActionListener
            }
            if (candidates.size == 1) {
                secretKeyField.text = candidates.first().resolvedValue
                return@addActionListener
            }
            val labels = candidates.map { it.toString() }.toTypedArray()
            val chosen = Messages.showEditableChooseDialog(
                I18n.t("crypto.dialog.scanYamlPick"),
                I18n.t("crypto.dialog.scanYaml"),
                Messages.getQuestionIcon(),
                labels,
                labels.first(),
                null
            )
            val idx = labels.indexOf(chosen)
            if (idx >= 0) {
                secretKeyField.text = candidates[idx].resolvedValue
            }
        }

        testBtn.addActionListener {
            val sample = (testInputField.text as String?)?.trim().orEmpty()
            if (sample.isEmpty()) return@addActionListener
            try {
                val cfg = buildConfig()
                val decrypted = CryptoService.decrypt(sample, cfg, String(secretKeyField.password))
                testResultLabel.text = I18n.t("crypto.dialog.testSuccess", decrypted)
                testResultLabel.foreground = UiHelpers.COLOR_GREEN_FG
            } catch (t: Throwable) {
                testResultLabel.text = I18n.t("crypto.decryptFailed", t.message ?: t.javaClass.simpleName)
                testResultLabel.foreground = UiHelpers.COLOR_RED_FG
            }
        }
    }

    private fun loadProfileIntoFields(profileName: String) {
        loadingProfileFields = true
        try {
            val draft = drafts[profileName]
            val cfg = draft?.first ?: cryptoStore.getConfig(profileName)
            val key = draft?.second ?: cryptoStore.getSecretKey(profileName)
            title = "${I18n.t("crypto.dialog.title")} — $profileName"
            prefixField.text = cfg.prefix
            modeCombo.selectedItem = cfg.mode
            encodingCombo.selectedItem = cfg.keyEncoding
            secretKeyField.text = key
            fixedIvField.text = cfg.fixedIv
            fixedIvField.isEnabled = cfg.mode == DecryptMode.AES_CBC_FIXED_IV
            revealSecondsSpinner.value = cfg.clampedRevealSeconds()
            testResultLabel.text = " "
        } finally {
            loadingProfileFields = false
        }
    }

    override fun createCenterPanel(): JComponent {
        val keyRow = JPanel(BorderLayout(4, 0)).apply {
            add(secretKeyField, BorderLayout.CENTER)
            val rightBtns = JPanel(FlowLayout(FlowLayout.RIGHT, 2, 0)).apply {
                add(eyeToggleBtn)
                add(scanYamlBtn)
            }
            add(rightBtns, BorderLayout.EAST)
        }

        val testPanel = JPanel(BorderLayout(4, 4)).apply {
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createTitledBorder(I18n.t("crypto.dialog.testSection")),
                JBUI.Borders.empty(4, 6)
            )
            val inputRow = JPanel(BorderLayout(6, 0)).apply {
                add(testInputField, BorderLayout.CENTER)
                add(testBtn, BorderLayout.EAST)
            }
            add(inputRow, BorderLayout.NORTH)
            add(testResultLabel, BorderLayout.SOUTH)
        }

        val form = FormBuilder.createFormBuilder()
            .addLabeledComponent(JBLabel(I18n.t("crypto.dialog.profile")), profileCombo, 1, false)
            .addLabeledComponent(JBLabel(I18n.t("crypto.dialog.prefix")), prefixField, 1, false)
            .addLabeledComponent(JBLabel(I18n.t("crypto.dialog.mode")), modeCombo, 1, false)
            .addLabeledComponent(JBLabel(I18n.t("crypto.dialog.keyEncoding")), encodingCombo, 1, false)
            .addLabeledComponent(JBLabel(I18n.t("crypto.dialog.secretKey")), keyRow, 1, false)
            .addLabeledComponent(JBLabel(I18n.t("crypto.dialog.fixedIv")), fixedIvField, 1, false)
            .addLabeledComponent(JBLabel(I18n.t("crypto.dialog.revealSeconds")), revealSecondsSpinner, 1, false)
            .addComponent(testPanel, 8)
            .panel

        form.preferredSize = Dimension(560, 360)
        form.border = JBUI.Borders.empty(6)
        return form
    }

    override fun doValidate(): ValidationInfo? {
        val rawKey = String(secretKeyField.password)
        val enc = (encodingCombo.selectedItem as? KeyEncoding) ?: KeyEncoding.UTF8
        if (rawKey.isNotBlank()) {
            try {
                CryptoService.decodeKeyBytes(rawKey, enc)
            } catch (t: Throwable) {
                return ValidationInfo(t.message ?: "Invalid AES key", secretKeyField)
            }
        }
        val mode = (modeCombo.selectedItem as? DecryptMode) ?: DecryptMode.AES_CBC_IV_PREFIX
        if (mode == DecryptMode.AES_CBC_FIXED_IV && rawKey.isNotBlank()) {
            val ivRaw = (fixedIvField.text as String?)?.trim().orEmpty()
            if (ivRaw.isEmpty()) {
                return ValidationInfo("Fixed IV (16 bytes) is required for AES/CBC Fixed IV mode.", fixedIvField)
            }
            try {
                val ivBytes = when (enc) {
                    KeyEncoding.UTF8 -> ivRaw.toByteArray(java.nio.charset.StandardCharsets.UTF_8)
                    KeyEncoding.BASE64 -> java.util.Base64.getDecoder().decode(ivRaw)
                    KeyEncoding.HEX -> {
                        val clean = ivRaw.replace(" ", "").removePrefix("0x").removePrefix("0X")
                        if (clean.length % 2 != 0) throw IllegalArgumentException("Invalid Hex IV length.")
                        ByteArray(clean.length / 2) { i -> clean.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
                    }
                }
                if (ivBytes.size != 16) {
                    return ValidationInfo("Fixed IV must be exactly 16 bytes (got ${ivBytes.size} bytes).", fixedIvField)
                }
            } catch (t: Throwable) {
                return ValidationInfo(t.message ?: "Invalid Fixed IV", fixedIvField)
            }
        }
        return null
    }

    override fun doOKAction() {
        drafts[selectedProfileName] = buildConfig() to String(secretKeyField.password)
        drafts.forEach { (_, pair) ->
            cryptoStore.saveConfig(pair.first, pair.second)
        }
        super.doOKAction()
    }

    private fun buildConfig(): ProfileCryptoConfig {
        return ProfileCryptoConfig(
            profileName = selectedProfileName,
            prefix = (prefixField.text as String?)?.trim().orEmpty(),
            mode = (modeCombo.selectedItem as? DecryptMode) ?: DecryptMode.AES_CBC_IV_PREFIX,
            keyEncoding = (encodingCombo.selectedItem as? KeyEncoding) ?: KeyEncoding.UTF8,
            fixedIv = (fixedIvField.text as String?)?.trim().orEmpty(),
            revealSeconds = ((revealSecondsSpinner.value as? Int) ?: 10).coerceIn(5, 60)
        )
    }
}
