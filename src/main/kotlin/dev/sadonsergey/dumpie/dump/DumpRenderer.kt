package dev.sadonsergey.dumpie.dump

import dev.sadonsergey.dumpie.dump.PhpUnserializer.Arr
import dev.sadonsergey.dumpie.dump.PhpUnserializer.Bool
import dev.sadonsergey.dumpie.dump.PhpUnserializer.Null
import dev.sadonsergey.dumpie.dump.PhpUnserializer.Num
import dev.sadonsergey.dumpie.dump.PhpUnserializer.Obj
import dev.sadonsergey.dumpie.dump.PhpUnserializer.Str
import dev.sadonsergey.dumpie.dump.PhpUnserializer.Value

/**
 * Renders a serialized Symfony\Component\VarDumper\Cloner\Data the way VarDumper's dumpers do,
 * following Data::dumpItem(): HTML with sf-dump-* classes (toggles handled by app.js) plus a plain-text copy.
 */
class DumpRenderer private constructor(private val data: Arr) {
    private val html = StringBuilder()
    private val text = StringBuilder()

    // Object handles already printed: later occurrences are shown collapsed, like VarDumper's soft references
    private val seen = mutableSetOf<Long>()

    private fun write(htmlPart: String, textPart: String = htmlPart) {
        html.append(htmlPart)
        text.append(textPart)
    }

    private fun item(raw: Value, depth: Int) {
        var item = raw
        if (item is Obj && item.long("type", TYPE_REF) == TYPE_REF) {
            // Hard reference wrapper: the actual value sits in "value"
            item = item.props["value"] ?: Null
        }
        if (item is Arr) {
            if (item.items.isEmpty()) return write("[]")
            item = arrayStub(item)
        }

        when (item) {
            is Obj -> stub(item, depth)
            is Str -> string(item.value, binary = false, cut = 0, depth = depth)
            is Num -> write(span("sf-dump-num", formatNumber(item.value)), formatNumber(item.value))
            is Bool -> write(span("sf-dump-const", item.value.toString()), item.value.toString())
            else -> write(span("sf-dump-const", "null"), "null")
        }
    }

    private fun stub(stub: Obj, depth: Int) {
        val type = stub.long("type", TYPE_REF)
        val cut = stub.long("cut", 0)
        val handle = stub.long("handle", 0)
        val firstSeen = stub.long("refCount", 0) == 0L || seen.add(handle)
        val position = stub.long("position", 0)
        val children = if (position > 0 && firstSeen) (data.items[position] as? Arr)?.items.orEmpty() else emptyMap()

        when (type) {
            TYPE_STRING -> string(stub.str("value"), binary = stub.long("class", 0) == STRING_BINARY, cut = cut, depth = depth)
            TYPE_ARRAY -> {
                val count = stub.long("value", 0)
                hash(span("sf-dump-note", "array:$count") + " [", "array:$count [", "]", children, stub.long("class", ARRAY_INDEXED), depth, cut)
            }
            TYPE_OBJECT -> {
                val name = stub.str("class")
                val ref = if (handle > 0) "#$handle" else ""
                val head = span("sf-dump-note", esc(name)) + " {" + span("sf-dump-ref", ref)
                if (!firstSeen) return write("$head …}", "$name {$ref …}")
                hash(head, "$name {$ref", "}", children, TYPE_OBJECT, depth, cut)
            }
            TYPE_RESOURCE -> {
                val name = "${stub.str("class")} resource"
                hash(span("sf-dump-note", esc(name)) + " {" + span("sf-dump-ref", "@$handle"), "$name {@$handle", "}", children, TYPE_RESOURCE, depth, cut)
            }
            TYPE_SCALAR -> {
                val label = ((stub.props["attr"] as? Arr)?.items?.get("value") as? Str)?.value ?: stub.str("value")
                write(span("sf-dump-const", esc(label)), label)
            }
            else -> write(span("sf-dump-const", "?"), "?")
        }
    }

    /** The cloner stores arrays compactly as [arrayType => position] with an optional [0 => cut]. */
    private fun arrayStub(compact: Arr): Obj {
        var arrayType = ARRAY_INDEXED
        var position = 0L
        compact.items.forEach { (key, value) ->
            if (key == 0L) return@forEach
            arrayType = (key as? Long) ?: arrayType
            position = (value as? Num)?.value?.toLong() ?: position
        }
        val cut = (compact.items[0L] as? Num)?.value?.toLong() ?: 0
        val count = cut + ((data.items[position] as? Arr)?.items?.size ?: 0)
        return Obj("Stub", linkedMapOf(
            "type" to Num(TYPE_ARRAY),
            "class" to Num(arrayType),
            "value" to Num(count),
            "position" to Num(position),
            "cut" to Num(cut),
        ))
    }

