/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * fork 新增：AI 设置页内容（第二阶段 S6，设计 §9）。
 */

package ua.acclorite.book_story.ui.settings.reader.ai

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import ua.acclorite.book_story.R
import ua.acclorite.book_story.presentation.settings.AiSettingsModel
import ua.acclorite.book_story.ui.common.components.common.LazyColumnWithScrollbar
import ua.acclorite.book_story.ui.settings.components.SettingsSubcategoryNote
import ua.acclorite.book_story.ui.settings.components.SettingsSubcategoryTitle
import ua.acclorite.book_story.ui.settings.reader.ai.components.AiApiKeyOption
import ua.acclorite.book_story.ui.settings.reader.ai.components.AiBaseUrlOption
import ua.acclorite.book_story.ui.settings.reader.ai.components.AiCacheOption
import ua.acclorite.book_story.ui.settings.reader.ai.components.AiGrammarOption
import ua.acclorite.book_story.ui.settings.reader.ai.components.AiModelOption
import ua.acclorite.book_story.ui.settings.reader.ai.components.AiProviderPresetOption
import ua.acclorite.book_story.ui.settings.reader.ai.components.AiReasoningEffortOption
import ua.acclorite.book_story.ui.settings.reader.ai.components.AiTargetLanguageOption
import ua.acclorite.book_story.ui.settings.reader.ai.components.AiTestConnectionOption
import ua.acclorite.book_story.ui.theme.Spacing

@Composable
fun AiSettingsLayout(
    model: AiSettingsModel,
    listState: LazyListState,
    paddingValues: PaddingValues
) {
    LazyColumnWithScrollbar(
        Modifier
            .fillMaxSize()
            .padding(top = paddingValues.calculateTopPadding()),
        state = listState
    ) {
        item {
            SettingsSubcategoryTitle(
                title = stringResource(id = R.string.ai_connection_title),
                modifier = Modifier.padding(top = Spacing.page, bottom = Spacing.s)
            )
        }

        item { AiProviderPresetOption() }
        item { AiBaseUrlOption() }
        item { AiModelOption() }
        item { AiReasoningEffortOption() }
        item { AiApiKeyOption(model) }
        item { AiTargetLanguageOption() }
        item { AiGrammarOption() }
        item { AiTestConnectionOption(model) }

        item {
            SettingsSubcategoryNote(text = stringResource(id = R.string.ai_test_uses_saved))
        }

        item {
            SettingsSubcategoryNote(text = stringResource(id = R.string.ai_privacy_note))
        }

        item { AiCacheOption(model) }
    }
}
