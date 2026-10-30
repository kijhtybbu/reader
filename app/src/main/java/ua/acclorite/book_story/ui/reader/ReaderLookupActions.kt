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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import ua.acclorite.book_story.presentation.reader.model.ReaderLookupGesture

/** 选区落定的防抖：拖动选择手柄时 `showMenu` 会被连续调用。 */
internal const val SELECTION_SETTLE_DEBOUNCE_MS = 350L

/**
 * 跨行选区的包围盒宽度≥首行行宽 —— 首行从起点到行尾、末行从行首到终点，
 * 两行的包围盒把整行都盖住了；而选中一个词时选区宽度≈词宽。
 * 所以「选区宽度接近行宽」就是跨行，不需要碰 Compose 在矩形底部加的手柄内边距。
 */
private const val SELECTION_LINE_WIDTH_FACTOR = 0.9f

/**
 * 反查词用的探测点相对选区顶边的偏移（px）。
 *
 * 不能用选区矩形的**几何中心**：Compose 的 `SelectionManager.getContentRect()` 在底部加了
 * `4 × HandleHeight`（给系统工具栏留位），中心会被推到当前行下方，正好落在下一行或行间
 * 空隙——长按选中「apple」却查出下一行的词，高亮也画到别的词上。选区顶边就是第一条
 * 选中线的行顶，往下几个像素一定还在这一行内。
 */
private const val SELECTION_PROBE_OFFSET_PX = 4f

/**
 * 选区矩形是不是跨行的。
 *
 * 不能用 `rect.height` 直接比：Compose 的 `SelectionManager.getContentRect()` 在底部额外加了
 * `4 × HandleHeight`（把系统工具栏抬高用的内边距），高度天然比行高多出一大截。
 * 宽度没有这个问题，而且跨行时必然接近行宽（见 [SELECTION_LINE_WIDTH_FACTOR]）。
 * 选区矩形是根坐标、词矩形是窗口坐标；阅读页是 edge-to-edge 全屏，两者原点相同。
 */
internal fun spansMultipleLines(selectionRect: Rect, hit: WordHit): Boolean =
    hit.lineWidth > 0f && selectionRect.width >= hit.lineWidth * SELECTION_LINE_WIDTH_FACTOR

@Immutable
class ReaderLookupActions(
    val gesture: ReaderLookupGesture,
    /** 取一个词。[anchor] 是词在窗口坐标里的矩形，null 时卡片居中。 */
    val onWord: (word: String, anchor: Rect?) -> Unit,
    /** 手指按下时立刻高亮命中的词（不查词）。 */
    val onPreview: (anchor: Rect) -> Unit,
    /** 手势被取消时撤掉预览高亮。 */
    val onPreviewDismiss: () -> Unit,
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
 * 同一个选区矩形的重取窗口（ms）。拖动选择手柄时 `showMenu` 会被连续调用，防抖后仍可能
 * 用同一矩形再报一次；窗口内的重复矩形只取一次词。超过窗口后再长按同一个词还是能重新弹卡片。
 */
private const val SELECTION_RELOOKUP_WINDOW_MS = 1500L

/**
 * 选区落定 → 取词。
 *
 * 只拿矩形、不读剪贴板：`SelectionToolbar.showMenu` 拿不到选中文本，而「复制到剪贴板再读回来」
 * 在 Android 13+ 每次写剪贴板都会弹系统提示，拖动选区时等于连续弹窗。
 * 词由注册表按选区首行反查 —— 长按本来就是选一个词，首行一定落在那个词上。
 *
 * 锚点必须用注册表给出的 [WordHit.rect]（归一化后的精确词矩形），不能把选区矩形传给卡片：
 * 后者是「选区包围盒 + 底部手柄内边距 + 裁剪到可见区」，带标点的词、靠边的词会整体偏移，
 * 卡片位置与正文高亮都会贴错（用户反馈的「有的单词高亮错位」）。
 */
@Composable
internal fun rememberLookupSelectionHandler(
    lookup: ReaderLookupActions,
    textLayouts: ReaderTextLayoutRegistry
): (rect: Rect, hideSelectionToolbar: () -> Unit) -> Unit {
    val scope = rememberCoroutineScope()
    val currentLookup = rememberUpdatedState(lookup)
    var job by remember { mutableStateOf<Job?>(null) }
    // 已经处理过的选区矩形 + 时间。为什么要去重：一次手势里 `showMenu` 会被多次调用
    // （拖动、焦点/选区更新），防抖后仍可能用同一个矩形再回调一次，不去重就会重复查两次。
    // 用时间窗口而不是永久去重：否则卡片关掉后再长按同一个词永远不出卡片。
    var lastHandledRect by remember { mutableStateOf<Rect?>(null) }
    var lastHandledAt by remember { mutableStateOf(0L) }

    return remember(textLayouts, scope) {
        { rect, hideSelectionToolbar ->
            job?.cancel()
            job = scope.launch {
                delay(SELECTION_SETTLE_DEBOUNCE_MS)
                val actions = currentLookup.value
                val now = System.currentTimeMillis()
                val duplicate = rect == lastHandledRect &&
                    now - lastHandledAt < SELECTION_RELOOKUP_WINDOW_MS
                if (actions.longPressEnabled && !duplicate) {
                    val probe = Offset(rect.center.x, rect.top + SELECTION_PROBE_OFFSET_PX)
                    val hit = textLayouts.hitTest(probe)
                    if (hit != null && !spansMultipleLines(rect, hit)) {
                        lastHandledRect = rect
                        lastHandledAt = now
                        // 系统 ActionMode 悬浮工具栏是一个触摸模态窗口：不收起它，卡片外的点击
                        // 全被它吞掉，卡片关不掉。卡片弹窗本身是非 focusable 的，不会因为
                        // 「焦点变化 → SelectionManager 重新 showMenu」把工具栏又弹回来。
                        hideSelectionToolbar()
                        actions.onWord(hit.word, hit.rect)
                    }
                }
            }
        }
    }
}
