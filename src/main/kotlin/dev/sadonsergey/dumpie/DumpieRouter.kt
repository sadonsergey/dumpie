package dev.sadonsergey.dumpie

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.wm.IdeFocusManager
import dev.sadonsergey.dumpie.settings.DumpieProjectSettings
import java.io.File

/**
 * Delivers a request received by one of the receivers to the session of the project it belongs to,
 * in this IDE or, through [DumpieRelay], in another IDE running Dumpie.
 */
object DumpieRouter {
    const val NOTIFICATION_GROUP = "Dumpie"
    const val SOURCE_RAY = "ray"
    const val SOURCE_DUMP = "dump"

    /** A project that may receive the request: open here ([local]) or in another IDE ([instance]). */
    internal class Target(val name: String, val path: String, val local: Project? = null, val instance: DumpieRelay.Instance? = null)

    fun dispatch(request: JsonObject, source: String) {
        val relay = service<DumpieRelay>()
        val remote = relay.others().flatMap { instance ->
            instance.projects.map { Target(it.name, it.path, instance = instance) }
        }
        val target = pick(request, localTargets() + remote)
        val instance = target?.instance
        if (instance != null && relay.forward(instance, request, source)) return
        deliver(target?.local ?: fallbackProject() ?: return, request, source)
    }

    /** A request another IDE forwarded here: it belongs to this IDE, only the project has to be chosen. */
    fun deliverForwarded(request: JsonObject, source: String) {
        deliver(pick(request, localTargets())?.local ?: fallbackProject() ?: return, request, source)
    }

    fun warnPortBusy(port: Int, what: String) {
        NotificationGroupManager.getInstance().getNotificationGroup(NOTIFICATION_GROUP)
            .createNotification(
                "Port $port is used by another application, $what is not received. Change the port in Settings | Tools | Dumpie.",
                NotificationType.WARNING,
            )
            .notify(null)
    }

    private fun deliver(project: Project, request: JsonObject, source: String) {
        val settings = DumpieProjectSettings.get(project)
        val accepted = when (source) {
            SOURCE_RAY -> settings.rayEnabled
            SOURCE_DUMP -> settings.dumpEnabled
            else -> true
        }
        if (accepted) project.service<DumpieProjectService>().receive(request)
    }

    private fun localTargets() = ProjectManager.getInstance().openProjects
        .filter { !it.isDisposed && it.basePath != null }
        .map { Target(it.name, it.basePath!!, local = it) }

    internal fun pick(request: JsonObject, targets: List<Target>): Target? {
        val payloads = request.get("payloads") as? JsonArray ?: return null
        val files = payloads.mapNotNull { ((it as? JsonObject)?.get("origin") as? JsonObject)?.str("file") }
        val projectName = (request.get("meta") as? JsonObject)?.str("project_name").orEmpty()

        // 1. The project that contains the file, whichever IDE has it open
        targets.firstOrNull { t -> files.any { it.startsWith(t.path.trimEnd('/') + "/") } }?.let { return it }
        // 2. ray()->project('name')
        if (projectName.isNotEmpty()) targets.firstOrNull { it.name.equals(projectName, ignoreCase = true) }?.let { return it }
        // 3. Paths from a container or VM: the longest suffix that exists in a project. Files that exist on this
        //    machine belong to a project that is not open anywhere, so they never match by suffix
        //    (every Laravel project has app/Http/Kernel.php).
        val foreign = files.filter { !File(it).exists() }
        return targets
            .map { t -> t to foreign.maxOfOrNull { FileResolver.suffixMatch(t.path, it) }.let { it ?: 0 } }
            .filter { (_, segments) -> segments > 0 }
            .maxByOrNull { (_, segments) -> segments }
            ?.first
    }

    private fun fallbackProject(): Project? {
        val projects = ProjectManager.getInstance().openProjects.filter { !it.isDisposed }
        return IdeFocusManager.getGlobalInstance().lastFocusedFrame?.project?.takeIf { it in projects } ?: projects.firstOrNull()
    }
}
