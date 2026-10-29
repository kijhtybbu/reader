/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * fork 新增：翻页动画选项（仅分页布局下可见，与翻页方向选项一致）。
 */

package ua.acclorite.book_story.ui.settings.reader.reading_mode.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import ua.acclorite.book_story.R
import ua.acclorite.book_story.presentation.reader.model.ReaderLayout
import ua.acclorite.book_story.presentation.reader.model.ReaderPagedAnimation
import ua.acclorite.book_story.ui.common.components.settings.ChipsWithTitle
import ua.acclorite.book_story.ui.common.helpers.LocalSettings
import ua.acclorite.book_story.ui.common.model.ListItem
import ua.acclorite.book_story.ui.theme.ExpandingTransition

@Composable
fun PagedAnimationOption() {
    val settings = LocalSettings.current

    ExpandingTransition(visible = settings.readerLayout.value == ReaderLayout.PAGED) {
        ChipsWithTitle(
            title = stringResource(R.string.paged_animation_option),
            chips = ReaderPagedAnimation.entries.map { animation ->
                ListItem(
                    item = animation,
                    title = stringResource(animation.title),
                    selected = animation == settings.pagedAnimation.value
                )
            },
            onClick = { settings.pagedAnimation.update(it) }
        )
    }
}
