package io.github.jiangyuyi.lightnovel.feature.reader

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderHardwareControlsTest {
    @Test
    fun volumeUpAndDownTurnOnePageOnInitialDownOnly() {
        val turns = mutableListOf<ReaderVolumeKeyDirection>()

        assertTrue(
            consumeReaderVolumeKey(
                KeyEvent.KEYCODE_VOLUME_UP,
                KeyEvent.ACTION_DOWN,
                0,
                onPrevious = { turns += ReaderVolumeKeyDirection.PREVIOUS },
                onNext = { turns += ReaderVolumeKeyDirection.NEXT },
            ),
        )
        assertTrue(
            consumeReaderVolumeKey(
                KeyEvent.KEYCODE_VOLUME_DOWN,
                KeyEvent.ACTION_DOWN,
                0,
                onPrevious = { turns += ReaderVolumeKeyDirection.PREVIOUS },
                onNext = { turns += ReaderVolumeKeyDirection.NEXT },
            ),
        )
        consumeReaderVolumeKey(
            KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.ACTION_DOWN,
            1,
            onPrevious = { turns += ReaderVolumeKeyDirection.PREVIOUS },
            onNext = { turns += ReaderVolumeKeyDirection.NEXT },
        )
        consumeReaderVolumeKey(
            KeyEvent.KEYCODE_VOLUME_UP,
            KeyEvent.ACTION_UP,
            0,
            onPrevious = { turns += ReaderVolumeKeyDirection.PREVIOUS },
            onNext = { turns += ReaderVolumeKeyDirection.NEXT },
        )

        assertEquals(
            listOf(ReaderVolumeKeyDirection.PREVIOUS, ReaderVolumeKeyDirection.NEXT),
            turns,
        )
    }

    @Test
    fun unrelatedKeysRemainAvailableToAndroid() {
        assertFalse(
            consumeReaderVolumeKey(
                KeyEvent.KEYCODE_BACK,
                KeyEvent.ACTION_DOWN,
                0,
                onPrevious = {},
                onNext = {},
            ),
        )
    }

    @Test
    fun volumePagingStaysEnabledWhenReaderControlsAreVisible() {
        assertTrue(
            readerVolumePagingEnabled(
                preferenceEnabled = true,
                menuVisible = false,
                settingsVisible = false,
                textSettingsVisible = false,
            ),
        )
        assertFalse(readerVolumePagingEnabled(true, true, false, false))
        assertFalse(readerVolumePagingEnabled(true, false, true, false))
        assertFalse(readerVolumePagingEnabled(true, false, false, true))
        assertFalse(readerVolumePagingEnabled(false, false, false, false))
    }
}
