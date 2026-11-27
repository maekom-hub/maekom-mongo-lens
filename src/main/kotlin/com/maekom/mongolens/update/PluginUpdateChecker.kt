package com.maekom.mongolens.update

import com.google.gson.JsonParser
import com.intellij.ide.plugins.PluginInstaller
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.ide.startup.StartupActionScriptManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.application.ex.ApplicationManagerEx
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.maekom.mongolens.i18n.I18n
import com.maekom.mongolens.ui.UiHelpers
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.time.Duration
import java.util.Comparator
import java.util.Locale
import java.util.zip.ZipFile

data class ReleaseInfo(
    val tagName: String,
    val version: SemVer,
    val htmlUrl: String,
    val releaseNotes: String,
    val assetName: String?,
    val assetDownloadUrl: String?,
    val assetSize: Long
)

object PluginUpdateChecker {
    private val LOG = Logger.getInstance(PluginUpdateChecker::class.java)

    const val PLUGIN_ID = "com.maekom.mongolens"
    const val RELEASES_API_URL = "https://api.github.com/repos/maekom-hub/maekom-mongo-lens/releases/latest"
    private const val FALLBACK_VERSION = "0.3.0"
    private const val USER_AGENT = "maekom-mongo-lens"
    private const val STAGING_DIR_NAME = "maekom-mongo-lens-staging"
    private const val LEGACY_TEMP_DIR_NAME = "maekom-mongo-lens-updates"
    private const val LEGACY_TMP_PREFIX = "maekom-mongo-lens-update-"
    private const val ASSET_PREFIX = "maekom-mongo-lens-"
    private const val MIN_PLUGIN_ZIP_BYTES = 10_240L

    private val ZIP_MAGIC_BYTES = byteArrayOf(0x50, 0x4B, 0x03, 0x04)

    private val httpClient: HttpClient by lazy {
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()
    }

    fun currentVersionString(): String {
        return try {
            PluginManagerCore.getPlugin(PluginId.getId(PLUGIN_ID))?.version ?: FALLBACK_VERSION
        } catch (_: Throwable) {
            FALLBACK_VERSION
        }
    }

    fun currentSemVer(): SemVer = SemVer.parse(currentVersionString()) ?: SemVer(0, 1, 0)

