/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * fork 新增：把取词回调打包成一份，穿过 ReaderContent → ReaderScaffold → 两种布局。
 *
 * 为什么不直接加两个 lambda 参数：两种布局原本已经有十几个回调参数了；而且这两个必须一起变
 * （手势决定单击/长按取词是否生效）。实例由 ReaderContent 用 `remember` 固定，避免每次重组都换掉
 * 导致 `pointerInput` 重启。
 *
 * 长按取词**不再**借用 `SelectionContainer` 的选区（旧的 `onSelectionSettled` 那条路已删除）：
 *  - Compose 长按选区的单词边界来自它自己的 `SelectionAdjustment.Word`，与正文注册表的命中
 *    不是一个坐标系（两端对齐的行还会偏一个词），于是屏幕上同时出现「选区高亮 + 卡片高亮」
 *    两块不同区间 —— 用户看到的就是「先选错一个词、再叠一个对的」。
 *  - 选区手柄一拖、系统 ActionMode 一重报（焦点变化/裁掉工具栏后）都会再走一次 `showMenu`，
 *    卡片刚关掉又会自己弹回来；拖动选择框也会触发取词。
 * 现在长按由布局里**唯一**的 `detectTapGestures` 处理，坐标走注册表（与单击同一条路，锚点就是
 * 卡片用的那个精确词矩形）。代价是长按模式下不能再同时选文本：阅读页的 `SelectionContainer`
 * 会按 [ReaderLookupActions.selectionEnabled] 用 `DisableSelection` 把选区整个关掉。
 */

package ua.acclorite.book_story.ui.reader

import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Rect
import ua.acclorite.book_story.presentation.reader.model.ReaderLookupGesture

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
    /** 取词是否开启（单击/长按任一）。关闭时按下也不该亮预览。 */
    val lookupEnabled: Boolean
        get() = gesture != ReaderLookupGesture.OFF

    /** 单击取词：接管「点到文字」的单击，空白区仍然翻页/呼菜单。 */
    val singleTapEnabled: Boolean
        get() = gesture == ReaderLookupGesture.SINGLE_TAP

    /** 长按取词：单击保持翻页，长按命中单词直接弹卡片。 */
    val longPressEnabled: Boolean
        get() = gesture == ReaderLookupGesture.LONG_PRESS

    /**
     * 正文选区（复制/翻译/词典工具栏）是否可用。
     *
     * 长按被取词接管时必须是 false：两套手势都盯着长按，结果就是「选区高亮 + 卡片高亮」
     * 叠在一起、手柄一拖就换词、卡片关掉又被 ActionMode 重报顶回来。
     * `SINGLE_TAP` 与 `OFF` 下长按仍然只做选区，取词不参与。
     */
    val selectionEnabled: Boolean
        get() = !longPressEnabled
}
