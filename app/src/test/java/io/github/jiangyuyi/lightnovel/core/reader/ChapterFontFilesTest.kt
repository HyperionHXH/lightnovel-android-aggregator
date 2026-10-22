package io.github.jiangyuyi.lightnovel.core.reader

import java.io.File
import java.io.IOException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ChapterFontFilesTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun `LRU cap preserves active font and rejects oversized font`() {
        val temporary = folder.newFolder("temporary")
        val files = ChapterFontFiles(temporary, folder.newFolder("offline"), folder.newFolder("legacy"), 12)
        val first = File(temporary, "first.ttf").apply { writeBytes(ByteArray(6)); setLastModified(1) }
        val active = File(temporary, "active.ttf").apply { writeBytes(ByteArray(6)); setLastModified(2) }
        files.reserve(6, setOf(active.name))
        assertFalse(first.exists())
        assertTrue(active.exists())
        try {
            files.reserve(13, emptySet())
            fail("oversized file accepted")
        } catch (_: IOException) { }
        assertEquals(6L, files.temporaryBytes())
        files.clearTemporary(setOf(active.name))
        assertTrue(active.exists())
        files.clearTemporary(emptySet())
        assertEquals(0L, files.temporaryBytes())
    }

    @Test fun `legacy migration pins downloaded font and clearing leaves durable user files intact`() {
        val temporary = folder.newFolder("temporary")
        val offline = folder.newFolder("offline")
        val legacy = folder.newFolder("legacy")
        val files = ChapterFontFiles(temporary, offline, legacy, 32)
        File(legacy, "downloaded.ttf").writeBytes(byteArrayOf(1, 2, 3))
        File(legacy, "online.ttf").writeBytes(byteArrayOf(4, 5, 6))
        val downloads = folder.newFolder("downloads")
        val book = File(downloads, "chapter.json").apply { writeText("saved chapter") }
        val progress = File(folder.root, "progress.json").apply { writeText("page=42") }
        val userFont = File(folder.newFolder("user_fonts"), "installed.ttf").apply { writeText("user") }
        files.migrateLegacy(setOf("downloaded.ttf"), emptySet())
        assertArrayEquals(byteArrayOf(1, 2, 3), File(offline, "downloaded.ttf").readBytes())
        assertEquals(3L, files.temporaryBytes())
        files.clearTemporary(emptySet())
        assertEquals(0L, files.temporaryBytes())
        assertEquals(3L, files.offlineBytes())
        assertEquals(File(offline, "downloaded.ttf"), files.find("downloaded.ttf"))
        assertEquals("saved chapter", book.readText())
        assertEquals("page=42", progress.readText())
        assertEquals("user", userFont.readText())
    }
}
