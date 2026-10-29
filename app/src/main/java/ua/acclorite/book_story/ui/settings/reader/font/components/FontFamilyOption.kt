/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 */

package ua.acclorite.book_story.ui.settings.reader.font.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import ua.acclorite.book_story.R
import ua.acclorite.book_story.ui.common.components.settings.ChipsWithTitle
import ua.acclorite.book_story.ui.common.helpers.LocalSettings
import ua.acclorite.book_story.ui.common.model.ListItem
import ua.acclorite.book_story.ui.reader.data.FontRegistry
import ua.acclorite.book_story.ui.reader.rememberFontFamily

@Composable
fun FontFamilyOption() {
    val settings = LocalSettings.current
    val selectedId = settings.fontFamily.value.id

    ChipsWithTitle(
        title = stringResource(id = R.string.font_family_option),
        chips = FontRegistry.fonts.map { item ->
            ListItem(
                item = item,
                title = item.fontName.asString(),
                textStyle = {
                    MaterialTheme.typography.labelLarge.copy(
                        fontFamily = rememberFontFamily(
                            font = item,
                            fontWeight = 400,
                            italic = false
                        )
                    )
                },
                // fork: FontWithName 现在带 source（用户字体还带索引条目），
                // data class 整体相等不再可靠，按 id 比较才是「同一个字体」。
                selected = item.id == selectedId
            )
        },
        onClick = { item ->
            settings.fontFamily.update(item)
        }
    )
}
