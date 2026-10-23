package dev.sadonsergey.dumpie.dump

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.util.concurrency.AppExecutorUtil
import dev.sadonsergey.dumpie.DumpieRelay
import dev.sadonsergey.dumpie.DumpieRouter
import dev.sadonsergey.dumpie.dump.PhpUnserializer.Arr
import dev.sadonsergey.dumpie.dump.PhpUnserializer.Num
import dev.sadonsergey.dumpie.dump.PhpUnserializer.Obj
import dev.sadonsergey.dumpie.dump.PhpUnserializer.Str
import dev.sadonsergey.dumpie.settings.DumpieSettings
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Receives dump()/dd() output from Symfony VarDumper's ServerDumper (VAR_DUMPER_FORMAT=server):
 * one line per dump, base64(serialize([Data, context])).
 */
@Service(Service.Level.APP)
class DumpServer : Disposable {
    private val log = logger<DumpServer>()
    private var server: ServerSocket? = null
    private val clients = ConcurrentHashMap.newKeySet<Socket>()
    private var warnedPort = 0
    private var retry: ScheduledFuture<*>? = null

    /** The port this IDE listens on, 0 while another application (or another IDE's Dumpie) holds it. */
    val boundPort: Int get() = server?.localPort ?: 0

    @Synchronized
    fun start() {
        val settings = DumpieSettings.get()
        if (server != null || !settings.dumpEnabled) return
        val port = settings.dumpPort
        val socket = try {
            ServerSocket().apply { bind(InetSocketAddress(InetAddress.getLoopbackAddress(), port)) }
        } catch (e: IOException) {
            // Another IDE's Dumpie on this port is normal: it forwards our projects' output here
            if (warnedPort != port && !service<DumpieRelay>().portOwnedByAnotherInstance(port)) {
                warnedPort = port
                log.warn("Cannot listen on port $port", e)
                DumpieRouter.warnPortBusy(port, "dump() output")
            }
            // Take the port over as soon as it is released
            if (retry == null) {
                retry = AppExecutorUtil.getAppScheduledExecutorService()
                    .scheduleWithFixedDelay({ start() }, RETRY_SECONDS, RETRY_SECONDS, TimeUnit.SECONDS)
            }
            return
        }
        server = socket
        retry?.cancel(false)
        retry = null
        AppExecutorUtil.getAppExecutorService().execute { accept(socket) }
        log.info("Listening for VarDumper dumps on port $port")
        service<DumpieRelay>().publish()
    }

    @Synchronized
    fun restart() {
        stop()
        start()
    }

    @Synchronized
    private fun stop() {
        retry?.cancel(false)
        retry = null
        val wasBound = server != null
        server?.close()
        server = null
        clients.forEach { it.close() }
        clients.clear()
        if (wasBound) service<DumpieRelay>().publish()
    }

    override fun dispose() {
        retry?.cancel(false)
        server?.close()
        server = null
        clients.forEach { it.close() }
        clients.clear()
    }

    private fun accept(socket: ServerSocket) {
        while (!socket.isClosed) {
            val client = try {
                socket.accept()
            } catch (e: IOException) {
                return
            }
            clients += client
            AppExecutorUtil.getAppExecutorService().execute { read(client) }
        }
    }

    // PHP keeps the connection open for the whole process (e.g. a php-fpm worker) and writes one line per dump
    private fun read(client: Socket) {
        try {
            client.getInputStream().bufferedReader(Charsets.US_ASCII).useLines { lines ->
                lines.forEach { line -> if (line.isNotBlank()) receive(line) }
            }
        } catch (e: IOException) {
            log.debug("Dump connection closed", e)
        } finally {
            clients -= client
            client.close()
        }
    }

    private fun receive(line: String) {
        val request = try {
            toRequest(PhpUnserializer.parse(Base64.getDecoder().decode(line.trim())))
        } catch (e: Exception) {
            log.warn("Cannot decode a dump", e)
            null
        } ?: return
        DumpieRouter.dispatch(request, DumpieRouter.SOURCE_DUMP)
    }

    /** Converts [Data, context] into a request in the same shape the page already renders (a "log" payload). */
    internal fun toRequest(message: PhpUnserializer.Value): JsonObject? {
        val pair = (message as? Arr)?.items ?: return null
        val data = pair[0L] as? Obj ?: return null
        val context = (pair[1L] as? Arr)?.items.orEmpty()
        val source = (context["source"] as? Arr)?.items.orEmpty()
        val rendered = DumpRenderer.render(data)

        val origin = JsonObject().apply {
            addProperty("file", (source["file"] as? Str)?.value)
            addProperty("line_number", (source["line"] as? Num)?.value)
            addProperty("hostname", "")
        }
        val log = JsonObject().apply {
            addProperty("type", "log")
            add("origin", origin)
            add("content", JsonObject().apply {
                add("values", JsonArray().apply { add(rendered.html) })
                add("meta", JsonArray().apply { add(JsonObject().apply { addProperty("clipboard_data", rendered.text) }) })
            })
        }
        val payloads = JsonArray().apply { add(log) }
        label(context)?.let { label ->
            payloads.add(JsonObject().apply {
                addProperty("type", "label")
                add("origin", origin)
                add("content", JsonObject().apply { addProperty("label", label) })
            })
        }

        return JsonObject().apply {
            addProperty("uuid", UUID.randomUUID().toString())
            add("payloads", payloads)
            add("meta", JsonObject())
        }
    }

    /** "GET /users" for HTTP requests, the command line for CLI runs. */
    private fun label(context: Map<Any, PhpUnserializer.Value>): String? {
        val request = (context["request"] as? Arr)?.items
        if (request != null) {
            val method = (request["method"] as? Str)?.value.orEmpty()
            val uri = (request["uri"] as? Str)?.value.orEmpty()
            return "$method $uri".trim().takeIf { it.isNotEmpty() }
        }
        // "/usr/bin/php /var/www/artisan queue:work" -> "artisan queue:work"
        val command = ((context["cli"] as? Arr)?.items?.get("command_line") as? Str)?.value ?: return null
        val short = command.split(' ').filter { it.isNotEmpty() }
            .map { it.substringAfterLast('/') }
            .dropWhile { it == "php" || it.startsWith("php8") || it.startsWith("php7") }
            .joinToString(" ")
        return if (short.length > 48) short.take(47) + "…" else short
    }

    companion object {
        private const val RETRY_SECONDS = 5L
    }
}
