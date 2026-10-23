package dev.sadonsergey.dumpie.dump

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * Parser for PHP's serialize() format, enough to read Symfony VarDumper payloads.
 * Strings stay as raw bytes decoded to UTF-8; objects keep their class and properties
 * with visibility prefixes ("\u0000Class\u0000name", "\u0000*\u0000name") stripped.
 */
class PhpUnserializer private constructor(private val bytes: ByteArray) {
    sealed interface Value
    data object Null : Value
    data class Bool(val value: Boolean) : Value
    data class Num(val value: Number) : Value
    data class Str(val value: String) : Value
    class Arr(val items: LinkedHashMap<Any, Value>) : Value
    class Obj(val className: String, val props: LinkedHashMap<String, Value>) : Value

    private var pos = 0

    // serialize() numbers every value (but not array keys) for r:/R: back-references
    private val slots = mutableListOf<Value>()

    private fun value(): Value {
        val type = bytes[pos].toInt().toChar()
        pos += 2 // type and ':' (or ';' for N)
        return when (type) {
            'N' -> Null.also { slots += it }
            'b' -> Bool(until(';') == "1").also { slots += it }
            'i' -> Num(until(';').toLong()).also { slots += it }
            'd' -> Num(parseDouble(until(';'))).also { slots += it }
            's' -> Str(string()).also { pos++; slots += it }
            'E' -> Str(string()).also { pos++; slots += it }
            'a' -> array()
            'O' -> obj()
            'r', 'R' -> slots[until(';').toInt() - 1].also { if (type == 'r') slots += it }
            else -> throw IllegalArgumentException("Unsupported serialized type '$type' at $pos")
        }
    }

    private fun array(): Arr {
        val count = until(':').toInt()
        pos++ // '{'
        val arr = Arr(LinkedHashMap())
        slots += arr
        repeat(count) {
            val key = key()
            arr.items[key] = value()
        }
        pos++ // '}'
        return arr
    }

    private fun obj(): Obj {
        val className = string()
        pos++ // ':'
        val count = until(':').toInt()
        pos++ // '{'
        val obj = Obj(className, LinkedHashMap())
        slots += obj
        repeat(count) {
            val name = key().toString().substringAfterLast('\u0000')
            obj.props[name] = value()
        }
        pos++ // '}'
        return obj
    }

    private fun key(): Any {
        val type = bytes[pos].toInt().toChar()
        pos += 2
        return when (type) {
            'i' -> until(';').toLong()
            's' -> string().also { pos++ }
            else -> throw IllegalArgumentException("Unsupported key type '$type' at $pos")
        }
    }

    /** Reads `len:"..."` where len is in bytes. */
    private fun string(): String {
        val length = until(':').toInt()
        pos++ // opening quote
        val value = decode(pos, length)
        pos += length + 1 // closing quote
        return value
    }

    /** UTF-8 when valid; otherwise (binary strings) non-ASCII bytes are shown as \xNN like VarDumper does. */
    private fun decode(offset: Int, length: Int): String {
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            decoder.decode(ByteBuffer.wrap(bytes, offset, length)).toString()
        } catch (e: CharacterCodingException) {
            buildString {
                for (i in offset until offset + length) {
                    val b = bytes[i].toInt() and 0xFF
                    if (b in 0x20..0x7E || b == 0x0A || b == 0x09) append(b.toChar()) else append("\\x%02X".format(b))
                }
            }
        }
    }

    private fun until(stop: Char): String {
        val start = pos
        while (bytes[pos].toInt().toChar() != stop) pos++
        return String(bytes, start, pos++ - start, Charsets.US_ASCII)
    }

    private fun parseDouble(text: String): Double = when (text) {
        "INF" -> Double.POSITIVE_INFINITY
        "-INF" -> Double.NEGATIVE_INFINITY
        "NAN" -> Double.NaN
        else -> text.toDouble()
    }

    companion object {
        fun parse(bytes: ByteArray): Value = PhpUnserializer(bytes).value()
    }
}
