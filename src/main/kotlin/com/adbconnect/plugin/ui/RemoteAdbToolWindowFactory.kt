package com.adbconnect.plugin.ui

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

/**
 * Factory for creating the Remote ADB Connector tool window.
 *
 * Registered in `plugin.xml` — the IDE calls [createToolWindowContent]
 * when the user opens the tool window for the first time.
 *
 * Implements [DumbAware] to allow the tool window to be available
 * during indexing.
 */
class RemoteAdbToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = RemoteAdbToolWindowPanel(project)

        val content = ContentFactory.getInstance().createContent(
            panel.getContent(),
            "",
            false
        )

        toolWindow.contentManager.addContent(content)
    }

    override fun shouldBeAvailable(project: Project): Boolean = true
}
