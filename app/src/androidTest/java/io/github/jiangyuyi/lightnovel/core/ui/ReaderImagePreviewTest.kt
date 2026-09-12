package io.github.jiangyuyi.lightnovel.core.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.size
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import io.github.jiangyuyi.lightnovel.core.model.ReaderImageScale
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.longClick
import org.junit.Rule
import org.junit.Test

class ReaderImagePreviewTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun longPressShowsVisibleSaveAction() {
        compose.setContent {
            MaterialTheme {
                ReaderImagePreview(
                    url = "https://cdn.example.test/illustration.jpg",
                    modifier = Modifier.size(240.dp),
                    imageScale = ReaderImageScale.FIT,
                    contentDescription = "插图",
                    errorText = "插图加载失败",
                )
            }
        }

        compose.onNodeWithTag("reader-image-inline").performClick()
        compose.onNodeWithTag("reader-image-dialog").assertIsDisplayed()
        compose.onNodeWithTag("reader-image-dialog-image")
            .performSemanticsAction(SemanticsActions.OnLongClick)
        compose.waitForIdle()
        compose.onNodeWithTag("reader-image-actions").assertIsDisplayed()
        compose.onNodeWithTag("reader-image-save").assertIsDisplayed()
    }

    @Test
    fun physicalLongPressShowsVisibleSaveAction() {
        compose.setContent {
            MaterialTheme {
                ReaderImagePreview(
                    url = "https://cdn.example.test/illustration.jpg",
                    modifier = Modifier.size(240.dp),
                    imageScale = ReaderImageScale.FIT,
                    contentDescription = "插图",
                    errorText = "插图加载失败",
                )
            }
        }

        compose.onNodeWithTag("reader-image-inline").performClick()
        compose.onNodeWithTag("reader-image-dialog").assertIsDisplayed()
        compose.onNodeWithTag("reader-image-dialog-image").performTouchInput {
            longClick()
        }
        compose.waitForIdle()
        compose.onNodeWithTag("reader-image-actions").assertIsDisplayed()
        compose.onNodeWithTag("reader-image-save").assertIsDisplayed()
    }
}
