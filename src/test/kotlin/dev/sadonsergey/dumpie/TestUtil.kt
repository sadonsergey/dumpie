package dev.sadonsergey.dumpie

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.PlatformTestUtil
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList

fun freePort(): Int = ServerSocket(0).use { it.localPort }

/** Waits for output delivered on pooled threads, keeping the EDT (where platform tests run) responsive. */
fun waitUntil(timeoutMs: Long = 5000, condition: () -> Boolean) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (!condition()) {
        check(System.currentTimeMillis() < deadline) { "Condition not met within $timeoutMs ms" }
        if (ApplicationManager.getApplication().isDispatchThread) PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        Thread.sleep(20)
    }
}

/** Collects what a session delivers to its panels. */
class Received {
    val requests = CopyOnWriteArrayList<JsonObject>()
    val listener: (String) -> Unit = { requests += JsonParser.parseString(it).asJsonObject }

    fun payloads(index: Int = 0) = requests[index].getAsJsonArray("payloads").map { it.asJsonObject }

    fun types(index: Int = 0) = payloads(index).map { it.get("type").asString }
}
