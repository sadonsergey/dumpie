package dev.sadonsergey.dumpie.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ProjectDetectorTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun file(path: String, text: String) = File(tmp.root, path).apply { parentFile.mkdirs(); writeText(text) }

    private fun composerLock(vararg packages: Pair<String, String>, dev: Pair<String, String>? = null) = file(
        "composer.lock",
        """{"packages": [${packages.joinToString(",") { """{"name": "${it.first}", "version": "${it.second}"}""" }}],
           "packages-dev": [${dev?.let { """{"name": "${it.first}", "version": "${it.second}"}""" }.orEmpty()}]}""",
    )

    @Test
    fun packagesFromBothSections() {
        composerLock("laravel/framework" to "v12.1.0", "symfony/var-dumper" to "v7.3.0", dev = "spatie/laravel-ray" to "1.40.0")
        val detection = ProjectDetector.detect(tmp.root)
        assertEquals("v12.1.0", detection.laravel)
        assertEquals("v7.3.0", detection.varDumper)
        assertEquals("1.40.0", detection.laravelRay)
        assertNull(detection.ray)
        assertFalse(detection.sail)
    }

    @Test
    fun brokenComposerLockIsIgnored() {
        file("composer.lock", "{not json")
        assertNull(ProjectDetector.detect(tmp.root).varDumper)
    }

    @Test
    fun dumpServerConfiguredInEnv() {
        file(".env", "APP_NAME=Shop\n# VAR_DUMPER_FORMAT=html\nVAR_DUMPER_FORMAT=\"server\"\n")
        val detection = ProjectDetector.detect(tmp.root)
        assertTrue(detection.dumpServerConfigured)
        assertEquals(File(tmp.root, ".env"), detection.envFile)
    }

    @Test
    fun tcpFormatCountsAsConfigured() {
        file(".env", "VAR_DUMPER_FORMAT=tcp://127.0.0.1:9912\n")
        assertTrue(ProjectDetector.detect(tmp.root).dumpServerConfigured)
    }

    @Test
    fun commentedFormatDoesNotCount() {
        file(".env", "# VAR_DUMPER_FORMAT=server\n")
        assertFalse(ProjectDetector.detect(tmp.root).dumpServerConfigured)
    }

    @Test
    fun logDirectories() {
        File(tmp.root, "storage/logs").mkdirs()
        assertEquals(listOf("storage/logs/*.log"), ProjectDetector.detect(tmp.root).logPatterns)
    }

    @Test
    fun noProjectRoot() {
        val detection = ProjectDetector.detect(null)
        assertNull(detection.envFile)
        assertTrue(detection.logPatterns.isEmpty())
    }

    @Test
    fun envLinesForHostAndSail() {
        assertEquals(
            listOf("VAR_DUMPER_FORMAT=server", "VAR_DUMPER_SERVER=127.0.0.1:9912"),
            ProjectDetector.envLines(ProjectDetector.detect(tmp.root), 9912),
        )
        composerLock("laravel/sail" to "v1.0.0")
        assertEquals(
            listOf("VAR_DUMPER_FORMAT=server", "VAR_DUMPER_SERVER=host.docker.internal:9913"),
            ProjectDetector.envLines(ProjectDetector.detect(tmp.root), 9913),
        )
    }

    @Test
    fun detectionFillsProjectSettings() {
        composerLock("symfony/var-dumper" to "v7.3.0")
        File(tmp.root, "var/log").mkdirs()
        val settings = DumpieProjectSettings()
        settings.applyDetection(ProjectDetector.detect(tmp.root))
        assertTrue(settings.state.dumpEnabled)
        assertTrue(settings.state.logsEnabled)
        assertEquals("var/log/*.log", settings.state.logPaths)
        assertTrue(settings.state.detected)
    }
}
