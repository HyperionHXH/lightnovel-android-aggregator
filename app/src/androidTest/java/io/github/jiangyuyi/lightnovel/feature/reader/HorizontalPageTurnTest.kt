package io.github.jiangyuyi.lightnovel.feature.reader

import android.view.KeyEvent
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.github.jiangyuyi.lightnovel.MainActivity
import io.github.jiangyuyi.lightnovel.core.model.ReaderImageScale
import io.github.jiangyuyi.lightnovel.core.model.ReaderTapInversion
import io.github.jiangyuyi.lightnovel.core.model.ReaderTapZone
import io.github.jiangyuyi.lightnovel.core.ui.ReaderImagePreview
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class HorizontalPageTurnTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private lateinit var pagerState: PagerState
    private val settledPages = mutableListOf<Int>()
    private var controlsToggles = 0

    private fun setPager(
        initialPage: Int = 0,
        inversion: ReaderTapInversion = ReaderTapInversion.NONE,
        image: Boolean = false,
        volumeEnabled: Boolean = false,
    ) {
        compose.activity.runOnUiThread { compose.activity.setContent {
            MaterialTheme {
                val state = rememberPagerState(initialPage = initialPage) { 3 }
                pagerState = state
                ReaderPagerSettledEffect(state, Unit) { settledPages += it }
                Box(Modifier.size(300.dp)) {
                    HorizontalPageTurn(
                        pagerState = state,
                        tapZone = ReaderTapZone.DEFAULT,
                        tapInversion = inversion,
                        volumePagingEnabled = volumeEnabled,
                        onToggleControls = { controlsToggles++ },
                        modifier = Modifier.fillMaxSize().testTag("pager"),
                    ) { index ->
                        Box(Modifier.fillMaxSize().testTag("page-$index")) {
                            if (image) {
                                ReaderImagePreview(
                                    url = "file:///missing-reader-illustration.png",
                                    modifier = Modifier.fillMaxSize(),
                                    imageScale = ReaderImageScale.FIT,
                                    contentDescription = "插图",
                                    errorText = "插图加载失败",
                                )
                            } else {
                                Text("第 ${index + 1} 页")
                            }
                        }
                    }
                }
            }
        } }
        compose.waitForIdle()
    }

    private fun advanceIntoAnimation() {
        repeat(20) {
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            if (pagerState.isScrollInProgress && pagerState.currentPageOffsetFraction != 0f) return
        }
        error("Page animation did not start")
    }

    private fun assertAdjacentPagesTranslateTogether(first: Int, second: Int) {
        compose.onNodeWithTag("page-$first").assertIsDisplayed()
        compose.onNodeWithTag("page-$second").assertIsDisplayed()
        val viewport = compose.onNodeWithTag("pager").getUnclippedBoundsInRoot()
        val current = compose.onNodeWithTag("page-$first").getUnclippedBoundsInRoot()
        val adjacent = compose.onNodeWithTag("page-$second").getUnclippedBoundsInRoot()
        assertTrue("Current page must follow the finger left", current.left < viewport.left)
        assertTrue("Adjacent page must enter from the right", adjacent.left > viewport.left)
        assertTrue("Adjacent page must already be inside the viewport", adjacent.left < viewport.right)
        assertEquals((viewport.right - viewport.left).value, (adjacent.left - current.left).value, 1f)
    }

    @Test
    fun fingerMovesTwoAdjacentPagesWithoutSavingIntermediateCurrentPage() {
        setPager()
        compose.onNodeWithTag("pager").performTouchInput {
            down(Offset(width * 0.9f, centerY))
            moveTo(Offset(width * 0.25f, centerY), delayMillis = 500)
        }

        assertAdjacentPagesTranslateTogether(0, 1)
        compose.runOnIdle {
            assertEquals(1, pagerState.currentPage)
            assertEquals(0, pagerState.settledPage)
            assertEquals(listOf(0), settledPages)
        }
        compose.onNodeWithTag("pager").performTouchInput {
            advanceEventTime(500)
            up()
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(1, pagerState.settledPage)
            assertEquals(listOf(0, 1), settledPages)
            assertEquals(0, controlsToggles)
        }
    }

    @Test
    fun canceledShortDragSnapsBackWithoutSavingOrTogglingControls() {
        setPager()
        compose.onNodeWithTag("pager").performTouchInput {
            down(Offset(width * 0.8f, centerY))
            moveTo(Offset(width * 0.6f, centerY), delayMillis = 500)
        }
        assertAdjacentPagesTranslateTogether(0, 1)
        compose.onNodeWithTag("pager").performTouchInput { cancel() }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(0, pagerState.settledPage)
            assertEquals(0f, pagerState.currentPageOffsetFraction, 0.001f)
            assertEquals(listOf(0), settledPages)
            assertEquals(0, controlsToggles)
        }
    }

    @Test
    fun reversingDragBeforeReleaseReturnsToOriginalPage() {
        setPager()
        compose.onNodeWithTag("pager").performTouchInput {
            down(Offset(width * 0.9f, centerY))
            moveTo(Offset(width * 0.2f, centerY), delayMillis = 500)
        }
        compose.runOnIdle { assertEquals(1, pagerState.currentPage) }
        compose.onNodeWithTag("pager").performTouchInput {
            moveTo(Offset(width * 0.85f, centerY), delayMillis = 500)
            advanceEventTime(500)
            up()
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(0, pagerState.settledPage)
            assertEquals(listOf(0), settledPages)
        }
    }

    @Test
    fun rightwardSwipeCommitsPreviousPage() {
        setPager(initialPage = 1)
        compose.onNodeWithTag("pager").performTouchInput {
            down(Offset(width * 0.1f, centerY))
            moveTo(Offset(width * 0.9f, centerY), delayMillis = 500)
            advanceEventTime(500)
            up()
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(0, pagerState.settledPage)
            assertEquals(listOf(1, 0), settledPages)
        }
    }

    @Test
    fun rapidEdgeTapsDoNotRestartOrOverlapPageAnimation() {
        setPager()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("pager").performTouchInput {
            click(Offset(width * 0.9f, centerY))
        }
        advanceIntoAnimation()
        assertAdjacentPagesTranslateTogether(0, 1)
        compose.runOnIdle { assertEquals(listOf(0), settledPages) }
        compose.onNodeWithTag("pager").performTouchInput {
            click(Offset(width * 0.9f, centerY))
        }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(1, pagerState.settledPage)
            assertEquals(listOf(0, 1), settledPages)
        }
    }

    @Test
    fun volumeKeyUsesTheSameAdjacentPageAnimation() {
        setPager(volumeEnabled = true)
        compose.mainClock.autoAdvance = false
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_VOLUME_DOWN)
        advanceIntoAnimation()
        assertAdjacentPagesTranslateTogether(0, 1)
        compose.runOnIdle { assertEquals(listOf(0), settledPages) }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(1, pagerState.settledPage)
            assertEquals(listOf(0, 1), settledPages)
        }
    }

    @Test
    fun invertedEdgeTapGoesPreviousAndCenterTapStillTogglesControls() {
        setPager(initialPage = 1, inversion = ReaderTapInversion.LEFT_RIGHT)
        compose.onNodeWithTag("pager").performTouchInput {
            click(Offset(width * 0.9f, centerY))
        }
        compose.waitForIdle()
        compose.onNodeWithTag("pager").performTouchInput { click(center) }
        compose.runOnIdle {
            assertEquals(0, pagerState.settledPage)
            assertEquals(listOf(1, 0), settledPages)
            assertEquals(1, controlsToggles)
        }
    }

    @Test
    fun physicalImageTapOpensPreviewAndLongPressRetainsSaveAction() {
        setPager(image = true)
        compose.onNodeWithTag("page-0").performTouchInput {
            click(Offset(width * 0.9f, centerY))
        }
        compose.onNodeWithTag("reader-image-dialog").assertIsDisplayed()
        compose.onNodeWithTag("reader-image-dialog-image").performTouchInput { longClick() }
        compose.onNodeWithTag("reader-image-actions").assertIsDisplayed()
        compose.onNodeWithTag("reader-image-save").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(0, pagerState.settledPage)
            assertEquals(listOf(0), settledPages)
            assertEquals(0, controlsToggles)
        }
    }
}
