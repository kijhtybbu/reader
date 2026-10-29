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
import ua.acclorite.book_story.ui.common.helpers.LocalSettings

@Composable
fun LetterSpacingOption() {
    val settings = LocalSettings.current

    SliderWithTitle(
        // fork: 该项是 1/100 em，不是 pt
        value = settings.letterSpacing.value to "×0.01em",
        fromValue = -8,
        toValue = 16,
        title = stringResource(id = R.string.letter_spacing_option),
        onValueChange = {
            settings.letterSpacing.update(it)
        }
    )
}