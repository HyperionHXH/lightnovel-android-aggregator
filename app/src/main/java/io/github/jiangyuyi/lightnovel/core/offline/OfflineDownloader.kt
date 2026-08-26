package io.github.jiangyuyi.lightnovel.core.offline

import io.github.jiangyuyi.lightnovel.core.reader.ChapterFontAccess
import io.github.jiangyuyi.lightnovel.core.reader.EmptyChapterFontAccess
import io.github.jiangyuyi.lightnovel.core.source.ChapterSummary
import io.github.jiangyuyi.lightnovel.core.source.NovelKey
import io.github.jiangyuyi.lightnovel.core.source.SourceErrorKind
import io.github.jiangyuyi.lightnovel.core.source.SourceException
import io.github.jiangyuyi.lightnovel.core.source.SourceRegistry
import io.github.jiangyuyi.lightnovel.core.source.VolumeSummary
import kotlinx.coroutines.CancellationException

class OfflineDownloader(
    private val registry: SourceRegistry,
    private val store: OfflineFileStore,
    private val chapterFonts: ChapterFontAccess = EmptyChapterFontAccess,
    private val onUpdated: (OfflineBookRecord) -> Unit = {},
    private val now: () -> Long = System::currentTimeMillis,
) {
    suspend fun download(novelKey: NovelKey, selectedVolumeId: String? = null): OfflineBookRecord {
        val detailProvider = registry.detailProvider(novelKey.sourceId)
            ?: throw SourceException(SourceErrorKind.UNKNOWN, "该来源不支持书籍详情")
        val readerProvider = registry.readerProvider(novelKey.sourceId)
            ?: throw SourceException(SourceErrorKind.UNKNOWN, "该来源不支持离线阅读")
        val detail = detailProvider.getNovelDetail(novelKey)
        val allVolumes = loadAllVolumes(novelKey)
        val targetVolumes = selectedVolumeId?.let { remoteId ->
            allVolumes.filter { it.key.remoteId == remoteId }.ifEmpty {
                throw SourceException(SourceErrorKind.UNKNOWN, "找不到要下载的分卷")
            }
        } ?: allVolumes
        val targetChapters = targetVolumes.flatMap { volume -> loadAllChapters(novelKey, volume) }
        val existing = store.readBook(novelKey)
        val mergedVolumes = (existing?.volumes.orEmpty() + targetVolumes).distinctBy { it.key }
        val mergedChapters = (existing?.chapters.orEmpty() + targetChapters)
            .distinctBy { it.key }
            .sortedWith(compareBy(ChapterSummary::order, { it.key.remoteId }))
        var record = OfflineBookRecord(
            novel = detail.novel.copy(inRemoteShelf = detail.novel.inRemoteShelf),
            selectedVolumeId = selectedVolumeId,
            volumes = mergedVolumes,
            chapters = mergedChapters,
            downloadedChapterIds = existing?.downloadedChapterIds.orEmpty(),
            failedChapters = existing?.failedChapters.orEmpty() - targetChapters.map { it.key.remoteId }.toSet(),
            status = OfflineDownloadStatus.DOWNLOADING,
            updatedAtMillis = now(),
        )
        record = persist(record)

        targetChapters.filterNot(ChapterSummary::locked).forEach { chapter ->
            if (store.hasChapter(novelKey, chapter.key)) {
                if (chapter.key.remoteId !in record.downloadedChapterIds) {
                    record = persist(
                        record.copy(
                            downloadedChapterIds = record.downloadedChapterIds + chapter.key.remoteId,
                            updatedAtMillis = now(),
                        ),
                    )
                }
                return@forEach
            }
            try {
                val content = readerProvider.getChapter(novelKey, chapter.key)
                chapterFonts.load(content.fontUrl)
                store.writeChapter(novelKey, content)
                record = persist(
                    record.copy(
                        downloadedChapterIds = record.downloadedChapterIds + chapter.key.remoteId,
                        failedChapters = record.failedChapters - chapter.key.remoteId,
                        updatedAtMillis = now(),
                    ),
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                record = persist(
                    record.copy(
                        failedChapters = record.failedChapters +
                            (chapter.key.remoteId to error.safeMessage("章节下载失败")),
                        status = OfflineDownloadStatus.FAILED,
                        error = error.safeMessage("下载失败"),
                        updatedAtMillis = now(),
                    ),
                )
                throw error
            }
        }

        return persist(
            record.copy(
                status = OfflineDownloadStatus.COMPLETE,
                error = null,
                updatedAtMillis = now(),
            ),
        )
    }

    private suspend fun loadAllVolumes(novelKey: NovelKey): List<VolumeSummary> {
        val provider = requireNotNull(registry.readerProvider(novelKey.sourceId))
        return buildList {
            var pageNumber = 1
            do {
                val page = provider.getVolumes(novelKey, pageNumber, PAGE_SIZE)
                addAll(page.items)
                pageNumber += 1
                if (pageNumber > MAX_PAGES) {
                    throw SourceException(SourceErrorKind.PARSING, "分卷分页数量异常")
                }
            } while (page.hasMore)
        }.distinctBy { it.key }
    }

    private suspend fun loadAllChapters(
        novelKey: NovelKey,
        volume: VolumeSummary,
    ): List<ChapterSummary> {
        val provider = requireNotNull(registry.readerProvider(novelKey.sourceId))
        return buildList {
            var pageNumber = 1
            do {
                val page = provider.getChapters(novelKey, volume.key, pageNumber, PAGE_SIZE)
                addAll(page.items)
                pageNumber += 1
                if (pageNumber > MAX_PAGES) {
                    throw SourceException(SourceErrorKind.PARSING, "章节分页数量异常")
                }
            } while (page.hasMore)
        }.distinctBy { it.key }
    }

    private suspend fun persist(record: OfflineBookRecord): OfflineBookRecord {
        store.writeBook(record)
        onUpdated(record)
        return record
    }

    private fun Throwable.safeMessage(fallback: String): String = message
        .orEmpty()
        .lineSequence()
        .firstOrNull()
        .orEmpty()
        .take(160)
        .ifBlank { fallback }

    private companion object {
        const val PAGE_SIZE = 50
        const val MAX_PAGES = 1_000
    }
}
