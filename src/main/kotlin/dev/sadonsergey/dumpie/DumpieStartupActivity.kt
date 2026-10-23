package dev.sadonsergey.dumpie

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import dev.sadonsergey.dumpie.dump.DumpServer
import dev.sadonsergey.dumpie.settings.DumpieProjectSettings
import dev.sadonsergey.dumpie.settings.ProjectDetector
import dev.sadonsergey.dumpie.ui.DumpiePanel

class DumpieStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        // Tests start the receivers themselves, on free ports instead of the real ones
        if (ApplicationManager.getApplication().isUnitTestMode) return

        // The relay goes first: receivers ask it whether a busy port belongs to another IDE's Dumpie
        val relay = service<DumpieRelay>()
        relay.start()
        service<RayServer>().start()
        service<DumpServer>().start()
        relay.publish()

        // The first time a project is opened its sources are filled from what it contains
        val settings = project.service<DumpieProjectSettings>()
        if (!settings.state.detected) settings.applyDetection(ProjectDetector.detect(project))
        project.service<LogWatcher>().restart()

        DumpiePanel.projectsChanged()
    }
}
