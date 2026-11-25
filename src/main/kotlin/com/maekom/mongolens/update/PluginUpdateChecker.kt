package com.maekom.mongolens.update

import com.google.gson.JsonParser
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.ide.startup.StartupActionScriptManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.application.ex.ApplicationManagerEx
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.maekom.mongolens.i18n.I18n
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Duration
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
    const val PLUGIN_ID = "com.maekom.mongolens"
    const val RELEASES_API_URL = "https://api.github.com/repos/maekom-hub/maekom-mongo-lens/releases/latest"
    private const val FALLBACK_VERSION = "0.1.1"

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
            val request = HttpRequest.newBuilder()
                .uri(URI.create(RELEASES_API_URL))
                .timeout(Duration.ofSeconds(12))
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "maekom-mongo-lens/${currentVersionString()}")
                .GET()
                .build()

            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() == 404) {
                // Repository exists but no release published yet
                return Result.success(null)
            }
            if (response.statusCode() !in 200..299) {
                return Result.failure(IOException("GitHub API HTTP ${response.statusCode()}"))
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
                    val name = obj.get("name")?.asString ?: continue
                    if (name.endsWith(".zip", ignoreCase = true)) {
                        assetName = name
                        assetUrl = obj.get("browser_download_url")?.asString
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
            Result.failure(t)
        }
    }

    fun promptAndInstallUpdate(project: Project, release: ReleaseInfo) {
        val current = currentVersionString()
        val confirm = Messages.showYesNoDialog(
            project,
            I18n.t("update.dialog.confirmDownload", release.tagName, current),
            I18n.t("update.dialog.title"),
            Messages.getQuestionIcon()
        )
        if (confirm != Messages.YES) return

        val downloadUrl = release.assetDownloadUrl
        if (downloadUrl.isNullOrBlank()) {
            Messages.showErrorDialog(
                project,
                I18n.t("update.dialog.error", "Release ${release.tagName} does not contain a .zip plugin asset."),
                I18n.t("update.dialog.title")
            )
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

                    val stagingDir = Paths.get(PathManager.getTempPath(), "maekom-mongo-lens-updates")
                    Files.createDirectories(stagingDir)
                    val zipFileName = release.assetName ?: "maekom-mongo-lens-${release.version}.zip"
                    val targetZip = stagingDir.resolve(zipFileName)

                    downloadFileWithProgress(downloadUrl, targetZip, release.assetSize, indicator)

                    indicator.text = I18n.t("update.dialog.verifying")
                    indicator.fraction = 0.85
                    verifyPluginZipIntegrity(targetZip, release.assetSize)

                    indicator.text = I18n.t("update.dialog.scheduling")
                    indicator.fraction = 0.95
                    schedulePluginInstall(targetZip)

                    indicator.fraction = 1.0
                    ApplicationManager.getApplication().invokeLater {
                        if (project.isDisposed) return@invokeLater
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
                    }
                } catch (t: Throwable) {
                    ApplicationManager.getApplication().invokeLater {
                        if (!project.isDisposed) {
                            Messages.showErrorDialog(
                                project,
                                I18n.t("update.dialog.error", t.message ?: t.javaClass.simpleName),
                                I18n.t("update.dialog.title")
                            )
                        }
                    }
                }
            }
        }.queue()
    }

    private fun downloadFileWithProgress(
        url: String,
        targetFile: Path,
        expectedSize: Long,
        indicator: ProgressIndicator
    ) {
        val request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofSeconds(60))
            .header("User-Agent", "maekom-mongo-lens/${currentVersionString()}")
            .GET()
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream())
        if (response.statusCode() !in 200..299) {
            throw IOException("Download failed with HTTP ${response.statusCode()}")
        }

        val contentLength = response.headers().firstValueAsLong("Content-Length").orElse(expectedSize)
        var downloaded = 0L
        val buffer = ByteArray(16 * 1024)

        response.body().use { input ->
            Files.newOutputStream(targetFile).use { output ->
                while (true) {
                    indicator.checkCanceled()
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    downloaded += read
                    if (contentLength > 0) {
                        val pct = (downloaded.toDouble() / contentLength.toDouble()).coerceIn(0.0, 1.0)
                        indicator.fraction = 0.05 + pct * 0.78
                    }
                }
            }
        }
    }

    private fun verifyPluginZipIntegrity(zipPath: Path, expectedSize: Long) {
        val actualSize = Files.size(zipPath)
        if (actualSize <= 0L) {
            Files.deleteIfExists(zipPath)
            throw IOException("Downloaded ZIP archive is empty.")
        }
        if (expectedSize > 0L && actualSize != expectedSize) {
            Files.deleteIfExists(zipPath)
            throw IOException("ZIP size mismatch: expected $expectedSize bytes, got $actualSize bytes.")
        }

        var hasJarEntry = false
        ZipFile(zipPath.toFile()).use { zf ->
            val entries = zf.entries()
            var entryCount = 0
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                entryCount++
                if (entry.name.contains("..")) {
                    throw IOException("Unsafe ZIP entry detected: ${entry.name}")
                }
                if (!entry.isDirectory && entry.name.endsWith(".jar", ignoreCase = true)) {
                    hasJarEntry = true
                }
            }
            if (entryCount == 0 || !hasJarEntry) {
                throw IOException("Invalid IntelliJ plugin archive: no plugin JAR found inside ZIP.")
            }
        }
    }

    private fun schedulePluginInstall(stagedZip: Path) {
        val pluginsDir = Paths.get(PathManager.getPluginsPath())
        val commands = mutableListOf<StartupActionScriptManager.ActionCommand>()

        val installedPath = PluginManagerCore.getPlugin(PluginId.getId(PLUGIN_ID))?.pluginPath
        val defaultPluginDir = pluginsDir.resolve("maekom-mongo-lens")
        val targetDeleteDir = installedPath ?: defaultPluginDir

        if (Files.exists(targetDeleteDir) && targetDeleteDir.normalize().startsWith(pluginsDir.normalize())) {
            commands.add(StartupActionScriptManager.DeleteCommand(targetDeleteDir))
        }
        commands.add(StartupActionScriptManager.UnzipCommand(stagedZip, pluginsDir))
        commands.add(StartupActionScriptManager.DeleteCommand(stagedZip))

        StartupActionScriptManager.addActionCommands(commands)
    }
}

typealias GitHubReleaseUpdater = PluginUpdateChecker
