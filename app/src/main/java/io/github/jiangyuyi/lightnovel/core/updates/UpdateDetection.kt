package io.github.jiangyuyi.lightnovel.core.updates

import io.github.jiangyuyi.lightnovel.core.source.NovelSummary

fun NovelSummary.hasNewerContentThan(
    previousChapterCount: Int?,
    previousUnreadChapterCount: Int?,
): Boolean = unreadChapterCount?.let { unread ->
    unread > 0 && unread > (previousUnreadChapterCount ?: 0)
} == true || (
    unreadChapterCount == null &&
        previousChapterCount != null &&
        previousChapterCount > 0 &&
        chapterCount > previousChapterCount
    )

fun NovelSummary.isUpdatedComparedTo(previous: SourceUpdateSnapshot?): Boolean =
    hasNewerContentThan(
        previousChapterCount = previous?.acknowledgedChapterCount ?: previous?.chapterCount,
        previousUnreadChapterCount = previous?.acknowledgedUnreadChapterCount,
    )
