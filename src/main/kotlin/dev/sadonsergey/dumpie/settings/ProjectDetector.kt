package dev.sadonsergey.dumpie.settings

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.project.Project
import java.io.File

/** Inspects composer.lock, .env and the log directories of a project. */
object ProjectDetector {
    data class Detection(
        val ray: String?,
        val laravelRay: String?,
        val varDumper: String?,
        val laravel: String?,
        val sail: Boolean,
        val envFile: File?,
        val dumpServerConfigured: Boolean,
        val logPatterns: List<String>,
    )

    fun detect(project: Project): Detection = detect(project.basePath?.let(::File))

    internal fun detect(root: File?): Detection {
        val packages = root?.let { readPackages(File(it, "composer.lock")) }.orEmpty()
        val env = root?.let { File(it, ".env") }?.takeIf { it.isFile }
        val format = env?.let { readEnv(it)["VAR_DUMPER_FORMAT"] }

        return Detection(
            ray = packages["spatie/ray"],
            laravelRay = packages["spatie/laravel-ray"],
            varDumper = packages["symfony/var-dumper"],
            laravel = packages["laravel/framework"],
            sail = "laravel/sail" in packages,
            envFile = env,
            dumpServerConfigured = format == "server" || format?.startsWith("tcp://") == true,
            logPatterns = LOG_DIRS.filter { root != null && File(root, it).isDirectory }.map { "$it/*.log" },
        )
    }

    /** Lines to add to .env so dump() sends to the IDE; containers reach the host through host.docker.internal. */
    fun envLines(detection: Detection, port: Int): List<String> =
        listOf("VAR_DUMPER_FORMAT=server") +
            if (detection.sail) listOf("VAR_DUMPER_SERVER=host.docker.internal:$port") else listOf("VAR_DUMPER_SERVER=127.0.0.1:$port")

    private fun readPackages(lock: File): Map<String, String> {
        if (!lock.isFile) return emptyMap()
        val json = try {
            JsonParser.parseString(lock.readText()) as? JsonObject
        } catch (e: Exception) {
            null
        } ?: return emptyMap()

        return listOf("packages", "packages-dev")
            .flatMap { (json.get(it) as? JsonArray)?.mapNotNull { p -> p as? JsonObject }.orEmpty() }
            .mapNotNull { p ->
                val name = p.get("name")?.asString ?: return@mapNotNull null
                name to (p.get("version")?.asString ?: "")
            }
            .toMap()
    }

    private fun readEnv(file: File): Map<String, String> = file.readLines()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") && "=" in it }
        .associate { line ->
            val (key, value) = line.split("=", limit = 2)
            key.trim() to value.trim().trim('"', '\'')
        }

    private val LOG_DIRS = listOf("storage/logs", "var/log")
}
