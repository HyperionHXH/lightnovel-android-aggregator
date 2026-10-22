package io.github.jiangyuyi.lightnovel.core.cache

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteFullException
import android.database.sqlite.SQLiteOpenHelper
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class SqliteCacheStore(
    context: Context,
    private val maxBytes: Long = MAX_DISK_BYTES,
    memoryBytes: Long = MAX_MEMORY_BYTES,
    databaseName: String = DATABASE_NAME,
) : CacheStore {
    private val databaseFile = context.applicationContext.getDatabasePath(databaseName)
    private val helper = CacheDatabase(context.applicationContext, maxBytes, databaseName)
    private val memory = MemoryCache(memoryBytes)
    private val mutex = Mutex()

    init {
        require(maxBytes >= 64L * 1024L)
        require(memoryBytes >= 0)
    }

    override suspend fun read(scope: String, key: String, allowDisk: Boolean): SerializedCacheEntry? =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val memoryKey = memoryKey(scope, key)
                val cached = memory[memoryKey]
                if (!allowDisk) return@withLock cached
                val database = helper.writableDatabase
                val entry = cached ?: database.query(
                    TABLE, arrayOf(COLUMN_PAYLOAD, COLUMN_SAVED_AT),
                    "$COLUMN_SCOPE = ? AND $COLUMN_KEY = ?", arrayOf(scope, key),
                    null, null, null, "1",
                ).use { cursor ->
                    if (!cursor.moveToFirst()) null
                    else SerializedCacheEntry(cursor.getString(0), cursor.getLong(1))
                } ?: return@withLock null
                memory.put(memoryKey, entry)
                database.update(
                    TABLE,
                    ContentValues().apply { put(COLUMN_LAST_ACCESS, System.currentTimeMillis()) },
                    "$COLUMN_SCOPE = ? AND $COLUMN_KEY = ?", arrayOf(scope, key),
                )
                entry
            }
        }

    override suspend fun write(scope: String, key: String, entry: SerializedCacheEntry, allowDisk: Boolean) {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val memoryKey = memoryKey(scope, key)
                memory.put(memoryKey, entry)
                if (!allowDisk) return@withLock
                val database = helper.writableDatabase
                database.delete(TABLE, "$COLUMN_SCOPE = ? AND $COLUMN_KEY = ?", arrayOf(scope, key))
                val bytes = entry.payload.toByteArray(Charsets.UTF_8).size.toLong() +
                    scope.toByteArray(Charsets.UTF_8).size + key.toByteArray(Charsets.UTF_8).size + 64L
                // Leave room for SQLite indexes/pages and never attempt a giant disposable record.
                val budget = maxBytes * 3L / 4L
                if (bytes > budget / 2L) {
                    return@withLock
                }
                evictTo(database, budget - bytes)
                val values = ContentValues().apply {
                    put(COLUMN_SCOPE, scope)
                    put(COLUMN_KEY, key)
                    put(COLUMN_PAYLOAD, entry.payload)
                    put(COLUMN_SAVED_AT, entry.savedAtMillis)
                    put(COLUMN_LAST_ACCESS, System.currentTimeMillis())
                    put(COLUMN_BYTES, bytes)
                }
                try {
                    database.insertOrThrow(TABLE, null, values)
                } catch (_: SQLiteFullException) {
                    // max_page_count is the final physical limit, including page/index overhead.
                    evictTo(database, budget / 2L)
                }
            }
        }
    }

    override suspend fun remove(scope: String, key: String) = mutate { database ->
        memory.remove(memoryKey(scope, key))
        database.delete(TABLE, "$COLUMN_SCOPE = ? AND $COLUMN_KEY = ?", arrayOf(scope, key))
    }

    override suspend fun removePrefix(scope: String, keyPrefix: String) = mutate { database ->
        memory.removeMatching { it.startsWith(memoryKey(scope, keyPrefix)) }
        database.delete(
            TABLE, "$COLUMN_SCOPE = ? AND substr($COLUMN_KEY, 1, ?) = ?",
            arrayOf(scope, keyPrefix.length.toString(), keyPrefix),
        )
    }

    override suspend fun clearPrivate() = mutate { database ->
        memory.removeMatching { it.substringBefore('\u0000').startsWith(USER_SCOPE_PREFIX) }
        database.delete(TABLE, "$COLUMN_SCOPE LIKE ?", arrayOf("$USER_SCOPE_PREFIX%"))
    }

    override suspend fun clearAll() = mutate { database ->
        memory.clear()
        database.delete(TABLE, null, null)
    }

    suspend fun usage(): TextCacheUsage = withContext(Dispatchers.IO) {
        mutex.withLock {
            helper.writableDatabase // Open/migrate before measuring the physical database files.
            TextCacheUsage(
                diskBytes = listOf(databaseFile, File(databaseFile.path + "-wal"),
                    File(databaseFile.path + "-shm"), File(databaseFile.path + "-journal"))
                    .sumOf { if (it.isFile) it.length() else 0L },
                memoryBytes = memory.sizeBytes,
            )
        }
    }

    suspend fun close() = withContext(Dispatchers.IO) {
        mutex.withLock {
            helper.close()
            memory.clear()
        }
    }

    private suspend fun mutate(action: (SQLiteDatabase) -> Unit) {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val database = helper.writableDatabase
                action(database)
            }
        }
    }

    private fun evictTo(database: SQLiteDatabase, budget: Long) {
        var total = database.rawQuery("SELECT COALESCE(SUM($COLUMN_BYTES), 0) FROM $TABLE", null)
            .use { it.moveToFirst(); it.getLong(0) }
        if (total <= budget) return
        val victims = database.query(
            TABLE, arrayOf(COLUMN_SCOPE, COLUMN_KEY, COLUMN_BYTES), null, null, null, null,
            "$COLUMN_LAST_ACCESS ASC, rowid ASC",
        ).use { cursor ->
            buildList {
                while (total > budget && cursor.moveToNext()) {
                    add(cursor.getString(0) to cursor.getString(1))
                    total -= cursor.getLong(2)
                }
            }
        }
        victims.forEach { (scope, key) ->
            database.delete(TABLE, "$COLUMN_SCOPE = ? AND $COLUMN_KEY = ?", arrayOf(scope, key))
            memory.remove(memoryKey(scope, key))
        }
    }


    private fun memoryKey(scope: String, key: String): String = "$scope\u0000$key"

    private class CacheDatabase(context: Context, private val maxBytes: Long, name: String) :
        SQLiteOpenHelper(context, name, null, DATABASE_VERSION) {
        override fun onConfigure(database: SQLiteDatabase) {
            database.disableWriteAheadLogging()
            // This database contains disposable data only. No unbounded WAL or on-disk journal.
            database.rawQuery("PRAGMA journal_mode=MEMORY", null).use { it.moveToFirst() }
        }

        override fun onCreate(database: SQLiteDatabase) {
            database.execSQL(
                """
                CREATE TABLE $TABLE (
                    $COLUMN_SCOPE TEXT NOT NULL,
                    $COLUMN_KEY TEXT NOT NULL,
                    $COLUMN_PAYLOAD TEXT NOT NULL,
                    $COLUMN_SAVED_AT INTEGER NOT NULL,
                    $COLUMN_LAST_ACCESS INTEGER NOT NULL,
                    $COLUMN_BYTES INTEGER NOT NULL,
                    PRIMARY KEY ($COLUMN_SCOPE, $COLUMN_KEY)
                )
                """.trimIndent(),
            )
            database.execSQL("CREATE INDEX cache_last_access ON $TABLE ($COLUMN_LAST_ACCESS)")
        }

        override fun onOpen(database: SQLiteDatabase) {
            super.onOpen(database)
            val autoVacuum = database.rawQuery("PRAGMA auto_vacuum", null)
                .use { it.moveToFirst(); it.getInt(0) }
            if (autoVacuum != 1) {
                database.execSQL("PRAGMA auto_vacuum=FULL")
                database.execSQL("VACUUM")
            }
            val pageSize = database.rawQuery("PRAGMA page_size", null)
                .use { it.moveToFirst(); it.getLong(0) }
            val pages = database.rawQuery("PRAGMA page_count", null)
                .use { it.moveToFirst(); it.getLong(0) }
            if (pages * pageSize > maxBytes) {
                // Old releases bounded payload bytes only. Reclaim their high-water files once.
                database.delete(TABLE, null, null)
                database.execSQL("VACUUM")
            }
            database.setMaximumSize(maxBytes)
        }

        override fun onUpgrade(database: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }

    companion object {
        const val MAX_DISK_BYTES = 96L * 1024L * 1024L
        const val MAX_MEMORY_BYTES = 8L * 1024L * 1024L
        internal const val DATABASE_NAME = "content_cache.db"
        private const val DATABASE_VERSION = 1
        private const val TABLE = "cache_entries"
        private const val COLUMN_SCOPE = "scope"
        private const val COLUMN_KEY = "entry_key"
        private const val COLUMN_PAYLOAD = "payload"
        private const val COLUMN_SAVED_AT = "saved_at"
        private const val COLUMN_LAST_ACCESS = "last_access"
        private const val COLUMN_BYTES = "byte_size"
        private const val USER_SCOPE_PREFIX = "user:"
    }
}

data class TextCacheUsage(val diskBytes: Long, val memoryBytes: Long)
