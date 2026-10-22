package io.github.jiangyuyi.lightnovel.feature.reader

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import io.github.jiangyuyi.lightnovel.core.model.ReaderTapInversion
import io.github.jiangyuyi.lightnovel.core.model.ReaderTapZone
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

/** Native pager motion keeps both neighbours attached to the finger, including snap-back. */
@Composable
internal fun HorizontalPageTurn(
    pagerState: PagerState,
    tapZone: ReaderTapZone,
    tapInversion: ReaderTapInversion,
    volumePagingEnabled: Boolean,
    onToggleControls: () -> Unit,
    modifier: Modifier = Modifier,
    pageContent: @Composable (Int) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var turning by remember(pagerState) { mutableStateOf(false) }
    val toggleControls by rememberUpdatedState(onToggleControls)

    fun requestTurn(delta: Int) {
        // Take the gate before launching: rapid taps/keys must not cancel and
        // restart one another or calculate a target from an intermediate page.
        if (pagerState.isScrollInProgress || turning) return
        turning = true
        scope.launch {
            try {
                val target = (pagerState.settledPage + delta).coerceIn(0, pagerState.pageCount - 1)
                if (target != pagerState.settledPage) pagerState.animateScrollToPage(target)
            } finally {
                turning = false
            }
        }
    }

    ReaderVolumeKeyEffect(
        enabled = volumePagingEnabled,
        onPrevious = { requestTurn(-1) },
        onNext = { requestTurn(1) },
    )

    HorizontalPager(
        state = pagerState,
        beyondViewportPageCount = 1,
        pageSpacing = 0.dp,
        // A native pager treats DOWN during a scroll as a request to stop it.
        // Finish a tap/key turn before accepting another gesture.
        userScrollEnabled = !turning,
        modifier = modifier.pointerInput(pagerState, tapZone, tapInversion) {
            awaitEachGesture {
                // Children (notably illustration previews) own their taps and
                // long presses. Pager drag consumption cancels this tap path.
                val down = awaitFirstDown(requireUnconsumed = true)
                val up = waitForUpOrCancellation()
                if (up != null) {
                    when (readerTapAction(
                        tapZone,
                        down.position.x / size.width.toFloat().coerceAtLeast(1f),
                        down.position.y / size.height.toFloat().coerceAtLeast(1f),
                        tapInversion,
                    )) {
                        ReaderTapAction.PREVIOUS -> requestTurn(-1)
                        ReaderTapAction.NEXT -> requestTurn(1)
                        ReaderTapAction.CONTROLS -> toggleControls()
                        ReaderTapAction.NONE -> Unit
                    }
                }
            }
        },
        pageContent = { pageContent(it) },
    )
}

/** A cancelled drag must not persist progress or navigate a chapter boundary. */
@Composable
internal fun ReaderPagerSettledEffect(
    pagerState: PagerState,
    contentKey: Any,
    onPageSettled: (Int) -> Unit,
) {
    val pageSettled by rememberUpdatedState(onPageSettled)
    LaunchedEffect(pagerState, contentKey) {
        snapshotFlow { pagerState.settledPage.takeUnless { pagerState.isScrollInProgress } }
            .filterNotNull()
            .distinctUntilChanged()
            .collect { pageSettled(it) }
    }
}
