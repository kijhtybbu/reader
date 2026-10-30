/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * fork 新增：翻译缓存的条数/占用与「清除翻译缓存」（第二阶段 S9，设计 §7.3/§9）。
 *
 * 读取占用与清除都是显式动作：进页面读一次（`AiSettingsModel.init`），清完再读一次。
 * 清除不可撤销，所以先弹确认对话框；占用按 MB 显示（预算是 200MB，字节数不直观）。
 */

package ua.acclorite.book_story.ui.settings.reader.ai.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ua.acclorite.book_story.R
import ua.acclorite.book_story.presentation.settings.AiCacheState
import ua.acclorite.book_story.presentation.settings.AiSettingsModel
import ua.acclorite.book_story.presentation.settings.formatCachePayloadSize
import ua.acclorite.book_story.ui.common.components.common.StyledText
import ua.acclorite.book_story.ui.common.components.dialog.Dialog
import ua.acclorite.book_story.ui.settings.components.SettingsSubcategoryTitle
import ua.acclorite.book_story.ui.theme.Spacing

@Composable
fun AiCacheOption(model: AiSettingsModel) {
    val state by model.cacheState.collectAsStateWithLifecycle()
    var confirming by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.page, vertical = Spacing.s)
    ) {
        SettingsSubcategoryTitle(
            title = stringResource(id = R.string.ai_cache_title),
            padding = 0.dp
        )
        Spacer(modifier = Modifier.height(Spacing.s))

        when (val current = state) {
            AiCacheState.Loading -> CacheStatusText(
                text = stringResource(id = R.string.ai_cache_loading)
            )

            is AiCacheState.Ready -> {
                CacheStatusText(
                    text = stringResource(
                        id = R.string.ai_cache_stats,
                        current.count,
                        formatCachePayloadSize(current.payloadBytes)
                    ),
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (current.cleared) {
                    CacheStatusText(
                        text = stringResource(id = R.string.ai_cache_cleared),
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            AiCacheState.LoadError -> CacheStatusText(
                text = stringResource(id = R.string.ai_cache_load_failed),
                color = MaterialTheme.colorScheme.error
            )

            AiCacheState.ClearError -> CacheStatusText(
                text = stringResource(id = R.string.ai_cache_clear_failed),
                color = MaterialTheme.colorScheme.error
            )
        }

        Spacer(modifier = Modifier.height(Spacing.s))

        Button(
            onClick = { confirming = true },
            enabled = state !is AiCacheState.Loading
        ) {
            StyledText(text = stringResource(id = R.string.ai_cache_clear))
        }
    }

    if (confirming) {
        Dialog(
            title = stringResource(id = R.string.ai_cache_clear_confirm_title),
            icon = Icons.Outlined.Delete,
            description = stringResource(id = R.string.ai_cache_clear_confirm_description),
            actionEnabled = true,
            onDismiss = { confirming = false },
            onAction = {
                confirming = false
                model.clearCache()
            },
            withContent = false
        )
    }
}

@Composable
private fun CacheStatusText(
    text: String,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant
) {
    StyledText(
        modifier = Modifier.fillMaxWidth(),
        text = text,
        style = MaterialTheme.typography.bodyMedium.copy(color = color)
    )
}
