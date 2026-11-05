/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 */

package ua.acclorite.book_story.ui.settings.reader.font.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import ua.acclorite.book_story.R
import ua.acclorite.book_story.ui.common.components.settings.SliderWithTitle
import ua.acclorite.book_story.ui.reader.typography.LocalReaderTypography

@Composable
fun BoldFontThicknessOption() {
    val typography = LocalReaderTypography.current

    SliderWithTitle(
        value = (typography.values.boldFontThickness / 100) to "",
        valuePlaceholder = "+${typography.values.boldFontThickness}",
        showPlaceholder = true,
        fromValue = 1,
        toValue = 8,
        title = stringResource(id = R.string.bold_font_thickness_option),
        onValueChange = { typography.actions.setBoldFontThickness(it * 100) }
    )
}
