package dev.sadonsergey.dumpie

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.editor.impl.DocumentMarkupModel
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.util.text.DateFormatUtil
import dev.sadonsergey.dumpie.settings.DumpieSettings
import dev.sadonsergey.dumpie.ui.DumpiePanel
import javax.swing.Icon

/**
 * Gutter icons on the lines that produced output: hovering shows what they sent, a click reveals the entry in the
 * tool window. Uses the document markup instead of a line marker provider, so it works in every IDE and language.
 */
@Service(Service.Level.PROJECT)
class OutputMarkers(private val project: Project) : Disposable {
    private class Hit(val uuid: String, val summary: String, val time: Long)

    private class LineOutput {
        val hits = ArrayDeque<Hit>()
        var highlighter: RangeHighlighter? = null
    }

    // Local file path -> 0-based line -> what it sent; touched on the EDT only
    private val files = mutableMapOf<String, MutableMap<Int, LineOutput>>()

    init {
        // Output for a file that is not open yet gets its icons when an editor opens it
        EditorFactory.getInstance().addEditorFactoryListener(object : EditorFactoryListener {
            override fun editorCreated(event: EditorFactoryEvent) {
                val file = FileDocumentManager.getInstance().getFile(event.editor.document) ?: return
                files[file.path]?.forEach { (line, output) -> show(event.editor.document, line, output) }
            }
        }, this)
    }

    /** Called for every request a session receives (any thread). */
    fun record(request: JsonObject) {
        if (!DumpieSettings.get().showGutterIcons) return
        val time = (request.get("meta") as? JsonObject)?.get(DumpieProjectService.RECEIVED_AT)?.asLong ?: System.currentTimeMillis()
        val uuid = request.str("uuid") ?: return
        val hits = (request.get("payloads") as? JsonArray ?: JsonArray()).mapNotNull { it as? JsonObject }.mapNotNull { payload ->
            val type = payload.str("type") ?: return@mapNotNull null
            val origin = payload.get("origin") as? JsonObject ?: return@mapNotNull null
            val file = origin.str("file")?.takeIf { !it.endsWith(".log") } ?: return@mapNotNull null
            val line = origin.get("line_number")?.takeIf { it.isJsonPrimitive }?.asInt ?: return@mapNotNull null
            val summary = summary(type, payload.get("content") as? JsonObject ?: JsonObject()) ?: return@mapNotNull null
            Triple(file, line, summary)
        }
        if (hits.isEmpty()) return

        ApplicationManager.getApplication().invokeLater({
            hits.forEach { (path, line, summary) ->
                val file = FileResolver.resolve(project, path) ?: return@forEach
                val output = files.getOrPut(file.path) { mutableMapOf() }.getOrPut(maxOf(line - 1, 0)) { LineOutput() }
                output.hits.addLast(Hit(uuid, summary, time))
                if (output.hits.size > MAX_HITS) output.hits.removeFirst()
                FileDocumentManager.getInstance().getCachedDocument(file)?.let { show(it, maxOf(line - 1, 0), output) }
            }
        }, project.disposed)
    }

    /** Session cleared: no output, no icons. */
    fun clear() {
        ApplicationManager.getApplication().invokeLater({
            files.values.flatMap { it.values }.forEach { it.highlighter?.dispose() }
            files.clear()
        }, project.disposed)
    }

    override fun dispose() {
        files.values.flatMap { it.values }.forEach { it.highlighter?.dispose() }
        files.clear()
    }

    private fun show(document: Document, line: Int, output: LineOutput) {
        if (line >= document.lineCount) return
        val highlighter = output.highlighter?.takeIf { it.isValid }
            ?: DocumentMarkupModel.forDocument(document, project, true)
                .addLineHighlighter(line, HighlighterLayer.ADDITIONAL_SYNTAX, null)
                .also { output.highlighter = it }
        highlighter.gutterIconRenderer = Renderer(output, output.hits.size)
    }

    private inner class Renderer(private val output: LineOutput, private val count: Int) : GutterIconRenderer() {
        override fun getIcon(): Icon = ICON

        override fun getTooltipText(): String {
            val recent = output.hits.takeLast(TOOLTIP_HITS).reversed().joinToString("<br>") {
                DateFormatUtil.formatTimeWithSeconds(it.time) + "&nbsp;&nbsp;" + StringUtil.escapeXmlEntities(it.summary)
            }
            val total = if (count == 1) "1 output" else "$count outputs"
            return "<b>Dumpie</b> · $total<br>$recent<br><small>Click to show in Dumpie</small>"
        }

        override fun isNavigateAction() = true

        override fun getClickAction() = object : AnAction() {
            override fun actionPerformed(e: AnActionEvent) {
                val uuid = output.hits.lastOrNull()?.uuid ?: return
                val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(DumpieProjectService.TOOL_WINDOW_ID) ?: return
                toolWindow.activate({ DumpiePanel.reveal(project, uuid) }, true)
            }
        }

        override fun equals(other: Any?) = other is Renderer && other.output === output && other.count == count

        override fun hashCode() = System.identityHashCode(output) * 31 + count
    }

    companion object {
        private val ICON = IconLoader.getIcon("/icons/dumpieToolWindow.svg", OutputMarkers::class.java)
        private const val MAX_HITS = 20
        private const val TOOLTIP_HITS = 3
        private const val SUMMARY_LENGTH = 100

        /** One line about a payload for the tooltip; null for payloads that only modify an entry. */
        internal fun summary(type: String, content: JsonObject): String? {
            val text = when (type) {
                // Dumps carry their plain text for copying; scalars come as they are
                "log" -> (content.get("meta") as? JsonArray)?.firstOrNull()?.let { (it as? JsonObject)?.str("clipboard_data") }
                    ?: (content.get("values") as? JsonArray)?.firstOrNull()?.takeIf { it.isJsonPrimitive }?.asString
                    ?: "log"
                "executed_query" -> "Query: " + content.str("sql").orEmpty()
                "exception" -> content.str("class").orEmpty().substringAfterLast('\\') + ": " + content.str("message").orEmpty()
                "application_log" -> content.str("value").orEmpty()
                "table" -> "Table " + content.str("label").orEmpty()
                "json_string" -> "JSON " + content.str("value").orEmpty()
                "custom" -> content.str("label")?.takeIf { it.isNotEmpty() } ?: "custom"
                "color", "label", "size", "hide", "remove", "expand", "notify", "show_app", "hide_app",
                "new_screen", "clear_all", "screen_color", "separator", "confetti", "create_lock" -> return null
                else -> type
            }
            val oneLine = text.replace(Regex("<[^>]+>"), "").replace(Regex("\\s+"), " ").trim()
            return if (oneLine.length > SUMMARY_LENGTH) oneLine.take(SUMMARY_LENGTH - 1) + "…" else oneLine
        }
    }
}