    fun checkLatestReleaseAsync(
        project: Project,
        silent: Boolean,
        onResult: (Result<ReleaseInfo?>) -> Unit
    ) {
        val task = object : Task.Backgroundable(
            project,
            I18n.t("header.update.checking"),
            true
        ) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                val res = fetchLatestRelease()
                ApplicationManager.getApplication().invokeLater {
                    if (!project.isDisposed) {
                        onResult(res)
                    }
                }
            }
        }
        task.queue()
    }

    fun fetchLatestRelease(): Result<ReleaseInfo?> {
        return try {
            val apiUri = validateTrustedHttpsUri(RELEASES_API_URL, allowApiHost = true)
            val request = HttpRequest.newBuilder()
                .uri(apiUri)
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", USER_AGENT)
                .GET()
                .build()

            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() != 200) {
                return Result.failure(IOException(I18n.t("update.error.httpStatus", response.statusCode())))
            }

            val json = JsonParser.parseString(response.body()).asJsonObject
            val tagName = json.get("tag_name")?.asString ?: return Result.success(null)
            val parsedVer = SemVer.parse(tagName) ?: return Result.success(null)
            val htmlUrl = json.get("html_url")?.asString ?: ""
            val body = json.get("body")?.takeIf { !it.isJsonNull }?.asString ?: ""

            var assetName: String? = null
            var assetUrl: String? = null
            var assetSize: Long = -1L

            val assets = json.getAsJsonArray("assets")
            if (assets != null) {
                for (elem in assets) {
                    val obj = elem.asJsonObject
                    val name = obj.get("name")?.asString?.trim() ?: continue
                    if (name.startsWith(ASSET_PREFIX) && name.endsWith(".zip", ignoreCase = true)) {
                        assetName = name
                        assetUrl = obj.get("browser_download_url")?.asString?.trim()
                        assetSize = obj.get("size")?.asLong ?: -1L
                        break
                    }
                }
            }

            val release = ReleaseInfo(
                tagName = tagName,
                version = parsedVer,
                htmlUrl = htmlUrl,
                releaseNotes = body,
                assetName = assetName,
                assetDownloadUrl = assetUrl,
                assetSize = assetSize
            )

            if (parsedVer.isNewerThan(currentSemVer())) {
                Result.success(release)
            } else {
                Result.success(null)
            }
        } catch (t: Throwable) {
            LOG.warn("Failed to check latest GitHub release", t)
            Result.failure(t)
        }
    }

    fun promptAndInstallUpdate(project: Project, release: ReleaseInfo) {
        val current = currentVersionString()
        val notesSummary = summarizeReleaseNotes(release.releaseNotes)
        val confirmMessage = if (notesSummary.isNotEmpty()) {
            I18n.t("update.dialog.confirmWithNotes", release.tagName, current, notesSummary)
        } else {
            I18n.t("update.dialog.confirmDownload", release.tagName, current)
        }
        val confirm = Messages.showYesNoDialog(
            project,
            confirmMessage,
            I18n.t("update.dialog.title"),
            I18n.t("update.dialog.updateNow"),
            Messages.getCancelButton(),
            Messages.getQuestionIcon()
        )
        if (confirm != Messages.YES) return

        val assetName = release.assetName?.trim().orEmpty()
        val downloadUrl = release.assetDownloadUrl?.trim().orEmpty()
        if (assetName.isEmpty() || downloadUrl.isEmpty()) {
            val msg = I18n.t("update.error.noAsset", release.tagName)
            LOG.warn(msg)
            UiHelpers.notify(project, msg, NotificationType.ERROR)
            return
        }

        // Pre-validate HTTPS and trusted GitHub domain before queuing background download
        val validatedUri = try {
            validateTrustedHttpsUri(downloadUrl, allowApiHost = false)
        } catch (t: Throwable) {
            val msg = t.message ?: I18n.t("update.error.untrustedUrl", downloadUrl)
            LOG.warn(msg, t)
            UiHelpers.notify(project, msg, NotificationType.ERROR)
            return
        }

        object : Task.Backgroundable(
            project,
            I18n.t("update.dialog.progressTitle", release.tagName),
            true
        ) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    indicator.isIndeterminate = false
                    indicator.fraction = 0.05
                    indicator.text = I18n.t("update.dialog.downloading", assetName)

                    // 1. Clean up stale legacy tmp/staging folders ONLY right before starting a new download (never deleteOnExit)
                    cleanupPreviousUpdateTempDirs()

                    // 2. Store ZIP outside IntelliJ's volatile `system/tmp` so IDE restart cleanup never deletes it before install
                    val stagingDir = Paths.get(PathManager.getSystemPath(), STAGING_DIR_NAME)
                    Files.createDirectories(stagingDir)
                    val safeFileName = Paths.get(assetName).fileName.toString()
                    val targetZip = stagingDir.resolve(safeFileName)

                    // 3. Download completely and ensure OutputStream is flushed & closed before verifying/installing
                    downloadFileWithProgress(validatedUri, targetZip, release.assetSize, indicator)

                    // 4. 3-point ZIP integrity & plugin package structure verification
                    indicator.text = I18n.t("update.dialog.verifying")
                    indicator.fraction = 0.88
                    verifyPluginZipIntegrity(targetZip, release.assetSize)

                    // 5. Schedule official IntelliJ restart installation on EDT only after all checks pass
                    indicator.text = I18n.t("update.dialog.scheduling")
                    indicator.fraction = 0.96
                    ApplicationManager.getApplication().invokeLater {
                        if (project.isDisposed) return@invokeLater
                        val installed = scheduleVerifiedPluginInstall(targetZip)
                        if (installed) {
                            val restartChoice = Messages.showYesNoDialog(
                                project,
                                I18n.t("update.dialog.restartMessage", release.tagName),
                                I18n.t("update.dialog.restartTitle"),
                                I18n.t("update.dialog.restartNow"),
                                I18n.t("update.dialog.restartLater"),
                                Messages.getInformationIcon()
                            )
                            if (restartChoice == Messages.YES) {
                                ApplicationManagerEx.getApplicationEx().restart(true)
                            }
                        } else {
                            val errMsg = I18n.t("update.error.installFailed", safeFileName)
                            UiHelpers.notify(project, errMsg, NotificationType.ERROR)
                        }
                    }
                    indicator.fraction = 1.0
                } catch (t: Throwable) {
                    val detail = t.message ?: t.javaClass.simpleName
                    LOG.warn("In-app plugin update failed for ${release.tagName}: $detail", t)
                    ApplicationManager.getApplication().invokeLater {
                        if (!project.isDisposed) {
                            UiHelpers.notify(
                                project,
                                I18n.t("update.dialog.error", detail),
                                NotificationType.ERROR
                            )
                        }
                    }
                }
            }
        }.queue()
    }

    private fun validateTrustedHttpsUri(rawUrl: String, allowApiHost: Boolean): URI {
        val uri = try {
            URI.create(rawUrl.trim())
        } catch (t: Throwable) {
            throw SecurityException(I18n.t("update.error.untrustedUrl", rawUrl), t)
        }
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        val host = uri.host?.lowercase(Locale.ROOT)
        val isHttps = scheme == "https"
        val isTrustedHost = when {
            host.isNullOrBlank() -> false
            allowApiHost && host == "api.github.com" -> true
            host == "github.com" -> true
            host.endsWith(".githubusercontent.com") -> true
            else -> false
        }
        if (!isHttps || !isTrustedHost) {
            throw SecurityException(I18n.t("update.error.untrustedUrl", rawUrl))
        }
        return uri
    }

    private fun cleanupPreviousUpdateTempDirs() {
        // Clean legacy v0.1.8 `PathManager.getTempPath()/maekom-mongo-lens-updates`
        runCatching {
            val legacyIdeTmp = Paths.get(PathManager.getTempPath(), LEGACY_TEMP_DIR_NAME)
            if (Files.exists(legacyIdeTmp)) {
                deleteDirectoryRecursivelyQuietly(legacyIdeTmp)
            }
        }
        // Clean previous staging folder files inside `PathManager.getSystemPath()/maekom-mongo-lens-staging`
        runCatching {
            val stagingDir = Paths.get(PathManager.getSystemPath(), STAGING_DIR_NAME)
            if (Files.isDirectory(stagingDir)) {
                deleteDirectoryRecursivelyQuietly(stagingDir)
            }
        }
        // Clean legacy v0.1.9 OS temp `maekom-mongo-lens-update-*` directories
        val tmpRoot = runCatching { Paths.get(System.getProperty("java.io.tmpdir")) }.getOrNull() ?: return
        if (!Files.isDirectory(tmpRoot)) return
        runCatching {
            Files.list(tmpRoot).use { stream ->
                stream.filter { path ->
                    Files.isDirectory(path) && path.fileName?.toString()?.startsWith(LEGACY_TMP_PREFIX) == true
                }.forEach { staleDir ->
                    deleteDirectoryRecursivelyQuietly(staleDir)
                }
            }
        }
    }

    private fun deleteDirectoryRecursivelyQuietly(dir: Path) {
        runCatching {
            Files.walk(dir).use { walk ->
                walk.sorted(Comparator.reverseOrder()).forEach { p ->
                    runCatching { Files.deleteIfExists(p) }
                }
            }
        }
    }

    private fun downloadFileWithProgress(
        uri: URI,
        targetFile: Path,
        expectedSize: Long,
        indicator: ProgressIndicator
    ) {
        val request = HttpRequest.newBuilder()
            .uri(uri)
            .timeout(Duration.ofSeconds(30))
            .header("User-Agent", USER_AGENT)
            .GET()
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream())
        if (response.statusCode() != 200) {
            runCatching { response.body().close() }
            throw IOException(I18n.t("update.error.httpStatus", response.statusCode()))
        }

        // Verify final redirected URI also remains on HTTPS + trusted GitHub domain
        validateTrustedHttpsUri(response.uri().toString(), allowApiHost = false)

        val contentLength = response.headers().firstValueAsLong("Content-Length").orElse(expectedSize)
        var downloaded = 0L
        val buffer = ByteArray(16 * 1024)

        response.body().use { input ->
            Files.newOutputStream(
                targetFile,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING
            ).use { output ->
                while (true) {
                    indicator.checkCanceled()
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    downloaded += read
                    if (contentLength > 0) {
                        val pct = (downloaded.toDouble() / contentLength.toDouble()).coerceIn(0.0, 1.0)
                        indicator.fraction = 0.05 + pct * 0.80
                    }
                }
                output.flush()
            }
        }
    }

    private fun verifyPluginZipIntegrity(zipPath: Path, expectedSize: Long) {
        // 1. Existence & minimum file size check (> 10 KB)
        if (!Files.isRegularFile(zipPath)) {
            throw IOException(I18n.t("update.error.zipIntegrity", "File does not exist"))
        }
        val actualSize = Files.size(zipPath)
        if (actualSize <= MIN_PLUGIN_ZIP_BYTES) {
            runCatching { Files.deleteIfExists(zipPath) }
            throw IOException(I18n.t("update.error.zipIntegrity", "File too small ($actualSize bytes)"))
        }
        if (expectedSize > 0L && actualSize != expectedSize) {
            runCatching { Files.deleteIfExists(zipPath) }
            throw IOException(
                I18n.t("update.error.zipIntegrity", "Size mismatch (expected $expectedSize, got $actualSize)")
            )
        }

        // 2. ZIP magic header bytes check (0x50, 0x4B, 0x03, 0x04)
        val header = ByteArray(4)
        val bytesRead = Files.newInputStream(zipPath).use { it.read(header) }
        if (bytesRead < 4 || !header.contentEquals(ZIP_MAGIC_BYTES)) {
            runCatching { Files.deleteIfExists(zipPath) }
            throw IOException(I18n.t("update.error.zipIntegrity", "Invalid ZIP magic header"))
        }

        // 3. ZipFile structure check: must contain maekom-mongo-lens/lib/*.jar and zero ZipSlip paths
        var hasExpectedPluginJar = false
        try {
            ZipFile(zipPath.toFile()).use { zf ->
                val entries = zf.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    val entryName = entry.name.replace('\\', '/')
                    if (entryName.contains("..")) {
                        throw IOException(I18n.t("update.error.zipIntegrity", "Unsafe ZIP entry: $entryName"))
                    }
                    if (!entry.isDirectory &&
                        entryName.startsWith("maekom-mongo-lens/lib/") &&
                        entryName.endsWith(".jar", ignoreCase = true)
                    ) {
                        hasExpectedPluginJar = true
                    }
                }
            }
        } catch (t: IOException) {
            runCatching { Files.deleteIfExists(zipPath) }
            throw t
        } catch (t: Throwable) {
            runCatching { Files.deleteIfExists(zipPath) }
            throw IOException(I18n.t("update.error.zipIntegrity", t.message ?: t.javaClass.simpleName), t)
        }

        if (!hasExpectedPluginJar) {
            runCatching { Files.deleteIfExists(zipPath) }
            throw IOException(
                I18n.t("update.error.zipIntegrity", "Missing maekom-mongo-lens/lib/*.jar entry")
            )
        }
    }

    /**
     * Schedules the verified plugin ZIP for atomic installation on IDE restart using IntelliJ's
     * official `PluginInstaller.installAfterRestart` (identical to "Install Plugin from Disk...").
     */
    private fun scheduleVerifiedPluginInstall(targetZip: Path): Boolean {
        return try {
            val currentDescriptor = PluginManagerCore.getPlugin(PluginId.getId(PLUGIN_ID))
            if (currentDescriptor != null) {
                PluginInstaller.installAfterRestart(
                    currentDescriptor,
                    targetZip,
                    currentDescriptor.pluginPath,
                    true
                )
            } else {
                val pluginsDir = Paths.get(PathManager.getPluginsPath())
                val defaultPluginDir = pluginsDir.resolve("maekom-mongo-lens")
                val commands = mutableListOf<StartupActionScriptManager.ActionCommand>()
                if (Files.exists(defaultPluginDir) && defaultPluginDir.normalize().startsWith(pluginsDir.normalize())) {
                    commands.add(StartupActionScriptManager.DeleteCommand(defaultPluginDir))
                }
                commands.add(StartupActionScriptManager.UnzipCommand(targetZip, pluginsDir))
                commands.add(StartupActionScriptManager.DeleteCommand(targetZip))
                StartupActionScriptManager.addActionCommands(commands)
            }
            true
        } catch (t: Throwable) {
            LOG.warn("Failed to schedule plugin install for $targetZip", t)
            false
        }
    }

    private fun summarizeReleaseNotes(rawNotes: String): String {
        val cleaned = rawNotes.trim()
        if (cleaned.isEmpty()) return ""
        val lines = cleaned.lines().map { it.trimEnd() }.filter { it.isNotBlank() }
        val joined = lines.take(10).joinToString("\n")
        return if (joined.length > 500 || lines.size > 10) {
            joined.take(500).trimEnd() + "\n..."
        } else {
            joined
        }
    }
}

typealias GitHubReleaseUpdater = PluginUpdateChecker
