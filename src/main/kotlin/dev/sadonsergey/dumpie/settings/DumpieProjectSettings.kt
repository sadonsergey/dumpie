package dev.sadonsergey.dumpie.settings

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.util.xmlb.annotations.Tag

/** Which sources feed this project's session. Stored in the workspace file, so it stays out of VCS. */
@Service(Service.Level.PROJECT)
@State(name = "DumpieProjectSettings", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class DumpieProjectSettings : SimplePersistentStateComponent<DumpieProjectSettings.State>(State()) {
    class State : BaseState() {
        var rayEnabled by property(true)
        var dumpEnabled by property(true)
        var logsEnabled by property(true)

        /** Glob patterns relative to the project root, one per line (only the file name part may contain wildcards). */
        var logPaths by string("")

        /** Servers of the toolbar menu whose output can be followed, at most one per SSH configuration. */
        var servers by list<Server>()

        /** Set once the project was inspected, so later user changes are not overwritten on startup. */
        var detected by property(false)
    }

    @Tag("server")
    class Server : BaseState() {
        /** Id of the IDE SSH configuration. */
        var sshConfig by string("")

        /** Log files on the server, absolute paths, one per line. */
        var logPaths by string("")

        /** Lowest Monolog level shown; empty shows every line, also lines that are not log records. */
        var logLevel by string("")

        /** Forward the server's ray() and dump() ports to this IDE while connected. */
        var forwardPorts by property(true)

        fun copy() = Server().also { it.copyFrom(this) }
    }

    /** Fills the settings from what the project contains. */
    fun applyDetection(detection: ProjectDetector.Detection) {
        state.dumpEnabled = detection.varDumper != null
        state.logsEnabled = detection.logPatterns.isNotEmpty()
        state.logPaths = detection.logPatterns.joinToString("\n")
        state.detected = true
    }

    companion object {
        fun get(project: Project): State = project.service<DumpieProjectSettings>().state
    }
}
