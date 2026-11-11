package dev.sadonsergey.dumpie.settings

import com.intellij.openapi.components.service
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.TableUtil
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.rows
import com.intellij.ui.dsl.builder.selected
import com.intellij.ui.table.TableView
import com.intellij.util.ui.ColumnInfo
import com.intellij.util.ui.ListTableModel
import dev.sadonsergey.dumpie.LogWatcher
import dev.sadonsergey.dumpie.OutputMarkers
import dev.sadonsergey.dumpie.RayServer
import dev.sadonsergey.dumpie.RemoteConnection
import dev.sadonsergey.dumpie.RemoteHosts
import dev.sadonsergey.dumpie.dump.DumpServer
import dev.sadonsergey.dumpie.ui.DumpiePanel
import java.awt.event.MouseEvent
import javax.swing.JList

/** Settings | Tools | Dumpie: IDE-wide receivers and the sources of the current project. */
class DumpieConfigurable(private val project: Project) : BoundConfigurable("Dumpie") {
    private val app = DumpieSettings.get()
    private val local = DumpieProjectSettings.get(project)
    private val detection = ProjectDetector.detect(project)

    override fun createPanel(): DialogPanel = panel {
        group("Receivers (all projects)") {
            row {
                checkBox("spatie/ray clients, port").bindSelected(app::rayEnabled)
                intTextField(1..65535).bindIntText(app::rayPort).columns(6)
            }
            row {
                checkBox("dump() / dd() via VarDumper server, port").bindSelected(app::dumpEnabled)
                intTextField(1..65535).bindIntText(app::dumpPort).columns(6)
            }
        }

        group("Display") {
            row {
                checkBox("Show the tool window when output arrives").bindSelected(app::showToolWindowOnMessage)
            }
            row {
                checkBox("Keep the project and screen bars visible while scrolling").bindSelected(app::pinBars)
            }
            row {
                checkBox("Use the editor background (white) in light themes instead of the tool window background")
                    .bindSelected(app::whiteBackgroundInLightThemes)
            }
            row {
                checkBox("Show an icon in the editor gutter on lines that produced output").bindSelected(app::showGutterIcons)
            }
            row {
                checkBox("Show the last output at the end of its line in the editor").bindSelected(app::showInlineValues)
            }
            row {
                checkBox("Warn before committing dump(), dd() and ray() calls").bindSelected(app::checkDebugCallsOnCommit)
            }.rowComment("Added lines of PHP, Blade, Twig and JavaScript files are checked")
            row {
                checkBox("Mark the tool window icon when errors arrive while it is hidden").bindSelected(app::errorBadge)
            }.rowComment("Exceptions and error log records; showing the tool window clears the mark and scrolls to the last error")
            row {
                checkBox("Treat entries without a color as gray in the color filter").bindSelected(app::grayUncolored)
            }.rowComment("dump() output, info log records and ray() calls without a color")
        }

        group("SQL queries") {
            row {
                checkBox("Show the number of queries and their total time in the toolbar").bindSelected(app::showQueryStats)
            }
            row {
                checkBox("Mark repeated queries (possible N+1)").bindSelected(app::markRepeatedQueries)
            }.rowComment("The same statement with other values ran 3 or more times on one screen")
            row {
                val slow = checkBox("Mark queries slower than").bindSelected(app::markSlowQueries)
                intTextField(1..600_000).bindIntText(app::slowQueryMs).columns(6).enabledIf(slow.selected)
                label("ms")
            }
        }

        group("This project") {
            row {
                checkBox("ray() output").bindSelected(local::rayEnabled)
            }.rowComment(rayStatus())

            row {
                checkBox("dump() output").bindSelected(local::dumpEnabled)
            }.rowComment(dumpStatus())

            if (detection.varDumper != null && !detection.dumpServerConfigured && detection.envFile != null) {
                row {
                    val lines = ProjectDetector.envLines(detection, app.dumpPort)
                    button("Add to .env") { SetupGuide.addToEnv(project) }
                    comment(lines.joinToString("<br>"))
                }
            }

            row {
                checkBox("Log files").bindSelected(local::logsEnabled)
            }.rowComment(
                if (detection.logPatterns.isEmpty()) "No storage/logs or var/log directory found in the project"
                else "Monolog line format, e.g. Laravel's laravel.log; only new records are shown",
            )
            row {
                textArea()
                    .bindText({ local.logPaths.orEmpty() }, { local.logPaths = it })
                    .rows(3)
                    .align(Align.FILL)
                    .comment("Glob patterns relative to the project root, one per line (wildcards in the file name only)")
            }

            row {
                button("Detect Again") {
                    project.service<DumpieProjectSettings>().applyDetection(ProjectDetector.detect(project))
                    reset()
                }
                comment("Fills this section from composer.lock, .env and the project's log directories")
            }
        }

        // IDEs without SSH support have no server settings
        val hosts = RemoteHosts.get(project)?.list() ?: return@panel
        val model = ListTableModel<DumpieProjectSettings.Server>(
            column("Server") { server -> hosts.firstOrNull { it.id == server.sshConfig }?.name ?: "Deleted SSH configuration" },
            column("Log files") { server -> server.logPaths.orEmpty().lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString(", ") },
            column("Show") { server -> level(server).toString() },
        )
        val table = TableView(model).apply {
            setShowGrid(false)
            visibleRowCount = 4
            emptyText.text = "No servers"
        }
        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent): Boolean {
                table.selectedObject?.let { edit(model, hosts, it) }
                return true
            }
        }.installOn(table)
        group("Servers (SSH)") {
            row {
                cell(
                    ToolbarDecorator.createDecorator(table)
                        .setAddAction { edit(model, hosts, null) }
                        .setEditAction { table.selectedObject?.let { edit(model, hosts, it) } }
                        .setRemoveAction { TableUtil.removeSelectedItems(table) }
                        .disableUpDownActions()
                        .createPanel(),
                ).align(Align.FILL)
            }.rowComment(
                "The server button in the tool window follows them, several at once if needed. They come from Settings | " +
                    "Tools | SSH Configurations",
            )
            onReset { model.items = local.servers.map { it.copy() } }
            onIsModified { model.items != local.servers }
            onApply { local.servers = model.items.map { it.copy() }.toMutableList() }
        }
    }

    /** Adds ([server] null) or edits a server of the table; nothing changes on Cancel. */
    private fun edit(model: ListTableModel<DumpieProjectSettings.Server>, hosts: List<RemoteHosts.Host>, server: DumpieProjectSettings.Server?) {
        val edited = server?.copy() ?: DumpieProjectSettings.Server()
        val taken = model.items.filter { it !== server }.mapNotNull { it.sshConfig }.toSet()
        if (!ServerDialog(edited, hosts, taken).showAndGet()) return
        if (server == null) model.addRow(edited) else {
            server.copyFrom(edited)
            model.fireTableDataChanged()
        }
    }

    private inner class ServerDialog(
        private val server: DumpieProjectSettings.Server,
        private val hosts: List<RemoteHosts.Host>,
        private val taken: Set<String>,
    ) : DialogWrapper(project) {
        private lateinit var host: ComboBox<RemoteHosts.Host>
        private lateinit var form: DialogPanel

        init {
            title = if (server.sshConfig.isNullOrEmpty()) "Add Server" else "Edit Server"
            init()
        }

        override fun createCenterPanel(): DialogPanel = panel {
            row("SSH configuration:") {
                host = comboBox(hosts, HostRenderer())
                    .bindItem({ hosts.firstOrNull { it.id == server.sshConfig } ?: hosts.firstOrNull { it.id !in taken } }, { server.sshConfig = it?.id.orEmpty() })
                    .component
            }.rowComment(
                "From Settings | Tools | SSH Configurations. Dumpie connects with the system ssh client, so the " +
                    "configuration needs a key pair or OpenSSH config and authentication agent",
            )
            row {
                label("Log files on the server:")
            }
            row {
                textArea()
                    .bindText({ server.logPaths.orEmpty() }, { server.logPaths = it })
                    .rows(4)
                    .columns(60)
                    .align(Align.FILL)
                    .comment("Absolute paths, one per line, e.g. /var/www/app/storage/logs/laravel.log; only new lines are shown")
            }
            row("Show:") {
                comboBox(LEVELS).bindItem({ level(server) }, { server.logLevel = it?.level.orEmpty() })
            }.rowComment("Every line also shows what is not a log record: worker output, PHP errors, other formats")
            row {
                checkBox("Forward ray() and dump() calls on the server to this IDE").bindSelected(server::forwardPorts)
            }.rowComment(
                "While connected, ports ${app.rayPort} and ${app.dumpPort} on the server's loopback lead here; " +
                    "spatie/ray and VAR_DUMPER_FORMAT=server on the server must send to 127.0.0.1",
            )
        }.also { form = it }

        override fun doValidate(): ValidationInfo? {
            val chosen = host.selectedItem as? RemoteHosts.Host ?: return ValidationInfo("Choose an SSH configuration", host)
            return if (chosen.id in taken) ValidationInfo("${chosen.name} is already in the list", host) else null
        }

        override fun doOKAction() {
            form.apply()
            super.doOKAction()
        }
    }

    private fun level(server: DumpieProjectSettings.Server) = LEVELS.firstOrNull { it.level == server.logLevel.orEmpty() } ?: LEVELS.first()

    private fun column(name: String, value: (DumpieProjectSettings.Server) -> String) =
        object : ColumnInfo<DumpieProjectSettings.Server, String>(name) {
            override fun valueOf(item: DumpieProjectSettings.Server) = value(item)
        }

    override fun apply() {
        super.apply()
        service<RayServer>().restart()
        service<DumpServer>().restart()
        project.service<LogWatcher>().restart()
        project.service<RemoteConnection>().restart()
        // Turning the gutter icons or inline values off removes the ones already shown
        ProjectManager.getInstance().openProjects.forEach { it.service<OutputMarkers>().refresh() }
        DumpiePanel.settingsChanged()
    }

    private class LevelChoice(val level: String, private val text: String) {
        override fun toString() = text
    }

    private val LEVELS = listOf(
        LevelChoice("", "Every line"),
        LevelChoice("debug", "Log records of any level"),
        LevelChoice("info", "INFO and above"),
        LevelChoice("notice", "NOTICE and above"),
        LevelChoice("warning", "WARNING and above"),
        LevelChoice("error", "ERROR and above"),
        LevelChoice("critical", "CRITICAL and above"),
    )

    private class HostRenderer : SimpleListCellRenderer<RemoteHosts.Host?>() {
        override fun customize(list: JList<out RemoteHosts.Host?>, value: RemoteHosts.Host?, index: Int, selected: Boolean, hasFocus: Boolean) {
            text = value?.name ?: "None"
        }
    }

    private fun rayStatus(): String = when {
        detection.laravelRay != null -> "spatie/laravel-ray ${detection.laravelRay} found"
        detection.ray != null -> "spatie/ray ${detection.ray} found"
        else -> "spatie/ray is not in composer.lock (a global install still works)"
    }

    private fun dumpStatus(): String = when {
        detection.varDumper == null -> "symfony/var-dumper is not in composer.lock"
        detection.dumpServerConfigured -> "symfony/var-dumper ${detection.varDumper} found, VAR_DUMPER_FORMAT is set in .env"
        else -> "symfony/var-dumper ${detection.varDumper} found; dump() is sent to the IDE once VAR_DUMPER_FORMAT=server is in .env"
    }
}
