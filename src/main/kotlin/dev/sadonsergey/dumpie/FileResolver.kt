package dev.sadonsergey.dumpie

import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import java.io.File

object FileResolver {
    /**
     * Finds a local file for a path reported by PHP. Paths from containers (e.g. /var/www/html/app/User.php)
     * are matched by the longest suffix that exists under the project root.
     */
    fun resolve(project: Project, path: String): VirtualFile? {
        val fs = LocalFileSystem.getInstance()
        fs.findFileByPath(path)?.let { return it }

        val base = project.basePath ?: return null
        val parts = path.trim('/').split('/')
        // Keep at least two segments so a bare "index.php" does not match a random file
        for (from in 0..parts.size - minOf(2, parts.size)) {
            fs.findFileByPath(base + "/" + parts.drop(from).joinToString("/"))?.let { return it }
        }
        return null
    }

    /**
     * How many trailing segments of [path] exist under [basePath] (0 if none, at least two are required).
     * Uses plain java.io so it also works for projects opened in another IDE.
     */
    fun suffixMatch(basePath: String, path: String): Int {
        val parts = path.trim('/').split('/')
        for (from in 0..parts.size - minOf(2, parts.size)) {
            if (File(basePath, parts.drop(from).joinToString("/")).isFile) return parts.size - from
        }
        return 0
    }

    /** The local copy of a file PHP reported, found like [resolve] but with plain java.io, so any thread can use it. */
    fun localFile(project: Project, path: String): File? {
        File(path).takeIf { it.isFile }?.let { return it }
        val base = project.basePath ?: return null
        val segments = suffixMatch(base, path).takeIf { it > 0 } ?: return null
        return File(base, path.trim('/').split('/').takeLast(segments).joinToString("/"))
    }

    fun open(project: Project, path: String, line: Int) {
        val file = resolve(project, path) ?: return
        OpenFileDescriptor(project, file, maxOf(line - 1, 0), 0).navigate(true)
    }
}
