package io.github.jiangyuyi.lightnovel.feature.reader

import android.view.KeyEvent
import androidx.test.core.app.ActivityScenario
import io.github.jiangyuyi.lightnovel.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderVolumeKeyActivityTest {
    @Test
    fun activityConsumesVolumeKeysForReaderHandler() {
        val turns = mutableListOf<ReaderVolumeKeyDirection>()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setReaderVolumeKeyHandler { keyCode, action, repeatCount ->
                    consumeReaderVolumeKey(
                        keyCode = keyCode,
                        action = action,
                        repeatCount = repeatCount,
                        onPrevious = { turns += ReaderVolumeKeyDirection.PREVIOUS },
                        onNext = { turns += ReaderVolumeKeyDirection.NEXT },
                    )
                }
                assertTrue(activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_UP)))
                assertTrue(activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_VOLUME_UP)))
                assertTrue(activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_DOWN)))
            }
        }

        assertEquals(
            listOf(ReaderVolumeKeyDirection.PREVIOUS, ReaderVolumeKeyDirection.NEXT),
            turns,
        )
    }
}
