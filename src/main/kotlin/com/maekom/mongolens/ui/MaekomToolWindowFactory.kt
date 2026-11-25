package com.maekom.mongolens.ui

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

class MaekomToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val mainPanel = MongoLensMainPanel(project, toolWindow.disposable)
        val content = ContentFactory.getInstance().createContent(mainPanel, "", false)
        content.setDisposer(mainPanel)
        toolWindow.contentManager.addContent(content)
    }
}
