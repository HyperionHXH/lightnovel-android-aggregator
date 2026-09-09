package io.github.jiangyuyi.lightnovel.feature.reader

import io.github.jiangyuyi.lightnovel.core.model.ReaderPreferences
import io.github.jiangyuyi.lightnovel.core.model.ReaderImageScale
import io.github.jiangyuyi.lightnovel.core.model.ReaderOrientation
import io.github.jiangyuyi.lightnovel.core.model.ReaderTapInversion
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderPreferencesDefaultsTest {
    @Test
    fun `volume paging is opt in while screen stays awake by default`() {
        val preferences = ReaderPreferences()

        assertFalse(preferences.volumeKeys)
        assertTrue(preferences.keepScreenOn)
        assertEquals(ReaderTapInversion.NONE, preferences.tapInversion)
        assertEquals(ReaderOrientation.DEFAULT, preferences.orientation)
        assertEquals(ReaderImageScale.FIT, preferences.imageScale)
    }
}
