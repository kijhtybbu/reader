/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 */

package ua.acclorite.book_story.ui.reader

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import ua.acclorite.book_story.presentation.reader.ReaderEvent

@Composable
fun ReaderBackHandler(
    /** fork: 取词卡片开着时，返回键先关卡片（焦点在卡片上时系统会先自己关掉，这里是兜底）。 */
    lookupOpen: Boolean = false,
    dismissLookup: () -> Unit = {},
    leave: (ReaderEvent.OnLeave) -> Unit,
    navigateBack: (ReaderEvent.OnNavigateBack) -> Unit
) {
    BackHandler {
        if (lookupOpen) {
            dismissLookup()
        } else {
            leave(
                ReaderEvent.OnLeave(
                    navigate = {
                        navigateBack(ReaderEvent.OnNavigateBack)
                    }
                )
            )
        }
    }
}