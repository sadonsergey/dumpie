package dev.sadonsergey.dumpie.ui

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.jcef.JBCefApp
import javax.swing.SwingConstants

class DumpieToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val content = if (JBCefApp.isSupported()) {
            val panel = DumpiePanel(project, toolWindow.disposable)
            ContentFactory.getInstance().createContent(panel.component, null, false)
        } else {
            val label = JBLabel("JCEF is not available in this IDE runtime", SwingConstants.CENTER)
            ContentFactory.getInstance().createContent(label, null, false)
        }
        toolWindow.contentManager.addContent(content)
    }
}
