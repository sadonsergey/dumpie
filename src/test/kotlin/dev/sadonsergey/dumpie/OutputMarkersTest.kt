package dev.sadonsergey.dumpie

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.impl.DocumentMarkupModel
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.sadonsergey.dumpie.settings.DumpieSettings
import java.io.File

class OutputMarkersTest : BasePlatformTestCase() {
    private val session get() = project.service<DumpieProjectService>()
    private lateinit var file: File

    override fun setUp() {
        super.setUp()
        file = File(project.basePath, "OrderController.php").apply { parentFile.mkdirs(); writeText("<?php\n\ndump(\$order);\nray('done');\n") }
    }

    override fun tearDown() {
        try {
            session.clear()
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            DumpieSettings.get().showGutterIcons = true
            file.delete()
        } finally {
            super.tearDown()
        }
    }

    private fun request(uuid: String, line: Int, payload: String) = JsonParser.parseString(
        """{"uuid":"$uuid","payloads":[{"type":"log","content":$payload,"origin":{"file":"${file.path}","line_number":$line}}],"meta":{}}""",
    ).asJsonObject

    private fun open() = myFixture.openFileInEditor(LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file)!!)

    private fun icons() = DocumentMarkupModel.forDocument(myFixture.editor.document, project, true).allHighlighters
        .filter { it.isValid && it.gutterIconRenderer != null }
        .associate { myFixture.editor.document.getLineNumber(it.startOffset) + 1 to it.gutterIconRenderer!!.tooltipText.orEmpty() }

    private fun receive(request: JsonObject) {
        session.receive(request)
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
    }

    fun testIconOnTheLineThatSentOutput() {
        open()
        receive(request("a", 3, """{"values":["first"]}"""))
        receive(request("b", 3, """{"values":["second"]}"""))
        val icons = icons()
        assertEquals(setOf(3), icons.keys)
        assertTrue(icons[3]!!, icons[3]!!.contains("2 outputs") && icons[3]!!.contains("second"))
    }

    fun testFileOpenedLaterGetsItsIcons() {
        receive(request("a", 4, """{"values":["done"]}"""))
        assertNull(FileDocumentManager.getInstance().getCachedDocument(LocalFileSystem.getInstance().findFileByIoFile(file)!!)
            ?.let { DocumentMarkupModel.forDocument(it, project, false)?.allHighlighters?.firstOrNull() })
        open()
        assertEquals(setOf(4), icons().keys)
    }

    fun testClearingTheSessionRemovesIcons() {
        open()
        receive(request("a", 3, """{"values":["x"]}"""))
        session.clear()
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        assertTrue(icons().isEmpty())
    }

    fun testIconsCanBeTurnedOff() {
        DumpieSettings.get().showGutterIcons = false
        open()
        receive(request("a", 3, """{"values":["x"]}"""))
        assertTrue(icons().isEmpty())
    }

    fun testSummaries() {
        fun content(json: String) = JsonParser.parseString(json).asJsonObject
        assertEquals("Query: select * from orders", OutputMarkers.summary("executed_query", content("""{"sql":"select *\n  from orders"}""")))
        assertEquals("Boom: Card declined", OutputMarkers.summary("exception", content("""{"class":"App\\Exceptions\\Boom","message":"Card declined"}""")))
        assertEquals("App\\Models\\Order {#5 +id: 1042 }", OutputMarkers.summary("log", content("""{"values":["<pre>html</pre>"],"meta":[{"clipboard_data":"App\\Models\\Order {#5\n  +id: 1042\n}"}]}""")))
        assertNull(OutputMarkers.summary("color", content("""{"color":"red"}""")))
        assertEquals(100, OutputMarkers.summary("log", content("""{"values":["${"x".repeat(300)}"]}"""))!!.length)
    }
}
