/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 */

package ua.acclorite.book_story.ui.settings.reader.translator.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import ua.acclorite.book_story.R
import ua.acclorite.book_story.ui.common.components.settings.SwitchWithTitle
import ua.acclorite.book_story.ui.reader.translator.LocalBookTranslator

@Composable
fun DoubleClickTranslationOption() {
    val translator = LocalBookTranslator.current

    SwitchWithTitle(
        selected = translator.values.doubleClickTranslation,
        title = stringResource(id = R.string.double_click_translation_option),
        description = stringResource(id = R.string.double_click_translation_option_desc),
        onClick = {
            translator.actions.setDoubleClickTranslation(
                !translator.values.doubleClickTranslation
            )
        }
    )
}