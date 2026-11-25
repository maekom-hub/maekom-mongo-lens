package com.maekom.mongolens.config

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileCopyEvent
import com.intellij.openapi.vfs.VirtualFileEvent
import com.intellij.openapi.vfs.VirtualFileListener
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.VirtualFileMoveEvent
import com.intellij.openapi.vfs.VirtualFilePropertyEvent
import com.intellij.util.Alarm

class YamlConfigWatcher(
    private val project: Project,
    parentDisposable: Disposable,
    private val onConfigChanged: () -> Unit
) {
    private val alarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, parentDisposable)

    init {
        val listener = object : VirtualFileListener {
            override fun contentsChanged(event: VirtualFileEvent) {
                if (isRelevant(event.file)) scheduleRefresh()
            }

            override fun fileCreated(event: VirtualFileEvent) {
                if (isRelevant(event.file)) scheduleRefresh()
            }

            override fun fileDeleted(event: VirtualFileEvent) {
                if (isRelevant(event.file)) scheduleRefresh()
            }

            override fun fileMoved(event: VirtualFileMoveEvent) {
                if (isRelevant(event.file)) scheduleRefresh()
            }

            override fun fileCopied(event: VirtualFileCopyEvent) {
                if (isRelevant(event.file)) scheduleRefresh()
            }

            override fun propertyChanged(event: VirtualFilePropertyEvent) {
                if (event.propertyName == VirtualFile.PROP_NAME && isRelevant(event.file)) {
                    scheduleRefresh()
                }
            }
        }
        VirtualFileManager.getInstance().addVirtualFileListener(listener, parentDisposable)
    }

    private fun isRelevant(file: VirtualFile?): Boolean {
        if (file == null) return false
        val basePath = project.basePath?.replace('\\', '/') ?: return false
        val path = file.path.replace('\\', '/')
        if (!path.startsWith(basePath)) return false

        val name = file.name.lowercase()
        if (name == ".env") return true
        if (!path.contains("/src/main/resources/")) return false
        return name == "application.yml" ||
            name == "application.yaml" ||
            (name.startsWith("application-") && (name.endsWith(".yml") || name.endsWith(".yaml")))
    }

    private fun scheduleRefresh() {
        alarm.cancelAllRequests()
        alarm.addRequest({
            if (!project.isDisposed) {
                onConfigChanged()
            }
        }, 400)
    }
}
