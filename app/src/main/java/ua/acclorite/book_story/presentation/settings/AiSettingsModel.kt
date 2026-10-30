/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * fork 新增：AI 设置页的状态（第二阶段 S6）。
 *
 * Key 只在「输入框 → ViewModel → AiKeyStore」之间过一次，不进入任何 UiState；
 * UI 只拿到「是否已保存」。[AiTestState] 只保留错误类别与 HTTP 状态码，展示文案在 UI 层映射。
 */

package ua.acclorite.book_story.presentation.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import ua.acclorite.book_story.core.log.logW
import ua.acclorite.book_story.data.ai.AiError
import ua.acclorite.book_story.data.ai.AiException
import ua.acclorite.book_story.data.ai.AiKeyStore
import ua.acclorite.book_story.data.ai.AiTranslationRepository
import javax.inject.Inject

private const val TAG = "AiSettingsModel"

sealed interface AiTestState {
    data object Idle : AiTestState
    data object Testing : AiTestState
    data object Success : AiTestState
    data class Error(val error: AiError, val statusCode: Int?) : AiTestState
}

@HiltViewModel
class AiSettingsModel @Inject constructor(
    private val keyStore: AiKeyStore,
    private val repository: AiTranslationRepository
) : ViewModel() {

    private val _hasKey = MutableStateFlow(false)
    val hasKey: StateFlow<Boolean> = _hasKey.asStateFlow()

    /** 保存/清除失败（Keystore 损坏、磁盘错误）；UI 只显示通用文案，不显示异常内容。 */
    private val _keyError = MutableStateFlow(false)
    val keyError: StateFlow<Boolean> = _keyError.asStateFlow()

    /**
     * 每次保存/清除成功后自增。UI 监听它再清空输入框：失败时保留用户打的 Key，不用重打，
     * 也不把「写入还没返回」当成成功。
     */
    private val _keySaves = MutableStateFlow(0)
    val keySaves: StateFlow<Int> = _keySaves.asStateFlow()

    private val _testState = MutableStateFlow<AiTestState>(AiTestState.Idle)
    val testState: StateFlow<AiTestState> = _testState.asStateFlow()

    private var testJob: Job? = null

    init {
        viewModelScope.launch {
            _hasKey.value = keyStore.get() != null
        }
    }

    fun saveKey(value: String) {
        viewModelScope.launch {
            try {
                keyStore.set(value)
                _hasKey.value = keyStore.get() != null
                _keyError.value = false
                _keySaves.value++
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // Tink 被 R8 删光时抛的是 Error；设置页要显示「保存失败」，不能崩。
                logW(TAG, "Could not save the AI API key.")
                _keyError.value = true
            }
            _testState.value = AiTestState.Idle
        }
    }

    fun clearKey() {
        viewModelScope.launch {
            try {
                keyStore.clear()
                _hasKey.value = false
                _keyError.value = false
                _keySaves.value++
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                logW(TAG, "Could not clear the AI API key.")
                _keyError.value = true
            }
            _testState.value = AiTestState.Idle
        }
    }

    fun testConnection() {
        if (_testState.value == AiTestState.Testing) return

        _testState.value = AiTestState.Testing
        testJob?.cancel()
        testJob = viewModelScope.launch {
            val result = repository.testConnection()
            _testState.value = result.fold(
                onSuccess = { AiTestState.Success },
                onFailure = { throwable ->
                    val aiException = throwable as? AiException
                    AiTestState.Error(
                        error = aiException?.error ?: AiError.UNKNOWN,
                        statusCode = aiException?.statusCode
                    )
                }
            )
        }
    }
}
