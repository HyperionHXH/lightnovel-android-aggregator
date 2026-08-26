package io.github.jiangyuyi.lightnovel.core.offline

import io.github.jiangyuyi.lightnovel.core.source.ChapterContent
import io.github.jiangyuyi.lightnovel.core.source.ChapterKey
import io.github.jiangyuyi.lightnovel.core.source.NovelKey

internal interface OfflineBookStore {
    suspend fun listBooks(): List<OfflineBookRecord>
    suspend fun readBook(key: NovelKey): OfflineBookRecord?
    suspend fun writeBook(record: OfflineBookRecord)
    suspend fun hasChapter(novelKey: NovelKey, chapterKey: ChapterKey): Boolean
    suspend fun readChapter(novelKey: NovelKey, chapterKey: ChapterKey): ChapterContent?
    suspend fun writeChapter(novelKey: NovelKey, content: ChapterContent)
    suspend fun deleteBook(key: NovelKey)
}
