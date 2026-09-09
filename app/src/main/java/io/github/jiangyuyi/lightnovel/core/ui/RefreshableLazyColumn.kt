package io.github.jiangyuyi.lightnovel.core.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter

/** A consistent pull-to-refresh surface for pages whose main content is a LazyColumn. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RefreshableLazyColumn(
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(bottom = 24.dp),
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(10.dp),
    listState: LazyListState = rememberLazyListState(),
    onLoadMore: (() -> Unit)? = null,
    loadMoreThreshold: Int = 5,
    content: LazyListScope.() -> Unit,
) {
    require(loadMoreThreshold >= 0) { "load more threshold must not be negative" }
    val refreshState = rememberPullToRefreshState()
    LaunchedEffect(listState, onLoadMore, loadMoreThreshold) {
        val loadMore = onLoadMore ?: return@LaunchedEffect
        snapshotFlow {
            val layout = listState.layoutInfo
            val lastVisible = layout.visibleItemsInfo.lastOrNull()?.index ?: -1
            lastVisible to layout.totalItemsCount
        }
            .distinctUntilChanged()
            .filter { (lastVisible, totalItems) ->
                totalItems > 0 && lastVisible >= totalItems - 1 - loadMoreThreshold
            }
            .collect { loadMore() }
    }
    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = onRefresh,
        state = refreshState,
        modifier = modifier.fillMaxSize(),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding,
            verticalArrangement = verticalArrangement,
            state = listState,
            content = content,
        )
    }
}
