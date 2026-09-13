package io.github.jiangyuyi.lightnovel.feature.reader

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.zIndex
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * A two-layer, card-style page turn. For a next-page turn the next page stays
 * fixed below the current page; for a previous-page turn the current page stays
 * fixed below the previous page as it enters from the left. This intentionally
 * avoids rotation, lighting and neighbour movement from a pager transition.
 */
@Composable
internal fun CardPageTurn(
    pagerState: PagerState,
    pageCount: Int,
    hasPreviousChapter: Boolean,
    hasNextChapter: Boolean,
    volumePagingEnabled: Boolean,
    onPreviousChapter: () -> Unit,
    onNextChapter: () -> Unit,
    onTap: (x: Float, y: Float, width: Int, height: Int) -> Unit,
    pageContent: @Composable (pageIndex: Int) -> Unit,
    pageBackground: Color = Color.Transparent,
    modifier: Modifier = Modifier,
) {
    val safePageCount = pageCount.coerceAtLeast(1)
    val offset = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    var containerWidth by remember { mutableIntStateOf(1) }
    var containerHeight by remember { mutableIntStateOf(1) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var adjacentPage by remember { mutableIntStateOf(-1) }
    var adjacentDirection by remember { mutableStateOf<CardTurnDirection?>(null) }
    var animating by remember { mutableStateOf(false) }
    val currentPage = pagerState.currentPage.coerceIn(0, safePageCount - 1)

    LaunchedEffect(currentPage, safePageCount) {
        offset.snapTo(0f)
        dragOffset = 0f
        adjacentPage = -1
        adjacentDirection = null
    }

    suspend fun settleTurn(
        direction: CardTurnDirection,
        targetOverride: Int? = null,
        commitImmediately: Boolean = false,
    ) {
        if (animating) return
        animating = true
        try {
            val current = pagerState.currentPage.coerceIn(0, safePageCount - 1)
            val target = targetOverride ?: when (direction) {
                CardTurnDirection.PREVIOUS -> (current - 1).takeIf { it >= 0 } ?: -1
                CardTurnDirection.NEXT -> (current + 1).takeIf { it < safePageCount } ?: -1
            }
            val width = containerWidth.coerceAtLeast(1).toFloat()
            val edgeTurn = target < 0 && when (direction) {
                CardTurnDirection.PREVIOUS -> current == 0 && hasPreviousChapter
                CardTurnDirection.NEXT -> current == safePageCount - 1 && hasNextChapter
            }
            adjacentPage = target
            adjacentDirection = direction.takeIf { target >= 0 }
            val signedDistance = if (direction == CardTurnDirection.NEXT) -dragOffset else dragOffset
            val shouldCommit = commitImmediately || signedDistance >= width * 0.25f
            if ((target >= 0 || edgeTurn) && shouldCommit) {
                val end = if (direction == CardTurnDirection.NEXT) -width else width
                offset.snapTo(dragOffset)
                offset.animateTo(end, animationSpec = tween(durationMillis = 180))
                if (target >= 0) {
                    // Card mode does not host a HorizontalPager; request the
                    // state update without waiting for pager layout work.
                    pagerState.requestScrollToPage(target)
                } else if (direction == CardTurnDirection.PREVIOUS) {
                    onPreviousChapter()
                } else {
                    onNextChapter()
                }
            } else {
                offset.snapTo(dragOffset)
                offset.animateTo(
                    0f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessMedium,
                    ),
                )
            }
        } finally {
            offset.snapTo(0f)
            dragOffset = 0f
            adjacentPage = -1
            adjacentDirection = null
            animating = false
        }
    }

    fun requestTurn(direction: CardTurnDirection) {
        scope.launch {
            settleTurn(direction, commitImmediately = true)
        }
    }

    // Card mode owns volume handling so the regular pager handler cannot race it.
    ReaderVolumeKeyEffect(
        enabled = volumePagingEnabled,
        onPrevious = { requestTurn(CardTurnDirection.PREVIOUS) },
        onNext = { requestTurn(CardTurnDirection.NEXT) },
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .clipToBounds()
            .onSizeChanged {
                containerWidth = it.width.coerceAtLeast(1)
                containerHeight = it.height.coerceAtLeast(1)
            }
            .pointerInput(currentPage, safePageCount, hasPreviousChapter, hasNextChapter) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var previous = down.position
                    var totalX = 0f
                    var totalY = 0f
                    var dragStarted = false
                    var canceled = false
                    var direction = CardTurnDirection.NEXT
                    var target = -1

                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id }
                            ?: break
                        val position = change.position
                        val deltaX = position.x - previous.x
                        val deltaY = position.y - previous.y
                        if (change.pressed) {
                            if (!dragStarted && !canceled) {
                                totalX += deltaX
                                totalY += deltaY
                                if (abs(totalX) > viewConfiguration.touchSlop ||
                                    abs(totalY) > viewConfiguration.touchSlop
                                ) {
                                    if (abs(totalY) > abs(totalX)) {
                                        canceled = true
                                    } else {
                                        dragStarted = true
                                        direction = if (totalX < 0f) {
                                            CardTurnDirection.NEXT
                                        } else {
                                            CardTurnDirection.PREVIOUS
                                        }
                                        val current = pagerState.currentPage.coerceIn(0, safePageCount - 1)
                                        target = when (direction) {
                                            CardTurnDirection.PREVIOUS -> (current - 1).takeIf { it >= 0 } ?: -1
                                            CardTurnDirection.NEXT -> (current + 1).takeIf { it < safePageCount } ?: -1
                                        }
                                        adjacentPage = target
                                        adjacentDirection = direction.takeIf { target >= 0 }
                                    }
                                }
                            }
                            if (dragStarted) {
                                change.consume()
                                dragOffset = (dragOffset + deltaX).coerceIn(
                                    -containerWidth.toFloat(),
                                    containerWidth.toFloat(),
                                )
                            }
                            previous = position
                        } else {
                            if (dragStarted) {
                                scope.launch {
                                    settleTurn(direction, targetOverride = target)
                                }
                            } else if (!canceled && abs(totalX) <= viewConfiguration.touchSlop &&
                                abs(totalY) <= viewConfiguration.touchSlop
                            ) {
                                onTap(
                                    down.position.x,
                                    down.position.y,
                                    containerWidth,
                                    containerHeight,
                                )
                            }
                            break
                        }
                    }
                }
            },
    ) {
        val direction = adjacentDirection
        if (adjacentPage in 0 until safePageCount && direction != null) {
            CardPageTurnLayers(
                currentPage = currentPage,
                adjacentPage = adjacentPage,
                direction = direction,
                dragOffset = if (animating) offset.value else dragOffset,
                containerWidth = containerWidth,
                pageBackground = pageBackground,
                pageContent = pageContent,
            )
        } else {
            Box(
                Modifier
                    .fillMaxSize()
                    .zIndex(1f)
                    .offset {
                        IntOffset(
                            (if (animating) offset.value else dragOffset).roundToInt(),
                            0,
                        )
                    }
                    .background(pageBackground)
            ) {
                pageContent(currentPage)
            }
        }
    }
}

