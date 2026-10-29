/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * fork 新增：尺寸 token。
 *
 * 背景：`ui/reader` + `ui/common` 里有 20 处 18.dp、19 处 8.dp、17 处 24.dp 这样的
 * 散落字面量，改一处间距要全局搜索，也无法回答「本 App 的间距阶梯是什么」。
 *
 * 使用约定（重要）：
 *   1. 本文件只提供阶梯，不代表存量代码已迁移。存量 90 余处字面量按「改到哪个组件
 *      就顺手迁移」的方式渐进替换，不做一次性全局替换 —— 那种 diff 无法 review，
 *      而且对视觉零收益。
 *   2. 新写的组件（Phase 2 起的排版面板、Phase 3 的手势浮层）一律从本文件取。
 *   3. 阅读正文的内边距来自用户设置（sidePadding / verticalPadding），不走本文件。
 */

package ua.acclorite.book_story.ui.theme

import androidx.compose.ui.unit.dp

/** 间距阶梯，4dp 基准 */
object Spacing {
    val xs = 4.dp
    val s = 8.dp
    val m = 12.dp
    val l = 16.dp
    /** 本仓库事实上的标准内边距：设置行、阅读页侧边留白都是 18dp（见下方 pageInset） */
    val page = 18.dp
    val xl = 24.dp
    val xxl = 32.dp
    /** 阅读页左右留白基准（对应 sidePadding 设置默认 6 × 3dp） */
    val pageInset = page
}

/** 圆角阶梯 */
object Radius {
    val xs = 4.dp
    val s = 8.dp
    val m = 12.dp
    val l = 16.dp
    /** ModalBottomSheet 顶部圆角，与 M3 默认一致 */
    val sheet = 28.dp
    /** 胶囊/芯片 */
    val pill = 999.dp
}

/** 固定尺寸 */
object Dimens {
    /** M3 TopAppBar 单行容器高，不是 56dp（仓库里所有顶栅都是 M3 组件） */
    val topBarHeight = 64.dp
    val iconButton = 40.dp
    /** 最小可点击区域，无障碍下限 */
    val touchTarget = 48.dp
    /** 封面宽高比，与 LibraryGridItem.kt:82 的 aspectRatio(1f / 1.5f) 一致 */
    val coverAspect = 1f / 1.5f
}
