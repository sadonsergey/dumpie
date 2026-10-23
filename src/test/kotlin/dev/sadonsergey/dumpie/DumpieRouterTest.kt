package dev.sadonsergey.dumpie

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Which open project a request belongs to. */
class DumpieRouterTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var shop: DumpieRouter.Target
    private lateinit var blog: DumpieRouter.Target
    private lateinit var root: File

    @Before
    fun projects() {
        root = tmp.root
        listOf("shop/app/Http/Kernel.php", "shop/app/Models/Order.php", "shop/public/index.php",
            "blog/app/Http/Kernel.php", "blog/web/public/index.php", "shop-old/app/Models/Order.php", "closed/app/Http/Kernel.php")
            .forEach { File(root, it).apply { parentFile.mkdirs(); writeText("<?php") } }
        shop = DumpieRouter.Target("shop", File(root, "shop").path)
        blog = DumpieRouter.Target("blog", File(root, "blog").path)
    }

    private fun request(vararg files: String, projectName: String? = null) = JsonObject().apply {
        add("payloads", JsonArray().apply {
            files.forEach { file ->
                add(JsonObject().apply {
                    addProperty("type", "log")
                    add("origin", JsonObject().apply { addProperty("file", file) })
                })
            }
        })
        add("meta", JsonObject().apply { projectName?.let { addProperty("project_name", it) } })
    }

    private fun pick(request: JsonObject) = DumpieRouter.pick(request, listOf(shop, blog))?.name

    @Test
    fun fileInsideProject() {
        assertEquals("shop", pick(request(File(root, "shop/app/Models/Order.php").path)))
    }

    @Test
    fun fileWinsOverProjectName() {
        assertEquals("shop", pick(request(File(root, "shop/app/Models/Order.php").path, projectName = "blog")))
    }

    @Test
    fun projectNameIgnoresCase() {
        assertEquals("blog", pick(request("/somewhere/else.php", projectName = "BLOG")))
    }

    @Test
    fun containerPathMatchesBySuffix() {
        assertEquals("shop", pick(request("/var/www/html/app/Models/Order.php")))
    }

    @Test
    fun longestSuffixWins() {
        // shop has public/index.php, blog has web/public/index.php
        assertEquals("blog", pick(request("/srv/web/public/index.php")))
    }

    @Test
    fun hostFileOfClosedProjectIsNotMatchedBySuffix() {
        // Every Laravel project has app/Http/Kernel.php; this one exists here but is not open
        assertNull(pick(request(File(root, "closed/app/Http/Kernel.php").path)))
    }

    @Test
    fun siblingFolderWithSamePrefixIsNotTheProject() {
        assertNull(pick(request(File(root, "shop-old/app/Models/Order.php").path)))
    }

    @Test
    fun requestWithoutPayloads() {
        assertNull(DumpieRouter.pick(JsonObject(), listOf(shop, blog)))
    }

    @Test
    fun suffixNeedsTwoSegments() {
        val base = File(root, "shop").path
        assertEquals(3, FileResolver.suffixMatch(base, "/var/www/html/app/Models/Order.php"))
        assertEquals(0, FileResolver.suffixMatch(File(root, "shop/public").path, "/var/www/html/index.php"))
    }
}
