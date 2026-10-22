package io.github.jiangyuyi.lightnovel.core.reader

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChapterFontRepositoryDeviceTest {
    @Test fun downloadedLegacyFontRemainsUsableAfterOnlineCacheClear() = runBlocking {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(base.cacheDir, "font_fixture_${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = File(root, "files").apply { mkdirs() }
            override fun getCacheDir(): File = File(root, "cache").apply { mkdirs() }
        }
        try {
            val systemFont = requireNotNull(File("/system/fonts").listFiles()?.firstOrNull {
                it.isFile && it.extension == "ttf"
            }) { "Device has no TTF font fixture" }
            val offlineUrl = "https://api.lightnovel.life/fonts/offline-fixture.ttf"
            val onlineUrl = "https://api.lightnovel.life/fonts/online-fixture.ttf"
            fun name(url: String) = MessageDigest.getInstance("SHA-256")
                .digest(url.toByteArray()).joinToString("") { "%02x".format(it) } + ".ttf"
            val legacy = File(context.filesDir, "reader_fonts").apply { mkdirs() }
            systemFont.copyTo(File(legacy, name(offlineUrl)))
            systemFont.copyTo(File(legacy, name(onlineUrl)))
            val userFont = File(context.filesDir, "user_fonts/installed.ttf").apply {
                parentFile!!.mkdirs(); writeText("installed by user")
            }
            val chapter = File(context.filesDir, "offline_library/chapter.json").apply {
                parentFile!!.mkdirs(); writeText("saved chapter")
            }
            val repository = ChapterFontRepository(context)
            repository.migrateLegacyFonts(setOf(offlineUrl))
            assertTrue(repository.usage().temporaryBytes > 0)
            repository.clearTemporary()
            assertEquals(0L, repository.usage().temporaryBytes)
            assertTrue(repository.usage().offlineBytes > 0)
            assertNotNull(repository.load(offlineUrl)) // Existing file: no network needed.
            assertEquals("installed by user", userFont.readText())
            assertEquals("saved chapter", chapter.readText())
            assertFalse(File(context.cacheDir, "chapter_fonts/${name(onlineUrl)}").exists())
            // A live Compose family pins its backing file across cleanup.
            systemFont.copyTo(File(context.cacheDir, "chapter_fonts/${name(onlineUrl)}").apply {
                parentFile!!.mkdirs()
            })
            val active = repository.load(onlineUrl)
            repository.clearTemporary()
            assertNotNull(active)
            assertTrue(File(context.cacheDir, "chapter_fonts/${name(onlineUrl)}").exists())
        } finally {
            root.deleteRecursively()
        }
    }
}
