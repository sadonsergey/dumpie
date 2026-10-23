package dev.sadonsergey.dumpie.settings

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

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

        /** Set once the project was inspected, so later user changes are not overwritten on startup. */
        var detected by property(false)
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
