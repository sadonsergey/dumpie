package dev.sadonsergey.dumpie.dump

import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** How a VarDumper message becomes a request the page renders. */
class DumpRequestTest {
    private fun request(name: String): JsonObject = DumpServer().toRequest(DumpFixtures.message(name))!!

    private fun JsonObject.payload(index: Int) = getAsJsonArray("payloads")[index].asJsonObject

    @Test
    fun dumpBecomesLogPayloadWithSource() {
        val log = request("nested").payload(0)
        assertEquals("log", log.get("type").asString)
        val origin = log.getAsJsonObject("origin")
        assertEquals("/var/www/html/app/Http/Controllers/OrderController.php", origin.get("file").asString)
        assertEquals(27, origin.get("line_number").asInt)

        val content = log.getAsJsonObject("content")
        assertTrue(content.getAsJsonArray("values")[0].asString.contains("sf-dump"))
        assertEquals(DumpFixtures.render("nested").text, content.getAsJsonArray("meta")[0].asJsonObject.get("clipboard_data").asString)
    }

    @Test
    fun httpRequestBecomesLabel() {
        val label = request("string").payload(1)
        assertEquals("label", label.get("type").asString)
        assertEquals("GET http://shop.test/orders/1042", label.getAsJsonObject("content").get("label").asString)
    }

    @Test
    fun commandLineIsShortened() {
        assertEquals("artisan queue:work --tries=3", request("scalars").payload(1).getAsJsonObject("content").get("label").asString)
    }

    @Test
    fun noLabelWithoutRequestOrCommand() {
        val request = request("nested")
        assertEquals(1, request.getAsJsonArray("payloads").size())
        assertTrue(request.get("uuid").asString.isNotEmpty())
    }
}
