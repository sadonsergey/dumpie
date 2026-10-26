package dev.sadonsergey.dumpie.settings

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service

/** IDE-wide receivers: they listen on fixed ports for every open project. */
@Service(Service.Level.APP)
@State(name = "DumpieSettings", storages = [Storage("dumpie.xml")])
class DumpieSettings : SimplePersistentStateComponent<DumpieSettings.State>(State()) {
    class State : BaseState() {
        var rayEnabled by property(true)
        var rayPort by property(23517)
        var dumpEnabled by property(true)
        var dumpPort by property(9912)
        var showToolWindowOnMessage by property(true)
        var pinBars by property(true)
        var whiteBackgroundInLightThemes by property(true)
        var showGutterIcons by property(true)
        var grayUncolored by property(true)
        var errorBadge by property(true)
        var showQueryStats by property(true)
        var markRepeatedQueries by property(true)
        var markSlowQueries by property(true)
        var slowQueryMs by property(100)
    }

    companion object {
        fun get(): State = service<DumpieSettings>().state
    }
}
