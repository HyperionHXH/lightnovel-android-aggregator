package io.github.jiangyuyi.lightnovel.feature.profile

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test

class ProfileDownloadSettingsTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun wifiDownloadSettingIsRenderedInSettingsSection() {
        compose.setContent {
            MaterialTheme {
                SettingsScreenContent(
                    wifiOnly = true,
                    backgroundUpdatesEnabled = false,
                    onWifiOnlyChange = {},
                    onBackgroundUpdatesChange = {},
                    onBack = {},
                )
            }
        }

        compose.onNodeWithText("设置").assertIsDisplayed()
        compose.onNodeWithText("下载设置").assertIsDisplayed()
        compose.onNodeWithText("仅使用 Wi-Fi 下载").assertIsDisplayed()
        compose.onNodeWithText("后台更新提醒").assertIsDisplayed()
    }
}
