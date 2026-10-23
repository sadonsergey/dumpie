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

    fun testClosedScreenLeavesTheHistory() {
        session.receive(request("a", "log"))
        session.receive(request("s", "new_screen"))
        session.receive(request("b", "log"))
        session.receive(request("b", "color"))
        session.receive(request("c", "log"))
        session.removeRequests(setOf("s", "b"), "\"Checkout\" closed")
        session.attach(listener)
        assertEquals(listOf("a", "c"), uuids())
    }

    fun testClearedOutputCanBeRestored() {
        session.receive(request("a", "log"))
        session.receive(request("b", "log"))
        session.clear()
        session.receive(request("c", "log"))

        // The archive lives as long as the session, so earlier tests may have left items: the newest is first
        val item = session.archived().first()
        assertEquals("Clear all", item.title)
        assertEquals(2, item.count)
        assertTrue(session.restore(item.id))
        assertTrue(session.archived().none { it.id == item.id })

        // Restored output goes back to where it was
        session.attach(listener)
        assertEquals(listOf("a", "b", "c"), uuids())
    }

    fun testClearAllFromPhpIsArchivedAndUndone() {
        session.receive(request("a", "log"))
        session.receive(request("x", "clear_all"))
        session.receive(request("b", "log"))
        val item = session.archived().first()
        assertEquals("Cleared from PHP", item.title)
        session.restore(item.id)
        session.attach(listener)
        assertEquals(listOf("a", "b"), uuids())
    }

    fun testClosedScreenIsArchivedWithItsTitle() {
        session.receive(request("a", "log"))
        session.receive(request("s", "new_screen"))
        session.receive(request("b", "log"))
        session.receive(request("b", "color"))
        session.removeRequests(setOf("s", "b"), "\"Checkout\" closed")
        val item = session.archived().first()
        assertEquals("\"Checkout\" closed", item.title)
        assertEquals(1, item.count)
        session.restore(item.id)
        session.attach(listener)
        assertEquals(listOf("a", "s", "b", "b"), uuids())
    }

    fun testArchiveKeepsTheLastTenClears() {
        repeat(12) {
            session.receive(request("r$it", "log"))
            session.clear()
        }
        assertEquals(10, session.archived().size)
        assertFalse(session.restore(-1))
    }

    fun testNothingToArchiveWhenEmpty() {
        session.clear()
        val newest = session.archived().firstOrNull()
        session.receive(request("s", "new_screen"))
        session.clear()
        assertSame(newest, session.archived().firstOrNull())
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
