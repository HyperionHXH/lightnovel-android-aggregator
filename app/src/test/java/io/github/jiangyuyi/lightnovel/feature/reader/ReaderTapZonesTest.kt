package io.github.jiangyuyi.lightnovel.feature.reader

import io.github.jiangyuyi.lightnovel.core.model.ReaderTapZone
import io.github.jiangyuyi.lightnovel.core.model.ReaderTapInversion
import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderTapZonesTest {
    @Test
    fun `default keeps center for controls and narrow edges for paging`() {
        assertEquals(ReaderTapAction.PREVIOUS, readerTapAction(ReaderTapZone.DEFAULT, 0.1f, 0.5f))
        assertEquals(ReaderTapAction.NEXT, readerTapAction(ReaderTapZone.DEFAULT, 0.9f, 0.5f))
        assertEquals(ReaderTapAction.CONTROLS, readerTapAction(ReaderTapZone.DEFAULT, 0.5f, 0.5f))
    }

    @Test
    fun `l shape uses the top and bottom corners`() {
        assertEquals(ReaderTapAction.PREVIOUS, readerTapAction(ReaderTapZone.L_SHAPE, 0.2f, 0.1f))
        assertEquals(ReaderTapAction.PREVIOUS, readerTapAction(ReaderTapZone.L_SHAPE, 0.2f, 0.9f))
        assertEquals(ReaderTapAction.NEXT, readerTapAction(ReaderTapZone.L_SHAPE, 0.8f, 0.9f))
        assertEquals(ReaderTapAction.CONTROLS, readerTapAction(ReaderTapZone.L_SHAPE, 0.5f, 0.5f))
    }

    @Test
    fun `disabled never changes page`() {
        assertEquals(ReaderTapAction.CONTROLS, readerTapAction(ReaderTapZone.DISABLED, 0.05f, 0.05f))
        assertEquals(ReaderTapAction.CONTROLS, readerTapAction(ReaderTapZone.DISABLED, 0.95f, 0.95f))
    }

    @Test
    fun `inverted tap directions swap only the requested axes`() {
        assertEquals(
            ReaderTapAction.NEXT,
            readerTapAction(ReaderTapZone.DEFAULT, 0.1f, 0.5f, ReaderTapInversion.LEFT_RIGHT),
        )
        assertEquals(
            ReaderTapAction.PREVIOUS,
            readerTapAction(ReaderTapZone.DEFAULT, 0.1f, 0.5f, ReaderTapInversion.UP_DOWN),
        )
        assertEquals(
            ReaderTapAction.NEXT,
            readerTapAction(ReaderTapZone.L_SHAPE, 0.2f, 0.1f, ReaderTapInversion.ALL),
        )
    }
}
