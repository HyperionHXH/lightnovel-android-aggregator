package io.github.jiangyuyi.lightnovel.feature.reader

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test

class ReaderLoadingPaneTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun loadingStateUsesCenteredReaderIndicator() {
        compose.setContent {
            MaterialTheme {
                Box(Modifier.size(320.dp)) {
                    ReaderLoadingPane()
                }
            }
        }

        compose.onNodeWithText("正在加载章节…").assertIsDisplayed()
    }
}
