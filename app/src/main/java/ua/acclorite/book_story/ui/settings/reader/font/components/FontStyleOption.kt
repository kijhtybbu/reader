/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 */

package ua.acclorite.book_story.ui.settings.reader.font.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextGeometricTransform
import ua.acclorite.book_story.R
import ua.acclorite.book_story.ui.common.components.settings.SegmentedButtonWithTitle
import ua.acclorite.book_story.ui.common.helpers.LocalSettings
import ua.acclorite.book_story.ui.common.model.ListItem
import ua.acclorite.book_story.ui.reader.rememberFontFamily

@Composable
fun FontStyleOption() {
    val settings = LocalSettings.current
    val isUserFont = settings.fontFamily.value.userEntry != null

    // fork: 用户字体没有 italic 字型，斜体由正文样式的几何倾斜合成；芯片里的示例也要
    // 显示同样的倾斜，否则用户会以为斜体对导入字体无效。
    val normalFamily = rememberFontFamily(
        font = settings.fontFamily.value,
        fontWeight = settings.fontThickness.value,
        italic = false
    )
    val italicFamily = rememberFontFamily(
        font = settings.fontFamily.value,
        fontWeight = settings.fontThickness.value,
        italic = true
    )

    SegmentedButtonWithTitle(
        title = stringResource(id = R.string.font_style_option),
        buttons = listOf(
            ListItem(
                item = false,
                title = stringResource(id = R.string.font_style_normal),
                textStyle = {
                    MaterialTheme.typography.labelLarge.copy(
                        fontFamily = normalFamily,
                        fontStyle = FontStyle.Normal
                    )
                },
                selected = !settings.italic.value
            ),
            ListItem(
                item = true,
                title = stringResource(id = R.string.font_style_italic),
                textStyle = {
                    MaterialTheme.typography.labelLarge.copy(
                        fontFamily = italicFamily,
                        fontStyle = FontStyle.Italic,
                        textGeometricTransform = if (isUserFont) {
                            TextGeometricTransform(skewX = -0.25f)
                        } else null
                    )
                },
                selected = settings.italic.value
            )
        ),
        onClick = { item ->
            settings.italic.update(item)
        }
    )
}
