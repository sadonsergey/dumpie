package dev.sadonsergey.dumpie.dump

import com.intellij.openapi.components.service
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.sadonsergey.dumpie.DumpieProjectService
import dev.sadonsergey.dumpie.Received
import dev.sadonsergey.dumpie.freePort
import dev.sadonsergey.dumpie.settings.DumpieProjectSettings
import dev.sadonsergey.dumpie.settings.DumpieSettings
import dev.sadonsergey.dumpie.waitUntil
import java.net.Socket

class DumpServerTest : BasePlatformTestCase() {
    private val server get() = service<DumpServer>()
    private val session get() = project.service<DumpieProjectService>()
    private val received = Received()
    private var port = 0

    override fun setUp() {
        super.setUp()
        port = freePort()
        DumpieSettings.get().dumpPort = port
        server.restart()
        session.attach(received.listener)
    }

    override fun tearDown() {
        try {
            session.detach(received.listener)
            session.clear()
            DumpieProjectSettings.get(project).dumpEnabled = true
            DumpieSettings.get().dumpEnabled = false
            server.restart()
            DumpieSettings.get().dumpEnabled = true
            DumpieSettings.get().dumpPort = 9912
        } finally {
            super.tearDown()
        }
    }

    // PHP keeps one connection per process and writes a line per dump
    private fun send(vararg lines: String) = Socket("127.0.0.1", port).use { socket ->
        socket.getOutputStream().apply {
            lines.forEach { write((it + "\n").toByteArray()) }
            flush()
        }
    }

    fun testDumpReachesSession() {
        send(DumpFixtures.line("string"))
        waitUntil { received.requests.size == 1 }
        assertEquals(listOf("log", "label"), received.types())
        assertTrue(received.payloads()[0].getAsJsonObject("content").getAsJsonArray("values")[0].asString.contains("hello &lt;b&gt;"))
    }

    fun testSeveralDumpsOverOneConnection() {
        send(DumpFixtures.line("nested"), DumpFixtures.line("object"))
        waitUntil { received.requests.size == 2 }
    }

    fun testBrokenLineIsSkipped() {
        send("not a dump", DumpFixtures.line("nested"))
        waitUntil { received.requests.size == 1 }
        Thread.sleep(200)
        assertEquals(1, received.requests.size)
    }

    fun testDumpOutputCanBeTurnedOffPerProject() {
        DumpieProjectSettings.get(project).dumpEnabled = false
        send(DumpFixtures.line("nested"))
        Thread.sleep(500)
        assertTrue(received.requests.isEmpty())
    }
}
