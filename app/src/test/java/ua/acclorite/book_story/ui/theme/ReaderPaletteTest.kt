/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * fork 新增：阅读区配色的对比度保证。
 *
 * 这类 bug 在真机上表现为「换了深色阅读背景后，上下栏文字看不清」，
 * 靠肉眼看截图很容易漏（尤其只测浅色预设时）。所以用断言把下限钉住。
 */

package ua.acclorite.book_story.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderPaletteTest {

    private data class Preset(val name: String, val background: Color, val onBackground: Color)

    private val presets = listOf(
        Preset("羊皮纸", Color(0xFFF5EFE0), Color(0xFF3A3226)),
        Preset("护眼绿", Color(0xFFE3EFE3), Color(0xFF2B3A2B)),
        Preset("夜黑", Color(0xFF121212), Color(0xFFCFCFCF)),
        Preset("纯黑", Color(0xFF000000), Color(0xFFB0B0B0)),
        Preset("蓝色浅色", Color(0xFFFAF8FF), Color(0xFF1B1B1F))
    )

    @Test
    fun `bar text stays readable on every preset`() {
        presets.forEach { preset ->
            val palette = readerPalette(preset.background, preset.onBackground)
            val ratio = contrastRatio(palette.onBar, palette.bar)
            assertTrue(
                "${preset.name}: 栏内文字对比度 $ratio 低于 ${MinTextContrast}",
                ratio >= MinTextContrast
            )
        }
    }

    @Test
    fun `muted text stays readable on every preset`() {
        presets.forEach { preset ->
            val palette = readerPalette(preset.background, preset.onBackground)
            val ratio = contrastRatio(palette.onBarMuted, palette.bar)
            // 次要文字放宽到 3:1（WCAG AA 对大字号/次要信息的底线）
            assertTrue(
                "${preset.name}: 次要文字对比度 $ratio 过低",
                ratio >= 3.0
            )
        }
    }

    @Test
    fun `bar stays in the same colour family as the page`() {
        presets.forEach { preset ->
            val palette = readerPalette(preset.background, preset.onBackground)
            val ratio = contrastRatio(palette.bar, palette.background)
            // 栏底色只是「略微抬起」，不应该变成另一套配色
            assertTrue(
                "${preset.name}: 栏底色与背景差异 $ratio 过大（应 < 1.35）",
                ratio < 1.35
            )
            assertTrue(
                "${preset.name}: 栏底色与背景几乎相同（$ratio），看不出层次",
                ratio > 1.0
            )
        }
    }

    @Test
    fun `accent is kept when it already contrasts with the bar`() {
        val palette = readerPalette(
            background = Color(0xFFFAF8FF),
            onBackground = Color(0xFF1B1B1F),
            accentSeed = Color(0xFF0B57D0) // 深蓝，在浅背景上对比度足够
        )
        assertEquals(Color(0xFF0B57D0), palette.accent)
    }

    @Test
    fun `accent falls back to onBar when the seed is unreadable`() {
        // 与浅背景几乎同色的强调色：直接用会看不见
        val palette = readerPalette(
            background = Color(0xFFFAF8FF),
            onBackground = Color(0xFF1B1B1F),
            accentSeed = Color(0xFFF0F0F5)
        )
        assertEquals(palette.onBar, palette.accent)
        assertTrue(contrastRatio(palette.accent, palette.bar) >= MinTextContrast)
    }

    @Test
    fun `default palette is self-consistent`() {
        val palette = ReaderPalette.Default
        assertTrue(contrastRatio(palette.onBackground, palette.background) >= MinTextContrast)
        assertTrue(contrastRatio(palette.onBar, palette.bar) >= MinTextContrast)
    }

    @Test
    fun `contrast ratio matches known reference values`() {
        // 黑/白对比度是 21:1，同色是 1:1
        assertEquals(21.0, contrastRatio(Color.Black, Color.White), 0.01)
        assertEquals(1.0, contrastRatio(Color.Red, Color.Red), 0.001)
    }
}
