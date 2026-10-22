package io.github.jiangyuyi.lightnovel.source.lightnovelshelf

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import io.github.jiangyuyi.lightnovel.core.source.AggregateSearchCoordinator
import io.github.jiangyuyi.lightnovel.core.source.SourceRegistry
import io.github.jiangyuyi.lightnovel.feature.bookshelf.AggregateBookshelfScreen
import io.github.jiangyuyi.lightnovel.feature.bookshelf.AggregateBookshelfViewModel
import io.github.jiangyuyi.lightnovel.feature.discover.AggregateDiscoverScreen
import io.github.jiangyuyi.lightnovel.feature.discover.AggregateDiscoverViewModel
import io.github.jiangyuyi.lightnovel.feature.search.AggregateSearchScreen
import io.github.jiangyuyi.lightnovel.feature.search.AggregateSearchViewModel
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Fixed remote transport at the adapter seam, never a production fallback or a claim of live authentication. */
class ShelfAuthorCardsTest {
    @get:Rule val compose = createComposeRule()
    private val models = ViewModelStore()

    @After fun closeModels() = compose.runOnUiThread { models.clear() }

    @Test fun discoverCardReceivesActualAuthorProgressively() = authorCard("discover")
    @Test fun searchCardReceivesActualAuthorProgressively() = authorCard("search")
    @Test fun remoteShelfCardReceivesActualAuthorProgressively() = authorCard("shelf")
    @Test fun absentAuthorReleasesLoadingCard() = authorCard("discover", author = null)
    @Test fun failedMetadataReleasesLoadingCard() = authorCard("discover", failMetadata = true)

    private fun authorCard(screen: String, author: String? = "真实作者", failMetadata: Boolean = false) {
        val ready = CompletableDeferred<Unit>()
        val gateway = CardGateway(ready, author, failMetadata)
        val source = LightNovelShelfSource(gateway)
        val registry = SourceRegistry(listOf(source))
        lateinit var model: ViewModel
        compose.runOnUiThread {
            model = when (screen) {
                "discover" -> AggregateDiscoverViewModel(registry)
                "search" -> AggregateSearchViewModel(AggregateSearchCoordinator(registry), registry)
                else -> AggregateBookshelfViewModel(registry)
            }
            models.put(screen, model)
        }
        compose.setContent {
            MaterialTheme {
                when (screen) {
                    "discover" -> AggregateDiscoverScreen(model as AggregateDiscoverViewModel, {}, {}, {})
                    "search" -> AggregateSearchScreen(model as AggregateSearchViewModel, {}, {}, initialQuery = "回归")
                    else -> AggregateBookshelfScreen(model as AggregateBookshelfViewModel, {}, {})
                }
            }
        }
        compose.waitUntil(5_000) { gateway.detailCalls == 1 }
        compose.onNodeWithText("作者补全回归书籍").assertDoesNotExist()
        compose.onNodeWithText("轻书架 · 真实作者").assertDoesNotExist()
        compose.onNodeWithContentDescription("正在加载书籍信息").assertIsDisplayed()
        ready.complete(Unit)
        compose.waitUntil(5_000) {
            when (val current = model) {
                is AggregateDiscoverViewModel -> current.state.value.sources.single().pendingMetadata.isEmpty()
                is AggregateSearchViewModel -> current.state.value.sources.single().pendingMetadata.isEmpty()
                is AggregateBookshelfViewModel -> current.state.value.sources.single().pendingMetadata.isEmpty()
                else -> false
            }
        }
        compose.onNodeWithContentDescription("正在加载书籍信息").assertDoesNotExist()
        if (!author.isNullOrBlank() && !failMetadata) {
            compose.onNodeWithText("轻书架 · $author").assertIsDisplayed()
        }
        compose.onNodeWithText("作者补全回归书籍").assertIsDisplayed()
        compose.onNodeWithText("上传者并非作者", substring = true).assertDoesNotExist()
        assertEquals(1, gateway.detailCalls)
    }

    private class CardGateway(
        private val ready: CompletableDeferred<Unit>,
        private val author: String?,
        private val failMetadata: Boolean,
    ) : LightNovelShelfGateway {
        @Volatile var detailCalls = 0
        private fun books() = listOf(ShelfBookItem(7, "作者补全回归书籍", null, null))
        override suspend fun listBooks(order: ShelfBookOrder, page: Int, pageSize: Int) = ShelfBookPage(1, 1, books())
        override suspend fun rank(days: Int) = books()
        override suspend fun search(query: String, page: Int, pageSize: Int) = ShelfBookPage(1, 1, books())
        override suspend fun getBookDetail(bookId: Long): ShelfBookDetail {
            detailCalls++
            ready.await()
            if (failMetadata) throw io.github.jiangyuyi.lightnovel.core.source.SourceException(
                io.github.jiangyuyi.lightnovel.core.source.SourceErrorKind.NETWORK, "unreachable",
            )
            return ShelfBookDetail(bookId, "详情不覆盖标题", null, author, "真实简介", emptyList(), 1, emptyList())
        }
        override suspend fun getShelf() = ShelfRemoteSnapshot(null, listOf(
            ShelfRemoteItem(ShelfRemoteItemType.BOOK, "7", 0, emptyList(), ""),
        ))
        override suspend fun getBooksByIds(ids: List<Long>) = books()
        override suspend fun getNovelContent(bookId: Long, sortNumber: Int): ShelfNovelContent = error("unexpected")
        override suspend fun saveShelf(snapshot: ShelfRemoteSnapshot) = Unit
        override suspend fun getProfile(): ShelfProfile = error("unexpected")
        override suspend fun checkIn(): ShelfCheckInResult = error("unexpected")
        override suspend fun login(email: String, password: String) = false
        override suspend fun restoreSession() = false
        override suspend fun logout() = Unit
    }
}
