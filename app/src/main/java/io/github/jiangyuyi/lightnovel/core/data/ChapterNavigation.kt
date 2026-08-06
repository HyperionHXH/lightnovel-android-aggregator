package io.github.jiangyuyi.lightnovel.core.data

import io.github.jiangyuyi.lightnovel.core.model.ChapterSummary

internal data class ChapterNeighbors(
    val previousChapterId: Long?,
    val nextChapterId: Long?,
)

internal fun resolveChapterNeighbors(
    chapters: List<ChapterSummary>,
    currentChapterId: Long,
): ChapterNeighbors? {
    val currentIndex = chapters.indexOfFirst { it.id == currentChapterId }
    if (currentIndex < 0) return null
    return ChapterNeighbors(
        previousChapterId = chapters.getOrNull(currentIndex - 1)?.id,
        nextChapterId = chapters.getOrNull(currentIndex + 1)?.id,
    )
}
