/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * fork 新增：把取词回调打包成一份，穿过 ReaderContent → ReaderScaffold → 两种布局。
 *
 * 为什么不直接加两个 lambda 参数：两种布局原本已经有十几个回调参数了；而且这两个必须一起变
 * （手势决定单击取词是否生效）。实例由 ReaderContent 用 `remember` 固定，避免每次重组都换掉
 * 导致 `pointerInput` 重启。
 *
 * 长按选区的解析也在这里（[rememberLookupSelectionHandler]）：选区落定的回调来自布局内部的
 * `SelectionContainer`，只有布局手里有取词注册表，所以「矩形 → 词」必须在这一层做。
 */

package ua.acclorite.book_story.ui.reader

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import ua.acclorite.book_story.presentation.reader.model.ReaderLookupGesture

/** 选区落定的防抖：拖动选择手柄时 `showMenu` 会被连续调用。 */
internal const val SELECTION_SETTLE_DEBOUNCE_MS = 350L

/**
 * 多行选区不取词。S8 会把长选区接到 AI 翻译上；在那之前，只有「一行内的短选区」
 * 才当成取词。用词矩形的高度做代理：命中到的词矩形高度≈行高，选区高出 1.5 倍
 * 就说明跨行了（两行选区≈2 倍行高，必须被拒）。
 */
private const val MAX_SELECTION_LINE_FACTOR = 1.5f

@Immutable
class ReaderLookupActions(
    val gesture: ReaderLookupGesture,
    /** 取一个词。[anchor] 是词在窗口坐标里的矩形，null 时卡片居中。 */
    val onWord: (word: String, anchor: Rect?) -> Unit,
    /** 翻页、滚动、切菜单、返回时关掉卡片。 */
    val onDismiss: () -> Unit
) {
    /** 单击取词：接管「点到文字」的单击，空白区仍然翻页/呼菜单。 */
    val singleTapEnabled: Boolean
        get() = gesture == ReaderLookupGesture.SINGLE_TAP

    val longPressEnabled: Boolean
        get() = gesture == ReaderLookupGesture.LONG_PRESS
}

/**
 * 选区落定 → 取词。
 *
 * 只拿矩形、不读剪贴板：`SelectionToolbar.showMenu` 拿不到选中文本，而「复制到剪贴板再读回来」
 * 在 Android 13+ 每次写剪贴板都会弹系统提示，拖动选区时等于连续弹窗。
 * 词由注册表按选区中心反查 —— 长按本来就是选一个词，中心一定落在那个词上。
 */
@Composable
internal fun rememberLookupSelectionHandler(
    lookup: ReaderLookupActions,
    textLayouts: ReaderTextLayoutRegistry
): (Rect) -> Unit {
    val scope = rememberCoroutineScope()
    val currentLookup = rememberUpdatedState(lookup)
    var job by remember { mutableStateOf<Job?>(null) }

    return remember(textLayouts, scope) {
        { rect ->
            job?.cancel()
            job = scope.launch {
                delay(SELECTION_SETTLE_DEBOUNCE_MS)
                val actions = currentLookup.value
                if (actions.longPressEnabled) {
                    val hit = textLayouts.hitTest(rect.center)
                    if (hit != null && rect.height <= hit.rect.height * MAX_SELECTION_LINE_FACTOR) {
                        actions.onWord(hit.word, rect)
                    }
                }
            }
        }
    }
}
