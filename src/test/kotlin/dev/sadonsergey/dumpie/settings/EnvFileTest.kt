package dev.sadonsergey.dumpie.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EnvFileTest {
    private val lines = listOf("VAR_DUMPER_FORMAT=server", "VAR_DUMPER_SERVER=127.0.0.1:9912")

    @Test
    fun appendsMissingKeysAfterBlankLine() {
        assertEquals(
            "APP_NAME=Shop\n\nVAR_DUMPER_FORMAT=server\nVAR_DUMPER_SERVER=127.0.0.1:9912\n",
            EnvFile.merge("APP_NAME=Shop\n", lines),
        )
    }

    @Test
    fun secondMergeChangesNothing() {
        val once = EnvFile.merge("APP_NAME=Shop\n", lines)!!
        assertNull(EnvFile.merge(once, lines))
    }

    @Test
    fun replacesExistingValueInPlace() {
        assertEquals(
            "A=1\nVAR_DUMPER_FORMAT=server\nB=2\n\nVAR_DUMPER_SERVER=127.0.0.1:9912\n",
            EnvFile.merge("A=1\nVAR_DUMPER_FORMAT=html\nB=2\n", lines),
        )
    }

    @Test
    fun commentedKeyDoesNotCount() {
        assertEquals(
            "# VAR_DUMPER_FORMAT=server\n\nVAR_DUMPER_FORMAT=server\nVAR_DUMPER_SERVER=127.0.0.1:9912\n",
            EnvFile.merge("# VAR_DUMPER_FORMAT=server\n", lines),
        )
    }

    @Test
    fun spacesAroundEqualsSignMatch() {
        assertNull(EnvFile.merge("VAR_DUMPER_FORMAT = server\nVAR_DUMPER_SERVER=127.0.0.1:9912", lines))
    }

    @Test
    fun emptyFileGetsOnlyTheLines() {
        assertEquals("VAR_DUMPER_FORMAT=server\nVAR_DUMPER_SERVER=127.0.0.1:9912\n", EnvFile.merge("", lines))
    }
}
