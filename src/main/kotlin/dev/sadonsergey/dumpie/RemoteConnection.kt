package dev.sadonsergey.dumpie

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import dev.sadonsergey.dumpie.settings.DumpieProjectSettings
import dev.sadonsergey.dumpie.settings.DumpieSettings
import dev.sadonsergey.dumpie.ui.DumpiePanel
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * Follows the project's servers through the system ssh client, each switched on from the toolbar menu: their log
 * files with `tail -F`, and ray() / dump() calls on them through remote port forwarding to this IDE's receivers.
 * Never connects on its own.
 */
@Service(Service.Level.PROJECT)
class RemoteConnection(private val project: Project) : Disposable {
    enum class State { OFF, CONNECTING, ON }

    /** A server of the toolbar menu; [problem] says why it cannot connect. */
    class Status(val id: String, val name: String, val state: State, val problem: String?)

    private class Connection(val id: String, val host: RemoteHosts.Host, val server: DumpieProjectSettings.Server, val process: Process) {
        val fingerprint = fingerprint(host, server)

        @Volatile
        var state = State.CONNECTING
        val errors = ConcurrentLinkedDeque<String>()
    }

    // SSH configuration id -> running connection
    private val connections = ConcurrentHashMap<String, Connection>()

    /** The configured servers with their state; null in IDEs without SSH support. */
    fun servers(): List<Status>? {
        val hosts = RemoteHosts.get(project)?.list() ?: return null
        return DumpieProjectSettings.get(project).servers.map { server ->
            val id = server.sshConfig.orEmpty()
            val host = hosts.firstOrNull { it.id == id }
            val problem = when {
                host == null -> "Its SSH configuration was deleted"
                host.usesPassword -> "Uses a password: Dumpie needs a key pair or OpenSSH config and authentication agent"
                else -> null
            }
            Status(id, host?.name ?: "Unknown server", connections[id]?.state ?: State.OFF, problem)
        }
    }

    fun toggle(id: String) = if (connections.containsKey(id)) disconnect(id) else connect(id)

    @Synchronized
    fun connect(id: String) {
        if (connections.containsKey(id)) return
        val server = DumpieProjectSettings.get(project).servers.firstOrNull { it.sshConfig == id }?.copy() ?: return
        val host = RemoteHosts.get(project)?.list()?.firstOrNull { it.id == id }
            ?: return notify("The SSH configuration of this server was deleted. Choose another one in Settings | Tools | Dumpie.", NotificationType.WARNING)
        if (host.usesPassword) {
            return notify(
                "${host.name} uses a password. Dumpie connects with the system ssh client: switch the SSH configuration " +
                    "to a key pair or to OpenSSH config and authentication agent.",
                NotificationType.ERROR,
            )
        }
        val process = try {
            GeneralCommandLine(command(host, server)).withCharset(Charsets.UTF_8).createProcess()
        } catch (e: Exception) {
            return notify("Cannot start ssh: ${e.message}", NotificationType.ERROR)
        }
        val connection = Connection(id, host, server, process)
        connections[id] = connection
        DumpiePanel.remoteChanged(project)
        val pool = ApplicationManager.getApplication()
        pool.executeOnPooledThread { readOutput(connection) }
        pool.executeOnPooledThread { readErrors(connection) }
        pool.executeOnPooledThread { closed(connection, process.waitFor()) }
    }

    @Synchronized
    fun disconnect(id: String) {
        val connection = connections.remove(id) ?: return
        connection.process.destroy()
        DumpiePanel.remoteChanged(project)
    }

    /** Settings applied: servers with other settings reconnect, removed ones disconnect, the rest keep running. */
    fun restart() {
        val servers = DumpieProjectSettings.get(project).servers
        val hosts = RemoteHosts.get(project)?.list().orEmpty()
        connections.values.toList().forEach { connection ->
            val server = servers.firstOrNull { it.sshConfig == connection.id }
            val host = hosts.firstOrNull { it.id == connection.id }
            if (server != null && host != null && fingerprint(host, server) == connection.fingerprint) return@forEach
            disconnect(connection.id)
            if (server != null) connect(connection.id)
        }
        DumpiePanel.remoteChanged(project)
    }

    override fun dispose() {
        connections.values.forEach { it.process.destroy() }
        connections.clear()
    }

    @Synchronized
    private fun closed(connection: Connection, code: Int) {
        // Disconnected from the menu, or replaced by a new connection
        if (!connections.remove(connection.id, connection)) return
        DumpiePanel.remoteChanged(project)
        val reason = connection.errors.lastOrNull { !it.startsWith("Warning:") } ?: "ssh exited with code $code"
        NotificationGroupManager.getInstance().getNotificationGroup(DumpieRouter.NOTIFICATION_GROUP)
            .createNotification("Disconnected from ${connection.host.name}: $reason", NotificationType.ERROR)
            .addAction(NotificationAction.createSimpleExpiring("Reconnect") { connect(connection.id) })
            .notify(project)
    }

    private fun connected(connection: Connection) {
        if (connections[connection.id] !== connection) return
        connection.state = State.ON
        DumpiePanel.remoteChanged(project)
    }

