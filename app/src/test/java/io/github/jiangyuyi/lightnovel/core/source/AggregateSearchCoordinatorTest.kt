package io.github.jiangyuyi.lightnovel.core.source

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AggregateSearchCoordinatorTest {
    @Test
    fun `one source failure does not discard another source result`() = runTest {
        val working = FakeSearchSource("working") {
            SourcePage(listOf(novel("working", "1", "可用结果")), page = 1)
        }
        val broken = FakeSearchSource("broken") {
            throw SourceException(SourceErrorKind.NETWORK, "network unavailable")
        }
        val coordinator = AggregateSearchCoordinator(SourceRegistry(listOf(working, broken)))

        val events = coordinator.search("test").toList()
        val successes = events.filterIsInstance<SourceSearchEvent.Success>()
        val failures = events.filterIsInstance<SourceSearchEvent.Failure>()

        assertEquals(listOf("可用结果"), successes.single().page.items.map { it.title })
        assertEquals(SourceErrorKind.NETWORK, failures.single().kind)
    }

    @Test
    fun `fast source result is emitted before slow source result`() = runTest {
        val slow = FakeSearchSource("slow") {
            delay(1_000)
            SourcePage(listOf(novel("slow", "1", "慢")), page = 1)
        }
        val fast = FakeSearchSource("fast") {
            SourcePage(listOf(novel("fast", "1", "快")), page = 1)
        }
        val coordinator = AggregateSearchCoordinator(SourceRegistry(listOf(slow, fast)))

        val successes = coordinator.search("test")
            .filterIsInstance<SourceSearchEvent.Success>()
            .toList()

        assertEquals(listOf("fast", "slow"), successes.map { it.source.id })
    }

    @Test
    fun `slow source is reported as timed out`() = runTest {
        val slow = FakeSearchSource("slow") {
            delay(1_000)
            SourcePage(emptyList(), page = 1)
        }
        val coordinator = AggregateSearchCoordinator(
            registry = SourceRegistry(listOf(slow)),
            perSourceTimeoutMillis = 100,
        )

        val events = coordinator.search("test").toList()
        val failure = events.filterIsInstance<SourceSearchEvent.Failure>().single()

        assertEquals(SourceErrorKind.TIMEOUT, failure.kind)
        assertTrue(failure.message.contains("超时"))
    }

    @Test
    fun `cancelling aggregate search cancels source request`() = runTest {
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val source = FakeSearchSource("source") {
            started.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                cancelled.complete(Unit)
            }
        }
        val coordinator = AggregateSearchCoordinator(SourceRegistry(listOf(source)))

        val searchJob = backgroundScope.launch {
            coordinator.search("first query").collect()
        }
        started.await()
        searchJob.cancelAndJoin()

        assertTrue(cancelled.isCompleted)
    }

    private class FakeSearchSource(
        id: String,
        private val response: suspend () -> SourcePage<NovelSummary>,
    ) : NovelSource, SearchProvider {
        override val descriptor = SourceDescriptor(id, id, setOf(SourceCapability.SEARCH))

        override suspend fun search(query: String, page: Int, pageSize: Int): SourcePage<NovelSummary> = response()
    }

    private fun novel(sourceId: String, remoteId: String, title: String) = NovelSummary(
        key = NovelKey(sourceId, remoteId),
        title = title,
    )
}
