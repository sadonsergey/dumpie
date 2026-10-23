package dev.sadonsergey.dumpie.dump

import java.util.Base64

/**
 * Lines written by Symfony VarDumper's ServerDumper (base64(serialize([Data, context]))), generated with
 * symfony/var-dumper 8.1 into src/test/resources/dumps.
 */
object DumpFixtures {
    fun line(name: String): String = DumpFixtures::class.java.getResource("/dumps/$name.txt")!!.readText().trim()

    fun message(name: String): PhpUnserializer.Value = PhpUnserializer.parse(Base64.getDecoder().decode(line(name)))

    fun render(name: String): DumpRenderer.Result {
        val data = (message(name) as PhpUnserializer.Arr).items[0L] as PhpUnserializer.Obj
        return DumpRenderer.render(data)
    }
}
