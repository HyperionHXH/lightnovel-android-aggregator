package io.github.jiangyuyi.lightnovel.desktop

import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopReaderInteractionTest {
    @Test
    fun `default keeps center for controls and narrow edges for paging`() {
        assertEquals(DesktopTapAction.PREVIOUS, desktopTapAction("default", 0.1, 0.5))
        assertEquals(DesktopTapAction.NEXT, desktopTapAction("default", 0.9, 0.5))
        assertEquals(DesktopTapAction.CONTROLS, desktopTapAction("default", 0.5, 0.5))
    }

    @Test
    fun `inversion swaps the selected axis`() {
        assertEquals(DesktopTapAction.NEXT, desktopTapAction("default", 0.1, 0.5, "left_right"))
        assertEquals(DesktopTapAction.NEXT, desktopTapAction("l_shape", 0.2, 0.1, "all"))
    }

    @Test
    fun `image scale maps to stable css`() {
        assertEquals("width:100%;height:auto;", desktopImageCss("fit_width"))
        assertEquals("max-width:100%;height:auto;", desktopImageCss("fit"))
    }
}
