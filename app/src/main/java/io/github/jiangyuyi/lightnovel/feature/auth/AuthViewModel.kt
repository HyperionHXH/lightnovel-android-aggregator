package io.github.jiangyuyi.lightnovel.feature.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.jiangyuyi.lightnovel.core.data.LightNovelRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class AuthState(
    val loading: Boolean = false,
    val completed: Boolean = false,
    val message: String? = null,
    val error: String? = null,
)

class AuthViewModel(private val repository: LightNovelRepository) : ViewModel() {
    private val _state = MutableStateFlow(AuthState())
    val state: StateFlow<AuthState> = _state.asStateFlow()

    fun login(username: String, password: String) = runAuth {
        require(username.isNotBlank() && password.isNotBlank()) { "请填写账号和密码" }
        repository.login(username, password)
        _state.value = AuthState(completed = true, message = "登录成功")
    }

    fun sendCode(email: String) = runAuth {
        require(EMAIL.matches(email.trim())) { "请输入有效邮箱" }
        repository.sendRegistrationCode(email)
        _state.value = AuthState(message = "验证码已发送，请检查邮箱")
    }

    fun register(email: String, code: String, nickname: String, password: String) = runAuth {
        require(EMAIL.matches(email.trim())) { "请输入有效邮箱" }
        require(code.isNotBlank() && nickname.isNotBlank() && password.length >= 6) { "请完整填写信息，密码至少 6 位" }
        repository.register(email, code, nickname, password)
        _state.value = AuthState(completed = true, message = "注册并登录成功")
    }

    fun consumeMessage() {
        _state.value = _state.value.copy(message = null, error = null)
    }

    private fun runAuth(block: suspend () -> Unit) {
        if (_state.value.loading) return
        _state.value = AuthState(loading = true)
        viewModelScope.launch {
            runCatching { block() }.onFailure {
                _state.value = AuthState(error = it.message ?: "操作失败")
            }
        }
    }

    private companion object {
        val EMAIL = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")
    }
}

