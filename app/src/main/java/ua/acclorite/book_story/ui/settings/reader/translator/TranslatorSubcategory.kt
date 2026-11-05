/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 */

@file:Suppress("FunctionName")

package ua.acclorite.book_story.ui.settings.reader.translator

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ua.acclorite.book_story.R
import ua.acclorite.book_story.ui.settings.components.SettingsSubcategory
import ua.acclorite.book_story.ui.settings.components.SettingsSubcategoryNote
import ua.acclorite.book_story.ui.settings.components.SettingsSubcategoryTitle
import ua.acclorite.book_story.ui.settings.reader.translator.components.AiSettingsOption
import ua.acclorite.book_story.ui.settings.reader.translator.components.DictionaryManagerOption
import ua.acclorite.book_story.ui.settings.reader.translator.components.DoubleClickTranslationOption
import ua.acclorite.book_story.ui.settings.reader.translator.components.LookupGestureOption

/**
 * @param bookScoped 书内翻译器 Tab：行为开关按书、默认关闭，并给出全局词典/Key 入口提示。
 * 全局设置页用默认 false，只保留词典与 AI 配置入口。
 */
fun LazyListScope.TranslatorSubcategory(
    titleColor: @Composable () -> Color = { MaterialTheme.colorScheme.primary },
    title: @Composable () -> String = { stringResource(id = R.string.translator_reader_settings) },
    showTitle: Boolean = true,
    showDivider: Boolean = true,
    bookScoped: Boolean = false
) {
    SettingsSubcategory(
        titleColor = titleColor,
        title = title,
        showTitle = showTitle,
        showDivider = showDivider
    ) {
        if (bookScoped) {
            // 书内：先「本书行为」，再「全局共享配置」。两段各有标题，避免
            // 开关、说明、跳转入口混在一起。
            item {
                SettingsSubcategoryTitle(
                    title = stringResource(id = R.string.translator_book_section),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    padding = 18.dp
                )
            }

            item {
                SettingsSubcategoryNote(
                    text = stringResource(id = R.string.translator_book_scope_note)
                )
            }

            item {
                LookupGestureOption()
            }

            item {
                DoubleClickTranslationOption()
            }

            item {
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp)
                )
            }

            item {
                SettingsSubcategoryTitle(
                    title = stringResource(id = R.string.translator_global_section),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    padding = 18.dp
                )
            }

            item {
                SettingsSubcategoryNote(
                    text = stringResource(id = R.string.translator_global_config_note)
                )
            }

            item {
                DictionaryManagerOption()
            }

            item {
                AiSettingsOption()
            }
        } else {
            item {
                DictionaryManagerOption()
            }

            item {
                AiSettingsOption()
            }
        }
    }
}