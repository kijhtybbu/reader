/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * fork 新增：进入词典管理页的设置行（第二阶段 S4，临时位置）。
 *
 * 设计 §9 的最终形态是「翻译与词典设置」一个页面；S9 做那个页面时把这一行并进去，
 * 现在先让 S4 的词典管理在设置里可达。
 */

package ua.acclorite.book_story.ui.settings.reader.translator.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import ua.acclorite.book_story.R
import ua.acclorite.book_story.presentation.settings.DictionaryManagerScreen
import ua.acclorite.book_story.ui.common.components.common.StyledText
import ua.acclorite.book_story.ui.common.helpers.noRippleClickable
import ua.acclorite.book_story.ui.navigator.LocalNavigator
import ua.acclorite.book_story.ui.theme.Spacing

@Composable
fun DictionaryManagerOption() {
    val navigator = LocalNavigator.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .noRippleClickable(onClick = { navigator.push(DictionaryManagerScreen) })
            .padding(horizontal = Spacing.page, vertical = Spacing.s),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.s)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            StyledText(
                text = stringResource(id = R.string.dictionary_manager_settings),
                style = MaterialTheme.typography.titleMedium.copy(
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            )
            StyledText(
                text = stringResource(id = R.string.dictionary_manager_settings_desc),
                style = MaterialTheme.typography.bodySmall.copy(
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
