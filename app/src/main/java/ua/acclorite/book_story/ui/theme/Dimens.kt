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
 *      就顺手迁移」的方式渐进替换 —— 那种一次性全局替换的 diff 无法 review，且对视觉零收益。
 *   2. 调用处有本文件里精确对应的值时一律取 token（如 `Spacing.m` = 12dp）；
 *      阶梯上没有对应值的（如 3dp、6dp 这类微调）保留字面量，**不要**为它另造 token。
 *   3. 漏删与错数一样是债：本文件里的声明必须有消费者，没人用的 token 直接删。
 *   4. 阅读正文的内边距来自用户设置（sidePadding / verticalPadding），不走本文件。
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
}

/**
 * 圆角阶梯。
 *
 * 4/8/12/16 与 M3 `Shapes()`（`Theme.kt` 已提供）完全重叠，所以这四档**不再单独列 token**：
 * 需要它们时直接用 `MaterialTheme.shapes`。本对象只留 M3 表达不了的场合 ——
 * 胶囊控件、以及「角色别名」（见 [cover]）。
 *
 * 角色别名存在的理由：数值 M3 有，但同一个角色必须跨布局取同一个值，
 * 用语义名把「这就是封面」写下来，以免下一个布局又自己挑一个 shapes 档。
 */
object Radius {
    /** 胶囊/芯片 */
    val pill = 999.dp
    /**
     * 书架封面圆角。上游网格用 shapes.medium（12dp）、列表缩略图用 shapes.small（8dp），
     * 同一张封面在两种布局里不是同一个形状。取 12dp：网格保持原样，列表跟过来。
     */
    val cover = 12.dp
}

/**
 * 固定尺寸。
 *
 * 只收录仓库里真实存在且有明确数值依据的量；写不准的宁可不收 ——
 * 一个没根据的 token 会在后续阶段静默地把布局改掉。
 */
object Dimens {
    /** 封面宽高比 2:3，网格项用它；不要在调用处再写字面量 */
    val coverAspect = 1f / 1.5f
    /** 顶部浮层（章节提示）的最大宽度，避免长章名横贯整屏 */
    val hintMaxWidth = 280.dp
    /**
     * 书架封面上的阅读进度环直径。取 16dp = bodySmall 行高，
     * 因此进度胶囊的高度与原先纯文字版本完全一致，网格项尺寸不变。
     */
    val coverProgressRing = 16.dp
    /** 进度环描边：16dp 环上的可辨识下限，再细在高密度屏上几乎看不见 */
    val coverProgressRingStroke = 2.dp
}
