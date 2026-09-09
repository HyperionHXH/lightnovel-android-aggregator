package io.github.jiangyuyi.lightnovel.feature.reader

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.graphics.Color
import io.github.jiangyuyi.lightnovel.core.model.ReaderMode
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ReaderMenuSheetTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun menuRendersScrollableActionsInPanel() {
        compose.setContent {
            MaterialTheme {
                ReaderMenuSheet(
                    bookTitle = "测试书籍",
                    chapterTitle = "第一章",
                    mode = ReaderMode.PAGED,
                    progressText = "已阅读 12%",
                    showProgressBar = true,
                    background = Color.White,
                    contentColor = Color.Black,
                    onDismiss = {},
                    onCatalog = {},
                    onSettings = {},
                    onTextSettings = {},
                    onRetry = {},
                    onToggleProgressBar = {},
                )
            }
        }

        compose.onNodeWithTag("reader-menu-panel").assertIsDisplayed()
        compose.onNodeWithText("测试书籍").assertIsDisplayed()
        compose.onNodeWithText("打开章节目录").assertIsDisplayed()
        compose.onNodeWithText("阅读设置").assertIsDisplayed()
    }

    @Test
    fun tappingOutsidePanelDismissesMenu() {
        var dismissed = false
        compose.setContent {
            MaterialTheme {
                ReaderMenuSheet(
                    bookTitle = "测试书籍",
                    chapterTitle = "第一章",
                    mode = ReaderMode.PAGED,
                    progressText = "已阅读 12%",
                    showProgressBar = true,
                    background = Color.White,
                    contentColor = Color.Black,
                    onDismiss = { dismissed = true },
                    onCatalog = {},
                    onSettings = {},
                    onTextSettings = {},
                    onRetry = null,
                    onToggleProgressBar = {},
                )
            }
        }

        compose.onNodeWithTag("reader-menu-scrim").performClick()
        assertTrue(dismissed)
    }
}
