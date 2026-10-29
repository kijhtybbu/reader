/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 */

package ua.acclorite.book_story.ui.settings.reader.text.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import ua.acclorite.book_story.R
import ua.acclorite.book_story.presentation.reader.model.ReaderTextAlignment
import ua.acclorite.book_story.ui.common.components.settings.SliderWithTitle
import ua.acclorite.book_story.ui.common.helpers.LocalSettings
import ua.acclorite.book_story.ui.theme.ExpandingTransition

@Composable
fun ParagraphIndentationOption() {
    val settings = LocalSettings.current

    ExpandingTransition(
        visible = settings.textAlignment.value != ReaderTextAlignment.CENTER &&
                settings.textAlignment.value != ReaderTextAlignment.END
    ) {
        SliderWithTitle(
            // fork: 该项是 ×6sp 倍率，不是 pt
            value = settings.paragraphIndentation.value to "×6sp",
            fromValue = 0,
            toValue = 12,
            title = stringResource(id = R.string.paragraph_indentation_option),
            onValueChange = {
                settings.paragraphIndentation.update(it)
            }
        )
    }
}