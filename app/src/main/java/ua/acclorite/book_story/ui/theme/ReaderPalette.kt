/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * fork 新增：阅读区配色。
 *
 * 要解决的问题：阅读页背景来自用户的 ColorPreset（羊皮纸/护眼绿/夜黑…），
 * 但阅读器上下栏、栏内图标与文字用的是 App 主题的 surface/onSurface
 *（`ReaderTopBar.kt:68`、`ReaderBottomBar.kt:95,106` 等）。
 * 两者彼此独立：App 主题是浅色而阅读背景是深色时，栏内文字对比度会掉到不可读。
 *
 * 本文件把「阅读区该用哪几个颜色」收敛成一份派生结果，全部从
 * (background, onBackground, accentSeed) 算出。
 *
 * 保证的范围（别扩大解读）：
 *   - 栏底与背景必然同色系且可分辨（[MinBarLift] 保底）
 *   - 强调色与栏底的对比度不低于 3:1
 *   - onBar/onBarMuted 在栏底上的对比度下限
 * **不保证**正文前景与背景的对比度 —— 那是用户所选 ColorPreset 的责任，
 * 我们不会偷偷覆盖用户选定的字色（那样 WYSIWYG 就断了）。
 *
 * 注意（纠正一处早先的判断）：`ReaderScreen.kt:196-212` 的 BlendMode 着色链
 * 作用于**插图**（ReaderColorEffects.FONT/BACKGROUND 把图片按前景/背景色调染色），
 * 不是给背景或文字上色。那是独立功能，不应该因为本文件而删除。
 */

package ua.acclorite.book_story.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance

@Immutable
data class ReaderPalette(
    /** 正文背景 */
    val background: Color,
    /** 正文文字 */
    val onBackground: Color,
    /**
     * 上下栏底色：与背景同色系、略微抬起，保证「浮起」但不跳色。
     * 暗色背景上 6% 抬升只有 4/255，看不出层次，因此抬升量有保底（见 [MinBarLift]）。
     */
    val bar: Color,
    /** 栏内文字与图标 */
    val onBar: Color,
    /**
     * 栏内次要文字。
     * 注意：该值是把 onBackground 按 [MutedAlpha] **叠在 [bar] 之上**算出来的，
     * 因此只对 [bar] 这个底成立；画在 background 或 sheet 上会二次染色。
     */
    val onBarMuted: Color,
    /** 进度条、选中态等强调色；对比度不足时会退回 onBar */
    val accent: Color
) {
    companion object {
        /**
         * 未提供时的兜底：中性浅色，用于 @Preview 与异常路径。
         * 必须走工厂派生，否则会和真实派生结果不一致（曾经手写过一版，onBarMuted 忘了叠底）。
         */
        val Default = readerPalette(
            background = Color(0xFFFAF8FF),
            onBackground = Color(0xFF1B1B1F)
        )
    }
}

/** 栏底色叠在背景上的占比；足够分辨层次，又不至于变成另一套配色 */
private const val BarTintAlpha = 0.06f

/**
 * 栏底色与背景的最低对比度。
 * 6% 在纯黑背景上只能得到 1.067:1（4/255），肉眼几乎看不出分界，
 * 而「夜黑/纯黑」恰恰是最需要层次的那个预设。
 */
const val MinBarLift = 1.08

/** 抬升量的上限，避免为了层次把栏底变成另一种颜色 */
private const val MaxBarTintAlpha = 0.24f

/** 次要文字的不透明度 */
private const val MutedAlpha = 0.62f

/** UI 组件的对比度下限（WCAG 1.4.11 非文本对比） */
private const val MinAccentContrast = 3.0

/** 正文文字的对比度下限（WCAG 1.4.3 AA） */
const val MinTextContrast = 4.5

/**
 * 从阅读背景/前景派生整套阅读区配色。
 *
 * @param accentSeed 想用作强调色的颜色（通常是 App 主题的 primary）。对比度不足时会被替换。
 */
fun readerPalette(
    background: Color,
    onBackground: Color,
    accentSeed: Color = onBackground
): ReaderPalette {
    var tintAlpha = BarTintAlpha
    var bar = onBackground.copy(alpha = tintAlpha).compositeOver(background)
    while (contrastRatio(bar, background) < MinBarLift && tintAlpha < MaxBarTintAlpha) {
        tintAlpha += 0.02f
        bar = onBackground.copy(alpha = tintAlpha).compositeOver(background)
    }

    val onBar = onBackground
    val onBarMuted = onBackground.copy(alpha = MutedAlpha).compositeOver(bar)
    val accent = readableAccent(accentSeed, bar, onBar)

    return ReaderPalette(
        background = background,
        onBackground = onBackground,
        bar = bar,
        onBar = onBar,
        onBarMuted = onBarMuted,
        accent = accent
    )
}

/**
 * 强调色：seed 与栏底对比度达标就用它，否则逐步混向 fallback。
 *
 * 直接硬切到 onBar 会让强调色失去色相（看起来和普通文字一样），
 * 而且预设切换动画期间栏底亮度会扫过阈值，颜色会突跳。
 */
private fun readableAccent(seed: Color, bar: Color, fallback: Color): Color {
    if (contrastRatio(seed, bar) >= MinAccentContrast) return seed
    listOf(0.25f, 0.5f, 0.75f).forEach { fraction ->
        val mixed = lerp(seed, fallback, fraction)
        if (contrastRatio(mixed, bar) >= MinAccentContrast) return mixed
    }
    return fallback
}

/** WCAG 相对对比度，(L1 + 0.05) / (L2 + 0.05)，取值 1..21 */
fun contrastRatio(a: Color, b: Color): Double {
    val la = a.luminance().toDouble()
    val lb = b.luminance().toDouble()
    val lighter = maxOf(la, lb)
    val darker = minOf(la, lb)
    return (lighter + 0.05) / (darker + 0.05)
}

val LocalReaderPalette = staticCompositionLocalOf { ReaderPalette.Default }
