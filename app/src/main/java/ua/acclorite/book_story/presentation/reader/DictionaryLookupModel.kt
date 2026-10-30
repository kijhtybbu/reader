/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * fork 新增：取词卡片的状态（第二阶段 S5）。
 *
 * 与 ReaderModel 分开的理由和 BookmarkViewModel 一样：取词是旁挂功能，不进阅读进度状态机。
 *
 * 关键约束：
 *  - 每次查询先取消上一次的 Job。连点两个词时，先发出的那次查询可能后回来，
 *    不取消就会把卡片的内容换成上一个词（锚点却是新词的）。
 *  - 状态里带 [LookupState] 的锚点，UI 只负责画，不再自己算位置。
 *  - AI 解释（S6）**没有**接：`onAiExplain` 由 UI 侧传 null，按钮是禁用态。
 */

package ua.acclorite.book_story.presentation.reader

import androidx.compose.ui.geometry.Rect
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import ua.acclorite.book_story.core.log.logW
import ua.acclorite.book_story.data.dictionary.DictionaryHit
import ua.acclorite.book_story.data.dictionary.DictionaryRepository
import ua.acclorite.book_story.ui.reader.normalizeLookupWord
import javax.inject.Inject

private const val TAG = "DictionaryLookupModel"

sealed interface LookupState {

    data object Idle : LookupState

    data class Loading(val word: String, val anchor: Rect?) : LookupState

    /**
     * @param entries 全部命中（多词典时按「精确 > 前缀 > 模糊」+ 优先词典排序）。
     * @param selectedIndex 当前展示正文的那一条，[html] 是它的 `Article.toHtml()`。
     * @param fromCache S7 的缓存命中标记；S5 还没有缓存，恒为 false。
     */
    data class Success(
        val word: String,
        val anchor: Rect?,
        val entries: List<DictionaryHit>,
        val selectedIndex: Int,
        val html: String,
        val fromCache: Boolean = false
    ) : LookupState

    data class NotFound(val word: String, val anchor: Rect?) : LookupState

    /** 查询本身失败（引擎/磁盘异常）。[word] 留给「重试」。 */
    data class Error(val word: String, val anchor: Rect?, val message: String) : LookupState
}

/** 当前状态的锚点矩形（窗口坐标）。卡片定位与「链接再查一次」都要保持同一位置。 */
val LookupState.anchor: Rect?
    get() = when (val state = this) {
        is LookupState.Loading -> state.anchor
        is LookupState.Success -> state.anchor
        is LookupState.NotFound -> state.anchor
        is LookupState.Error -> state.anchor
        LookupState.Idle -> null
    }

@HiltViewModel
class DictionaryLookupModel @Inject constructor(
    private val repository: DictionaryRepository
) : ViewModel() {

    private val _state = MutableStateFlow<LookupState>(LookupState.Idle)
    val state: StateFlow<LookupState> = _state.asStateFlow()

    /**
     * 手指按下时命中的词矩形，用来**立刻**画正文高亮。
     * 卡片本身要等双击判定（约 300ms）之后才出现；没有这个预览，用户点完会觉得「没反应」。
     */
    private val _previewAnchor = MutableStateFlow<Rect?>(null)
    val previewAnchor: StateFlow<Rect?> = _previewAnchor.asStateFlow()

    private var job: Job? = null

    /** 手指按下时先亮高亮，不查词。 */
    fun preview(anchor: Rect) {
        _previewAnchor.value = anchor
    }

    /** 手势被取消（拖动/翻页）或双击翻译时撤掉预览高亮。 */
    fun clearPreview() {
        _previewAnchor.value = null
    }

    /**
     * 查一个词。
     *
     * @param anchor 词在窗口坐标系里的矩形；null 表示锚点未知（长按选区的 rect 拿不到时），
     *   UI 会退回居中卡片。
     * @param sentence 词所在的句子。S6 的 AI 解释会用它，本期不参与查询。
     */
    fun lookup(word: String, anchor: Rect?, sentence: String? = null) {
        val normalized = normalizeLookupWord(word) ?: return
        job?.cancel()
        job = viewModelScope.launch {
            publish(LookupState.Loading(normalized, anchor))
            // 预览矩形与 Loading 的锚点通常相同；清掉它，避免「卡片居中 + 残留旧高亮」的组合。
            _previewAnchor.value = null
            val hits = try {
                repository.lookup(normalized)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logW(TAG, "Could not look up [$normalized]: ${e.message}")
                publish(LookupState.Error(normalized, anchor, e.message.orEmpty()))
                return@launch
            }
            val first = hits.firstOrNull()
            if (first == null) {
                publish(LookupState.NotFound(normalized, anchor))
                return@launch
            }
            val html = repository.article(first.dictId, first).orEmpty()
            publish(
                LookupState.Success(
                    word = normalized,
                    anchor = anchor,
                    entries = hits,
                    selectedIndex = 0,
                    html = html
                )
            )
        }
    }

    /** 切换多词典命中里的第 [index] 条（词条选择器）。 */
    fun select(index: Int) {
        val current = _state.value as? LookupState.Success ?: return
        val hit = current.entries.getOrNull(index) ?: return
        if (index == current.selectedIndex) return
        job?.cancel()
        job = viewModelScope.launch {
            val html = repository.article(hit.dictId, hit).orEmpty()
            // 期间可能已经 dismiss 或发起了新的查询：只在状态还是这次命中时落地。
            val latest = _state.value as? LookupState.Success ?: return@launch
            if (latest.word != current.word) return@launch
            publish(latest.copy(selectedIndex = index, html = html))
        }
    }

    /** 重试上一次查询（错误态的「重试」）。 */
    fun retry() {
        val word = when (val current = _state.value) {
            is LookupState.Error -> current.word
            is LookupState.NotFound -> current.word
            else -> return
        }
        val anchor = when (val current = _state.value) {
            is LookupState.Error -> current.anchor
            is LookupState.NotFound -> current.anchor
            else -> null
        }
        lookup(word, anchor)
    }

    fun dismiss() {
        job?.cancel()
        job = null
        _previewAnchor.value = null
        _state.value = LookupState.Idle
    }

    /**
     * 只在自己还活着的时候写状态。取消之后协程可能还会跑完最后几行（仓库把
     * `CancellationException` 重抛之前的那一次调用已经返回），不拦住的话卡片会在
     * 关闭后又自己弹出来。
     */
    private fun CoroutineScope.publish(state: LookupState) {
        if (isActive) _state.value = state
    }
}
