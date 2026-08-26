package io.github.jiangyuyi.lightnovel.core.ui

import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test

class NovelCoverTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun missingCoverShowsStablePlaceholder() {
        compose.setContent {
            MaterialTheme {
                NovelCover(
                    url = null,
                    title = "测试书籍",
                    modifier = Modifier.size(width = 76.dp, height = 108.dp),
                )
            }
        }

        compose.onNodeWithText("暂无封面").assertIsDisplayed()
    }
}
