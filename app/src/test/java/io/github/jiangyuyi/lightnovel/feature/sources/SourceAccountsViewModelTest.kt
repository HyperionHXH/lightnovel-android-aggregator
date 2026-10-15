package io.github.jiangyuyi.lightnovel.feature.sources

import io.github.jiangyuyi.lightnovel.MainDispatcherRule
import io.github.jiangyuyi.lightnovel.core.source.AccountProvider
import io.github.jiangyuyi.lightnovel.core.source.NovelSource
import io.github.jiangyuyi.lightnovel.core.source.PasswordCredentials
import io.github.jiangyuyi.lightnovel.core.source.RewardProvider
import io.github.jiangyuyi.lightnovel.core.source.RewardCenter
import io.github.jiangyuyi.lightnovel.core.source.RewardCenterProvider
import io.github.jiangyuyi.lightnovel.core.source.RewardResult
import io.github.jiangyuyi.lightnovel.core.source.RewardStatus
import io.github.jiangyuyi.lightnovel.core.source.SourceCapability
import io.github.jiangyuyi.lightnovel.core.source.SourceDescriptor
import io.github.jiangyuyi.lightnovel.core.source.SourceRegistry
import io.github.jiangyuyi.lightnovel.core.source.SourceSession
import io.github.jiangyuyi.lightnovel.core.source.SourceProfile
import io.github.jiangyuyi.lightnovel.core.source.SourceProfileProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runCurrent
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
        assertEquals("Lv.3", states.getValue("first").profile?.levelLabel)
        assertEquals(25L, states.getValue("first").profile?.balance)
    }

    @Test
    fun `sign in cancels stale profile and reloads authoritative growth`() = runTest(mainDispatcherRule.dispatcher) {
        val pending = CompletableDeferred<Unit>()
        val source = object : FakeAccountSource("growth", SourceSession(true)) {
            override suspend fun getProfile(): SourceProfile {
                if (claimCalls == 0) pending.await()
                return super.getProfile()
            }
        }
        val viewModel = SourceAccountsViewModel(SourceRegistry(listOf(source)))
        runCurrent()
        assertTrue(viewModel.state.value.accounts.single().profileLoading)

        viewModel.claimDailyReward("growth")
        advanceUntilIdle()
        pending.complete(Unit)
        advanceUntilIdle()

        val account = viewModel.state.value.accounts.single()
        assertEquals("Lv.3", account.profile?.levelLabel)
        assertEquals(25L, account.profile?.balance)
        assertTrue(account.rewardStatus?.claimedToday == true)
        assertNull(account.profileError)
        assertEquals("已领取 5 轻币", account.notice)
    }

    @Test
    fun `failed post sign profile refresh retains known level and reports stale data`() = runTest(mainDispatcherRule.dispatcher) {
        val source = object : FakeAccountSource("growth", SourceSession(true)) {
            override suspend fun getProfile(): SourceProfile {
                if (claimCalls > 0) error("资料服务不可用")
                return super.getProfile()
            }
        }
        val viewModel = SourceAccountsViewModel(SourceRegistry(listOf(source)))
        advanceUntilIdle()
        viewModel.claimDailyReward("growth")
        advanceUntilIdle()

        val account = viewModel.state.value.accounts.single()
        assertEquals("Lv.2", account.profile?.levelLabel)
        assertEquals("资料服务不可用", account.profileError)
        assertTrue(account.rewardStatus?.claimedToday == true)
        assertEquals("已领取 5 轻币", account.notice)
    }

    @Test
    fun `logout cancels pending growth and clears source account data`() = runTest(mainDispatcherRule.dispatcher) {
        val pending = CompletableDeferred<Unit>()
        val source = object : FakeAccountSource("growth", SourceSession(true)) {
            override suspend fun getProfile(): SourceProfile {
                pending.await()
                return super.getProfile()
            }
        }
        val viewModel = SourceAccountsViewModel(SourceRegistry(listOf(source)))
        runCurrent()
        viewModel.logout("growth")
        advanceUntilIdle()
        pending.complete(Unit)
        advanceUntilIdle()

        val account = viewModel.state.value.accounts.single()
        assertFalse(account.session.loggedIn)
        assertNull(account.profile)
        assertNull(account.rewardStatus)
        assertFalse(account.profileLoading)
    }

    private open class FakeAccountSource(
        id: String,
        private val restored: SourceSession,
    ) : NovelSource, AccountProvider, RewardProvider, SourceProfileProvider {
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
            claimedToday = claimCalls > 0,
            balance = if (claimCalls > 0) 25 else 20,
            streakDays = if (claimCalls > 0) 3 else 2,
        )

        override suspend fun claimDailyReward(): RewardResult {
            claimCalls += 1
            return RewardResult(rewardAmount = 5, balance = 25, streakDays = 3)
        }

        override suspend fun getProfile() = SourceProfile(
            sourceId = descriptor.id,
            balance = if (claimCalls > 0) 25 else 20,
            levelLabel = if (claimCalls > 0) "Lv.3" else "Lv.2",
        )
    }

    private class FakeWelfareSource : NovelSource, AccountProvider, RewardCenterProvider {
        override val descriptor = SourceDescriptor(
            "welfare",
            "福利来源",
            setOf(SourceCapability.ACCOUNT, SourceCapability.DAILY_REWARD, SourceCapability.REWARD_CENTER),
        )
        override suspend fun restoreSession() = SourceSession(true, accountId = "7", displayName = "用户")
        override suspend fun login(credentials: PasswordCredentials) = restoreSession()
        override suspend fun logout() = Unit
        override suspend fun getRewardStatus() = RewardStatus(false)
        override suspend fun claimDailyReward() = RewardResult(rewardAmount = 10)
        override suspend fun getRewardCenter() = RewardCenter(
            signTitle = "新手签到",
            claimed = false,
            claimable = true,
        )
    }
}
