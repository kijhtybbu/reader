/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * fork 新增：取词手势选项（第二阶段 S5，设计 §5.1）。
 *
 * 文案要点：SINGLE_TAP 确实接管了「点到文字」的单击，不说清用户会以为翻页坏了。
 */

package ua.acclorite.book_story.ui.settings.reader.translator.components

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import ua.acclorite.book_story.R
import ua.acclorite.book_story.presentation.reader.model.ReaderLookupGesture
import ua.acclorite.book_story.ui.common.components.settings.ChipsWithTitle
import ua.acclorite.book_story.ui.common.model.ListItem
import ua.acclorite.book_story.ui.reader.translator.LocalBookTranslator
import ua.acclorite.book_story.ui.settings.components.SettingsSubcategoryNote

@Composable
fun LookupGestureOption() {
    val translator = LocalBookTranslator.current

    Column {
        ChipsWithTitle(
            title = stringResource(id = R.string.lookup_gesture_option),
            chips = ReaderLookupGesture.entries.map { gesture ->
                ListItem(
                    item = gesture,
                    title = stringResource(gesture.title),
                    selected = gesture == translator.values.lookupGesture
                )
            },
            onClick = { translator.actions.setLookupGesture(it) }
        )

        SettingsSubcategoryNote(text = stringResource(id = R.string.lookup_gesture_note))
    }
}
