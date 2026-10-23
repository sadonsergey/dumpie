package dev.sadonsergey.dumpie.ui

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.microsoft.playwright.Browser
import com.microsoft.playwright.Locator
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import com.microsoft.playwright.impl.driver.Driver
import dev.sadonsergey.dumpie.dump.DumpFixtures
import org.junit.After
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test

/** The tool window page (src/main/resources/webview) in headless Chromium, driven through its window.dumpie API. */
class WebviewTest {
    companion object {
        private lateinit var playwright: Playwright
        private lateinit var browser: Browser

        @BeforeClass
        @JvmStatic
        fun startBrowser() {
            // By default the first start downloads every browser; the tests only need headless Chromium
            val env = mapOf("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD" to "1")
            Driver.ensureDriverInstalled(env, false).createProcessBuilder().apply {
                command().addAll(listOf("install", "--only-shell", "chromium"))
                inheritIO()
            }.start().waitFor()
            playwright = Playwright.create(Playwright.CreateOptions().setEnv(env))
            browser = playwright.chromium().launch()
        }

        @AfterClass
        @JvmStatic
        fun stopBrowser() {
            browser.close()
            playwright.close()
        }
    }

    private lateinit var page: Page

    @Before
    fun openPage() {
        page = browser.newPage(Browser.NewPageOptions().setViewportSize(560, 480))
        page.setContent(DumpiePanel.page())
        // What the IDE injects: messages from the page end up in window.posted
        page.evaluate("() => { window.posted = []; window.dumpiePost = m => window.posted.push(JSON.parse(m)); }")
    }

    @After
    fun closePage() = page.close()

    private fun request(uuid: String, type: String, content: String, file: String = "/app/x.php", at: Long? = null) =
        """{"uuid":"$uuid","payloads":[{"type":"$type","content":$content,"origin":{"file":"$file","line_number":3}}],""" +
            """"meta":{${at?.let { "\"dumpie_received_at\":$it" }.orEmpty()}}}"""

    private fun log(uuid: String, text: String = uuid, at: Long? = null) = request(uuid, "log", """{"values":["$text"]}""", at = at)

    private fun receive(vararg requests: String, source: String = "shop") {
        page.evaluate("([items, id]) => items.forEach(r => window.dumpie.receive(JSON.parse(r), {id: id, name: id}))", listOf(requests.toList(), source))
    }

    private fun js(script: String): Any? = page.evaluate(script)

    private fun count(selector: String) = page.locator(selector).count()

    private fun texts(selector: String) = page.locator(selector).allTextContents()

    private fun posted(): List<JsonObject> = JsonParser.parseString(js("() => JSON.stringify(window.posted)") as String).asJsonArray.map { it.asJsonObject }

    private fun Locator.force() = click(Locator.ClickOptions().setForce(true))

    private fun localTime(at: Long) = js("() => new Date($at).toLocaleTimeString()") as String

    private fun sessions(json: String, projectCount: Int) = js("() => window.dumpie.sessions($json, $projectCount)")

    private fun status(dump: String, ray: String, canAddEnv: Boolean = true) = js(
        """() => window.dumpie.status({project: 'shop',
            dump: {state: '$dump', canAddEnv: $canAddEnv, envLines: ['VAR_DUMPER_FORMAT=server', 'VAR_DUMPER_SERVER=127.0.0.1:9912']},
            ray: {state: '$ray', package: 'spatie/ray 1.0.0', install: 'composer require spatie/ray --dev'},
            logs: {state: 'ready', patterns: 'storage/logs/*.log'}})""",
    )

    // Entries

    @Test
    fun logEntryShowsTextSourceAndCount() {
        receive(log("a", "hello"))
        assertEquals(listOf("hello"), texts(".entry .text"))
        assertEquals(listOf("x.php:3"), texts(".entry-meta a[data-file]"))
        assertEquals("1", page.textContent("#count"))
        assertTrue(page.locator("#empty").isHidden())
    }

    @Test
    fun followUpRequestsModifyTheEntry() {
        receive(log("a"), request("a", "color", """{"color":"red"}"""), request("a", "label", """{"label":"Order"}"""),
            request("a", "size", """{"size":"lg"}"""), request("a", "hide", "{}"))
        assertEquals(1, count(".entry"))
        val classes = page.getAttribute(".entry", "class").orEmpty()
        assertTrue(classes, classes.contains("color-red") && classes.contains("size-lg") && classes.contains("collapsed"))
        assertEquals("Order", page.textContent(".entry-label"))
    }

