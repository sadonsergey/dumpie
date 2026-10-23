package dev.sadonsergey.dumpie.ui

import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionPromoter
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.PlatformDataKeys
import com.intellij.openapi.project.DumbAware
import dev.sadonsergey.dumpie.DumpieProjectService

/**
 * Keyboard actions of the feed (Tools | Dumpie, Keymap). Their default shortcuts work in the Dumpie tool window only,
 * so ⌘K still commits in the editor; from the menu or Find Action they work anywhere.
 */
abstract class DumpieCommandAction(private val command: String) : AnAction(), DumbAware {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabled = project != null && DumpiePanel.exists(project) &&
            (e.place != ActionPlaces.KEYBOARD_SHORTCUT || inDumpie(e.dataContext))
    }

    override fun actionPerformed(e: AnActionEvent) {
        e.project?.let { DumpiePanel.command(it, command) }
    }

    companion object {
        fun inDumpie(context: DataContext) = PlatformDataKeys.TOOL_WINDOW.getData(context)?.id == DumpieProjectService.TOOL_WINDOW_ID
    }
}

class ClearAllAction : DumpieCommandAction("clearAll")
class ClearScreenAction : DumpieCommandAction("clearScreen")
class NewScreenAction : DumpieCommandAction("newScreen")
class PauseAction : DumpieCommandAction("pause")
class SearchAction : DumpieCommandAction("search")
class NextScreenAction : DumpieCommandAction("nextScreen")
class PreviousScreenAction : DumpieCommandAction("previousScreen")

/** Inside the Dumpie tool window its actions win over IDE actions with the same shortcut (Commit, Find, ...). */
class DumpieActionPromoter : ActionPromoter {
    override fun promote(actions: List<AnAction>, context: DataContext): List<AnAction> =
        if (DumpieCommandAction.inDumpie(context)) actions.filterIsInstance<DumpieCommandAction>() else emptyList()
}
