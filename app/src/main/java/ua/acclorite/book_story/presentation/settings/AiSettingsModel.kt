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
import ua.acclorite.book_story.data.ai.cache.TranslationCacheRepository
import java.util.Locale
import javax.inject.Inject

private const val TAG = "AiSettingsModel"

sealed interface AiTestState {
    data object Idle : AiTestState
    data object Testing : AiTestState
    data object Success : AiTestState
    data class Error(val error: AiError, val statusCode: Int?) : AiTestState
}

/**
 * 「翻译缓存」一行的状态（S9）。
 *
 * [Loading] 同时覆盖「首次读占用」与「正在清除」：两种情况按钮都该禁用。
 * [Ready.cleared] 只在刚清除成功的那一次为 true，用来显示一次「缓存已清除」的结果；
 * 离开页面重建 ViewModel 后自然消失；本页没有下拉刷新。
 */
sealed interface AiCacheState {
    data object Loading : AiCacheState
    data class Ready(
        val count: Int,
        val payloadBytes: Long,
        val cleared: Boolean = false
    ) : AiCacheState

    data object LoadError : AiCacheState
    data object ClearError : AiCacheState
}

/** 缓存占用按 MB 显示（设计 §7.3 的预算是 200MB，用字节数反而不直观）。 */
internal fun formatCachePayloadSize(bytes: Long): String =
    String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0))

@HiltViewModel
class AiSettingsModel @Inject constructor(
    private val keyStore: AiKeyStore,
    private val repository: AiTranslationRepository,
    private val cache: TranslationCacheRepository
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

    private val _cacheState = MutableStateFlow<AiCacheState>(AiCacheState.Loading)
    val cacheState: StateFlow<AiCacheState> = _cacheState.asStateFlow()

    private var testJob: Job? = null
    private var cacheJob: Job? = null

    init {
        viewModelScope.launch {
            _hasKey.value = keyStore.get() != null
        }
        refreshCache()
    }

    /**
     * 每次进入设置页调用（`AiSettingsScreen` 的 `LaunchedEffect`）。
     *
     * `Navigator` 是单 Activity 回退栈、没有 per-screen `ViewModelStoreOwner`，`hiltViewModel()`
     * 拿到的是 Activity 作用域实例：上一次访问留下的「缓存已清除」与连接测试结果会一直显示，
     * 缓存条数也停在旧值。这里把它们重置并重读占用。
     */
    fun onEnter() {
        testJob?.cancel()
        _testState.value = AiTestState.Idle
        refreshCache()
    }

    /** 重新读取缓存的条数与占用；失败只改变这一行的状态，不影响 Key/连接的交互。 */
    fun refreshCache() {
        cacheJob?.cancel()
        cacheJob = viewModelScope.launch {
            _cacheState.value = AiCacheState.Loading
            _cacheState.value = try {
                val stats = cache.stats()
                AiCacheState.Ready(count = stats.count, payloadBytes = stats.payloadBytes)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logW(TAG, "Could not read the AI cache usage: ${e.message}")
                AiCacheState.LoadError
            }
        }
    }

    /** 清空全部翻译缓存（用户确认后调用）。清除成功后显示 0 条，不保留旧占用。 */
    fun clearCache() {
        cacheJob?.cancel()
        cacheJob = viewModelScope.launch {
            _cacheState.value = AiCacheState.Loading
            _cacheState.value = try {
                cache.clear()
                val stats = try {
                    cache.stats()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // 缓存已经清空了；读不到新的占用也按 0 显示，不能把成功报成失败。
                    logW(TAG, "Could not read the AI cache usage after clearing: ${e.message}")
                    null
                }
                AiCacheState.Ready(
                    count = stats?.count ?: 0,
                    payloadBytes = stats?.payloadBytes ?: 0L,
                    cleared = true
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logW(TAG, "Could not clear the AI cache: ${e.message}")
                AiCacheState.ClearError
            }
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
