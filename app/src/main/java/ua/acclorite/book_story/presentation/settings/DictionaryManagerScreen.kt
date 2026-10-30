/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * fork 新增：词典管理设置页（第二阶段 S4）。
 *
 * S9 会把词典、AI、缓存并进一个完整的「翻译与词典设置」页；现在先给
 * `TranslatorSubcategory` 一个直接入口，避免把 S4 的验收卡在 S9 的页面上。
 */

package ua.acclorite.book_story.presentation.settings

import android.os.Parcelable
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import kotlinx.parcelize.Parcelize
import ua.acclorite.book_story.presentation.navigator.Screen
import ua.acclorite.book_story.ui.common.components.top_bar.collapsibleTopAppBarScrollBehavior
import ua.acclorite.book_story.ui.navigator.LocalNavigator
import ua.acclorite.book_story.ui.settings.reader.dictionary.DictionaryManagerContent

@Parcelize
object DictionaryManagerScreen : Screen, Parcelable {

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.current
        val (scrollBehavior, listState) = TopAppBarDefaults.collapsibleTopAppBarScrollBehavior()

        DictionaryManagerContent(
            scrollBehavior = scrollBehavior,
            listState = listState,
            navigateBack = {
                navigator.pop()
            }
        )
    }
}
