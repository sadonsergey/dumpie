package dev.sadonsergey.dumpie.dump

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DumpRendererTest {
    private fun text(name: String) = DumpFixtures.render(name).text

    @Test
    fun string() {
        assertEquals("\"hello <b>\"", text("string"))
    }

    @Test
    fun scalarsInIndexedArray() {
        assertEquals("array:6 [\n  0 => 42\n  1 => 1.5\n  2 => 2.0\n  3 => true\n  4 => null\n  5 => -7\n]", text("scalars"))
    }

    @Test
    fun nestedArrays() {
        assertEquals(
            "array:2 [\n  \"user\" => array:2 [\n    \"name\" => \"Ann\"\n    \"roles\" => array:1 [\n      0 => \"admin\"\n    ]\n  ]\n  \"n\" => 1\n]",
            text("nested"),
        )
    }

    @Test
    fun objectWithVisibilityMarks() {
        assertEquals("Order {#2\n  +id: 1042\n  #total: 9.5\n  -secret: \"x\"\n}", text("object"))
    }

    @Test
    fun repeatedObjectIsCollapsed() {
        assertEquals(
            "array:2 [\n  0 => Order {#2\n    +id: 1042\n    #total: 9.5\n    -secret: \"x\"\n  }\n  1 => Order {#2 …}\n]",
            text("same_object"),
        )
    }

    @Test
    fun multilineString() {
        assertEquals("\"\"\"\n  first\n  second\n\"\"\"", text("multiline"))
    }

    @Test
    fun binaryAndUnicodeStrings() {
        // Bytes that are not UTF-8 are shown escaped
        assertEquals("b\"\\xFF\\xFEA\"", text("binary"))
        assertEquals("\"привет 🙂\"", text("unicode"))
    }

    @Test
    fun cutArrayShowsTotalAndRest() {
        assertEquals("array:1 [\n  \"list\" => array:5 [\n    0 => 1\n    1 => 2\n    2 => 3\n   …2]\n]", text("cut"))
    }

    @Test
    fun emptyArrayAndLabel() {
        assertEquals("[]", text("empty"))
        assertEquals("2 \"second arg\"", text("labelled"))
    }

    @Test
    fun htmlIsEscapedAndCollapsible() {
        val string = DumpFixtures.render("string").html
        assertTrue(string.startsWith("<pre class=\"sf-dump dumpie-dump\">"))
        assertTrue(string.contains("hello &lt;b&gt;"))
        assertFalse(string.contains("<b>"))

        // The first level is open, deeper levels start collapsed
        val nested = DumpFixtures.render("nested").html
        assertTrue(nested.contains("<samp class=\"sf-dump-expanded\" data-depth=\"1\">"))
        assertTrue(nested.contains("<samp class=\"sf-dump-compact\" data-depth=\"2\">"))
        assertTrue(nested.contains("<a class=\"sf-dump-toggle\">"))
    }
}
