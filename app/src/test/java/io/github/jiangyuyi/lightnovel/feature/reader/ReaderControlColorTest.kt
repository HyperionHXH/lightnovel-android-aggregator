package io.github.jiangyuyi.lightnovel.feature.reader

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ReaderControlColorTest {
    @Test
    fun `light reader surface gets a distinct control panel`() {
        val background = Color(0xFFF7EED9)

        assertNotEquals(background, readerControlPanelColor(background))
    }

    @Test
    fun `dark reader surface gets a distinct control panel`() {
        val background = Color(0xFF171416)

        assertNotEquals(background, readerControlPanelColor(background))
    }
}