    @Test
    fun contentForSameUuidReplacesTheBody() {
        receive(log("a", "first"), log("a", "second"))
        assertEquals(listOf("second"), texts(".entry .text"))
    }

    @Test
    fun removeDeletesTheEntry() {
        receive(log("a"), log("b"), request("a", "remove", "{}"))
        assertEquals(listOf("b"), texts(".entry .text"))
    }

    @Test
    fun clearAllStartsOver() {
        receive(log("a"), request("s", "new_screen", """{"name":"Checkout"}"""), log("b"), request("c", "clear_all", "{}"))
        assertEquals(0, count(".entry"))
        assertEquals(1, count(".screen"))
        assertTrue(page.locator("#screen-bar").isHidden())
        assertTrue(page.locator("#empty").isVisible())
    }

    @Test
    fun entriesAreLimited() {
        val batch = JsonArray().apply {
            repeat(1600) { i -> add(JsonObject().apply { add("r", JsonParser.parseString(log("e$i"))); add("s", JsonParser.parseString("""{"id":"shop","name":"shop"}""")) }) }
        }
        js("() => window.dumpie.receiveBatch($batch)")
        assertEquals(1500, count(".entry"))
        assertEquals(0, count(".entry[data-uuid='e99']"))
        assertEquals(1, count(".entry[data-uuid='e1599']"))
    }

    @Test
    fun exceptionShowsAppFrameAndFoldsVendorFrames() {
        receive(request("x", "exception", """{"class":"App\\Exceptions\\Boom","message":"Failed","frames":[
            {"file_name":"/app/vendor/a.php","line_number":1,"class":"V","method":"m","vendor_frame":true},
            {"file_name":"/app/x.php","line_number":5,"vendor_frame":false,"snippet":[{"line_number":4,"text":"before"},{"line_number":5,"text":"failing"}]},
            {"file_name":"/app/vendor/b.php","line_number":2,"vendor_frame":true},
            {"file_name":"/app/vendor/c.php","line_number":3,"vendor_frame":true}]}"""))
        assertTrue(page.getAttribute(".entry", "class").orEmpty().contains("color-red"))
        assertEquals("failing", page.textContent(".snippet .line.hl .code"))
        assertEquals(listOf("1 vendor frame", "2 vendor frames"), texts(".vendor-group summary"))
    }

