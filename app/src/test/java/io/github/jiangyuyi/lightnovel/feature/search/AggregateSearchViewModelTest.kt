package io.github.jiangyuyi.lightnovel.feature.search

import io.github.jiangyuyi.lightnovel.MainDispatcherRule
import io.github.jiangyuyi.lightnovel.core.source.AggregateSearchCoordinator
import io.github.jiangyuyi.lightnovel.core.source.NovelKey
import io.github.jiangyuyi.lightnovel.core.source.NovelSource
import io.github.jiangyuyi.lightnovel.core.source.NovelSummary
import io.github.jiangyuyi.lightnovel.core.source.SearchProvider
import io.github.jiangyuyi.lightnovel.core.source.SourceCapability
import io.github.jiangyuyi.lightnovel.core.source.SourceDescriptor
import io.github.jiangyuyi.lightnovel.core.source.SourceErrorKind
import io.github.jiangyuyi.lightnovel.core.source.SourceException
import io.github.jiangyuyi.lightnovel.core.source.SourcePage
import io.github.jiangyuyi.lightnovel.core.source.SourceRegistry
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AggregateSearchViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `query is debounced before both sources are searched`() = runTest(mainDispatcherRule.dispatcher) {
        var calls = 0
        val first = FakeSearchSource("first") { query ->
            calls += 1
            SourcePage(listOf(novel("first", query)), page = 1)
        }
        val second = FakeSearchSource("second") { query ->
            calls += 1
            SourcePage(listOf(novel("second", query)), page = 1)
        }
        val registry = SourceRegistry(listOf(first, second))
        val viewModel = AggregateSearchViewModel(AggregateSearchCoordinator(registry), registry)

        viewModel.setQuery("书名")
        advanceTimeBy(399)
        assertEquals(0, calls)

        advanceTimeBy(1)
        advanceUntilIdle()

        assertEquals(2, calls)
        assertEquals(listOf("书名", "书名"), viewModel.state.value.sources.flatMap { it.items }.map { it.title })
        assertTrue(viewModel.state.value.sources.all { it.searched })
    }

    @Test
    fun `authentication failure remains isolated from successful source`() = runTest(mainDispatcherRule.dispatcher) {
        val working = FakeSearchSource("working") {
            SourcePage(listOf(novel("working", "可用结果")), page = 1)
        }
        val protected = FakeSearchSource("protected") {
            throw SourceException(SourceErrorKind.AUTHENTICATION, "unauthorized")
        }
        val registry = SourceRegistry(listOf(working, protected))
        val viewModel = AggregateSearchViewModel(
            AggregateSearchCoordinator(registry),
            registry,
            debounceMillis = 0,
        )

        viewModel.setQuery("测试")
        advanceUntilIdle()

        val workingState = viewModel.state.value.sources.first { it.descriptor.id == "working" }
        val protectedState = viewModel.state.value.sources.first { it.descriptor.id == "protected" }
        assertEquals("可用结果", workingState.items.single().title)
        assertFalse(workingState.loading)
        assertEquals(SourceErrorKind.AUTHENTICATION, protectedState.errorKind)
        assertEquals("请先登录该来源", protectedState.errorMessage)
    }

    private class FakeSearchSource(
        id: String,
        private val response: suspend (String) -> SourcePage<NovelSummary>,
    ) : NovelSource, SearchProvider {
        override val descriptor = SourceDescriptor(id, id, setOf(SourceCapability.SEARCH))

        override suspend fun search(query: String, page: Int, pageSize: Int): SourcePage<NovelSummary> =
            response(query)
    }

    private fun novel(sourceId: String, title: String) = NovelSummary(
        key = NovelKey(sourceId, "1"),
        title = title,
    )
}
