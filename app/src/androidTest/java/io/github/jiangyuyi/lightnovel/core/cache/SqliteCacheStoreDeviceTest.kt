package io.github.jiangyuyi.lightnovel.core.cache

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SqliteCacheStoreDeviceTest {
    @Test fun diskEvictionPromotesRecentlyReadEntries() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "storage_fixture_${UUID.randomUUID()}.db"
        val store = SqliteCacheStore(context, 128L * 1024L, 0, name)
        try {
            fun entry() = SerializedCacheEntry("x".repeat(30_000), 1)
            store.write("public", "a", entry(), true)
            Thread.sleep(5)
            store.write("public", "b", entry(), true)
            Thread.sleep(5)
            store.write("public", "c", entry(), true)
            Thread.sleep(5)
            assertNotNull(store.read("public", "a", true))
            store.write("public", "d", entry(), true)
            assertNotNull(store.read("public", "a", true))
            assertNull(store.read("public", "b", true))
            assertNotNull(store.read("public", "d", true))
        } finally {
            store.close()
            context.deleteDatabase(name)
        }
    }

    @Test fun physicalCapClearAndOversizePreserveOtherData() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "storage_fixture_${UUID.randomUUID()}.db"
        val cap = 128L * 1024L
        val sentinel = File(context.filesDir, "storage_fixture_${UUID.randomUUID()}").apply { mkdirs() }
        val download = File(sentinel, "download/chapter.json").apply { parentFile!!.mkdirs(); writeText("downloaded") }
        val progress = File(sentinel, "progress.json").apply { writeText("page=42") }
        val userFont = File(sentinel, "user_fonts/font.ttf").apply { parentFile!!.mkdirs(); writeText("installed") }
        val store = SqliteCacheStore(context, cap, 1024, name)
        try {
            repeat(100) { index ->
                store.write("source:lns", "chapter:$index", SerializedCacheEntry("x".repeat(10_000), index.toLong()), true)
                assertTrue("physical file exceeds limit", store.usage().diskBytes <= cap)
            }
            assertNull(store.read("source:lns", "chapter:0", true))
            assertNotNull(store.read("source:lns", "chapter:99", true))
            val before = store.usage().diskBytes
            store.write("public", "huge", SerializedCacheEntry("x".repeat(256_000), 101), true)
            assertNull(store.read("public", "huge", true))
            assertTrue(store.usage().diskBytes <= cap)
            store.write("public", "memory", SerializedCacheEntry("small", 102), false)
            assertNotNull(store.read("public", "memory", false))
            store.clearAll()
            assertNull(store.read("public", "memory", false))
            assertNull(store.read("source:lns", "chapter:99", true))
            val after = store.usage()
            assertEquals(0L, after.memoryBytes)
            assertTrue("deleted SQLite pages not reclaimed: $before -> ${after.diskBytes}", after.diskBytes < before)
            assertEquals("downloaded", download.readText())
            assertEquals("page=42", progress.readText())
            assertEquals("installed", userFont.readText())
        } finally {
            store.close()
            context.deleteDatabase(name)
            sentinel.deleteRecursively()
        }
    }

    @Test fun legacyHighWaterDatabaseIsReclaimedOnOpen() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "storage_fixture_${UUID.randomUUID()}.db"
        val file = context.getDatabasePath(name)
        file.parentFile!!.mkdirs()
        val cap = 128L * 1024L
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL("CREATE TABLE cache_entries(scope TEXT NOT NULL, entry_key TEXT NOT NULL, payload TEXT NOT NULL, saved_at INTEGER NOT NULL, last_access INTEGER NOT NULL, byte_size INTEGER NOT NULL, PRIMARY KEY(scope, entry_key))")
            db.execSQL("CREATE INDEX cache_last_access ON cache_entries(last_access)")
            db.execSQL("INSERT INTO cache_entries VALUES ('public','old',?,1,1,?)", arrayOf("x".repeat(512_000), 512_000))
            db.version = 1
        }
        assertTrue(file.length() > cap)
        val store = SqliteCacheStore(context, cap, 1024, name)
        try {
            assertTrue(store.usage().diskBytes <= cap)
            assertNull(store.read("public", "old", true))
        } finally {
            store.close()
            context.deleteDatabase(name)
        }
    }
}
