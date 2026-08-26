package io.github.jiangyuyi.lightnovel.feature.search

import io.github.jiangyuyi.lightnovel.core.source.NovelKey
import io.github.jiangyuyi.lightnovel.core.source.NovelSummary
import io.github.jiangyuyi.lightnovel.core.source.SourceCapability
import io.github.jiangyuyi.lightnovel.core.source.SourceDescriptor
import org.junit.Assert.assertEquals
import org.junit.Test

class AggregateSearchPresentationTest {
    @Test
    fun `results alternate sources instead of burying the second source`() {
        val first = source("first", 3)
        val second = source("second", 2)

        val results = interleaveSourceResults(listOf(first, second))

        assertEquals(
            listOf("first-1", "second-1", "first-2", "second-2", "first-3"),
            results.map { it.novel.title },
        )
    }

    private fun source(id: String, count: Int) = SourceSearchUiState(
        descriptor = SourceDescriptor(id, id, setOf(SourceCapability.SEARCH)),
        items = (1..count).map { index ->
            NovelSummary(
                key = NovelKey(id, index.toString()),
                title = "$id-$index",
            )
        },
        searched = true,
    )
}
