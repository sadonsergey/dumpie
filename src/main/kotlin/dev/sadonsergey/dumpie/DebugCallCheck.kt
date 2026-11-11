package dev.sadonsergey.dumpie

import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.vcs.CheckinProjectPanel
import com.intellij.openapi.vcs.VcsException
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.CommitContext
import com.intellij.openapi.vcs.checkin.CheckinHandler
import com.intellij.openapi.vcs.checkin.CheckinHandlerFactory
import com.intellij.openapi.vcs.checkin.CommitCheck
import com.intellij.openapi.vcs.checkin.CommitInfo
import com.intellij.openapi.vcs.checkin.CommitProblem
import com.intellij.openapi.vcs.checkin.CommitProblemWithDetails
import com.intellij.openapi.vfs.VirtualFile
import dev.sadonsergey.dumpie.settings.DumpieSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Stops a commit whose added lines call dump(), dd(), ray() and friends; the commit view offers Commit Anyway. */
class DebugCallCheckFactory : CheckinHandlerFactory() {
    override fun createHandler(panel: CheckinProjectPanel, commitContext: CommitContext): CheckinHandler = DebugCallCheck()
}

private class DebugCallCheck : CheckinHandler(), CommitCheck {
    override fun getExecutionOrder() = CommitCheck.ExecutionOrder.EARLY

    override fun isEnabled() = DumpieSettings.get().checkDebugCallsOnCommit

    // Loading the committed revisions may run the VCS
    override suspend fun runCheck(commitInfo: CommitInfo): CommitProblem? {
        val calls = withContext(Dispatchers.IO) { commitInfo.committedChanges.flatMap(::addedCalls) }
        return if (calls.isEmpty()) null else DebugCallsProblem(calls)
    }

    private fun addedCalls(change: Change): List<DebugCall> {
        val after = change.afterRevision ?: return emptyList()
        val path = after.file.path
        if (path.substringAfterLast('.').lowercase() !in EXTENSIONS || IGNORED_DIRS.any { it in path }) return emptyList()
        val file = after.file.virtualFile ?: return emptyList()
        val text = try {
            after.content
        } catch (e: VcsException) {
            null
        } ?: return emptyList()
        if (text.length > MAX_LENGTH) return emptyList()

        // Lines already in the previous revision are not new, even when they moved
        val before = try {
            change.beforeRevision?.content
        } catch (e: VcsException) {
            null
        }
        val existing = before.orEmpty().lines().map { it.trim() }.groupingBy { it }.eachCount().toMutableMap()
        return text.lines().mapIndexedNotNull { index, line ->
            val trimmed = line.trim()
            val call = CALL.find(line)?.takeIf { !isComment(trimmed) && !DEFINITION.containsMatchIn(line) } ?: return@mapIndexedNotNull null
            val seen = existing[trimmed] ?: 0
            if (seen > 0) {
                existing[trimmed] = seen - 1
                return@mapIndexedNotNull null
            }
            DebugCall(file, index, call.range.first, call.value.trimStart('-', '>').substringBefore('(').trim() + "()", trimmed)
        }
    }

    private fun isComment(line: String) = COMMENT_STARTS.any { line.startsWith(it) }

    companion object {
        // A call of a global debug helper, or ->dump() / ->dd() on a collection or a query builder
        private val CALL = Regex("""(?<![\w$>:])(?:dd|ddd|dump|ray|rd|var_dump)\s*\(|->(?:dd|dump)\s*\(\s*\)""")
        private val DEFINITION = Regex("""\bfunction\s+(?:dd|ddd|dump|ray|rd)\b""")
        private val COMMENT_STARTS = listOf("//", "#", "*", "/*", "{{--", "<!--")
        private val EXTENSIONS = setOf("php", "phtml", "twig", "js", "jsx", "mjs", "cjs", "ts", "tsx", "vue")
        private val IGNORED_DIRS = listOf("/vendor/", "/node_modules/")
        private const val MAX_LENGTH = 1_000_000
    }
}

private class DebugCall(val file: VirtualFile, val line: Int, val column: Int, val name: String, val text: String) {
    val location get() = "${file.name}:${line + 1}"

    fun navigate(project: Project) = OpenFileDescriptor(project, file, line, column).navigate(true)

    // Shown by the popup list
    override fun toString() = "$location   ${text.take(80)}"
}

private class DebugCallsProblem(private val calls: List<DebugCall>) : CommitProblemWithDetails {
    override val text: String
        get() {
            val first = calls.first()
            val more = if (calls.size > 1) " and ${calls.size - 1} more" else ""
            return "Debug call ${first.name} in ${first.location}$more"
        }

    override val showDetailsAction: String get() = if (calls.size == 1) "Show" else "Show all"

    override fun showDetails(project: Project) {
        if (calls.size == 1) return calls.first().navigate(project)
        JBPopupFactory.getInstance().createPopupChooserBuilder(calls)
            .setTitle("Debug Calls in the Commit")
            .setItemChosenCallback { it.navigate(project) }
            .createPopup()
            .showCenteredInCurrentWindow(project)
    }
}