    private fun command(host: RemoteHosts.Host, server: DumpieProjectSettings.Server): List<String> {
        val app = DumpieSettings.get()
        val command = mutableListOf(
            "ssh", "-T",
            // Never wait for a password prompt nobody sees; remember a new host key, refuse a changed one
            "-o", "BatchMode=yes", "-o", "StrictHostKeyChecking=accept-new",
            "-o", "ConnectTimeout=15", "-o", "ServerAliveInterval=15", "-o", "ServerAliveCountMax=3",
        )
        if (host.port > 0) command += listOf("-p", host.port.toString())
        host.keyPath?.let { command += listOf("-i", it.replaceFirst(Regex("^~"), Regex.escapeReplacement(System.getProperty("user.home")))) }
        if (server.forwardPorts) {
            // Ports on the server's loopback only, so nothing else on the network can reach this IDE
            if (app.rayEnabled) command += listOf("-R", "127.0.0.1:${app.rayPort}:127.0.0.1:${app.rayPort}")
            if (app.dumpEnabled) command += listOf("-R", "127.0.0.1:${app.dumpPort}:127.0.0.1:${app.dumpPort}")
        }
        command += if (host.user.isNotEmpty()) "${host.user}@${host.host}" else host.host
        val paths = logPaths(server)
        val follow = if (paths.isEmpty()) "tail -f /dev/null" else "tail -n 0 -F -- " + paths.joinToString(" ") { quote(it) }
        command += "echo $READY; exec $follow"
        return command
    }

    private class Pending(val path: String, val record: LogWatcher.Record) {
        val uuid = UUID.randomUUID().toString()
        var changed = true
    }

    private fun readOutput(connection: Connection) {
        var path = logPaths(connection.server).singleOrNull().orEmpty()
        val minLevel = LEVELS.indexOf(connection.server.logLevel.orEmpty())
        val everyLine = minLevel < 0
        var pending: Pending? = null
        fun flush() {
            val record = pending?.takeIf { it.changed } ?: return
            record.changed = false
            // Sent again with the same uuid when more of a long record (a stack trace) arrives later
            project.service<DumpieProjectService>().receive(LogWatcher.request(record.path, record.record, connection.host.name, record.uuid))
        }
        try {
            connection.process.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    val file = FILE_HEADER.matchEntire(line)
                    val header = LogWatcher.HEADER.find(line)
                    when {
                        line == READY -> connected(connection)
                        // tail names the file before output of another one
                        file != null -> {
                            flush()
                            pending = null
                            path = file.groupValues[1]
                        }
                        header != null -> {
                            flush()
                            val (channel, level, message) = header.destructured
                            // Below the chosen level: dropped with its stack trace; unknown levels are shown
                            pending = if (LEVELS.indexOf(level.lowercase()) in 0 until minLevel) null
                            else Pending(path, LogWatcher.Record(0, channel, level, message, StringBuilder()))
                        }
                        else -> {
                            val current = pending
                            // A Monolog record owns every following line; other lines only keep a PHP stack trace together
                            if (current != null && (current.record.level.isNotEmpty() || CONTINUATION.containsMatchIn(line))) {
                                current.record.rest.append(line).append('\n')
                                if (line.isNotBlank()) current.changed = true
                            } else if (everyLine && line.isNotBlank()) {
                                flush()
                                pending = Pending(path, LogWatcher.Record(0, "", "", line, StringBuilder()))
                            }
                        }
                    }
                    // tail writes a record at once: nothing more buffered means it is complete for now
                    if (!reader.ready()) flush()
                }
            }
        } catch (_: Exception) {
            // The stream closes with the process
        }
        flush()
    }

    private fun readErrors(connection: Connection) {
        val warned = mutableSetOf<String>()
        val name = connection.host.name
        try {
            connection.process.errorStream.bufferedReader(Charsets.UTF_8).forEachLine { line ->
                connection.errors.addLast(line)
                if (connection.errors.size > MAX_ERRORS) connection.errors.removeFirst()
                val warning = when {
                    line.contains("remote port forwarding failed", ignoreCase = true) ->
                        line.substringAfterLast(' ').let { "Port $it is taken on $name (another IDE may be connected), its output is not forwarded." }
                    line.startsWith("tail: cannot open") ->
                        "Log file not found on $name: ${line.substringAfter("tail: ")}"
                    else -> null
                }
                if (warning != null && warned.add(warning)) notify(warning, NotificationType.WARNING)
            }
        } catch (_: Exception) {
            // The stream closes with the process
        }
    }

    private fun notify(text: String, type: NotificationType) {
        NotificationGroupManager.getInstance().getNotificationGroup(DumpieRouter.NOTIFICATION_GROUP)
            .createNotification(text, type)
            .notify(project)
    }

    companion object {
        private const val READY = "dumpie:connected"
        private const val MAX_ERRORS = 20
        private val FILE_HEADER = Regex("""^==> (.+) <==$""")
        private val SAFE_PATH = Regex("""[\w./~*?\[\]-]+""")
        private val LEVELS = listOf("debug", "info", "notice", "warning", "error", "critical", "alert", "emergency")

        // PHP error_log: "Stack trace:", "#0 /path(12): ...", "  thrown in /path on line 12"
        private val CONTINUATION = Regex("""^(?:Stack trace:|#\d+ |\s+thrown in )""")

        private fun logPaths(server: DumpieProjectSettings.Server) =
            server.logPaths.orEmpty().lines().map { it.trim() }.filter { it.isNotEmpty() }

        /** What a running connection was started with: applying other settings reconnects it. */
        private fun fingerprint(host: RemoteHosts.Host, server: DumpieProjectSettings.Server): String {
            val app = DumpieSettings.get()
            return listOf(
                host.host, host.port, host.user, host.keyPath, server.logPaths, server.logLevel, server.forwardPorts,
                app.rayEnabled, app.rayPort, app.dumpEnabled, app.dumpPort,
            ).joinToString("\u0000")
        }

        /** Plain paths and globs stay as they are for the remote shell; anything else is single-quoted. */
        private fun quote(path: String) = if (SAFE_PATH.matches(path)) path else "'" + path.replace("'", "'\\''") + "'"
    }
}
