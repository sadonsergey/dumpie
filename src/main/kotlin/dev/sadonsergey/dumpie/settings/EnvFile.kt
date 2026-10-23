package dev.sadonsergey.dumpie.settings

/** Edits .env text without duplicating keys. */
object EnvFile {
    /**
     * Sets every `KEY=VALUE` of [lines] in [text]: an existing uncommented key gets its value replaced in place,
     * a missing key is appended. Returns null when the text already contains all of them.
     */
    fun merge(text: String, lines: List<String>): String? {
        val result = text.split('\n').toMutableList()
        val missing = mutableListOf<String>()
        var changed = false

        lines.forEach { line ->
            val key = line.substringBefore('=').trim()
            val index = result.indexOfFirst { it.trim().startsWith("$key=") || it.trim().startsWith("$key =") }
            when {
                index < 0 -> missing += line
                result[index].trim().replace(" ", "") != line.replace(" ", "") -> {
                    result[index] = line
                    changed = true
                }
            }
        }
        if (missing.isNotEmpty()) {
            // Keep a blank line between the user's settings and ours, and end the file with a newline
            while (result.isNotEmpty() && result.last().isBlank()) result.removeAt(result.lastIndex)
            if (result.isNotEmpty()) result += ""
            result += missing
            result += ""
            changed = true
        }
        return if (changed) result.joinToString("\n") else null
    }
}