    private fun hash(
        htmlHead: String,
        textHead: String,
        close: String,
        children: Map<Any, Value>,
        hashType: Long,
        depth: Int,
        cut: Long,
    ) {
        write(htmlHead, textHead)
        val ellipsis = if (cut > 0) " …$cut" else ""
        if (children.isEmpty()) {
            write(span("sf-dump-ellipsis", ellipsis) + close, ellipsis + close)
            return
        }

        // Like VarDumper's default maxDepth=1: the first level is open, deeper levels start collapsed
        val open = depth < 1
        write(
            "<a class=\"sf-dump-toggle\"><span>${if (open) "▼" else "▶"}</span></a>" +
                "<samp class=\"${if (open) "sf-dump-expanded" else "sf-dump-compact"}\" data-depth=\"${depth + 1}\">",
            "",
        )
        val indent = "  ".repeat(depth + 1)
        children.forEach { (key, child) ->
            write("\n$indent")
            key(key, hashType)
            item(child, depth + 1)
        }
        write("\n" + "  ".repeat(depth) + "</samp>", "\n" + "  ".repeat(depth))
        write(span("sf-dump-ellipsis", ellipsis) + close, ellipsis + close)
    }

    private fun key(key: Any, hashType: Long) {
        when (hashType) {
            TYPE_OBJECT -> {
                val name = key.toString()
                val (sign, css, bare) = if (name.startsWith('\u0000')) {
                    val parts = name.split('\u0000')
                    val bare = parts.getOrElse(2) { "" }
                    when (parts.getOrElse(1) { "" }) {
                        "+" -> Triple("+", "sf-dump-public", bare)
                        "~" -> Triple("~", "sf-dump-meta", bare)
                        "*" -> Triple("#", "sf-dump-protected", bare)
                        else -> Triple("-", "sf-dump-private", bare)
                    }
                } else {
                    Triple("+", "sf-dump-public", name)
                }
                write("$sign${span(css, esc(bare))}: ", "$sign$bare: ")
            }
            TYPE_RESOURCE -> write(span("sf-dump-meta", esc(key.toString())) + ": ", "$key: ")
            else -> if (key is Long) {
                write(span("sf-dump-index", key.toString()) + " => ", "$key => ")
            } else {
                write("\"" + span("sf-dump-key", esc(key.toString())) + "\" => ", "\"$key\" => ")
            }
        }
    }

    private fun string(value: String, binary: Boolean, cut: Long, depth: Int) {
        val prefix = if (binary) "b" else ""
        val ellipsis = if (cut > 0) "…" else ""
        if ('\n' !in value) {
            write("$prefix\"" + span("sf-dump-str", esc(value)) + span("sf-dump-ellipsis", ellipsis) + "\"", "$prefix\"$value$ellipsis\"")
            return
        }
        // Multi-line strings use VarDumper's triple-quote block
        val indent = "  ".repeat(depth + 1)
        val body = value.lines().joinToString("\n") { indent + it }
        write(
            "$prefix\"\"\"\n" + span("sf-dump-str", esc(body)) + span("sf-dump-ellipsis", ellipsis) + "\n" + "  ".repeat(depth) + "\"\"\"",
            "$prefix\"\"\"\n$body$ellipsis\n" + "  ".repeat(depth) + "\"\"\"",
        )
    }

    private fun Obj.long(name: String, default: Long): Long = (props[name] as? Num)?.value?.toLong() ?: default

    private fun Obj.str(name: String): String = when (val v = props[name]) {
        is Str -> v.value
        is Num -> formatNumber(v.value)
        else -> ""
    }

    private fun formatNumber(n: Number): String = when (n) {
        is Double -> if (n.isFinite() && n == Math.floor(n)) "${n.toLong()}.0" else n.toString()
        else -> n.toString()
    }

    private fun span(css: String, content: String) = if (content.isEmpty()) "" else "<span class=\"$css\">$content</span>"

    private fun esc(value: String) = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    class Result(val html: String, val text: String)

    companion object {
        private const val TYPE_REF = 1L
        private const val TYPE_STRING = 2L
        private const val TYPE_ARRAY = 3L
        private const val TYPE_OBJECT = 4L
        private const val TYPE_RESOURCE = 5L
        private const val TYPE_SCALAR = 6L
        private const val STRING_BINARY = 1L
        private const val ARRAY_INDEXED = 2L

        /** [dataObject] is the unserialized Data instance. */
        fun render(dataObject: Obj): Result {
            val data = dataObject.props["data"] as? Arr ?: return Result("", "")
            val position = (dataObject.props["position"] as? Num)?.value?.toLong() ?: 0
            val key: Any = when (val k = dataObject.props["key"]) {
                is Num -> k.value.toLong()
                is Str -> k.value
                else -> 0L
            }
            val root = (data.items[position] as? Arr)?.items?.get(key) ?: Null

            val renderer = DumpRenderer(data)
            renderer.write("<pre class=\"sf-dump dumpie-dump\">", "")
            // dump($a, $b) sends one dump per argument labelled 1, 2, ...
            val label = ((dataObject.props["context"] as? Arr)?.items?.get("label") as? Str)?.value.orEmpty()
            if (label.isNotEmpty()) renderer.write(renderer.span("sf-dump-label", renderer.esc(label)) + " ", "$label ")
            renderer.item(root, 0)
            renderer.write("</pre>", "")
            return Result(renderer.html.toString(), renderer.text.toString())
        }
    }
}
