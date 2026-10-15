package io.github.jiangyuyi.lightnovel.core.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SourceExperienceTest {
    @Test
    fun `progress is relative to current level floor not lifetime experience`() {
        val experience = SourceExperience(total = 140, levelStart = 100, nextLevelAt = 200)

        assertEquals(40L, experience.earnedInLevel)
        assertEquals(100L, experience.requiredInLevel)
        assertEquals(60L, experience.remaining)
        assertEquals(0.4f, checkNotNull(experience.fraction), 0.001f)
    }

    @Test
    fun `missing or inconsistent boundaries do not invent a progress bar`() {
        assertNull(SourceExperience(total = 0).fraction)
        assertNull(SourceExperience(total = 10, levelStart = 100, nextLevelAt = 200).fraction)
        assertNull(SourceExperience(total = 140, levelStart = 100, nextLevelAt = 100).fraction)
        assertNull(SourceExperience(total = 140, levelStart = -1, nextLevelAt = 200).fraction)
    }

    @Test
    fun `level boundaries and terminal level stay inside valid progress range`() {
        assertEquals(0f, checkNotNull(SourceExperience(100, 100, 200).fraction), 0f)
        assertEquals(1f, checkNotNull(SourceExperience(200, 100, 200).fraction), 0f)
        assertEquals(0L, SourceExperience(250, 100, 200).remaining)
        assertEquals(1f, checkNotNull(SourceExperience(250, maxLevel = true).fraction), 0f)
    }

    @Test
    fun `large experience totals avoid float precision loss before subtraction`() {
        val floor = 9_000_000_000L
        val experience = SourceExperience(floor + 1, floor, floor + 4)

        assertEquals(1L, experience.earnedInLevel)
        assertEquals(3L, experience.remaining)
        assertEquals(0.25f, checkNotNull(experience.fraction), 0f)
    }
}
