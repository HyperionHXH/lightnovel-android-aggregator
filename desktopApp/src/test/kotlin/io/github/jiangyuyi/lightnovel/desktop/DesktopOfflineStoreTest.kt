package io.github.jiangyuyi.lightnovel.desktop

import java.nio.file.Files
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DesktopOfflineStoreTest {
    @Test
    fun `offline store writes atomic chapter and exports only available chapters`() {
        val root = Files.createTempDirectory("mixn-offline-test").toFile()
        try {
            val store = DesktopOfflineStore(root)
            val book = DesktopBook("light_novel_kingdom", "101", "测试书")
            val available = DesktopChapter("light_novel_kingdom", "1", "101", "7", "第一章", 1)
            val locked = DesktopChapter("light_novel_kingdom", "2", "101", "7", "第二章", 2, locked = true, coinPrice = 3)
            store.save(book, DesktopChapterContent(available, "测试书", "第一卷", bodyHtml = "<p>正文</p>"))
            assertTrue(store.has(book, available))
            assertFalse(store.has(book, locked))

            val output = root.resolve("book.epub")
            store.exportEpub(book, listOf(available, locked), output)
            ZipFile(output).use { zip ->
                assertNotNull(zip.getEntry("mimetype"))
                assertNotNull(zip.getEntry("OEBPS/chapter-0.xhtml"))
                assertTrue(zip.getEntry("OEBPS/chapter-1.xhtml") == null)
            }
        } finally {
            root.deleteRecursively()
        }
    }
}
