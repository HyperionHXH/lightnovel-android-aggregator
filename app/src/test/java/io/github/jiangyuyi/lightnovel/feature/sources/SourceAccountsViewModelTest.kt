package io.github.jiangyuyi.lightnovel.feature.sources

import io.github.jiangyuyi.lightnovel.MainDispatcherRule
import io.github.jiangyuyi.lightnovel.core.source.AccountProvider
import io.github.jiangyuyi.lightnovel.core.source.NovelSource
import io.github.jiangyuyi.lightnovel.core.source.PasswordCredentials
import io.github.jiangyuyi.lightnovel.core.source.RewardProvider
import io.github.jiangyuyi.lightnovel.core.source.RewardCenter
import io.github.jiangyuyi.lightnovel.core.source.RewardCenterProvider
import io.github.jiangyuyi.lightnovel.core.source.RewardTask
import io.github.jiangyuyi.lightnovel.core.source.RewardResult
import io.github.jiangyuyi.lightnovel.core.source.RewardStatus
import io.github.jiangyuyi.lightnovel.core.source.SourceCapability
import io.github.jiangyuyi.lightnovel.core.source.SourceDescriptor
import io.github.jiangyuyi.lightnovel.core.source.SourceRegistry
import io.github.jiangyuyi.lightnovel.core.source.SourceSession
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SourceAccountsViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `sessions restore independently and signed in reward status loads`() =
        runTest(mainDispatcherRule.dispatcher) {
            val signedIn = FakeAccountSource(
                id = "signed-in",
                restored = SourceSession(true, accountId = "7", displayName = "用户"),
            )
            val signedOut = FakeAccountSource(
                id = "signed-out",
                restored = SourceSession(false),
            )
            val viewModel = SourceAccountsViewModel(SourceRegistry(listOf(signedIn, signedOut)))

            advanceUntilIdle()

            val states = viewModel.state.value.accounts.associateBy { it.descriptor.id }
            assertTrue(states.getValue("signed-in").session.loggedIn)
            assertEquals(20L, states.getValue("signed-in").rewardStatus?.balance)
            assertFalse(states.getValue("signed-out").session.loggedIn)
            assertNull(states.getValue("signed-out").error)
        }

    @Test
    fun `login and daily reward update only selected source`() = runTest(mainDispatcherRule.dispatcher) {
        val first = FakeAccountSource("first", SourceSession(false))
        val second = FakeAccountSource("second", SourceSession(false))
        val viewModel = SourceAccountsViewModel(SourceRegistry(listOf(first, second)))
        advanceUntilIdle()

        viewModel.login("first", "mail@example.com", "secret")
        advanceUntilIdle()
        viewModel.claimDailyReward("first")
        advanceUntilIdle()

        val states = viewModel.state.value.accounts.associateBy { it.descriptor.id }
        assertEquals("mail@example.com", first.lastIdentifier)
        assertTrue(states.getValue("first").session.loggedIn)
        assertTrue(states.getValue("first").rewardStatus?.claimedToday == true)
        assertEquals(25L, states.getValue("first").rewardStatus?.balance)
        assertFalse(states.getValue("second").session.loggedIn)
        assertEquals(0, second.claimCalls)
    }

    @Test
    fun `reward center loads and claims only a claimable task`() = runTest(mainDispatcherRule.dispatcher) {
        val source = FakeWelfareSource()
        val viewModel = SourceAccountsViewModel(SourceRegistry(listOf(source)))
        advanceUntilIdle()

        val task = viewModel.state.value.accounts.single().rewardCenter?.tasks?.single()
        assertTrue(task?.claimable == true)

        viewModel.claimRewardTask("welfare", checkNotNull(task))
        advanceUntilIdle()

        assertEquals(1, source.claimCalls)
        assertTrue(viewModel.state.value.accounts.single().rewardCenter?.tasks?.single()?.claimed == true)
        assertEquals("已领取 6 轻币", viewModel.state.value.accounts.single().notice)
    }

    private class FakeAccountSource(
        id: String,
        private val restored: SourceSession,
    ) : NovelSource, AccountProvider, RewardProvider {
        override val descriptor = SourceDescriptor(
            id,
            id,
            setOf(SourceCapability.ACCOUNT, SourceCapability.DAILY_REWARD),
        )
        var lastIdentifier: String? = null
        var claimCalls = 0
        private var currentSession = restored

        override suspend fun restoreSession(): SourceSession = currentSession

        override suspend fun login(credentials: PasswordCredentials): SourceSession {
            lastIdentifier = credentials.identifier
            currentSession = SourceSession(true, displayName = "登录用户")
            return currentSession
        }

        override suspend fun logout() {
            currentSession = SourceSession(false)
        }

        override suspend fun getRewardStatus() = RewardStatus(
            claimedToday = false,
            balance = 20,
            streakDays = 2,
        )

        override suspend fun claimDailyReward(): RewardResult {
            claimCalls += 1
            return RewardResult(rewardAmount = 5, balance = 25, streakDays = 3)
        }
    }

    private class FakeWelfareSource : NovelSource, AccountProvider, RewardCenterProvider {
        override val descriptor = SourceDescriptor(
            "welfare",
            "福利来源",
            setOf(SourceCapability.ACCOUNT, SourceCapability.DAILY_REWARD, SourceCapability.REWARD_CENTER),
        )
        var claimCalls = 0
        private var taskClaimed = false

        override suspend fun restoreSession() = SourceSession(true, accountId = "7", displayName = "用户")
        override suspend fun login(credentials: PasswordCredentials) = restoreSession()
        override suspend fun logout() = Unit
        override suspend fun getRewardStatus() = RewardStatus(taskClaimed)
        override suspend fun claimDailyReward() = RewardResult(rewardAmount = 10)
        override suspend fun getRewardCenter() = RewardCenter(
            signTitle = "新手签到",
            claimed = false,
            claimable = true,
            tasks = listOf(
                RewardTask(
                    id = 300002,
                    key = "daily_add_bookshelf_v1",
                    title = "收藏一个作品",
                    rewardAmount = 6,
                    claimed = taskClaimed,
                    claimable = !taskClaimed,
                    totalProgress = 1,
                ),
            ),
        )

        override suspend fun claimRewardTask(taskId: Long, taskKey: String): RewardResult {
            claimCalls += 1
            taskClaimed = true
            return RewardResult(rewardAmount = 6)
        }

        override suspend fun claimEarnCoin(taskKey: String) = RewardResult(rewardAmount = 100)
    }
}
