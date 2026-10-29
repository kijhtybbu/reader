/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * fork 新增：阅读器底部面板的「常驻高频排版区」。
 *
 * 改动前，改一次字号要：点正文 → 底部面板 → 切到「阅读器」Tab → 进「字体」子页 → 拖滑块 → 返回。
 * 四层操作，而拖动时面板又遮住正文，看不到效果。
 *
 * 现在这些项直接摆在第一个 Tab：字号 / 行距 / 段距 / 字距 / 缩进 / 对齐 / 字体。
 * 它们同时也是中文排版里最常调的东西（对齐与首行缩进尤其）。
 *
 * 注意：这里只重排入口，不新增能力 —— 每一项都是原有设置项的原控件。
 * 样例预览不在这里，它固定在面板顶部（见 ReaderSettingsBottomSheet 的排版页），
 * 否则滚到下半部分调字距/缩进时就看不到预览了。
 */

package ua.acclorite.book_story.ui.reader

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.MaterialTheme
import ua.acclorite.book_story.ui.settings.components.SettingsSubcategory
import ua.acclorite.book_story.ui.settings.reader.font.components.FontFamilyOption
import ua.acclorite.book_story.ui.settings.reader.font.components.FontSizeOption
import ua.acclorite.book_story.ui.settings.reader.font.components.LetterSpacingOption
import ua.acclorite.book_story.ui.settings.reader.text.components.LineHeightOption
import ua.acclorite.book_story.ui.settings.reader.text.components.ParagraphHeightOption
import ua.acclorite.book_story.ui.settings.reader.text.components.ParagraphIndentationOption
import ua.acclorite.book_story.ui.settings.reader.text.components.TextAlignmentOption

/**
 * 顺序按「改动频率」排：预设 → 字号 → 行距 → 段距 → 字距 → 缩进 → 对齐 → 字体。
 * 字体放在最后，因为它是横向 chips，占高度最多。
 *
 * 第一项是面板内的样例预览：分页模式下正文会在每次重排时淡出（见 ReaderTypographyPresets.kt），
 * 所以拖动时真正能看的是这段样例，而不是面板上方的正文。
 */
fun LazyListScope.ReaderTypographyQuickSettings() {
    SettingsSubcategory(
        titleColor = { MaterialTheme.colorScheme.primary },
        title = { "" },
        showTitle = false,
        showDivider = false
    ) {
        item { ReaderTypographyPresets() }
        item { FontSizeOption() }
        item { LineHeightOption() }
        item { ParagraphHeightOption() }
        item { LetterSpacingOption() }
        item { ParagraphIndentationOption() }
        item { TextAlignmentOption() }
        item { FontFamilyOption() }
    }
}
