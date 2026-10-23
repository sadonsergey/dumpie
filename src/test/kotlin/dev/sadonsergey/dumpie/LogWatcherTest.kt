package dev.sadonsergey.dumpie

import com.intellij.openapi.components.service
import com.intellij.openapi.util.io.FileUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.sadonsergey.dumpie.settings.DumpieProjectSettings
import java.io.File
import java.nio.file.Files

class LogWatcherTest : BasePlatformTestCase() {
    private val watcher get() = project.service<LogWatcher>()
    private val session get() = project.service<DumpieProjectService>()
    private val received = Received()
    private lateinit var dir: File
    private lateinit var log: File

    override fun setUp() {
        super.setUp()
        dir = Files.createTempDirectory("dumpie-logs").toFile()
        log = File(dir, "laravel.log").apply { writeText("[2026-10-23 11:59:00] local.INFO: Old record\n") }
        DumpieProjectSettings.get(project).apply {
            logsEnabled = true
            logPaths = "${dir.path}/*.log"
        }
        session.attach(received.listener)
        watcher.restart()
        // The first scan takes the existing content as history
        Thread.sleep(400)
    }

    override fun tearDown() {
        try {
            DumpieProjectSettings.get(project).apply {
                logsEnabled = false
                logPaths = ""
            }
            watcher.restart()
            DumpieProjectSettings.get(project).logsEnabled = true
            session.detach(received.listener)
            session.clear()
            FileUtil.delete(dir)
        } finally {
            super.tearDown()
        }
    }

    private fun content(index: Int) = received.payloads(index)[0].getAsJsonObject("content")

    private fun line(index: Int) = received.payloads(index)[0].getAsJsonObject("origin").get("line_number").asInt

    fun testNewRecordsWithStackTrace() {
        log.appendText(
            "[2026-10-23 12:00:01] local.ERROR: Boom {\"id\":1}\n#0 /app/x.php(12): foo()\n#1 {main}\n" +
                "[2026-10-23 12:00:02] local.INFO: Done\n",
        )
        waitUntil { received.requests.size == 2 }

        assertEquals("Boom {\"id\":1}", content(0).get("value").asString)
        assertEquals("local.ERROR", content(0).get("level").asString)
        assertEquals("#0 /app/x.php(12): foo()\n#1 {main}", content(0).get("context").asString)
        assertEquals(listOf("application_log", "color"), received.types(0))
        assertEquals("red", received.payloads(0)[1].getAsJsonObject("content").get("color").asString)
        assertEquals(2, line(0))

        assertEquals("Done", content(1).get("value").asString)
        assertEquals(listOf("application_log"), received.types(1))
        assertEquals(5, line(1))
    }

    fun testNewFileIsReadFromStart() {
        File(dir, "worker.log").writeText("[2026-10-23T12:00:01.000000+00:00] worker.WARNING: Slow job\n")
        waitUntil { received.requests.size == 1 }
        assertEquals("worker.WARNING", content(0).get("level").asString)
        assertEquals(1, line(0))
        assertEquals("orange", received.payloads()[1].getAsJsonObject("content").get("color").asString)
    }

    fun testTruncatedFileStartsOver() {
        log.writeText("[2026-10-23 12:00:03] local.DEBUG: Fresh\n")
        waitUntil { received.requests.size == 1 }
        assertEquals("Fresh", content(0).get("value").asString)
        assertEquals(1, line(0))
    }

    fun testOtherFilesAreIgnored() {
        File(dir, "notes.txt").writeText("[2026-10-23 12:00:01] local.ERROR: Not a log\n")
        Thread.sleep(1500)
        assertTrue(received.requests.isEmpty())
    }

    fun testTurnedOffLogsAreNotFollowed() {
        DumpieProjectSettings.get(project).logsEnabled = false
        watcher.restart()
        log.appendText("[2026-10-23 12:00:01] local.ERROR: Unseen\n")
        Thread.sleep(1500)
        assertTrue(received.requests.isEmpty())
    }
}
