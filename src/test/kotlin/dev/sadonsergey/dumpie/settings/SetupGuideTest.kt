package dev.sadonsergey.dumpie.settings

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File

/** The light test project has a real base directory, so composer.lock and .env are plain files there. */
class SetupGuideTest : BasePlatformTestCase() {
    private val root get() = File(project.basePath!!)
    private val written = mutableListOf<File>()

    override fun tearDown() {
        try {
            written.forEach { it.delete() }
            DumpieProjectSettings.get(project).apply {
                dumpEnabled = true
                rayEnabled = true
                logsEnabled = true
                logPaths = ""
            }
        } finally {
            super.tearDown()
        }
    }

    private fun file(name: String, text: String) = File(root, name).apply {
        parentFile.mkdirs()
        writeText(text)
        written += this
        // What the IDE's file watcher does for files changed outside the IDE
        VfsUtil.markDirtyAndRefresh(false, false, false, this)
    }

    private fun composerLock(vararg packages: String) =
        file("composer.lock", """{"packages": [${packages.joinToString(",") { """{"name": "$it", "version": "1.0.0"}""" }}]}""")

    private fun state(source: String) = SetupGuide.status(project).getAsJsonObject(source).get("state").asString

    fun testDumpNeedsEnvLine() {
        composerLock("symfony/var-dumper")
        file(".env", "APP_NAME=Shop\n")
        val dump = SetupGuide.status(project).getAsJsonObject("dump")
        assertEquals("setup", dump.get("state").asString)
        assertTrue(dump.get("canAddEnv").asBoolean)
        assertEquals("VAR_DUMPER_FORMAT=server", dump.getAsJsonArray("envLines")[0].asString)
    }

    fun testDumpReadyAndOff() {
        composerLock("symfony/var-dumper")
        file(".env", "VAR_DUMPER_FORMAT=server\n")
        assertEquals("ready", state("dump"))
        DumpieProjectSettings.get(project).dumpEnabled = false
        assertEquals("off", state("dump"))
    }

    fun testDumpUnavailableWithoutVarDumper() {
        assertEquals("unavailable", state("dump"))
    }

    fun testRayPackageAndInstallCommand() {
        composerLock("laravel/framework")
        val ray = SetupGuide.status(project).getAsJsonObject("ray")
        assertEquals("setup", ray.get("state").asString)
        assertEquals("composer require spatie/laravel-ray --dev", ray.get("install").asString)

        composerLock("spatie/ray")
        assertEquals("ready", state("ray"))
        assertEquals("spatie/ray 1.0.0", SetupGuide.status(project).getAsJsonObject("ray").get("package").asString)
    }

    fun testLogPatterns() {
        DumpieProjectSettings.get(project).logPaths = "storage/logs/*.log\n\n var/log/*.log "
        val logs = SetupGuide.status(project).getAsJsonObject("logs")
        assertEquals("ready", logs.get("state").asString)
        assertEquals("storage/logs/*.log, var/log/*.log", logs.get("patterns").asString)
        DumpieProjectSettings.get(project).logPaths = ""
        assertEquals("unavailable", state("logs"))
    }

    fun testAddToEnvWritesOnce() {
        composerLock("symfony/var-dumper")
        val env = file(".env", "APP_NAME=Shop\n")
        assertTrue(SetupGuide.addToEnv(project))
        assertFalse(SetupGuide.addToEnv(project))
        FileDocumentManager.getInstance().saveAllDocuments()
        assertEquals("APP_NAME=Shop\n\nVAR_DUMPER_FORMAT=server\nVAR_DUMPER_SERVER=127.0.0.1:9912\n", env.readText())
        assertEquals("ready", state("dump"))
    }

    fun testAddToEnvWithoutEnvFile() {
        composerLock("symfony/var-dumper")
        assertFalse(SetupGuide.addToEnv(project))
    }
}
