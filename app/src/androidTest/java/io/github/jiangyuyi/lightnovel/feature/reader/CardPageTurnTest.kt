package io.github.jiangyuyi.lightnovel.feature.reader

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.pager.PagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class CardPageTurnTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun draggingCurrentCardLeftCommitsNextPage() {
        var pagerStateRef: PagerState? = null
        compose.setContent {
            MaterialTheme {
                val pagerState = rememberPagerState { 3 }
                pagerStateRef = pagerState
                Box(Modifier.size(300.dp)) {
                    CardPageTurn(
                        pagerState = pagerState,
                        pageCount = 3,
                        hasPreviousChapter = false,
                        hasNextChapter = false,
                        volumePagingEnabled = false,
                        onPreviousChapter = {},
                        onNextChapter = {},
                        onTap = { _, _, _, _ -> },
                        pageContent = { index ->
                            Box(Modifier.fillMaxSize().testTag("page-$index")) {
                                Text("第 ${index + 1} 页", color = Color.Black)
                            }
                        },
                        modifier = Modifier.fillMaxSize().testTag("card-turn"),
                    )
                }
            }
        }

        compose.onNodeWithTag("card-turn").performTouchInput {
            swipe(
                start = Offset(280f, 150f),
                end = Offset(10f, 150f),
                durationMillis = 300,
            )
        }
        compose.waitUntil(timeoutMillis = 2_000) {
            pagerStateRef?.currentPage == 1
        }

        assertEquals(1, pagerStateRef?.currentPage)
    }

    @Test
    fun draggingCurrentCardRightCommitsPreviousPage() {
        var pagerStateRef: PagerState? = null
        compose.setContent {
            MaterialTheme {
                val pagerState = rememberPagerState(initialPage = 1) { 3 }
                pagerStateRef = pagerState
                Box(Modifier.size(300.dp)) {
                    CardPageTurn(
                        pagerState = pagerState,
                        pageCount = 3,
                        hasPreviousChapter = false,
                        hasNextChapter = false,
                        volumePagingEnabled = false,
                        onPreviousChapter = {},
                        onNextChapter = {},
                        onTap = { _, _, _, _ -> },
                        pageContent = { index ->
                            Box(Modifier.fillMaxSize().testTag("page-$index")) {
                                Text("第 ${index + 1} 页", color = Color.Black)
                            }
                        },
                        modifier = Modifier.fillMaxSize().testTag("card-turn"),
                    )
                }
            }
        }

        compose.onNodeWithTag("card-turn").performTouchInput {
            swipe(
                start = Offset(20f, 150f),
                end = Offset(290f, 150f),
                durationMillis = 300,
            )
        }
        compose.waitUntil(timeoutMillis = 2_000) {
            pagerStateRef?.currentPage == 0
        }

        assertEquals(0, pagerStateRef?.currentPage)
    }

    @Test
    fun previousTurnPlacesPreviousPageAboveCurrentAndEntersFromLeft() {
        val layout = cardTurnLayout(
            direction = CardTurnDirection.PREVIOUS,
            currentPage = 1,
            adjacentPage = 0,
            containerWidth = 300f,
            dragOffset = 120f,
        )

        assertEquals(1, layout.underPage)
        assertEquals(0, layout.overPage)
        assertEquals(-180f, layout.overTranslationX)
    }

    @Test
    fun nextTurnPlacesNextPageBelowCurrentAndMovesCurrentLeft() {
        val layout = cardTurnLayout(
            direction = CardTurnDirection.NEXT,
            currentPage = 1,
            adjacentPage = 2,
            containerWidth = 300f,
            dragOffset = -120f,
        )

        assertEquals(2, layout.underPage)
        assertEquals(1, layout.overPage)
        assertEquals(-120f, layout.overTranslationX)
    }

    @Test
    fun cardLayersKeepAdjacentPageContentVisibleBelowMovingPage() {
        compose.setContent {
            MaterialTheme {
                Box(Modifier.size(300.dp)) {
                    CardPageTurnLayers(
                        currentPage = 1,
                        adjacentPage = 2,
                        direction = CardTurnDirection.NEXT,
                        dragOffset = -120f,
                        containerWidth = 300,
                        pageBackground = Color.White,
                        pageContent = { index ->
                            Box(Modifier.fillMaxSize().testTag("layer-page-$index")) {
                                Text("第 ${index + 1} 页", color = Color.Black)
                            }
                        },
                    )
                }
            }
        }

        compose.onNodeWithTag("layer-page-1").assertIsDisplayed()
        compose.onNodeWithTag("layer-page-2").assertIsDisplayed()
    }

}
