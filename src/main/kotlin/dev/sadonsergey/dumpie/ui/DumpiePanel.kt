package dev.sadonsergey.dumpie.ui

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import com.intellij.ide.BrowserUtil
import com.intellij.ide.ui.LafManagerListener
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.keymap.KeymapUtil
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.colors.EditorColorsListener
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.project.ProjectManagerListener
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.testFramework.LightVirtualFile
import com.intellij.ui.JBColor
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBase
import com.intellij.ui.jcef.JBCefJSQuery
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.sadonsergey.dumpie.DumpieProjectService
import dev.sadonsergey.dumpie.FileResolver
import dev.sadonsergey.dumpie.RayServer
import dev.sadonsergey.dumpie.settings.DumpieConfigurable
import dev.sadonsergey.dumpie.settings.DumpieSettings
import dev.sadonsergey.dumpie.settings.SetupGuide
import dev.sadonsergey.dumpie.str
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLoadHandlerAdapter
import java.awt.Color
import java.awt.datatransfer.StringSelection
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.text.DateFormatUtil
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.JComponent

/**
 * Message feed rendered in JCEF. The page (resources/webview) does all payload rendering;
 * this class pipes requests into it and handles actions coming back from it.
 * The panel shows its own project's session by default and can be switched to another open project's session
 * or to all sessions at once.
 */
class DumpiePanel(private val project: Project, parent: Disposable) {
    private val browser = JBCefBrowser.createBuilder().build()
    private val query = JBCefJSQuery.create(browser as JBCefBrowserBase)

    // Viewed project, or null for all projects
    @Volatile
    private var viewed: Project? = project
    private val attached = mutableMapOf<Project, (String) -> Unit>()

    // Output waiting to be sent to the page: a burst (or a history replay) goes in a few calls instead of thousands
    private val outbox = ConcurrentLinkedQueue<String>()
    private val flushPending = AtomicBoolean(false)

    // Unread entries per session that is not on screen; reset when the session is shown
    private val unread = ConcurrentHashMap<Project, Int>()
    private val sessionsPushPending = AtomicBoolean(false)

    // Entry to scroll to once the page has loaded: a gutter icon clicked before the tool window was opened
    @Volatile
    private var loaded = false
    @Volatile
    private var pendingReveal: String? = null

    val component: JComponent get() = browser.component

