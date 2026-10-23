package dev.sadonsergey.dumpie

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.util.concurrency.AppExecutorUtil
import dev.sadonsergey.dumpie.settings.DumpieProjectSettings
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.FileSystems
import java.util.UUID
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Follows the project's log files (Monolog line format, e.g. storage/logs/laravel.log) and turns new records
 * into entries of the project's session. Only records written after the IDE started watching are shown.
 */
@Service(Service.Level.PROJECT)
class LogWatcher(private val project: Project) : Disposable {
    private val log = logger<LogWatcher>()
    private var task: ScheduledFuture<*>? = null

    private class Tail(var offset: Long, var line: Int)

    private val tails = mutableMapOf<File, Tail>()
    private var firstScan = true

    @Synchronized
    fun restart() {
        task?.cancel(false)
        task = null
        tails.clear()
        firstScan = true
        if (!DumpieProjectSettings.get(project).logsEnabled) return
        task = AppExecutorUtil.getAppScheduledExecutorService()
            .scheduleWithFixedDelay({ poll() }, 0, POLL_SECONDS, TimeUnit.SECONDS)
    }

    override fun dispose() {
        task?.cancel(false)
    }

    @Synchronized
    private fun poll() {
        if (project.isDisposed) return
        try {
            files().forEach { file ->
                val length = file.length()
                val tail = tails.getOrPut(file) {
                    // Existing content is history; files created later (e.g. a new daily log) are read from the start
                    if (firstScan) Tail(length, if (length <= MAX_COUNTED) countLines(file) else -1) else Tail(0, 0)
                }
                if (length < tail.offset) {
                    tail.offset = 0
                    tail.line = 0
                }
                if (length > tail.offset) read(file, tail, length)
            }
            firstScan = false
        } catch (e: Exception) {
            log.warn("Cannot read project logs", e)
        }
    }

    private fun files(): List<File> {
        val base = project.basePath ?: return emptyList()
        return DumpieProjectSettings.get(project).logPaths.orEmpty().lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .flatMap { pattern ->
                val full = File(pattern).let { if (it.isAbsolute) it else File(base, pattern) }
                val dir = full.parentFile ?: return@flatMap emptyList()
                val matcher = FileSystems.getDefault().getPathMatcher("glob:" + full.name)
                dir.listFiles { f -> f.isFile && matcher.matches(f.toPath().fileName) }?.toList().orEmpty()
            }
    }

    private fun read(file: File, tail: Tail, length: Long) {
        if (length - tail.offset > MAX_CHUNK) {
            // A burst too large to show line by line: keep only the newest part, line numbers become unknown
            tail.offset = length - MAX_CHUNK
            tail.line = -1
        }
        val chunk = RandomAccessFile(file, "r").use { raf ->
            raf.seek(tail.offset)
            ByteArray((length - tail.offset).toInt()).also { raf.readFully(it) }
        }
        tail.offset = length

        var record: Record? = null
        String(chunk, Charsets.UTF_8).lines().dropLastWhile { it.isEmpty() }.forEach { text ->
            if (tail.line >= 0) tail.line++
            val header = HEADER.find(text)
            if (header != null) {
                record?.let { send(file, it) }
                val (channel, level, message) = header.destructured
                record = Record(tail.line, channel, level, message, StringBuilder())
            } else {
                record?.rest?.append(text)?.append('\n')
            }
        }
        record?.let { send(file, it) }
    }

    private class Record(val line: Int, val channel: String, val level: String, val message: String, val rest: StringBuilder)

    private fun send(file: File, record: Record) {
        val origin = JsonObject().apply {
            addProperty("file", file.path)
            addProperty("line_number", record.line.takeIf { it > 0 } ?: 1)
            addProperty("hostname", "")
        }
        val payloads = JsonArray()
        payloads.add(JsonObject().apply {
            addProperty("type", "application_log")
            add("origin", origin)
            add("content", JsonObject().apply {
                addProperty("value", record.message)
                addProperty("level", "${record.channel}.${record.level}")
                record.rest.toString().trim().takeIf { it.isNotEmpty() }?.let { addProperty("context", it) }
            })
        })
        COLORS[record.level.lowercase()]?.let { color ->
            payloads.add(JsonObject().apply {
                addProperty("type", "color")
                add("origin", origin)
                add("content", JsonObject().apply { addProperty("color", color) })
            })
        }
        project.service<DumpieProjectService>().receive(JsonObject().apply {
            addProperty("uuid", UUID.randomUUID().toString())
            add("payloads", payloads)
            add("meta", JsonObject())
        })
    }

    private fun countLines(file: File): Int = file.bufferedReader().use { r -> var n = 0; while (r.readLine() != null) n++; n }

    companion object {
        private const val POLL_SECONDS = 1L
        private const val MAX_CHUNK = 5L * 1024 * 1024
        private const val MAX_COUNTED = 50L * 1024 * 1024

        // Monolog LineFormatter: [2026-10-23 12:00:00] local.ERROR: message {"context"} []
        private val HEADER = Regex("""^\[(?:\d{4}-\d{2}-\d{2}[T ][^\]]*)] ([\w-]+)\.(\w+): (.*)$""")

        private val COLORS = mapOf(
            "emergency" to "red", "alert" to "red", "critical" to "red", "error" to "red",
            "warning" to "orange", "notice" to "blue", "debug" to "gray",
        )
    }
}
