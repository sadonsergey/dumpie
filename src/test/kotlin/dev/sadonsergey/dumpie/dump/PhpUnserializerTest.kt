package dev.sadonsergey.dumpie.dump

import dev.sadonsergey.dumpie.dump.PhpUnserializer.Arr
import dev.sadonsergey.dumpie.dump.PhpUnserializer.Bool
import dev.sadonsergey.dumpie.dump.PhpUnserializer.Null
import dev.sadonsergey.dumpie.dump.PhpUnserializer.Num
import dev.sadonsergey.dumpie.dump.PhpUnserializer.Obj
import dev.sadonsergey.dumpie.dump.PhpUnserializer.Str
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PhpUnserializerTest {
    private fun parse(text: String) = PhpUnserializer.parse(text.toByteArray(Charsets.UTF_8))

    @Test
    fun scalars() {
        assertEquals(Null, parse("N;"))
        assertEquals(Bool(true), parse("b:1;"))
        assertEquals(Bool(false), parse("b:0;"))
        assertEquals(Num(-7L), parse("i:-7;"))
        assertEquals(Num(1.5), parse("d:1.5;"))
        assertEquals(Num(Double.POSITIVE_INFINITY), parse("d:INF;"))
        assertTrue(((parse("d:NAN;") as Num).value as Double).isNaN())
    }

    @Test
    fun stringLengthIsInBytes() {
        assertEquals(Str("héllo"), parse("s:6:\"héllo\";"))
    }

    @Test
    fun binaryStringShowsEscapedBytes() {
        val bytes = "s:3:\"".toByteArray() + byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 'A'.code.toByte()) + "\";".toByteArray()
        assertEquals(Str("\\xFF\\xFEA"), PhpUnserializer.parse(bytes))
    }

    @Test
    fun arrayKeepsKeysAndOrder() {
        val arr = parse("a:2:{i:0;s:1:\"a\";s:1:\"k\";i:5;}") as Arr
        assertEquals(listOf<Any>(0L, "k"), arr.items.keys.toList())
        assertEquals(Str("a"), arr.items[0L])
        assertEquals(Num(5L), arr.items["k"])
    }

    @Test
    fun objectPropertiesLoseVisibilityPrefixes() {
        val obj = parse("O:5:\"Order\":3:{s:2:\"id\";i:1;s:8:\"\u0000*\u0000total\";d:9.5;s:13:\"\u0000Order\u0000secret\";s:1:\"x\";}") as Obj
        assertEquals("Order", obj.className)
        assertEquals(listOf("id", "total", "secret"), obj.props.keys.toList())
        assertEquals(Num(9.5), obj.props["total"])
    }

    @Test
    fun referencesPointToEarlierValues() {
        val objects = parse("a:2:{i:0;O:8:\"stdClass\":0:{}i:1;r:2;}") as Arr
        assertSame(objects.items[0L], objects.items[1L])

        val values = parse("a:2:{i:0;i:5;i:1;R:2;}") as Arr
        assertEquals(Num(5L), values.items[1L])
    }

    @Test(expected = IllegalArgumentException::class)
    fun unsupportedTypeFails() {
        parse("C:3:\"Foo\":0:{}")
    }
}
