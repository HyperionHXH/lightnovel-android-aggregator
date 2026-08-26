package io.github.jiangyuyi.lightnovel.feature.discover

import io.github.jiangyuyi.lightnovel.MainDispatcherRule
import io.github.jiangyuyi.lightnovel.core.source.DiscoverFeed
import io.github.jiangyuyi.lightnovel.core.source.DiscoverProvider
import io.github.jiangyuyi.lightnovel.core.source.NovelKey
import io.github.jiangyuyi.lightnovel.core.source.NovelSource
import io.github.jiangyuyi.lightnovel.core.source.NovelSummary
import io.github.jiangyuyi.lightnovel.core.source.SourceCapability
import io.github.jiangyuyi.lightnovel.core.source.SourceDescriptor
import io.github.jiangyuyi.lightnovel.core.source.SourceErrorKind
import io.github.jiangyuyi.lightnovel.core.source.SourceException
import io.github.jiangyuyi.lightnovel.core.source.SourcePage
import io.github.jiangyuyi.lightnovel.core.source.SourceRegistry
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AggregateDiscoverViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `discover loads one selected source at a time`() =
        runTest(mainDispatcherRule.dispatcher) {
            var firstCalls = 0
            var secondCalls = 0
            val first = FakeDiscoverSource("first") { _, _, _ ->
                firstCalls += 1
                SourcePage(listOf(novel("first", "1"), novel("first", "2")), page = 1)
            }
            val second = FakeDiscoverSource("second") { _, _, _ ->
                secondCalls += 1
                SourcePage(listOf(novel("second", "1"), novel("second", "2")), page = 1)
            }
            val viewModel = AggregateDiscoverViewModel(SourceRegistry(listOf(first, second)))

            advanceUntilIdle()
            assertEquals(listOf("first-1", "first-2"), viewModel.state.value.sources.single().items.map { it.title })
            assertEquals(1, firstCalls)
            assertEquals(0, secondCalls)

            viewModel.selectSource("second")
            advanceUntilIdle()
            assertEquals(listOf("second-1", "second-2"), viewModel.state.value.sources.single().items.map { it.title })
            assertEquals(1, secondCalls)
        }

    @Test
    fun `one source failure remains isolated from another source result`() =
        runTest(mainDispatcherRule.dispatcher) {
            val working = FakeDiscoverSource("working") { _, _, _ ->
                SourcePage(listOf(novel("working", "1")), page = 1)
            }
            val broken = FakeDiscoverSource("broken") { _, _, _ ->
                throw SourceException(SourceErrorKind.NETWORK, "offline")
            }
            val viewModel = AggregateDiscoverViewModel(SourceRegistry(listOf(working, broken)))

            advanceUntilIdle()

            val workingState = viewModel.state.value.sources.single()
            assertEquals("working-1", workingState.items.single().title)
            assertFalse(workingState.loading)
            viewModel.selectSource("broken")
            advanceUntilIdle()
            val brokenState = viewModel.state.value.sources.single()
            assertEquals(SourceErrorKind.NETWORK, brokenState.errorKind)
            assertEquals("网络连接失败，请检查网络", brokenState.errorMessage)
        }

    @Test
    fun `selecting a source exposes its own feeds and only loads that source`() =
        runTest(mainDispatcherRule.dispatcher) {
            val calls = mutableListOf<Pair<String, DiscoverFeed>>()
            val first = FakeDiscoverSource(
                id = "first",
                discoverFeeds = listOf(DiscoverFeed.POPULAR, DiscoverFeed.WEEKLY_RANK),
            ) { feed, _, _ ->
                calls += "first" to feed
                SourcePage(emptyList(), page = 1)
            }
            val second = FakeDiscoverSource("second") { feed, _, _ ->
                calls += "second" to feed
                SourcePage(emptyList(), page = 1)
            }
            val viewModel = AggregateDiscoverViewModel(SourceRegistry(listOf(first, second)))
            advanceUntilIdle()
            calls.clear()

            viewModel.selectFeed(DiscoverFeed.WEEKLY_RANK)
            advanceUntilIdle()

            assertEquals(listOf(DiscoverFeed.POPULAR, DiscoverFeed.WEEKLY_RANK), viewModel.state.value.feeds)
            assertEquals(listOf("first" to DiscoverFeed.WEEKLY_RANK), calls)
            assertEquals(listOf("first"), viewModel.state.value.sources.map { it.descriptor.id })
        }

    @Test
    fun `slow discover source is reported as timed out`() = runTest(mainDispatcherRule.dispatcher) {
        val slow = FakeDiscoverSource("slow") { _, _, _ ->
            delay(1_000)
            SourcePage(emptyList(), page = 1)
        }
        val viewModel = AggregateDiscoverViewModel(
            registry = SourceRegistry(listOf(slow)),
            perSourceTimeoutMillis = 100,
        )

        advanceUntilIdle()

        val source = viewModel.state.value.sources.single()
        assertEquals(SourceErrorKind.TIMEOUT, source.errorKind)
        assertEquals("请求超时，请稍后重试", source.errorMessage)
    }

    private class FakeDiscoverSource(
        id: String,
        override val discoverFeeds: List<DiscoverFeed> = listOf(
            DiscoverFeed.POPULAR,
            DiscoverFeed.NEWEST,
            DiscoverFeed.LATEST,
        ),
        private val response: suspend (DiscoverFeed, Int, Int) -> SourcePage<NovelSummary>,
    ) : NovelSource, DiscoverProvider {
        override val descriptor = SourceDescriptor(id, id, setOf(SourceCapability.DISCOVER))

        override suspend fun discover(
            feed: DiscoverFeed,
            page: Int,
            pageSize: Int,
        ): SourcePage<NovelSummary> = response(feed, page, pageSize)
    }

    private fun novel(sourceId: String, remoteId: String) = NovelSummary(
        key = NovelKey(sourceId, remoteId),
        title = "$sourceId-$remoteId",
    )
}