/**
 * Draws the two complete pages used by card paging. The lower page is laid
 * out first and remains stationary. Only the upper page uses a placement
 * offset, so its background, text and illustrations move as one surface.
 * The lambda form of offset reads drag state during placement and avoids
 * remeasuring the paginated content on every pointer event.
 */
@Composable
internal fun CardPageTurnLayers(
    currentPage: Int,
    adjacentPage: Int,
    direction: CardTurnDirection,
    dragOffset: Float,
    containerWidth: Int,
    pageBackground: Color,
    pageContent: @Composable (pageIndex: Int) -> Unit,
) {
    val underPage = if (direction == CardTurnDirection.PREVIOUS) currentPage else adjacentPage
    val overPage = if (direction == CardTurnDirection.PREVIOUS) adjacentPage else currentPage
    val overTranslation = when (direction) {
        CardTurnDirection.PREVIOUS -> dragOffset - containerWidth
        CardTurnDirection.NEXT -> dragOffset
    }

    key("card-under-$underPage") {
        Box(
            Modifier
                .fillMaxSize()
                .zIndex(0f)
                .background(pageBackground),
        ) {
            pageContent(underPage)
        }
    }
    key("card-over-$overPage") {
        Box(
            Modifier
                .fillMaxSize()
                .zIndex(1f)
                .offset { IntOffset(overTranslation.roundToInt(), 0) }
                .background(pageBackground),
        ) {
            pageContent(overPage)
        }
    }
}

internal enum class CardTurnDirection {
    PREVIOUS,
    NEXT,
}

internal data class CardTurnLayout(
    val underPage: Int,
    val overPage: Int,
    val overTranslationX: Float,
)

internal fun cardTurnLayout(
    direction: CardTurnDirection,
    currentPage: Int,
    adjacentPage: Int,
    containerWidth: Float,
    dragOffset: Float,
): CardTurnLayout = when (direction) {
    CardTurnDirection.PREVIOUS -> CardTurnLayout(
        underPage = currentPage,
        overPage = adjacentPage,
        overTranslationX = dragOffset - containerWidth,
    )
    CardTurnDirection.NEXT -> CardTurnLayout(
        underPage = adjacentPage,
        overPage = currentPage,
        overTranslationX = dragOffset,
    )
}
