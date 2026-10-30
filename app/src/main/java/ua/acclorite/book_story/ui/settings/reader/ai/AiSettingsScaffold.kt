/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 */

package ua.acclorite.book_story.ui.settings.reader.ai

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import ua.acclorite.book_story.presentation.settings.AiSettingsModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiSettingsScaffold(
    model: AiSettingsModel,
    scrollBehavior: TopAppBarScrollBehavior,
    listState: LazyListState,
    navigateBack: () -> Unit
) {
    Scaffold(
        Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection)
            .windowInsetsPadding(WindowInsets.navigationBars),
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            AiSettingsTopBar(
                scrollBehavior = scrollBehavior,
                navigateBack = navigateBack
            )
        }
    ) { paddingValues ->
        AiSettingsLayout(
            model = model,
            listState = listState,
            paddingValues = paddingValues
        )
    }
}
