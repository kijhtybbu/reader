/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * fork 新增：阅读区配色的派生契约。
 *
 * 说明能测与不能测：
 *   - 能测：派生本身的保证（栏底抬升下限、强调色对比度、onBar 契约）
 *   - 不能测：UI 里谁用了哪个颜色。上一轮的真实 bug 是顶栏图标仍取 App 主题的
 *     onSurfaceVariant，palette 本身完全正确 —— 那类问题只能靠 review 或 UI 测试。
 *     所以这里不做「假装的覆盖」。
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
    fun `bar is always distinguishable from the page`() {
        presets.forEach { preset ->
            val palette = readerPalette(preset.background, preset.onBackground)
            val ratio = contrastRatio(palette.bar, preset.background)
            assertTrue(
                "${preset.name}: 栏底与背景只有 $ratio:1，低于 $MinBarLift —— 暗色端会看不出分界",
                ratio >= MinBarLift
            )
        }
    }

    @Test
    fun `bar never drifts into a different colour family`() {
        presets.forEach { preset ->
            val palette = readerPalette(preset.background, preset.onBackground)
            assertTrue(
                "${preset.name}: 栏底与背景差异过大",
                contrastRatio(palette.bar, preset.background) < 1.35
            )
        }
    }

    @Test
    fun `onBar is the preset foreground, never overridden`() {
        presets.forEach { preset ->
            val palette = readerPalette(preset.background, preset.onBackground)
            assertEquals(
                "${preset.name}: onBar 必须等于用户选定的字色，不能悄悄改",
                preset.onBackground,
                palette.onBar
            )
        }
    }

    @Test
    fun `muted text keeps a readable floor on the bar`() {
        presets.forEach { preset ->
            val palette = readerPalette(preset.background, preset.onBackground)
            assertTrue(
                "${preset.name}: 次要文字在栏底上的对比度过低",
                contrastRatio(palette.onBarMuted, palette.bar) >= 3.0
            )
        }
    }

    @Test
    fun `accent is kept when it already contrasts with the bar`() {
        val seed = Color(0xFF0B57D0)
        val palette = readerPalette(Color(0xFFFAF8FF), Color(0xFF1B1B1F), seed)
        assertEquals(seed, palette.accent)
    }

    @Test
    fun `accent stays readable when the seed is too close to the bar`() {
        val palette = readerPalette(
            background = Color(0xFFFAF8FF),
            onBackground = Color(0xFF1B1B1F),
            accentSeed = Color(0xFFF0F0F5) // 与浅背景几乎同色
        )
        assertTrue(
            "强调色必须达到 3:1 非文本对比度",
            contrastRatio(palette.accent, palette.bar) >= 3.0
        )
    }

    @Test
    fun `accent keeps some hue before falling back to plain text colour`() {
        // 深紫在浅色栏底上对比度不足，但混向字色后仍应保留色相，而不是直接变成灰
        val seed = Color(0xFFD0BCFF)
        val palette = readerPalette(Color(0xFFF5EFE0), Color(0xFF3A3226), seed)
        assertTrue(
            "强调色不应该退化成与正文完全相同的颜色（那样就失去强调作用）",
            palette.accent != palette.onBar
        )
        assertTrue(contrastRatio(palette.accent, palette.bar) >= 3.0)
    }

    @Test
    fun `Default is exactly what the factory derives`() {
        assertEquals(
            readerPalette(Color(0xFFFAF8FF), Color(0xFF1B1B1F)),
            ReaderPalette.Default
        )
    }

    @Test
    fun `contrast ratio matches known reference values`() {
        assertEquals(21.0, contrastRatio(Color.Black, Color.White), 0.01)
        assertEquals(1.0, contrastRatio(Color.Red, Color.Red), 0.001)
    }

    @Test
    fun `bar still lifts when the preset foreground is close to its background`() {
        // bg 纯黑 + fg #1A1A1A：按前景叠色到 24% 上限也只有 1.04:1，
        // 必须走「朝背景反方向推」的兜底。（#333 在 24% 时刚好 1.08，测不出兜底，别用）
        val palette = readerPalette(Color(0xFF000000), Color(0xFF1A1A1A))
        assertTrue(
            "预设前景过近时栏底仍然要能被抬起",
            contrastRatio(palette.bar, Color(0xFF000000)) >= MinBarLift
        )
    }

    @Test
    fun `accent clears the non-text floor on every preset`() {
        presets.forEach { preset ->
            val palette = readerPalette(preset.background, preset.onBackground)
            assertTrue(
                "${preset.name}: 强调色在栏底上只有 ${contrastRatio(palette.accent, palette.bar)}:1",
                contrastRatio(palette.accent, palette.bar) >= 3.0
            )
        }
    }

    @Test
    fun `mid tone presets are not silently promised to be readable`() {
        // 中灰背景 + 白字：正文对比度本身就不够（3.95:1），palette 不负责修正，
        // 但栏底与强调色仍须满足各自的契约。
        val background = Color(0xFF808080)
        val onBackground = Color(0xFFFFFFFF)
        assertTrue("前提：该预设正文对比度本身就不达标", contrastRatio(onBackground, background) < MinTextContrast)

        val palette = readerPalette(background, onBackground)
        assertTrue(contrastRatio(palette.bar, background) >= MinBarLift)
        assertTrue(contrastRatio(palette.accent, palette.bar) >= 3.0)
    }
}
