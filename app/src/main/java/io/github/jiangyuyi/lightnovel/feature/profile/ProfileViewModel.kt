package io.github.jiangyuyi.lightnovel.feature.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.jiangyuyi.lightnovel.core.data.LightNovelRepository
import io.github.jiangyuyi.lightnovel.core.model.AccountProfile
import io.github.jiangyuyi.lightnovel.core.model.MessageSummary
import io.github.jiangyuyi.lightnovel.core.source.SourceProfile
import io.github.jiangyuyi.lightnovel.core.source.SourceRegistry
import io.github.jiangyuyi.lightnovel.core.source.SourceSession
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

data class ProfileState(
    val profile: AccountProfile? = null,
    val messageSummary: MessageSummary = MessageSummary(),
    val sourceSessions: Map<String, SourceSession> = emptyMap(),
    val sourceProfiles: Map<String, SourceProfile> = emptyMap(),
    val sourceProfileLoading: Set<String> = emptySet(),
    val sourceProfileErrors: Map<String, String> = emptyMap(),
    val loading: Boolean = false,
    val refreshing: Boolean = false,
    val error: String? = null,
    val refreshError: String? = null,
    val lastUpdatedAt: Long? = null,
)

class ProfileViewModel(
    private val repository: LightNovelRepository,
    private val sourceRegistry: SourceRegistry,
) : ViewModel() {
    private val _state = MutableStateFlow(ProfileState())
    val state: StateFlow<ProfileState> = _state.asStateFlow()
    private var refreshJob: Job? = null

    fun refresh(lightNovelKingdomLoggedIn: Boolean, forceRefresh: Boolean = false) {
        refreshJob?.cancel()
        val current = _state.value
        _state.value = current.copy(
            profile = if (lightNovelKingdomLoggedIn) current.profile else null,
            messageSummary = if (lightNovelKingdomLoggedIn) current.messageSummary else MessageSummary(),
            loading = lightNovelKingdomLoggedIn && current.profile == null,
            refreshing = forceRefresh,
            error = null,
            refreshError = null,
            sourceProfileLoading = sourceRegistry.accountProviders().map { it.descriptor.id }.toSet(),
            sourceProfileErrors = emptyMap(),
        )
        refreshJob = viewModelScope.launch {
            if (lightNovelKingdomLoggedIn) {
                launch {
                    repository.profileUpdates(forceRefresh)
                        .catch { throwable ->
                            val state = _state.value
                            val message = throwable.message ?: "个人资料加载失败"
                            _state.value = if (state.profile == null) {
                                state.copy(loading = false, refreshing = false, error = message)
                            } else {
                                state.copy(loading = false, refreshing = false, refreshError = message)
                            }
                        }
                        .collect { update ->
                            _state.value = _state.value.copy(
                                profile = update.data,
                                loading = false,
                                refreshing = update.refreshing,
                                error = null,
                                refreshError = update.error?.message,
                                lastUpdatedAt = update.savedAtMillis,
                            )
                        }
                }
                launch {
                    repository.messageSummaryUpdates(forceRefresh)
                        .catch { }
                        .collect { update ->
                            _state.value = _state.value.copy(messageSummary = update.data)
                        }
                }
            } else {
                _state.value = _state.value.copy(loading = false, refreshing = false)
            }
            sourceRegistry.accountProviders().forEach { provider ->
                launch { loadSourceProfile(provider.descriptor.id) }
            }
        }
    }

    private suspend fun loadSourceProfile(sourceId: String) {
        val accountProvider = sourceRegistry.accountProvider(sourceId)
        val profileProvider = sourceRegistry.profileProvider(sourceId)
        if (accountProvider == null) {
            finishSource(sourceId, null, "该来源不支持账号")
            return
        }
        runCatching {
            val session = accountProvider.restoreSession()
            val profile = if (session.loggedIn && profileProvider != null) profileProvider.getProfile() else null
            session to profile
        }.onSuccess { (session, profile) ->
            val state = _state.value
            _state.value = state.copy(
                sourceSessions = state.sourceSessions + (sourceId to session),
                sourceProfiles = if (profile == null) state.sourceProfiles - sourceId else state.sourceProfiles + (sourceId to profile),
                sourceProfileLoading = state.sourceProfileLoading - sourceId,
                sourceProfileErrors = state.sourceProfileErrors - sourceId,
            )
        }.onFailure { throwable ->
            finishSource(sourceId, null, throwable.message ?: "来源资料加载失败")
        }
    }

    private fun finishSource(sourceId: String, session: SourceSession?, error: String?) {
        val state = _state.value
        _state.value = state.copy(
            sourceSessions = if (session == null) state.sourceSessions else state.sourceSessions + (sourceId to session),
            sourceProfileLoading = state.sourceProfileLoading - sourceId,
            sourceProfileErrors = if (error == null) state.sourceProfileErrors - sourceId
            else state.sourceProfileErrors + (sourceId to error),
        )
    }
}