    init {
        Disposer.register(parent, browser)
        Disposer.register(parent, query)
        Disposer.register(parent) { detachAll() }
        panels += this
        Disposer.register(parent) { panels -= this }

        query.addHandler { message ->
            onMessage(message)
            null
        }

        DumpieProjectService.addActivityListener(parent) { source, cleared -> onActivity(source, cleared) }

        browser.jbCefClient.addLoadHandler(object : CefLoadHandlerAdapter() {
            override fun onLoadEnd(cefBrowser: CefBrowser, frame: CefFrame, httpStatusCode: Int) {
                if (!frame.isMain) return
                execute("window.dumpiePost = function (msg) { ${query.inject("msg")} };")
                applyTheme()
                applyOptions()
                show(viewed)
                loaded = true
                pendingReveal?.let {
                    pendingReveal = null
                    reveal(it)
                }
            }
        }, browser.cefBrowser)

        ApplicationManager.getApplication().messageBus.connect(parent).apply {
            subscribe(LafManagerListener.TOPIC, LafManagerListener { applyTheme() })
            subscribe(EditorColorsManager.TOPIC, EditorColorsListener { applyTheme() })
            // The setup hints in the empty feed follow edits of .env and composer.lock
            subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
                override fun after(events: List<VFileEvent>) {
                    if (events.any { it.path.substringAfterLast('/') in SETUP_FILES }) pushStatus()
                }
            })
            subscribe(ProjectManager.TOPIC, object : ProjectManagerListener {
                override fun projectClosed(closed: Project) {
                    if (closed == viewed && closed != project) {
                        show(project)
                    } else {
                        synchronized(this@DumpiePanel) { attached.remove(closed) }
                        unread.remove(closed)
                        pushSessions(closed)
                    }
                }
            })
        }

        browser.loadHTML(page())
    }

    /** Pushes the current IDE colors and fonts into the page as CSS variables. */
    private fun applyTheme() {
        val scheme = EditorColorsManager.getInstance().globalScheme
        val uiFont = UIUtil.getLabelFont()
        val vars = JsonObject().apply {
            // Light themes may use the editor background (white) instead of the gray tool window one
            val editorBackground = JBColor.isBright() && DumpieSettings.get().whiteBackgroundInLightThemes
            addProperty("--bg", css(if (editorBackground) scheme.defaultBackground else JBUI.CurrentTheme.ToolWindow.background()))
            addProperty("--fg", css(UIUtil.getLabelForeground()))
            addProperty("--muted", css(UIUtil.getContextHelpForeground()))
            addProperty("--border", css(JBColor.border()))
            addProperty("--accent", css(JBUI.CurrentTheme.Link.Foreground.ENABLED))
            addProperty("--ui-font", "\"${uiFont.family}\", system-ui, sans-serif")
            addProperty("--ui-size", "${uiFont.size}px")
            addProperty("--mono-font", "\"${scheme.editorFontName}\", Menlo, monospace")
            addProperty("--mono-size", "${scheme.editorFontSize - 1}px")
            SYNTAX_KEYS.forEach { (name, key) ->
                scheme.getAttributes(key)?.foregroundColor?.let { addProperty(name, css(it)) }
            }
        }
        execute("window.dumpie && window.dumpie.theme($vars, ${!JBColor.isBright()});")
    }

    private fun applyOptions() {
        // Entry times follow the IDE's time format (system format or Settings | System Settings | Date Formats)
        val onePm = LocalDate.now().atTime(13, 5).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val hour12 = !DateFormatUtil.formatTimeWithSeconds(onePm).contains("13")
        val locale = Locale.getDefault().toLanguageTag()
        val settings = DumpieSettings.get()
        val shortcuts = JsonObject().apply {
            SHORTCUT_ACTIONS.forEach { (name, id) -> addProperty(name, KeymapUtil.getFirstKeyboardShortcutText(id)) }
        }
        execute("window.dumpie && window.dumpie.options({pinBars: ${settings.pinBars}, locale: '$locale', hour12: $hour12, " +
            "queryStats: ${settings.showQueryStats}, repeatedQueries: ${settings.markRepeatedQueries}, " +
            "grayUncolored: ${settings.grayUncolored}, " +
            "slowQueryMs: ${if (settings.markSlowQueries) settings.slowQueryMs else 0}, shortcuts: $shortcuts});")
    }

    private fun css(color: Color) = "rgb(${color.red}, ${color.green}, ${color.blue})"

    private fun openProjects(except: Project? = null) =
        ProjectManager.getInstance().openProjects.filter { !it.isDisposed && it != except }

    private fun findProject(id: String) = openProjects().firstOrNull { it.locationHash == id }

    /** Shows one project's session, or all of them merged when [target] is null. */
    @Synchronized
    private fun show(target: Project?) {
        detachAll()
        viewed = when {
            target == null -> null
            target.isDisposed -> project
            else -> target
        }
        viewed?.let { unread.remove(it) } ?: unread.clear()
        // Output of the previous session must not reach the page after the reset
        outbox.clear()
        execute("window.dumpie.reset();")
        pushSessions()
        pushStatus()

        val sources = viewed?.let { listOf(it) } ?: openProjects()
        sources.forEach { p ->
            val source = JsonObject().apply {
                addProperty("id", p.locationHash)
                addProperty("name", p.name)
            }
            attached[p] = { json -> enqueue("{\"r\":$json,\"s\":$source}") }
        }
        DumpieProjectService.attachAll(attached.mapKeys { (p, _) -> p.service<DumpieProjectService>() })
    }

    /** Scrolls to an entry of this panel's project (gutter icon click), switching back to that session if needed. */
    @Synchronized
    private fun reveal(uuid: String) {
        if (!loaded) {
            pendingReveal = uuid
            return
        }
        if (viewed != null && viewed != project) show(project)
        flush()
        execute("window.dumpie.reveal(${JsonPrimitive(uuid)});")
    }

    private fun enqueue(item: String) {
        outbox.add(item)
        if (flushPending.compareAndSet(false, true)) {
            AppExecutorUtil.getAppScheduledExecutorService().schedule({ flush() }, FLUSH_DELAY_MS, TimeUnit.MILLISECONDS)
        }
    }

    // Runs under the panel lock, so it cannot interleave with show() resetting the page
    @Synchronized
    private fun flush() {
        flushPending.set(false)
        while (true) {
            val batch = generateSequence { outbox.poll() }.take(FLUSH_BATCH).toList()
            if (batch.isEmpty()) return
            execute("window.dumpie.receiveBatch([${batch.joinToString(",")}]);")
        }
    }

    @Synchronized
    private fun detachAll() {
        attached.forEach { (p, listener) -> if (!p.isDisposed) p.service<DumpieProjectService>().detach(listener) }
        attached.clear()
    }

    private fun onActivity(source: Project, cleared: Boolean) {
        when {
            cleared -> if (unread.remove(source) == null) return
            // Everything is on screen in the All projects feed and in the source's own session
            viewed == null || source == viewed -> return
            else -> unread.merge(source, 1, Int::plus)
        }
        // A burst of output (e.g. every SQL query) refreshes the bar at most every 250 ms
        if (sessionsPushPending.compareAndSet(false, true)) {
            AppExecutorUtil.getAppScheduledExecutorService().schedule({
                sessionsPushPending.set(false)
                pushSessions()
            }, 250, TimeUnit.MILLISECONDS)
        }
    }

    /** The same request ray()->newScreen() sends, so a screen opened from the toolbar is kept in the session. */
    private fun newScreenRequest() = JsonObject().apply {
        addProperty("uuid", UUID.randomUUID().toString())
        add("payloads", JsonArray().apply {
            add(JsonObject().apply {
                addProperty("type", "new_screen")
                add("content", JsonObject().apply { addProperty("name", "") })
                add("origin", JsonObject())
            })
        })
        add("meta", JsonObject())
    }

    /** Tells the empty feed what the viewed project still needs to send output here (off the EDT: reads files). */
    private fun pushStatus() {
        val target = viewed ?: project
        ApplicationManager.getApplication().executeOnPooledThread {
            if (!target.isDisposed) execute("window.dumpie && window.dumpie.status(${SetupGuide.status(target)});")
        }
    }

    /** Sends the list of sessions to the switcher in the page: all projects first, then each open project. */
    private fun pushSessions(closing: Project? = null) {
        val sessions = JsonArray()
        sessions.add(JsonObject().apply {
            addProperty("id", ALL)
            addProperty("name", "All")
            addProperty("viewed", viewed == null)
        })
        openProjects(closing).forEach { p ->
            sessions.add(JsonObject().apply {
                addProperty("id", p.locationHash)
                addProperty("name", p.name)
                addProperty("own", p == project)
                addProperty("unread", unread[p] ?: 0)
                addProperty("viewed", p == viewed)
            })
        }
        execute("window.dumpie.sessions($sessions, ${openProjects(closing).size});")
    }

    companion object {
        private val panels = CopyOnWriteArrayList<DumpiePanel>()

        private const val ALL = "all"
        private const val FLUSH_DELAY_MS = 40L
        private const val FLUSH_BATCH = 300

        private val SETUP_FILES = setOf(".env", "composer.lock")
        private val EXTENSION = Regex("\\w{1,10}")
        private val UNSAFE_NAME = Regex("[^\\w.:#\\- ]+")

        /** Called when a project opens: switchers list it, and panels showing all projects start receiving from it. */
        fun projectsChanged() = panels.forEach { if (it.viewed == null) it.show(null) else it.pushSessions() }

        fun reveal(project: Project, uuid: String) = panels.firstOrNull { it.project == project }?.reveal(uuid)

        fun exists(project: Project) = panels.any { it.project == project }

        /** A keyboard action (see DumpieActions): clearAll, clearScreen, newScreen, pause, search, nextScreen, previousScreen. */
        fun command(project: Project, name: String) =
            panels.firstOrNull { it.project == project }?.execute("window.dumpie && window.dumpie.command(${JsonPrimitive(name)});")

        // Toolbar tooltips and the empty screen show the current shortcuts, remapped ones included
        private val SHORTCUT_ACTIONS = mapOf(
            "clearAll" to "Dumpie.ClearAll", "clearScreen" to "Dumpie.ClearScreen", "newScreen" to "Dumpie.NewScreen",
            "pause" to "Dumpie.Pause", "search" to "Dumpie.Search",
            "nextScreen" to "Dumpie.NextScreen", "previousScreen" to "Dumpie.PreviousScreen",
        )

        /** The page with app.css and app.js inlined. */
        internal fun page(): String {
            fun resource(name: String) = DumpiePanel::class.java.getResource("/webview/$name")!!.readText()

            return resource("index.html")
                .replace("/*CSS*/", resource("app.css"))
                .replace("/*JS*/", resource("app.js"))
        }

        /** Called after the settings page is applied. */
        fun settingsChanged() = panels.forEach {
            it.applyTheme()
            it.applyOptions()
            it.pushStatus()
        }

        // Editor highlighting used to color VarDumper output and exception snippets (see --syn-* in app.css)
        private val SYNTAX_KEYS = mapOf(
            "--syn-string" to DefaultLanguageHighlighterColors.STRING,
            "--syn-number" to DefaultLanguageHighlighterColors.NUMBER,
            "--syn-keyword" to DefaultLanguageHighlighterColors.KEYWORD,
            "--syn-field" to DefaultLanguageHighlighterColors.INSTANCE_FIELD,
            "--syn-class" to DefaultLanguageHighlighterColors.CLASS_NAME,
            "--syn-meta" to DefaultLanguageHighlighterColors.METADATA,
            "--syn-function" to DefaultLanguageHighlighterColors.FUNCTION_CALL,
            "--syn-comment" to DefaultLanguageHighlighterColors.LINE_COMMENT,
        )
    }

    private fun execute(js: String) {
        browser.cefBrowser.executeJavaScript(js, browser.cefBrowser.url, 0)
    }

    /** An entry in an editor tab of this window; the extension picks the highlighting. */
    private fun openInEditor(text: String, lang: String, title: String) {
        // Dumps are VarDumper text, not PHP code: as .php they would get no highlighting at all
        val extension = lang.takeIf { it.matches(EXTENSION) && it != "php" } ?: "txt"
        val name = title.replace(UNSAFE_NAME, " ").trim().take(60).ifEmpty { "entry" }
        val file = LightVirtualFile("Dumpie $name.$extension", text)
        ApplicationManager.getApplication().invokeLater({ OpenFileDescriptor(project, file).navigate(true) }, project.disposed)
    }

    private fun onMessage(message: String) {
        val msg = try {
            JsonParser.parseString(message) as? JsonObject
        } catch (e: Exception) {
            null
        } ?: return

        when (msg.str("action")) {
            "open" -> {
                val file = msg.str("file") ?: return
                val line = msg.get("line")?.asInt ?: 1
                // Files of another session open in that project's window
                val target = msg.str("session")?.let(::findProject) ?: viewed ?: project
                ApplicationManager.getApplication().invokeLater({ FileResolver.open(target, file, line) }, target.disposed)
            }
            "lock" -> msg.str("name")?.let { service<RayServer>().releaseLock(it, msg.str("mode") == "stop") }
            "clear" -> (viewed?.let { listOf(it) } ?: openProjects()).forEach { it.service<DumpieProjectService>().clear() }
            "session" -> when (val id = msg.str("id")) {
                ALL -> show(null)
                null -> Unit
                else -> findProject(id)?.let { show(it) }
            }
            "copy" -> msg.str("text")?.let { CopyPasteManager.getInstance().setContents(StringSelection(it)) }
            "openText" -> msg.str("text")?.let { text -> openInEditor(text, msg.str("lang").orEmpty(), msg.str("title").orEmpty()) }
            "addEnv" -> ApplicationManager.getApplication().invokeLater({ SetupGuide.addToEnv(viewed ?: project); pushStatus() }, project.disposed)
            "settings" -> ApplicationManager.getApplication().invokeLater({
                ShowSettingsUtil.getInstance().showSettingsDialog(viewed ?: project, DumpieConfigurable::class.java)
            }, project.disposed)
            "refresh" -> pushStatus()
            "newScreen" -> (viewed ?: project).service<DumpieProjectService>().receive(newScreenRequest())
            "removeRequests" -> {
                val uuids = (msg.get("uuids") as? JsonArray)?.mapNotNull { it.takeIf { e -> e.isJsonPrimitive }?.asString }?.toSet() ?: return
                val sessions = viewed?.let { listOf(it) } ?: openProjects()
                val title = msg.str("title") ?: "Removed"
                sessions.forEach { it.service<DumpieProjectService>().removeRequests(uuids, title) }
                // Other windows showing these sessions replay them without the closed screen
                panels.filter { it !== this && (it.viewed == null || it.viewed in sessions) }.forEach { it.show(it.viewed) }
            }
            "archive" -> {
                val list = JsonArray()
                (viewed?.let { listOf(it) } ?: openProjects())
                    .flatMap { p -> p.service<DumpieProjectService>().archived().map { p to it } }
                    .sortedByDescending { (_, item) -> item.time }
                    .forEach { (p, item) ->
                        list.add(JsonObject().apply {
                            addProperty("id", item.id)
                            addProperty("session", p.locationHash)
                            addProperty("project", p.name)
                            addProperty("time", item.time)
                            addProperty("title", item.title)
                            addProperty("count", item.count)
                        })
                    }
                execute("window.dumpie.archive($list);")
            }
            "restore" -> {
                val session = msg.str("session")?.let(::findProject) ?: return
                val id = msg.get("id")?.takeIf { it.isJsonPrimitive }?.asLong ?: return
                if (!session.service<DumpieProjectService>().restore(id)) return
                // Every window showing this session replays it with the restored output
                panels.filter { it.viewed == null || it.viewed == session }.forEach { it.show(it.viewed) }
            }
            "browse" -> msg.str("url")
                ?.takeIf { it.startsWith("http://") || it.startsWith("https://") || it.startsWith("mailto:") }
                ?.let { BrowserUtil.browse(it) }
        }
    }
}
