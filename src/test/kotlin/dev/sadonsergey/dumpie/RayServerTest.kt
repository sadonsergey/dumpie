package dev.sadonsergey.dumpie

import com.google.gson.JsonParser
import com.intellij.openapi.components.service
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.sadonsergey.dumpie.settings.DumpieProjectSettings
import dev.sadonsergey.dumpie.settings.DumpieSettings
import java.net.ConnectException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files

class RayServerTest : BasePlatformTestCase() {
    private val server get() = service<RayServer>()
    private val session get() = project.service<DumpieProjectService>()
    private val received = Received()
    private val http = HttpClient.newHttpClient()
    private var port = 0

    override fun setUp() {
        super.setUp()
        port = freePort()
        DumpieSettings.get().rayPort = port
        server.restart()
        session.attach(received.listener)
    }

    override fun tearDown() {
        try {
            session.detach(received.listener)
            session.clear()
            DumpieProjectSettings.get(project).rayEnabled = true
            DumpieSettings.get().rayEnabled = false
            server.restart()
            DumpieSettings.get().rayEnabled = true
            DumpieSettings.get().rayPort = 23517
        } finally {
            super.tearDown()
        }
    }

    private fun send(method: String, path: String, body: String? = null): HttpResponse<String> = http.send(
        HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path"))
            .method(method, body?.let { HttpRequest.BodyPublishers.ofString(it) } ?: HttpRequest.BodyPublishers.noBody())
            .build(),
        HttpResponse.BodyHandlers.ofString(),
    )

    private fun post(uuid: String, type: String, content: String) = send(
        "POST", "/",
        """{"uuid":"$uuid","payloads":[{"type":"$type","content":$content,"origin":{"file":"/nowhere/x.php","line_number":3}}],"meta":{}}""",
    )

    private fun lockState(name: String) = JsonParser.parseString(send("GET", "/locks/$name").body()).asJsonObject.let {
        it.get("active").asBoolean to it.get("stop_execution").asBoolean
    }

    fun testAvailabilityCheckIs404() {
        // The PHP client treats 404 as "the server is up"
        assertEquals(404, send("GET", "/_availability_check").statusCode())
    }

    fun testPostedRequestReachesSession() {
        assertEquals(200, post("u1", "log", """{"values":["hello"]}""").statusCode())
        assertEquals("u1", received.requests.single().get("uuid").asString)
        assertEquals(listOf("log"), received.types())
    }

    fun testRayOutputCanBeTurnedOffPerProject() {
        DumpieProjectSettings.get(project).rayEnabled = false
        post("u1", "log", """{"values":["hello"]}""")
        assertTrue(received.requests.isEmpty())
    }

    fun testMalformedBodyIsIgnored() {
        assertEquals(200, send("POST", "/", "{oops").statusCode())
        assertTrue(received.requests.isEmpty())
    }

    fun testPauseContinues() {
        post("u1", "create_lock", """{"name":"lock-1"}""")
        assertEquals(true to false, lockState("lock-1"))
        server.releaseLock("lock-1", stopExecution = false, broadcast = false)
        assertEquals(false to false, lockState("lock-1"))
    }

    fun testPauseStopsOnce() {
        post("u1", "create_lock", """{"name":"lock-2"}""")
        server.releaseLock("lock-2", stopExecution = true, broadcast = false)
        assertEquals(false to true, lockState("lock-2"))
        // The client has read the stop; the lock is gone
        assertEquals(false to false, lockState("lock-2"))
    }

    fun testUnknownLockIsInactive() {
        assertEquals(false to false, lockState("never-created"))
    }

    fun testLocalImageIsInlined() {
        val image = Files.createTempFile("pixel", ".png").toFile().apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val text = Files.createTempFile("notes", ".txt").toFile().apply { writeText("x") }
        try {
            post("u1", "custom", """{"content":"<img src=\"file://${image.path}\"><img src=\"file://${text.path}\">","label":"Image"}""")
            val content = received.payloads()[0].getAsJsonObject("content").get("content").asString
            assertTrue(content.contains("src=\"data:image/png;base64,AQID\""))
            assertTrue(content.contains("src=\"file://${text.path}\""))
        } finally {
            image.delete()
            text.delete()
        }
    }

    fun testTurningOffReleasesPort() {
        DumpieSettings.get().rayEnabled = false
        server.restart()
        assertEquals(0, server.boundPort)
        assertThrows(ConnectException::class.java) { send("GET", "/_availability_check") }
    }
}
