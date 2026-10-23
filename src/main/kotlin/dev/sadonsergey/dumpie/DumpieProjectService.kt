package dev.sadonsergey.dumpie

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindowManager
import dev.sadonsergey.dumpie.settings.DumpieSettings
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Debug session of one project: keeps received requests and forwards them to every panel viewing it
 * (its own tool window and tool windows of other projects switched to this session).
 */
@Service(Service.Level.PROJECT)
class DumpieProjectService(private val project: Project) {
    private val history = ArrayDeque<Entry>()
    private val listeners = mutableListOf<(String) -> Unit>()

    fun receive(request: JsonObject) {
        val payloads = (request.get("payloads") as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
        val types = payloads.mapNotNull { it.str("type") }.toSet()
        // Entry times in the page must not change when the history is replayed (session switch, reload);
        // a request forwarded by another IDE keeps the time it was received there
        val meta = request.get("meta") as? JsonObject ?: JsonObject().also { request.add("meta", it) }
        if (!meta.has(RECEIVED_AT)) meta.addProperty(RECEIVED_AT, System.currentTimeMillis())
        val json = request.toString()

        synchronized(LOCK) {
            if ("clear_all" in types) history.clear()
            history.addLast(Entry(sequence++, json))
            if (history.size > MAX_HISTORY) history.removeFirst()
            listeners.forEach { it(json) }
        }

        // Unread counters of the session bars: only output that adds an entry counts
        when {
            "clear_all" in types -> activityListeners.forEach { it(project, true) }
            types.any { it !in SILENT_TYPES } -> activityListeners.forEach { it(project, false) }
        }

        ApplicationManager.getApplication().invokeLater({ applySideEffects(types, payloads) }, project.disposed)
    }

    /** Replays the history to [listener] and subscribes it to new requests. */
    fun attach(listener: (String) -> Unit) = attachAll(mapOf(this to listener))

    fun detach(listener: (String) -> Unit) = synchronized(LOCK) { listeners -= listener }

    fun clear() {
        synchronized(LOCK) { history.clear() }
        activityListeners.forEach { it(project, true) }
    }

    private fun applySideEffects(types: Set<String>, payloads: List<JsonObject>) {
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID) ?: return
        when {
            "hide_app" in types -> toolWindow.hide()
            "show_app" in types -> toolWindow.activate(null)
            !toolWindow.isVisible && DumpieSettings.get().showToolWindowOnMessage -> toolWindow.show()
        }

        payloads.filter { it.str("type") == "notify" }.forEach {
            val text = (it.get("content") as? JsonObject)?.str("value") ?: return@forEach
            NotificationGroupManager.getInstance().getNotificationGroup(DumpieRouter.NOTIFICATION_GROUP)
                .createNotification(text, NotificationType.INFORMATION)
                .notify(project)
        }
    }

    private class Entry(val seq: Long, val json: String)

    companion object {
        const val TOOL_WINDOW_ID = "Dumpie"
        private const val MAX_HISTORY = 1000
        private const val RECEIVED_AT = "dumpie_received_at"

        // Payload types that only modify or control an entry, so they do not count as new output
        private val SILENT_TYPES = setOf(
            "color", "label", "size", "hide", "remove", "expand", "show_app", "hide_app", "notify", "screen_color", "new_screen",
        )

        /** Called with (project, cleared) whenever a session gets new output or is cleared. */
        private val activityListeners = CopyOnWriteArrayList<(Project, Boolean) -> Unit>()

        fun addActivityListener(parent: Disposable, listener: (Project, Boolean) -> Unit) {
            activityListeners += listener
            Disposer.register(parent) { activityListeners -= listener }
        }

        // One lock for all sessions: replaying several of them in arrival order must not race with new requests
        private val LOCK = Any()
        private var sequence = 0L

        /** Replays the histories of several sessions merged in arrival order, then subscribes each listener. */
        fun attachAll(targets: Map<DumpieProjectService, (String) -> Unit>) = synchronized(LOCK) {
            // A page reload attaches the same listener again
            targets.forEach { (session, listener) -> session.listeners -= listener }
            targets.flatMap { (session, listener) -> session.history.map { it to listener } }
                .sortedBy { (entry, _) -> entry.seq }
                .forEach { (entry, listener) -> listener(entry.json) }
            targets.forEach { (session, listener) -> session.listeners += listener }
        }
    }
}
