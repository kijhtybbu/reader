/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 */

package ua.acclorite.book_story.ui.settings.reader.ai

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import ua.acclorite.book_story.presentation.settings.AiSettingsModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiSettingsContent(
    model: AiSettingsModel,
    scrollBehavior: TopAppBarScrollBehavior,
    listState: LazyListState,
    navigateBack: () -> Unit
) {
    AiSettingsScaffold(
        model = model,
        scrollBehavior = scrollBehavior,
        listState = listState,
        navigateBack = navigateBack
    )
}
