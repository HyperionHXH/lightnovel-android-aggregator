package io.github.jiangyuyi.lightnovel.feature.reader

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import io.github.jiangyuyi.lightnovel.core.model.ReaderMode
import io.github.jiangyuyi.lightnovel.core.model.ReaderPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** Exercises the actual paginating screen bodies, not just the shared pager wiring. */
@RunWith(Parameterized::class)
class PagedReaderStateTest(private val sourceReader: Boolean) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "sourceReader={0}")
        fun readers(): List<Array<Any>> = listOf(arrayOf<Any>(false), arrayOf<Any>(true))
    }

    @get:Rule
    val compose = createComposeRule()

    private val progress = mutableListOf<Int>()
    private var previousChapters = 0
    private var nextChapters = 0
    private var anchor by mutableIntStateOf(0)
    private var preferences by mutableStateOf(ReaderPreferences(mode = ReaderMode.PAGED))
    private var viewportHeight by mutableStateOf(400.dp)
    private var blocks by mutableStateOf<List<ReaderBlock>>(List(6) { index ->
        // Each illustration fills exactly one page, independent of device font metrics.
        ReaderBlock.Illustration("file:///missing-reader-$index.png", width = 1, height = 100)
    })

    private fun setReader(initialAnchor: Int) {
        anchor = initialAnchor
        compose.setContent {
            MaterialTheme {
                Box(Modifier.size(300.dp, viewportHeight).testTag("reader")) {
                    if (sourceReader) {
                        SourcePagedReader(
                            blocks = blocks,
                            chapterTitle = "Fixture",
                            preferences = preferences,
                            colors = SourceReaderColors(Color.White, Color.Black),
                            chapterFontFamily = null,
                            customFontFamily = null,
                            anchorBlock = anchor,
                            onPreviousChapter = { previousChapters++ },
                            hasPreviousChapter = true,
                            hasNextChapter = true,
                            onNextChapter = { nextChapters++ },
                            onProgress = { progress += it },
                            onToggleControls = {},
                            controlsVisible = true,
                            showProgressBar = true,
                            volumePagingEnabled = false,
                            safeTopPadding = 0.dp,
                        )
                    } else {
                        PagedReader(
                            blocks = blocks,
                            chapterTitle = "Fixture",
                            preferences = preferences,
                            colors = ReaderColors(Color.White, Color.Black),
                            customFontFamily = null,
                            anchorBlock = anchor,
                            onAnchorChanged = { anchor = it },
                            onProgress = { progress += it },
                            onToggleControls = {},
                            onPreviousChapter = { previousChapters++ },
                            onNextChapter = { nextChapters++ },
                            hasPreviousChapter = true,
                            hasNextChapter = true,
                            safeTopPadding = 0.dp,
                            controlsVisible = true,
                            showProgressBar = true,
                            volumePagingEnabled = false,
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun assertPage(number: Int) {
        compose.onNodeWithText("Fixture · 第 $number / 6 页").assertIsDisplayed()
    }

    private fun swipeNext() {
        compose.onNodeWithTag("reader").performTouchInput {
            down(Offset(width * 0.9f, height * 0.4f))
            moveTo(Offset(width * 0.1f, height * 0.4f), delayMillis = 500)
            advanceEventTime(500)
            up()
        }
        compose.waitForIdle()
    }

    @Test
    fun restoredAnchorAndLabelsExcludeLeadingChapterBoundary() {
        setReader(initialAnchor = 3)
        assertPage(4)
        compose.runOnIdle {
            assertEquals(listOf(3), progress)
            assertEquals(0, previousChapters)
            assertEquals(0, nextChapters)
        }
        swipeNext()
        assertPage(5)
        compose.runOnIdle { assertEquals(listOf(3, 4), progress) }
    }

    @Test
    fun previousBoundaryDoesNotNavigateDuringDragOrCanceledReturn() {
        setReader(initialAnchor = 0)
        assertPage(1)
        compose.onNodeWithTag("reader").performTouchInput {
            down(Offset(width * 0.1f, height * 0.4f))
            moveTo(Offset(width * 0.85f, height * 0.4f), delayMillis = 500)
        }
        compose.onNodeWithText("正在进入上一章…").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(0, previousChapters)
            assertEquals(listOf(0), progress)
        }
        compose.onNodeWithTag("reader").performTouchInput {
            moveTo(Offset(width * 0.15f, height * 0.4f), delayMillis = 500)
            advanceEventTime(500)
            up()
        }
        compose.waitForIdle()
        assertPage(1)
        compose.runOnIdle {
            assertEquals(0, previousChapters)
            assertEquals(listOf(0), progress)
        }
        compose.onNodeWithTag("reader").performTouchInput {
            down(Offset(width * 0.1f, height * 0.4f))
            moveTo(Offset(width * 0.9f, height * 0.4f), delayMillis = 500)
            advanceEventTime(500)
            up()
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(1, previousChapters)
            assertEquals(0, nextChapters)
            assertEquals(listOf(0), progress)
        }
    }

    @Test
    fun nextBoundaryNavigatesOnlyAfterReleaseAndSettle() {
        setReader(initialAnchor = 5)
        assertPage(6)
        compose.onNodeWithTag("reader").performTouchInput {
            down(Offset(width * 0.9f, height * 0.4f))
            moveTo(Offset(width * 0.15f, height * 0.4f), delayMillis = 500)
        }
        compose.onNodeWithText("正在进入下一章…").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(0, nextChapters)
            assertEquals(listOf(5), progress)
        }
        compose.onNodeWithTag("reader").performTouchInput {
            advanceEventTime(500)
            up()
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(1, nextChapters)
            assertEquals(0, previousChapters)
            assertEquals(listOf(5), progress)
        }
        compose.runOnIdle {
            anchor = 0
            blocks = List(6) { index ->
                ReaderBlock.Illustration("file:///next-chapter-$index.png", width = 1, height = 100)
            }
        }
        compose.waitForIdle()
        assertPage(1)
        compose.runOnIdle {
            assertEquals(listOf(5, 0), progress)
            assertEquals(1, nextChapters)
            assertEquals(0, previousChapters)
        }
    }

    @Test
    fun seekingFirstAndLastContentPagesNeverSelectsBoundaryPages() {
        setReader(initialAnchor = 3)
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress)).performTouchInput {
            click(Offset(width * 0.08f, centerY))
        }
        compose.waitForIdle()
        assertPage(1)
        compose.runOnIdle {
            assertEquals(0, progress.last())
            assertEquals(0, previousChapters)
        }
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress)).performTouchInput {
            click(Offset(width * 0.92f, centerY))
        }
        compose.waitForIdle()
        assertPage(6)
        compose.runOnIdle {
            assertEquals(5, progress.last())
            assertEquals(0, nextChapters)
        }
        swipeNext()
        compose.runOnIdle { assertEquals(1, nextChapters) }
    }

    @Test
    fun repaginationAndCardRoundTripKeepSettledAnchorAndPageNumbers() {
        setReader(initialAnchor = 2)
        swipeNext()
        assertPage(4)
        compose.runOnIdle { viewportHeight = 420.dp }
        compose.waitForIdle()
        assertPage(4)
        compose.runOnIdle { preferences = preferences.copy(mode = ReaderMode.CARD) }
        compose.waitForIdle()
        assertPage(4)
        compose.runOnIdle { preferences = preferences.copy(mode = ReaderMode.PAGED) }
        compose.waitForIdle()
        assertPage(4)
        compose.runOnIdle {
            assertTrue(progress.drop(1).all { it == 3 })
            assertEquals(0, previousChapters)
            assertEquals(0, nextChapters)
        }
    }
}
