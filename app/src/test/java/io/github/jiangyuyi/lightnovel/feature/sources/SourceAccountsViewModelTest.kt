package io.github.jiangyuyi.lightnovel.feature.sources

import io.github.jiangyuyi.lightnovel.MainDispatcherRule
import io.github.jiangyuyi.lightnovel.core.source.AccountProvider
import io.github.jiangyuyi.lightnovel.core.source.NovelSource
import io.github.jiangyuyi.lightnovel.core.source.PasswordCredentials
import io.github.jiangyuyi.lightnovel.core.source.RewardProvider
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
}
