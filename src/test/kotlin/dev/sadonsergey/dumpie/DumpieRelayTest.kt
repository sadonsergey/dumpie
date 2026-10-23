package dev.sadonsergey.dumpie

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.components.service
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.sadonsergey.dumpie.settings.DumpieSettings
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/** Several IDEs with Dumpie at once; the registry directory is the test JVM's own java.io.tmpdir. */
class DumpieRelayTest : BasePlatformTestCase() {
    private val relay get() = service<DumpieRelay>()
    private val session get() = project.service<DumpieProjectService>()
    private val received = Received()
    private val dir = File(System.getProperty("java.io.tmpdir"), "dumpie-" + System.getProperty("user.name"))
    private val own get() = JsonParser.parseString(File(dir, "${ProcessHandle.current().pid()}.json").readText()).asJsonObject
    private val written = mutableListOf<File>()

    override fun setUp() {
        super.setUp()
        relay.start()
        session.attach(received.listener)
    }

    override fun tearDown() {
        try {
            written.forEach { it.delete() }
            session.detach(received.listener)
            session.clear()
        } finally {
            super.tearDown()
        }
    }

    private fun writeEntry(pid: Long, ports: List<Int>) = File(dir, "$pid.json").apply {
        writeText("""{"pid":$pid,"port":1,"token":"t","ports":$ports,"projects":[{"name":"blog","path":"/x/blog"}]}""")
        written += this
    }

    private fun ownInstance(token: String = own.get("token").asString) =
        DumpieRelay.Instance(0, own.get("port").asInt, token, emptyList(), emptyList())

    private fun request(uuid: String) = JsonObject().apply {
        addProperty("uuid", uuid)
        add("payloads", JsonParser.parseString("""[{"type":"log","content":{"values":["hi"]}}]"""))
        add("meta", JsonObject())
    }

    fun testRegistryEntryListsOpenProjects() {
        relay.publish()
        assertEquals(ProcessHandle.current().pid(), own.get("pid").asLong)
        assertTrue(own.get("token").asString.isNotEmpty())
        val projects = own.getAsJsonArray("projects").map { it.asJsonObject.get("name").asString to it.asJsonObject.get("path").asString }
        assertTrue(projects.contains(project.name to project.basePath))
    }

    fun testClosingProjectIsLeftOut() {
        relay.publish(closing = project)
        assertTrue(own.getAsJsonArray("projects").none { it.asJsonObject.get("name").asString == project.name })
    }

    fun testOtherLiveInstancesAreRead() {
        val other = ProcessHandle.current().parent().get().pid()
        writeEntry(other, listOf(45678))
        val instance = relay.others().single()
        assertEquals(other, instance.pid)
        assertEquals("blog", instance.projects.single().name)
        assertTrue(relay.portOwnedByAnotherInstance(45678))
        assertFalse(relay.portOwnedByAnotherInstance(45679))
    }

    fun testEntriesOfFinishedProcessesAreRemoved() {
        val finished = ProcessBuilder("true").start().apply { waitFor() }.pid()
        val entry = writeEntry(finished, listOf(45678))
        assertTrue(relay.others().isEmpty())
        assertFalse(entry.exists())
    }

    fun testForwardedRequestReachesSession() {
        assertTrue(relay.forward(ownInstance(), request("fwd-1"), DumpieRouter.SOURCE_RAY))
        assertEquals("fwd-1", received.requests.single().get("uuid").asString)
    }

    fun testWrongTokenIsRejected() {
        assertFalse(relay.forward(ownInstance(token = "nope"), request("fwd-2"), DumpieRouter.SOURCE_RAY))
        assertTrue(received.requests.isEmpty())
    }

    fun testPauseCanBeReleasedFromAnotherIde() {
        val port = freePort()
        DumpieSettings.get().rayPort = port
        service<RayServer>().restart()
        try {
            val http = HttpClient.newHttpClient()
            fun call(builder: HttpRequest.Builder) = http.send(builder.build(), HttpResponse.BodyHandlers.ofString()).body()
            call(
                HttpRequest.newBuilder(URI("http://127.0.0.1:$port/"))
                    .POST(HttpRequest.BodyPublishers.ofString("""{"uuid":"p","payloads":[{"type":"create_lock","content":{"name":"remote"}}],"meta":{}}""")),
            )
            // What the other IDE sends when Stop is pressed there
            call(
                HttpRequest.newBuilder(URI("http://127.0.0.1:${own.get("port").asInt}/lock"))
                    .header("X-Dumpie-Token", own.get("token").asString)
                    .POST(HttpRequest.BodyPublishers.ofString("""{"name":"remote","stop":true}""")),
            )
            assertEquals("""{"active":false,"stop_execution":true}""", call(HttpRequest.newBuilder(URI("http://127.0.0.1:$port/locks/remote")).GET()))
        } finally {
            DumpieSettings.get().rayEnabled = false
            service<RayServer>().restart()
            DumpieSettings.get().rayEnabled = true
            DumpieSettings.get().rayPort = 23517
        }
    }
}
