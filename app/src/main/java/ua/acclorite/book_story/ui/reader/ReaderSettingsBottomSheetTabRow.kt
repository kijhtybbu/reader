/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 */

package ua.acclorite.book_story.ui.reader

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import ua.acclorite.book_story.R
import ua.acclorite.book_story.ui.common.components.modal_bottom_sheet.ModalBottomSheetTabRow

@Composable
fun ReaderSettingsBottomSheetTabRow(
    currentPage: Int,
    scrollToPage: (Int) -> Unit
) {
    // fork: 顺序改为「排版优先」。原来排版类设置埋在第二个 Tab 的子页里，
    // 改一次字号要四层操作；现在第一屏就是高频排版项。
    // 翻译与词典独立成 Tab，放在最后，避免和排版项挤在同一屏。
    val tabItems = listOf(
        stringResource(id = R.string.typography_tab),
        stringResource(id = R.string.more_tab),
        stringResource(id = R.string.color_tab),
        stringResource(id = R.string.translator_reader_settings)
    )

    ModalBottomSheetTabRow(
        selectedTabIndex = currentPage,
        tabs = tabItems
    ) { index ->
        scrollToPage(index)
    }
}