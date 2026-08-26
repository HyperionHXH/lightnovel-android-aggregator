package io.github.jiangyuyi.lightnovel.feature.sources

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.jiangyuyi.lightnovel.core.source.PasswordCredentials
import io.github.jiangyuyi.lightnovel.core.source.RewardStatus
import io.github.jiangyuyi.lightnovel.core.source.SourceDescriptor
import io.github.jiangyuyi.lightnovel.core.source.SourceRegistry
import io.github.jiangyuyi.lightnovel.core.source.SourceSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class SourceAccountItemState(
    val descriptor: SourceDescriptor,
    val session: SourceSession = SourceSession(loggedIn = false),
    val checking: Boolean = true,
    val signingIn: Boolean = false,
    val signingOut: Boolean = false,
    val rewardLoading: Boolean = false,
    val rewardStatus: RewardStatus? = null,
    val error: String? = null,
    val notice: String? = null,
) {
    val busy: Boolean get() = checking || signingIn || signingOut || rewardLoading
}

data class SourceAccountsState(
    val accounts: List<SourceAccountItemState> = emptyList(),
)

class SourceAccountsViewModel(
    private val registry: SourceRegistry,
) : ViewModel() {
    private val _state = MutableStateFlow(
        SourceAccountsState(
            registry.accountProviders().map { SourceAccountItemState(it.descriptor) },
        ),
    )
    val state: StateFlow<SourceAccountsState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh(sourceId: String? = null) {
        val ids = sourceId?.let(::listOf) ?: _state.value.accounts.map { it.descriptor.id }
        ids.forEach { id ->
            val provider = registry.accountProvider(id) ?: return@forEach
            update(id) { it.copy(checking = true, error = null, notice = null) }
            viewModelScope.launch {
                runSourceCatching { provider.restoreSession() }
                    .onSuccess { session ->
                        update(id) { it.copy(session = session, checking = false, error = null) }
                        if (session.loggedIn) loadRewardStatus(id)
                    }
                    .onFailure { error ->
                        update(id) {
                            it.copy(checking = false, error = error.toSourceUiMessage("会话检查失败"))
                        }
                    }
            }
        }
    }

    fun login(sourceId: String, identifier: String, password: String) {
        val normalizedIdentifier = identifier.trim()
        if (normalizedIdentifier.isBlank() || password.isBlank()) {
            update(sourceId) { it.copy(error = "请输入账号和密码", notice = null) }
            return
        }
        val provider = registry.accountProvider(sourceId) ?: return
        update(sourceId) { it.copy(signingIn = true, error = null, notice = null) }
        viewModelScope.launch {
            runSourceCatching { provider.login(PasswordCredentials(normalizedIdentifier, password)) }
                .onSuccess { session ->
                    update(sourceId) {
                        it.copy(
                            session = session,
                            signingIn = false,
                            error = if (session.loggedIn) null else "登录失败，请检查账号和密码",
                        )
                    }
                    if (session.loggedIn) loadRewardStatus(sourceId)
                }
                .onFailure { error ->
                    update(sourceId) {
                        it.copy(signingIn = false, error = error.toSourceUiMessage("登录失败"))
                    }
                }
        }
    }

    fun logout(sourceId: String) {
        val provider = registry.accountProvider(sourceId) ?: return
        update(sourceId) { it.copy(signingOut = true, error = null, notice = null) }
        viewModelScope.launch {
            runSourceCatching { provider.logout() }
                .onSuccess {
                    update(sourceId) {
                        it.copy(
                            session = SourceSession(loggedIn = false),
                            signingOut = false,
                            rewardStatus = null,
                        )
                    }
                }
                .onFailure { error ->
                    update(sourceId) {
                        it.copy(signingOut = false, error = error.toSourceUiMessage("退出失败"))
                    }
                }
        }
    }

    fun claimDailyReward(sourceId: String) {
        val provider = registry.rewardProvider(sourceId) ?: return
        update(sourceId) { it.copy(rewardLoading = true, error = null, notice = null) }
        viewModelScope.launch {
            runSourceCatching { provider.claimDailyReward() }
                .onSuccess { result ->
                    update(sourceId) { current ->
                        current.copy(
                            rewardLoading = false,
                            rewardStatus = RewardStatus(
                                claimedToday = true,
                                balance = result.balance ?: current.rewardStatus?.balance,
                                streakDays = result.streakDays ?: current.rewardStatus?.streakDays,
                            ),
                            notice = result.rewardAmount?.takeIf { it > 0 }?.let { "已领取 $it 轻币" }
                                ?: "今日已签到",
                        )
                    }
                }
                .onFailure { error ->
                    update(sourceId) {
                        it.copy(rewardLoading = false, error = error.toSourceUiMessage("签到失败"))
                    }
                }
        }
    }

    private fun loadRewardStatus(sourceId: String) {
        val provider = registry.rewardProvider(sourceId) ?: return
        update(sourceId) { it.copy(rewardLoading = true) }
        viewModelScope.launch {
            runSourceCatching { provider.getRewardStatus() }
                .onSuccess { reward ->
                    update(sourceId) { it.copy(rewardLoading = false, rewardStatus = reward) }
                }
                .onFailure { error ->
                    update(sourceId) {
                        it.copy(rewardLoading = false, error = error.toSourceUiMessage("签到状态加载失败"))
                    }
                }
        }
    }

    private fun update(sourceId: String, transform: (SourceAccountItemState) -> SourceAccountItemState) {
        _state.value = _state.value.copy(
            accounts = _state.value.accounts.map { account ->
                if (account.descriptor.id == sourceId) transform(account) else account
            },
        )
    }
}
