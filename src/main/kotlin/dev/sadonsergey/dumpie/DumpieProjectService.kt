package dev.sadonsergey.dumpie

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
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

    // What clears removed, newest first, so it can be restored
    private val archive = ArrayDeque<Archived>()
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
            if ("clear_all" in types) {
                archive("Cleared from PHP", history.toList())
                history.clear()
            }
            history.addLast(Entry(sequence++, json, request.str("uuid"), types))
            if (history.size > MAX_HISTORY) history.removeFirst()
            listeners.forEach { it(json) }
        }

        val markers = project.service<OutputMarkers>()
        if ("clear_all" in types) markers.clear()
        markers.record(request)

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

    /** Drops the requests of a closed or cleared screen (follow-up requests included); they go to the archive. */
    fun removeRequests(uuids: Set<String>, title: String) = synchronized(LOCK) {
        archive(title, history.filter { it.uuid in uuids })
        history.removeAll { it.uuid in uuids }
    }

    fun clear() {
        synchronized(LOCK) {
            archive("Clear all", history.toList())
            history.clear()
        }
        project.service<OutputMarkers>().clear()
        activityListeners.forEach { it(project, true) }
    }

    fun archived(): List<Archived> = synchronized(LOCK) { archive.toList() }

    /** Puts archived output back where it was; an earlier clear_all would hide it again, so those go. */
    fun restore(id: Long): Boolean = synchronized(LOCK) {
        val item = archive.firstOrNull { it.id == id } ?: return false
        archive.remove(item)
        val merged = (history.filter { "clear_all" !in it.types } + item.entries).sortedBy { it.seq }.takeLast(MAX_HISTORY)
        history.clear()
        history.addAll(merged)
        true
    }

    // Under LOCK
    private fun archive(title: String, removed: List<Entry>) {
        val item = Archived(sequence, System.currentTimeMillis(), title, removed)
        if (item.count == 0) return
        archive.addFirst(item)
        if (archive.size > MAX_ARCHIVE) archive.removeLast()
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

    class Entry internal constructor(internal val seq: Long, internal val json: String, internal val uuid: String?, internal val types: Set<String>)

    /** One clear: its title for the archive list and the requests it removed. */
    class Archived internal constructor(val id: Long, val time: Long, val title: String, internal val entries: List<Entry>) {
        /** Entries the page showed (follow-up requests and screen markers do not count). */
        val count: Int = entries.filter { e -> e.types.any { it !in SILENT_TYPES && it != "clear_all" } }.mapNotNull { it.uuid }.toSet().size
    }

    companion object {
        const val TOOL_WINDOW_ID = "Dumpie"
        private const val MAX_HISTORY = 1000
        private const val MAX_ARCHIVE = 10
        internal const val RECEIVED_AT = "dumpie_received_at"

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
