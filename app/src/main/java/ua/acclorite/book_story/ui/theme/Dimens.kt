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
    /**
     * 18dp：仓库里最高频的间距（全工程约 84 处，设置行、阅读页左右留白都是它）。
     * 注意：其中约 6 处是 `Modifier.size(18.dp)` 的图标尺寸，不是间距，
     * 迁移时别机械替换。
     */
    val page = 18.dp
    val xl = 24.dp
    val xxl = 32.dp
    /** 阅读页左右留白基准（对应 sidePadding 设置默认 6 × 3dp） */
    val pageInset = page
}

/**
 * 圆角阶梯。
 *
 * 注意：这里的 4/8/12/16 与 M3 `Shapes()`（`Theme.kt` 已提供）完全重叠。
 * 新代码优先用 `MaterialTheme.shapes`；本对象只用于 M3 表达不了的场合
 * （例如 sheet 顶部圆角 28dp、胶囊控件）。存在两个真相源是刻意的，别双写。
 */
object Radius {
    val xs = 4.dp
    val s = 8.dp
    val m = 12.dp
    val l = 16.dp
    /** ModalBottomSheet 顶部圆角，与 M3 默认一致，M3 Shapes 里没有这一档 */
    val sheet = 28.dp
    /** 胶囊/芯片 */
    val pill = 999.dp
}

/**
 * 固定尺寸。
 *
 * 只收录仓库里真实存在且有明确数值依据的量；写不准的宁可不收 ——
 * 一个没根据的 token 会在后续阶段静默地把布局改掉。
 */
object Dimens {
    /** M3 TopAppBar 单行容器高（仓库里所有顶栅都是 M3 TopAppBar，不是 56dp） */
    val topBarHeight = 64.dp
    /** 封面宽高比，与 LibraryGridItem.kt:82 的 aspectRatio(1f / 1.5f) 一致 */
    val coverAspect = 1f / 1.5f
}
