package io.github.jiangyuyi.lightnovel.feature.profile

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import io.github.jiangyuyi.lightnovel.core.model.ReaderFont
import io.github.jiangyuyi.lightnovel.core.preferences.EmptyReaderPreferencesAccess
import io.github.jiangyuyi.lightnovel.core.reader.UserFontRepository
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse

class FontSelectionScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun showsBuiltInFontsWithoutRemovedWeights() {
        val repository = UserFontRepository(
            context = ApplicationProvider.getApplicationContext(),
            definitions = emptyList(),
        )

        compose.setContent {
            MaterialTheme {
                FontSelectionScreen(
                    readerPreferences = EmptyReaderPreferencesAccess,
                    userFonts = repository,
                    onBack = {},
                )
            }
        }

        compose.onNodeWithText("字体预览与选择").assertIsDisplayed()
        compose.onNodeWithText("中文阅读字体").assertIsDisplayed()
        compose.onNodeWithText("思源宋体").assertIsDisplayed()

        val labels = ReaderFont.entries.map { it.label }
        assertEquals(7, labels.size)
        assertEquals(
            listOf("系统默认", "系统黑体", "系统宋体", "等宽字体", "手写体", "紧凑黑体", "圆体"),
            labels,
        )
        assertFalse(labels.any { it == "细黑" || it == "中黑" || it == "特黑" })
    }
}
