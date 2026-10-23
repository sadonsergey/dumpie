package dev.sadonsergey.dumpie.settings

import com.intellij.openapi.components.service
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.rows
import dev.sadonsergey.dumpie.LogWatcher
import dev.sadonsergey.dumpie.RayServer
import dev.sadonsergey.dumpie.dump.DumpServer
import dev.sadonsergey.dumpie.ui.DumpiePanel

/** Settings | Tools | Dumpie: IDE-wide receivers and the sources of the current project. */
class DumpieConfigurable(private val project: Project) : BoundConfigurable("Dumpie") {
    private val app = DumpieSettings.get()
    private val local = DumpieProjectSettings.get(project)
    private val detection = ProjectDetector.detect(project)

    override fun createPanel(): DialogPanel = panel {
        group("Receivers (all projects)") {
            row {
                checkBox("spatie/ray clients, port").bindSelected(app::rayEnabled)
                intTextField(1..65535).bindIntText(app::rayPort).columns(6)
            }
            row {
                checkBox("dump() / dd() via VarDumper server, port").bindSelected(app::dumpEnabled)
                intTextField(1..65535).bindIntText(app::dumpPort).columns(6)
            }
            row {
                checkBox("Show the tool window when output arrives").bindSelected(app::showToolWindowOnMessage)
            }
            row {
                checkBox("Keep the project and screen bars visible while scrolling").bindSelected(app::pinBars)
            }
        }

        group("This project") {
            row {
                checkBox("ray() output").bindSelected(local::rayEnabled)
            }.rowComment(rayStatus())

            row {
                checkBox("dump() output").bindSelected(local::dumpEnabled)
            }.rowComment(dumpStatus())

            if (detection.varDumper != null && !detection.dumpServerConfigured && detection.envFile != null) {
                row {
                    val lines = ProjectDetector.envLines(detection, app.dumpPort)
                    button("Add to .env") { SetupGuide.addToEnv(project) }
                    comment(lines.joinToString("<br>"))
                }
            }

            row {
                checkBox("Log files").bindSelected(local::logsEnabled)
            }.rowComment(
                if (detection.logPatterns.isEmpty()) "No storage/logs or var/log directory found in the project"
                else "Monolog line format, e.g. Laravel's laravel.log; only new records are shown",
            )
            row {
                textArea()
                    .bindText({ local.logPaths.orEmpty() }, { local.logPaths = it })
                    .rows(3)
                    .align(Align.FILL)
                    .comment("Glob patterns relative to the project root, one per line (wildcards in the file name only)")
            }

            row {
                button("Detect Again") {
                    project.service<DumpieProjectSettings>().applyDetection(ProjectDetector.detect(project))
                    reset()
                }
                comment("Fills this section from composer.lock, .env and the project's log directories")
            }
        }
    }

    override fun apply() {
        super.apply()
        service<RayServer>().restart()
        service<DumpServer>().restart()
        project.service<LogWatcher>().restart()
        DumpiePanel.settingsChanged()
    }

    private fun rayStatus(): String = when {
        detection.laravelRay != null -> "spatie/laravel-ray ${detection.laravelRay} found"
        detection.ray != null -> "spatie/ray ${detection.ray} found"
        else -> "spatie/ray is not in composer.lock (a global install still works)"
    }

    private fun dumpStatus(): String = when {
        detection.varDumper == null -> "symfony/var-dumper is not in composer.lock"
        detection.dumpServerConfigured -> "symfony/var-dumper ${detection.varDumper} found, VAR_DUMPER_FORMAT is set in .env"
        else -> "symfony/var-dumper ${detection.varDumper} found; dump() is sent to the IDE once VAR_DUMPER_FORMAT=server is in .env"
    }
}
