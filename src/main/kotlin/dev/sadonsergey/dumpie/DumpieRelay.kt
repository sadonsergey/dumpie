package dev.sadonsergey.dumpie

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.project.ProjectManagerListener
import com.intellij.util.concurrency.AppExecutorUtil
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.sadonsergey.dumpie.dump.DumpServer
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Duration
import java.util.UUID

/**
 * Links Dumpie instances running in several IDEs at once (PhpStorm and WebStorm, two IDE versions, ...).
 * Only one IDE can own the receiver ports; it forwards output of projects opened in another IDE to that IDE.
 *
 * Every instance publishes `<pid>.json` (open projects, owned ports, relay port, token) in a per-user directory
 * under java.io.tmpdir and serves a loopback relay endpoint on a random port.
 */
@Service(Service.Level.APP)
class DumpieRelay : Disposable {
    class RemoteProject(val name: String, val path: String)
    class Instance(val pid: Long, val port: Int, val token: String, val ports: List<Int>, val projects: List<RemoteProject>)

    private val log = logger<DumpieRelay>()
    private val token = UUID.randomUUID().toString()
    private val pid = ProcessHandle.current().pid()
    private val dir = File(System.getProperty("java.io.tmpdir"), "dumpie-" + System.getProperty("user.name"))
    private val self = File(dir, "$pid.json")
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()
    private var server: HttpServer? = null

    @Synchronized
    fun start() {
        if (server != null) return
        server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
            createContext("/") { handle(it) }
            executor = AppExecutorUtil.getAppExecutorService()
            start()
        }
        ApplicationManager.getApplication().messageBus.connect(this)
            .subscribe(ProjectManager.TOPIC, object : ProjectManagerListener {
                override fun projectClosed(project: Project) = publish(closing = project)
            })
        publish()
    }

    /** Rewrites this instance's registry entry; call whenever projects or owned ports change. */
    @Synchronized
    fun publish(closing: Project? = null) {
        val relayPort = server?.address?.port ?: return
        val entry = JsonObject().apply {
            addProperty("pid", pid)
            addProperty("port", relayPort)
            addProperty("token", token)
            add("ports", JsonArray().apply {
                listOf(service<RayServer>().boundPort, service<DumpServer>().boundPort).filter { it > 0 }.forEach { add(it) }
            })
            add("projects", JsonArray().apply {
                ProjectManager.getInstance().openProjects
                    .filter { !it.isDisposed && it != closing && it.basePath != null }
                    .forEach { p -> add(JsonObject().apply { addProperty("name", p.name); addProperty("path", p.basePath) }) }
            })
        }
        try {
            dir.mkdirs()
            val tmp = File(dir, "$pid.json.tmp")
            tmp.writeText(entry.toString())
            // Same-user only: the token authorizes forwarded requests
            tmp.setReadable(false, false)
            tmp.setReadable(true, true)
            Files.move(tmp.toPath(), self.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: IOException) {
            log.warn("Cannot publish the Dumpie instance registry entry", e)
        }
    }

    /** Other live Dumpie instances; entries of processes that are gone are removed. */
    fun others(): List<Instance> = dir.listFiles { f -> f.name.endsWith(".json") }.orEmpty().mapNotNull { file ->
        val json = try {
            JsonParser.parseString(file.readText()) as? JsonObject
        } catch (e: Exception) {
            null
        }
        val otherPid = json?.get("pid")?.asLong ?: return@mapNotNull null
        if (otherPid == pid) return@mapNotNull null
        if (!ProcessHandle.of(otherPid).map { it.isAlive }.orElse(false)) {
            file.delete()
            return@mapNotNull null
        }
        Instance(
            pid = otherPid,
            port = json.get("port").asInt,
            token = json.str("token").orEmpty(),
            ports = (json.get("ports") as? JsonArray)?.map { it.asInt }.orEmpty(),
            projects = (json.get("projects") as? JsonArray)?.mapNotNull { it as? JsonObject }
                ?.mapNotNull { p -> RemoteProject(p.str("name") ?: return@mapNotNull null, p.str("path") ?: return@mapNotNull null) }
                .orEmpty(),
        )
    }

    /** True when another Dumpie instance owns [port], so a failed bind is not a conflict with a foreign app. */
    fun portOwnedByAnotherInstance(port: Int) = others().any { port in it.ports }

    /** Hands [request] to the IDE that has the project open; false when that IDE did not answer. */
    fun forward(instance: Instance, request: JsonObject, source: String): Boolean = try {
        val response = client.send(
            HttpRequest.newBuilder(URI("http://127.0.0.1:${instance.port}/relay?source=$source"))
                .header(TOKEN_HEADER, instance.token)
                .timeout(Duration.ofSeconds(3))
                .POST(HttpRequest.BodyPublishers.ofString(request.toString()))
                .build(),
            HttpResponse.BodyHandlers.discarding(),
        )
        response.statusCode() == 200
    } catch (e: Exception) {
        log.debug("Dumpie instance ${instance.pid} did not accept a forwarded request", e)
        false
    }

    /** The IDE that received ray()->pause() keeps the lock; Continue/Stop may be pressed in any IDE. */
    fun broadcastLock(name: String, stopExecution: Boolean) {
        val body = JsonObject().apply {
            addProperty("name", name)
            addProperty("stop", stopExecution)
        }.toString()
        others().forEach { instance ->
            client.sendAsync(
                HttpRequest.newBuilder(URI("http://127.0.0.1:${instance.port}/lock"))
                    .header(TOKEN_HEADER, instance.token)
                    .timeout(Duration.ofSeconds(3))
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build(),
                HttpResponse.BodyHandlers.discarding(),
            )
        }
    }

    override fun dispose() {
        server?.stop(0)
        server = null
        self.delete()
    }

    private fun handle(exchange: HttpExchange) {
        try {
            if (exchange.requestMethod != "POST" || exchange.requestHeaders.getFirst(TOKEN_HEADER) != token) {
                exchange.sendResponseHeaders(403, -1)
                return
            }
            val body = JsonParser.parseString(exchange.requestBody.readBytes().toString(Charsets.UTF_8)) as? JsonObject
            when (exchange.requestURI.path) {
                "/relay" -> {
                    val source = exchange.requestURI.query.orEmpty().substringAfter("source=", "")
                    body?.let { DumpieRouter.deliverForwarded(it, source) }
                }
                "/lock" -> body?.str("name")?.let { service<RayServer>().releaseLock(it, body.get("stop")?.asBoolean == true, broadcast = false) }
            }
            exchange.sendResponseHeaders(200, -1)
        } catch (e: Exception) {
            log.warn("Failed to handle a relay request", e)
        } finally {
            exchange.close()
        }
    }

    companion object {
        private const val TOKEN_HEADER = "X-Dumpie-Token"
    }
}
