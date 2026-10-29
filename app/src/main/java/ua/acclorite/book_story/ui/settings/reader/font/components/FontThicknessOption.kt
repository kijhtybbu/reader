/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 */

package ua.acclorite.book_story.ui.settings.reader.font.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import ua.acclorite.book_story.R
import ua.acclorite.book_story.ui.common.components.common.StyledText
import ua.acclorite.book_story.ui.common.components.settings.SliderWithTitle
import ua.acclorite.book_story.ui.common.helpers.LocalSettings
import ua.acclorite.book_story.ui.theme.Spacing

@Composable
fun FontThicknessOption() {
    val settings = LocalSettings.current
    val userEntry = settings.fontFamily.value.userEntry

    Column(modifier = Modifier.fillMaxWidth()) {
        SliderWithTitle(
            value = (settings.fontThickness.value / 100) to "",
            valuePlaceholder = settings.fontThickness.value.toString(),
            showPlaceholder = true,
            fromValue = 1,
            toValue = 9,
            title = stringResource(id = R.string.font_thickness_option),
            onValueChange = { settings.fontThickness.update(it * 100) }
        )

        // fork: 导入字体的字重行为必须写明，不能「滑块在动但字体没变」。
        // 可变字体走真实 wght 轴；静态字体只有一份 face，≥700 由系统合成粗体，
        // 100–600 显示字体自带字重。斜体另由 TextStyle 的几何倾斜合成。
        if (userEntry != null) {
            StyledText(
                text = stringResource(
                    id = if (userEntry.variable) R.string.user_font_weight_note_variable
                    else R.string.user_font_weight_note_static
                ),
                modifier = Modifier.padding(horizontal = Spacing.page, vertical = Spacing.xs),
                style = MaterialTheme.typography.bodySmall.copy(
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            )
        }
    }
}
