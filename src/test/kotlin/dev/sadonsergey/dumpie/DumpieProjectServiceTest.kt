package dev.sadonsergey.dumpie

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class DumpieProjectServiceTest : BasePlatformTestCase() {
    private val session get() = project.service<DumpieProjectService>()
    private val seen = mutableListOf<String>()
    private val listener: (String) -> Unit = { seen += it }

    override fun tearDown() {
        try {
            session.detach(listener)
            session.clear()
        } finally {
            super.tearDown()
        }
    }

    private fun request(uuid: String, vararg types: String, meta: JsonObject = JsonObject()) = JsonObject().apply {
        addProperty("uuid", uuid)
        add("payloads", JsonArray().apply {
            types.forEach { type -> add(JsonObject().apply { addProperty("type", type); add("content", JsonObject()) }) }
        })
        add("meta", meta)
    }

    private fun uuids() = seen.map { JsonParser.parseString(it).asJsonObject.get("uuid").asString }

    fun testReceiveTimeIsStampedOnce() {
        session.attach(listener)
        session.receive(request("a", "log"))
        session.receive(request("b", "log", meta = JsonObject().apply { addProperty("dumpie_received_at", 1234L) }))

        val stamped = JsonParser.parseString(seen[0]).asJsonObject.getAsJsonObject("meta").get("dumpie_received_at").asLong
        assertTrue(stamped > 0)
        // A request forwarded by another IDE keeps the time it got there
        assertEquals(1234L, JsonParser.parseString(seen[1]).asJsonObject.getAsJsonObject("meta").get("dumpie_received_at").asLong)
    }

    fun testHistoryIsReplayedThenFollowed() {
        session.receive(request("a", "log"))
        session.receive(request("b", "log"))
        session.attach(listener)
        session.receive(request("c", "log"))
        assertEquals(listOf("a", "b", "c"), uuids())
    }

    fun testReplayKeepsTimes() {
        session.receive(request("a", "log"))
        session.attach(listener)
        val first = seen.single()
        session.detach(listener)
        seen.clear()
        session.attach(listener)
        assertEquals(first, seen.single())
    }

    fun testReattachingDoesNotDuplicate() {
        session.attach(listener)
        session.attach(listener)
        session.receive(request("a", "log"))
        assertEquals(listOf("a"), uuids())
    }

    fun testDetachStopsDelivery() {
        session.attach(listener)
        session.detach(listener)
        session.receive(request("a", "log"))
        assertTrue(seen.isEmpty())
    }

    fun testClearAllDropsEarlierHistory() {
        session.receive(request("a", "log"))
        session.receive(request("b", "clear_all"))
        session.receive(request("c", "log"))
        session.attach(listener)
        assertEquals(listOf("b", "c"), uuids())
    }

    fun testHistoryIsBounded() {
        repeat(1005) { session.receive(request("r$it", "log")) }
        session.attach(listener)
        assertEquals(1000, seen.size)
        assertEquals("r5", uuids().first())
    }

    fun testClearEmptiesHistory() {
        session.receive(request("a", "log"))
        session.clear()
        session.attach(listener)
        assertTrue(seen.isEmpty())
    }

    fun testActivityCountsOnlyNewOutput() {
        val activity = mutableListOf<Pair<Project, Boolean>>()
        DumpieProjectService.addActivityListener(testRootDisposable) { p, cleared -> activity += p to cleared }

        session.receive(request("a", "color", "label"))
        session.receive(request("b", "new_screen"))
        assertTrue(activity.isEmpty())

        session.receive(request("c", "log", "color"))
        session.receive(request("d", "clear_all"))
        session.clear()
        assertEquals(listOf(project to false, project to true, project to true), activity)
    }
}
