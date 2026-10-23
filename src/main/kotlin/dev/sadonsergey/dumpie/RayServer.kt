package dev.sadonsergey.dumpie

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.util.concurrency.AppExecutorUtil
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.sadonsergey.dumpie.settings.DumpieSettings
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * HTTP endpoint compatible with the spatie/ray PHP client protocol.
 */
@Service(Service.Level.APP)
class RayServer : Disposable {
    private val log = logger<RayServer>()
    private var server: HttpServer? = null
    private var warnedPort = 0
    private var retry: ScheduledFuture<*>? = null

    /** The port this IDE listens on, 0 while another application (or another IDE's Dumpie) holds it. */
    val boundPort: Int get() = server?.address?.port ?: 0

    // Pause locks: name -> stop requested; a missing entry means "released"
    private val locks = ConcurrentHashMap<String, Boolean>()

    @Synchronized
    fun start() {
        val settings = DumpieSettings.get()
        if (server != null || !settings.rayEnabled) return
        val port = settings.rayPort
        try {
            server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0).apply {
                createContext("/") { handle(it) }
                executor = AppExecutorUtil.getAppExecutorService()
                start()
            }
            log.info("Listening for spatie/ray clients on port $port")
            retry?.cancel(false)
            retry = null
            service<DumpieRelay>().publish()
        } catch (e: IOException) {
            // Another IDE's Dumpie on this port is normal: it forwards our projects' output here
            if (warnedPort != port && !service<DumpieRelay>().portOwnedByAnotherInstance(port)) {
                warnedPort = port
                log.warn("Cannot listen on port $port", e)
                DumpieRouter.warnPortBusy(port, "ray() output")
            }
            // Take the port over as soon as it is released (the Ray app or the other IDE quits)
            if (retry == null) {
                retry = AppExecutorUtil.getAppScheduledExecutorService()
                    .scheduleWithFixedDelay({ start() }, RETRY_SECONDS, RETRY_SECONDS, TimeUnit.SECONDS)
            }
        }
    }

    /** Applies changed settings (enabled flag, port). */
    @Synchronized
    fun restart() {
        stop()
        start()
    }

    @Synchronized
    private fun stop() {
        retry?.cancel(false)
        retry = null
        if (server == null) return
        server?.stop(0)
        server = null
        service<DumpieRelay>().publish()
    }

    /** [broadcast]: the lock may live in another IDE's Dumpie, the one that received ray()->pause(). */
    fun releaseLock(name: String, stopExecution: Boolean, broadcast: Boolean = true) {
        if (stopExecution) locks.replace(name, true) else locks.remove(name)
        if (broadcast) service<DumpieRelay>().broadcastLock(name, stopExecution)
    }

    override fun dispose() {
        retry?.cancel(false)
        server?.stop(0)
        server = null
    }

    private fun handle(exchange: HttpExchange) {
        try {
            val path = exchange.requestURI.path
            when {
                exchange.requestMethod == "POST" && path == "/" -> {
                    receive(exchange.requestBody.readBytes().toString(Charsets.UTF_8))
                    respond(exchange, 200, "{}")
                }
                exchange.requestMethod == "GET" && path.startsWith("/locks/") ->
                    respond(exchange, 200, lockState(path.removePrefix("/locks/")))
                // The client treats 404 on /_availability_check as "server is up"
                else -> respond(exchange, 404, "")
            }
        } catch (e: Exception) {
            log.warn("Failed to handle ${exchange.requestURI}", e)
        } finally {
            exchange.close()
        }
    }

    private fun receive(body: String) {
        val request = try {
            JsonParser.parseString(body) as? JsonObject
        } catch (e: Exception) {
            log.debug("Malformed request body", e)
            null
        } ?: return
        val payloads = request.get("payloads") as? JsonArray ?: return

        payloads.forEach { payload ->
            val obj = payload as? JsonObject ?: return@forEach
            val content = obj.get("content") as? JsonObject ?: return@forEach
            when (obj.str("type")) {
                "create_lock" -> content.str("name")?.let { locks[it] = false }
                "custom" -> content.get("content")
                    ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
                    ?.let { content.addProperty("content", inlineLocalImages(it.asString)) }
            }
        }

        DumpieRouter.dispatch(request, DumpieRouter.SOURCE_RAY)
    }

    /** ray()->image('/local/path.png') sends a file:// URL, which the JCEF page cannot load; embed it instead. */
    private fun inlineLocalImages(html: String): String {
        if (!html.contains("src=\"file://")) return html
        return LOCAL_IMAGE.replace(html) { match ->
            val file = File(match.groupValues[1])
            val mime = IMAGE_TYPES[file.extension.lowercase()]
            if (mime == null || !file.isFile || file.length() > MAX_IMAGE_BYTES) return@replace match.value
            "src=\"data:$mime;base64,${Base64.getEncoder().encodeToString(file.readBytes())}\""
        }
    }

    private fun lockState(name: String): String {
        val stop = locks[name] ?: return """{"active":false,"stop_execution":false}"""
        if (!stop) return """{"active":true,"stop_execution":false}"""
        locks.remove(name)
        return """{"active":false,"stop_execution":true}"""
    }

    private fun respond(exchange: HttpExchange, code: Int, body: String) {
        val bytes = body.toByteArray()
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(code, if (bytes.isEmpty()) -1 else bytes.size.toLong())
        if (bytes.isNotEmpty()) exchange.responseBody.use { it.write(bytes) }
    }

    companion object {
        private const val RETRY_SECONDS = 5L
        private const val MAX_IMAGE_BYTES = 10L * 1024 * 1024
        private val LOCAL_IMAGE = Regex("src=\"file://([^\"]+)\"")
        private val IMAGE_TYPES = mapOf(
            "png" to "image/png", "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "gif" to "image/gif",
            "webp" to "image/webp", "svg" to "image/svg+xml", "bmp" to "image/bmp",
        )
    }
}

internal fun JsonObject.str(key: String): String? = get(key)?.takeIf { it.isJsonPrimitive }?.asString
