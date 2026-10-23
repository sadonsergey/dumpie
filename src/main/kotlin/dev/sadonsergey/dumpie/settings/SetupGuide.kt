package dev.sadonsergey.dumpie.settings

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import dev.sadonsergey.dumpie.DumpieRouter

/** What a project needs to send output to Dumpie; shown in the empty feed and used by the settings page. */
object SetupGuide {
    private const val READY = "ready"
    private const val SETUP = "setup"
    private const val UNAVAILABLE = "unavailable"
    private const val OFF = "off"

    /** Per-source state for the page: ready, setup (one step missing), unavailable or off. */
    fun status(project: Project): JsonObject {
        val detection = ProjectDetector.detect(project)
        val app = DumpieSettings.get()
        val local = DumpieProjectSettings.get(project)

        val dump = JsonObject().apply {
            addProperty("state", when {
                detection.varDumper == null -> UNAVAILABLE
                !app.dumpEnabled || !local.dumpEnabled -> OFF
                detection.dumpServerConfigured -> READY
                else -> SETUP
            })
            addProperty("canAddEnv", detection.envFile != null)
            add("envLines", JsonArray().apply { ProjectDetector.envLines(detection, app.dumpPort).forEach { add(it) } })
        }
        val rayPackage = detection.laravelRay?.let { "spatie/laravel-ray $it" } ?: detection.ray?.let { "spatie/ray $it" }
        val ray = JsonObject().apply {
            addProperty("state", when {
                !app.rayEnabled || !local.rayEnabled -> OFF
                rayPackage != null -> READY
                else -> SETUP
            })
            addProperty("package", rayPackage)
            addProperty("install", if (detection.laravel != null) "composer require spatie/laravel-ray --dev" else "composer require spatie/ray --dev")
        }
        val patterns = local.logPaths.orEmpty().lines().map { it.trim() }.filter { it.isNotEmpty() }
        val logs = JsonObject().apply {
            addProperty("state", when {
                !local.logsEnabled -> OFF
                patterns.isEmpty() -> UNAVAILABLE
                else -> READY
            })
            addProperty("patterns", patterns.joinToString(", "))
        }

        return JsonObject().apply {
            addProperty("project", project.name)
            add("dump", dump)
            add("ray", ray)
            add("logs", logs)
        }
    }

    /** Sets the VarDumper server lines in the project's .env without duplicating keys (undoable, saved right away). */
    fun addToEnv(project: Project): Boolean {
        val detection = ProjectDetector.detect(project)
        val lines = ProjectDetector.envLines(detection, DumpieSettings.get().dumpPort)
        val file = detection.envFile?.let { LocalFileSystem.getInstance().refreshAndFindFileByIoFile(it) } ?: return false
        val document = FileDocumentManager.getInstance().getDocument(file) ?: return false
        // Pressing the button twice (or with the keys already present) must not duplicate them
        val merged = EnvFile.merge(document.text, lines)
        if (merged != null) {
            WriteCommandAction.runWriteCommandAction(project, "Add Dumpie Settings to .env", null, {
                document.replaceString(0, document.textLength, merged)
                FileDocumentManager.getInstance().saveDocument(document)
            })
        }
        val message = if (merged != null) "Added to .env: ${lines.joinToString(", ")}" else ".env already contains ${lines.joinToString(", ")}"
        NotificationGroupManager.getInstance().getNotificationGroup(DumpieRouter.NOTIFICATION_GROUP)
            .createNotification(message, NotificationType.INFORMATION)
            .notify(project)
        return merged != null
    }
}
