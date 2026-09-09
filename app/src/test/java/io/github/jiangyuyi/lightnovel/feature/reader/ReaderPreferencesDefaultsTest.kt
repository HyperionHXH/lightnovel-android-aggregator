package io.github.jiangyuyi.lightnovel.feature.reader

import io.github.jiangyuyi.lightnovel.core.model.ReaderPreferences
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderPreferencesDefaultsTest {
    @Test
    fun `volume paging is opt in while screen stays awake by default`() {
        val preferences = ReaderPreferences()

        assertFalse(preferences.volumeKeys)
        assertTrue(preferences.keepScreenOn)
    }
}
