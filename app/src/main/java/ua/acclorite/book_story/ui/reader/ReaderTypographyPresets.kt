/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * fork 新增：排版预设 + 面板内实时预览。
 *
 * 为什么需要面板内预览：
 * 分页模式下，任何排版参数变化都会把正文淡出到 alpha 0 重新分页
 * （ReaderLayoutPaged.kt:249 → contentAlpha，:308 才恢复），而滑块是逐步回调的，
 * 所以「拖动时看正文」在默认布局下根本不成立 —— 文字会一直在闪。
 * 与其去改那个淡出闸门（它还负责首次加载与滚动定位），不如在面板里放一段
 * 用当前设置渲染的样例文字：与阅读布局无关，拖到哪看到哪。
 *
 * 预设只是把一组设置一次性写入，不引入新的存储结构。
 */

package ua.acclorite.book_story.ui.reader

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import ua.acclorite.book_story.R
import ua.acclorite.book_story.ui.common.components.common.StyledText
import ua.acclorite.book_story.ui.common.components.settings.ChipsWithTitle
import ua.acclorite.book_story.ui.common.helpers.LocalSettings
import ua.acclorite.book_story.ui.common.model.ListItem

/**
 * 一组预设值。
 *
 * 注意单位与语义（都来自 SettingsManager，不是直觉值）：
 *  - fontSize / lineHeight 都是 sp；行高实际是 `fontSize + lineHeight`
 *  - paragraphHeight 是 dp 倍率（× 3dp）
 *  - letterSpacing 是 1/100 em
 *  - paragraphIndentation 是 sp 倍率（× 6sp），5 ≈ 16sp 字号下的两字缩进
 */
private data class TypographyPreset(
    val labelRes: Int,
    val fontSize: Int,
    val lineHeight: Int,
    val paragraphHeight: Int,
    val letterSpacing: Int,
    val paragraphIndentation: Int
)

private val typographyPresets = listOf(
    TypographyPreset(R.string.preset_compact, fontSize = 14, lineHeight = 2, paragraphHeight = 6, letterSpacing = 0, paragraphIndentation = 4),
    TypographyPreset(R.string.preset_standard, fontSize = 16, lineHeight = 4, paragraphHeight = 8, letterSpacing = 0, paragraphIndentation = 5),
    TypographyPreset(R.string.preset_large, fontSize = 20, lineHeight = 8, paragraphHeight = 12, letterSpacing = 0, paragraphIndentation = 5),
    TypographyPreset(R.string.preset_eye_care, fontSize = 18, lineHeight = 6, paragraphHeight = 10, letterSpacing = 2, paragraphIndentation = 5)
)

@Composable
fun ReaderTypographyPresets() {
    val settings = LocalSettings.current

    val current = listOf(
        settings.fontSize.value,
        settings.lineHeight.value,
        settings.paragraphHeight.value,
        settings.letterSpacing.value,
        settings.paragraphIndentation.value
    )

    ChipsWithTitle(
        title = stringResource(id = R.string.typography_presets_option),
        chips = typographyPresets.map { preset ->
            ListItem(
                item = preset,
                title = stringResource(id = preset.labelRes),
                selected = current == listOf(
                    preset.fontSize,
                    preset.lineHeight,
                    preset.paragraphHeight,
                    preset.letterSpacing,
                    preset.paragraphIndentation
                )
            )
        },
        onClick = { preset ->
            settings.fontSize.update(preset.fontSize)
            settings.lineHeight.update(preset.lineHeight)
            settings.paragraphHeight.update(preset.paragraphHeight)
            settings.letterSpacing.update(preset.letterSpacing)
            settings.paragraphIndentation.update(preset.paragraphIndentation)
        }
    )
}

/**
 * 面板内样例预览：完全按当前排版设置渲染一段固定文字。
 * 颜色用 App 主题（面板本身是不透明的主题表面），不要用阅读区配色。
 */
@Composable
fun ReaderTypographyPreview() {
    val settings = LocalSettings.current

    val style = TextStyle(
        fontFamily = settings.fontFamily.value.font,
        fontWeight = FontWeight(settings.fontThickness.lastValue),
        fontSize = settings.fontSize.lastValue.sp,
        lineHeight = (settings.fontSize.lastValue + settings.lineHeight.lastValue).sp,
        letterSpacing = (settings.letterSpacing.lastValue / 100f).em,
        textIndent = TextIndent(firstLine = (settings.paragraphIndentation.lastValue * 6).sp),
        textAlign = settings.textAlignment.lastValue.textAlignment,
        lineBreak = LineBreak.Paragraph,
        color = MaterialTheme.colorScheme.onSurface
    )

    StyledText(
        text = stringResource(id = R.string.typography_preview_sample),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 12.dp),
        style = style
    )
}
