/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * fork 新增：排版预设 + 面板内样例预览。
 *
 * 为什么需要面板内预览：
 * 分页模式下，任何排版参数变化都会把正文淡出到 alpha 0 重新分页
 * （ReaderLayoutPaged.kt:249 → contentAlpha，:308 才恢复），而滑块是逐步回调的，
 * 所以「拖动时看面板上方的正文」在默认布局下不成立 —— 文字会一直在闪。
 *
 * 预览必须读 `settings.X.value`（@Composable 的 collectAsStateWithLifecycle），
 * 不能用 `settings.X.lastValue` —— 后者只是 MutableStateFlow.value 的普通 getter，
 * 不参与 Compose 快照，读它不会在设置变化时触发重组（第一版就是这样写的，
 * 预览永远停在首帧，等于没有预览）。
 *
 * 样式与阅读页共用 readerParagraphTextStyle，预设只是把一组设置一次性写入。
 */

package ua.acclorite.book_story.ui.reader

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ua.acclorite.book_story.R
import ua.acclorite.book_story.ui.common.components.common.StyledText
import ua.acclorite.book_story.ui.common.components.settings.ChipsWithTitle
import ua.acclorite.book_story.ui.common.helpers.LocalSettings
import ua.acclorite.book_story.ui.common.model.ListItem

/**
 * 一组预设值。
 *
 * 单位与语义（都来自 SettingsManager，不是直觉值）：
 *  - fontSize / lineHeight 都是 sp；行高实际是 `fontSize + lineHeight`
 *  - paragraphHeight 是 dp 倍率（× 3dp）
 *  - letterSpacing 是 1/100 em
 *  - paragraphIndentation 是 sp 倍率（× 6sp）
 *
 * 所有值都落在各滑块的取值范围内，避免写入后滑块与数值不一致。
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

    // 用 .value 订阅：预设被应用后 chip 要立刻变成选中态
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
            // 已经是当前值时不再重复写 5 次 DataStore
            val target = listOf(
                preset.fontSize,
                preset.lineHeight,
                preset.paragraphHeight,
                preset.letterSpacing,
                preset.paragraphIndentation
            )
            if (current != target) {
                settings.fontSize.update(preset.fontSize)
                settings.lineHeight.update(preset.lineHeight)
                settings.paragraphHeight.update(preset.paragraphHeight)
                settings.letterSpacing.update(preset.letterSpacing)
                settings.paragraphIndentation.update(preset.paragraphIndentation)
            }
        }
    )
}

/**
 * 面板内样例预览：按当前排版设置渲染两段固定文字。
 *
 * 两段是必要的 —— 段距（paragraphHeight）不是 TextStyle 字段，而是段落之间的间距，
 * 只有一段文字时「标准」和「大字」预设的段距差异完全看不出来。
 *
 * 颜色用 App 主题（面板本身是不透明的主题表面）；阅读配色在颜色 Tab 里，不在这一页。
 */
@Composable
fun ReaderTypographyPreview() {
    val settings = LocalSettings.current

    // 全部用 .value：这些是 @Composable getter，读它们才会在拖动滑块时触发重组
    val style = readerParagraphTextStyle(
        fontFamily = settings.fontFamily.value.font,
        fontWeight = FontWeight(settings.fontThickness.value),
        fontSizeSp = settings.fontSize.value,
        lineHeightDeltaSp = settings.lineHeight.value,
        letterSpacingHundredths = settings.letterSpacing.value,
        paragraphIndentationSetting = settings.paragraphIndentation.value,
        textAlignment = settings.textAlignment.value,
        italic = settings.italic.value,
        color = MaterialTheme.colorScheme.onSurface
    )
    val gapDp = readerParagraphGapDp(
        paragraphHeightSetting = settings.paragraphHeight.value,
        lineHeightDeltaSp = settings.lineHeight.value
    )
    val sidePaddingDp = readerSidePaddingDp(settings.sidePadding.value)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = sidePaddingDp.dp, vertical = 12.dp)
    ) {
        StyledText(
            text = stringResource(id = R.string.typography_preview_sample),
            style = style
        )
        Spacer(modifier = Modifier.height(gapDp.dp))
        StyledText(
            text = stringResource(id = R.string.typography_preview_sample_second),
            style = style
        )
    }
    HorizontalDivider()
}