    @Test
    fun dumpTogglesOpenAndClose() {
        val html = DumpFixtures.render("nested").html
        receive(JsonObject().apply {
            addProperty("uuid", "d")
            add("payloads", JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("type", "log")
                    add("content", JsonObject().apply { add("values", JsonArray().apply { add(html) }) })
                })
            })
        }.toString())
        val nested = page.locator("samp[data-depth='2']").first()
        assertTrue(nested.getAttribute("class").orEmpty().contains("sf-dump-compact"))
        page.locator("samp[data-depth='1'] > a.sf-dump-toggle").first().click()
        assertTrue(nested.getAttribute("class").orEmpty().contains("sf-dump-expanded"))
        page.locator("samp[data-depth='1'] > a.sf-dump-toggle").first().click()
        assertTrue(nested.getAttribute("class").orEmpty().contains("sf-dump-compact"))
    }

    @Test
    fun edgeCollapsesTheEntry() {
        receive(log("a"))
        page.locator(".entry-edge").force()
        assertTrue(page.getAttribute(".entry", "class").orEmpty().contains("collapsed"))
    }

    // Screens

    @Test
    fun newScreenAddsTabWithReceiveTime() {
        val at = 1_790_000_000_000
        receive(log("a", at = at), request("s", "new_screen", """{"name":"Checkout"}""", at = at + 60_000), log("b"))
        assertEquals(listOf("Screen 1" + localTime(at), "Checkout" + localTime(at + 60_000)), texts(".screen-tab"))
        assertTrue(page.locator("#screen-bar").isVisible())
        assertEquals(listOf("b"), page.locator(".screen:not([hidden]) .entry .text").allTextContents())

        page.locator(".screen-tab").first().click()
        assertEquals(listOf("a"), page.locator(".screen:not([hidden]) .entry .text").allTextContents())
        assertEquals("1", page.textContent("#count"))
    }

    @Test
    fun emptyScreenIsReused() {
        receive(request("c", "clear_all", "{}"), request("s", "new_screen", """{"name":"Checkout"}"""))
        assertEquals(1, count(".screen-tab"))
        assertTrue(page.textContent(".screen-tab").orEmpty().startsWith("Checkout"))
    }

    @Test
    fun screensAreLimited() {
        repeat(12) { i -> receive(request("s$i", "new_screen", """{"name":"S$i"}"""), log("e$i")) }
        assertEquals(10, count(".screen-tab"))
        assertTrue(page.textContent(".screen-tab").orEmpty().startsWith("S2"))
        // Entries of dropped screens go with them
        assertEquals(10, count(".entry"))
        assertEquals(0, count(".entry[data-uuid='e0']"))
    }

    @Test
    fun newScreenButtonsAskTheIde() {
        receive(request("s", "new_screen", """{"name":"Checkout"}"""))
        page.click("#new-screen")
        page.click(".screen-add")
        assertEquals(listOf("newScreen", "newScreen"), posted().map { it.get("action").asString })
    }

    // Toolbar

    @Test
    fun searchMatchesVisibleTextOnly() {
        receive(log("a", "needle here"), log("b", "other"),
            request("c", "log", """{"values":["<pre class=\"sf-dump\">dumped</pre><script>var secretword = 1</script>"]}"""))
        page.click("#search")
        page.fill("#filter", "needle")
        page.waitForTimeout(300.0)
        assertEquals(listOf("a"), page.locator(".entry:not(.hidden-by-filter)").evaluateAll("els => els.map(e => e.dataset.uuid)"))

        page.fill("#filter", "secretword")
        page.waitForTimeout(300.0)
        assertEquals(0, count(".entry:not(.hidden-by-filter)"))

        page.press("#filter", "Escape")
        assertEquals(3, count(".entry:not(.hidden-by-filter)"))
        assertTrue(page.locator("#filter").isHidden())
    }

    @Test
    fun colorDotsFilter() {
        receive(log("a"), request("a", "color", """{"color":"red"}"""), log("b"), request("b", "color", """{"color":"green"}"""), log("c"))
        page.click(".dot[data-color='red']")
        assertEquals(listOf("a"), page.locator(".entry:not(.hidden-by-filter)").evaluateAll("els => els.map(e => e.dataset.uuid)"))
        page.click(".dot[data-color='green']")
        assertEquals(2, count(".entry:not(.hidden-by-filter)"))
        page.click(".dot[data-color='red']")
        page.click(".dot[data-color='green']")
        assertEquals(3, count(".entry:not(.hidden-by-filter)"))
    }

    @Test
    fun pauseQueuesOutputButNeverAPausedScript() {
        page.click("#pause")
        receive(log("a"))
        assertEquals(0, count(".entry"))
        assertEquals("1", page.textContent("#pending"))
        assertTrue(page.locator("#paused-bar").isVisible())

        // ray()->pause() blocks PHP until Continue is pressed, so it shows up right away
        receive(request("l", "create_lock", """{"name":"lock-1"}"""))
        assertEquals(1, count(".entry"))

        page.click("#resume")
        assertEquals(2, count(".entry"))
        assertTrue(page.locator("#paused-bar").isHidden())
    }

    @Test
    fun clearButtonClearsAndTellsTheIde() {
        receive(log("a"))
        page.click("#clear")
        assertEquals(0, count(".entry"))
        assertEquals(listOf("clear"), posted().map { it.get("action").asString })
    }

    // Messages to the IDE

    @Test
    fun lockButtonsReleaseTheScriptOnce() {
        receive(request("l", "create_lock", """{"name":"lock-1"}"""))
        page.click("[data-lock-action='stop']")
        val message = posted().single()
        assertEquals("lock", message.get("action").asString)
        assertEquals("lock-1", message.get("name").asString)
        assertEquals("stop", message.get("mode").asString)
        assertTrue(page.locator("[data-lock-action='continue']").isDisabled())
        assertEquals("Stopped", page.textContent(".lock span"))
    }

    @Test
    fun fileLinkOpensInTheOwningSession() {
        receive(log("a"), source = "blog")
        page.click(".entry-meta a[data-file]")
        val message = posted().single()
        assertEquals("open", message.get("action").asString)
        assertEquals("/app/x.php", message.get("file").asString)
        assertEquals(3, message.get("line").asInt)
        assertEquals("blog", message.get("session").asString)
    }

    @Test
    fun externalLinksOpenInTheBrowser() {
        receive(request("a", "custom", """{"content":"<a href=\"https://example.com/x\">docs</a>","label":""}"""))
        page.click(".entry-body a")
        assertEquals("https://example.com/x", posted().single().get("url").asString)
    }

    @Test
    fun copyAsMarkdown() {
        receive(request("q", "executed_query", """{"sql":"select 1","connection_name":"mysql","time":1.5}"""))
        page.locator(".entry-copy").force()
        val message = posted().single()
        assertEquals("copy", message.get("action").asString)
        assertEquals("x.php:3\n```sql\nselect 1\n```", message.get("text").asString)
    }

    // Sessions

    @Test
    fun sessionBarOnlyWithSeveralProjects() {
        sessions("""[{id: 'shop', name: 'shop', own: true, viewed: true}]""", 1)
        assertTrue(page.locator("#session-bar").isHidden())
    }

    @Test
    fun sessionChipsShowUnreadAndSwitch() {
        sessions(
            """[{id: 'all', name: 'All projects'}, {id: 'shop', name: 'shop', own: true, viewed: true},
                {id: 'blog', name: 'blog', unread: 5}, {id: 'api', name: 'api', unread: 150}]""",
            3,
        )
        assertTrue(page.locator("#session-bar").isVisible())
        assertEquals(listOf("5", "99+"), texts(".session-chip .unread"))
        assertEquals("this", page.textContent(".session-chip.active small"))

        page.click(".session-chip.active")
        assertTrue(posted().isEmpty())
        page.click("[data-session='blog']")
        assertEquals("blog", posted().single().get("id").asString)
    }

    @Test
    fun allProjectsTagsEntriesWithTheirProject() {
        sessions("""[{id: 'all', name: 'All projects', viewed: true}, {id: 'shop', name: 'shop', own: true}, {id: 'blog', name: 'blog'}]""", 2)
        receive(log("a"), source = "blog")
        assertEquals("blog", page.textContent(".entry-project"))
    }

    @Test
    fun resetDropsEverythingIncludingQueuedOutput() {
        receive(log("a"))
        page.click("#pause")
        receive(log("b"))
        js("() => window.dumpie.reset()")
        assertEquals(0, count(".entry"))
        assertEquals("0", page.textContent("#pending"))
        page.click("#resume")
        assertEquals(0, count(".entry"))
    }

    // Setup guide

    @Test
    fun guideOnlyWhenNothingCanArrive() {
        status(dump = "setup", ray = "setup")
        assertEquals(3, count(".guide-row"))
        assertTrue(page.locator("#guide-links").isVisible())

        status(dump = "ready", ray = "setup")
        assertEquals(0, count(".guide-row"))
        assertTrue(page.locator("#guide-links").isHidden())

        status(dump = "setup", ray = "ready")
        assertEquals(0, count(".guide-row"))
    }

    @Test
    fun guideActions() {
        status(dump = "setup", ray = "setup")
        page.click("[data-action='addEnv']")
        page.locator(".guide-row.setup [data-copy]").first().click()
        val messages = posted()
        assertEquals("addEnv", messages[0].get("action").asString)
        assertEquals("VAR_DUMPER_FORMAT=server\nVAR_DUMPER_SERVER=127.0.0.1:9912", messages[1].get("text").asString)

        status(dump = "setup", ray = "setup", canAddEnv = false)
        assertEquals(0, count("[data-action='addEnv']"))
    }

    // Look

    @Test
    fun barsStayPinnedWhileScrolling() {
        sessions("""[{id: 'shop', name: 'shop', own: true, viewed: true}, {id: 'blog', name: 'blog'}]""", 2)
        receive(*Array(40) { log("e$it") })
        fun top(selector: String) = (js("() => document.querySelector(\"$selector\").getBoundingClientRect().top") as Number).toDouble()

        js("() => window.scrollTo(0, 2000)")
        assertEquals(0.0, top(".toolbar"), 0.5)
        assertEquals(top(".toolbar") + (js("() => document.querySelector('.toolbar').offsetHeight") as Number).toDouble(), top("#session-bar"), 0.5)

        js("() => window.dumpie.options({pinBars: false})")
        js("() => window.scrollTo(0, 2000)")
        assertEquals(0.0, top(".toolbar"), 0.5)
        assertTrue(top("#session-bar") < 0)
    }

    @Test
    fun themeFollowsTheIde() {
        js("() => window.dumpie.theme({'--bg': 'rgb(1, 2, 3)'}, false)")
        assertEquals("light", page.getAttribute("html", "data-theme"))
        assertEquals("rgb(1, 2, 3)", js("() => getComputedStyle(document.body).backgroundColor"))
    }

    @Test
    fun pageStartsEmpty() {
        assertEquals(0, count(".entry"))
        assertTrue(page.locator("#empty").isVisible())
        assertTrue(page.locator("#guide-links").isHidden())
        assertFalse(page.locator("#session-bar").isVisible())
    }
}
