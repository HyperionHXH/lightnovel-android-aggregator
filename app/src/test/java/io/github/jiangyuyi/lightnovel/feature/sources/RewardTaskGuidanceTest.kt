package io.github.jiangyuyi.lightnovel.feature.sources

import io.github.jiangyuyi.lightnovel.core.source.RewardTask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RewardTaskGuidanceTest {
    @Test
    fun `bookshelf task explains the action and links to discovery`() {
        val guidance = RewardTask(
            id = 1,
            key = "favorite_book",
            title = "收藏一本作品",
        ).completionGuidance()

        assertEquals(RewardTaskDestination.DISCOVER, guidance.destination)
        assertTrue(guidance.instruction.contains("加入书架"))
    }

    @Test
    fun `official-only task is labelled instead of pretending Mixn can complete it`() {
        val guidance = rewardTaskGuidance("sleep", "开始睡觉", "", claimed = false)

        assertEquals(RewardTaskDestination.OFFICIAL_CLIENT, guidance.destination)
        assertTrue(guidance.instruction.contains("官方客户端"))
    }

    @Test
    fun `claimed task no longer offers a destination`() {
        val guidance = rewardTaskGuidance("read", "阅读作品", "", claimed = true)

        assertEquals(RewardTaskDestination.NONE, guidance.destination)
        assertTrue(guidance.instruction.contains("已经领取"))
    }
}
