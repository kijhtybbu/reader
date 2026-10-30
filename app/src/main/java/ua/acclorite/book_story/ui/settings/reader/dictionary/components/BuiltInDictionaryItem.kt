/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * fork 新增：内置 ECDICT 条目（第二阶段 S4）。
 *
 * 内置词典不可删除/禁用，所以这里只有「设为优先」一个操作；状态与进度直接读
 * [DictionaryRegistry.builtInState]，安装没完成时也能看到进度和失败原因。
 */

package ua.acclorite.book_story.ui.settings.reader.dictionary.components

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ua.acclorite.book_story.R
import ua.acclorite.book_story.data.dictionary.BuiltInState
import ua.acclorite.book_story.data.dictionary.DictionaryRegistry
import ua.acclorite.book_story.data.dictionary.InstalledDictionary
import ua.acclorite.book_story.ui.common.components.common.StyledText
import ua.acclorite.book_story.ui.common.helpers.noRippleClickable
import ua.acclorite.book_story.ui.theme.Spacing
import java.text.NumberFormat
import kotlin.math.roundToInt

@Composable
fun BuiltInDictionaryItem(
    dictionary: InstalledDictionary,
    active: Boolean,
    onSelect: () -> Unit
) {
    val context = LocalContext.current
    val state by DictionaryRegistry.builtInState.collectAsStateWithLifecycle()
    val priorityDescription = stringResource(id = R.string.dictionary_priority_content_desc)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .noRippleClickable(onClick = onSelect)
            .padding(horizontal = Spacing.page, vertical = Spacing.s),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.s)
    ) {
        RadioButton(
            selected = active,
            onClick = null,
            modifier = Modifier.semantics { contentDescription = priorityDescription }
        )

        Column(modifier = Modifier.weight(1f)) {
            StyledText(
                text = dictionary.name,
                style = MaterialTheme.typography.titleSmall.copy(
                    color = if (active) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface
                ),
                maxLines = 1
            )
            StyledText(
                text = builtInStatusText(state, dictionary),
                style = MaterialTheme.typography.bodySmall.copy(
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            )
            (state as? BuiltInState.Extracting)?.let { extracting ->
                LinearProgressIndicator(
                    progress = { extracting.progress.coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = Spacing.xs)
                )
            }
            (state as? BuiltInState.Error)?.let { error ->
                StyledText(
                    text = error.message,
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = MaterialTheme.colorScheme.error
                    )
                )
            }
        }

        Icon(
            imageVector = Icons.Outlined.Lock,
            contentDescription = stringResource(id = R.string.dictionary_builtin_not_deletable),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = Spacing.s)
        )
    }
}

@Composable
private fun builtInStatusText(
    state: BuiltInState,
    dictionary: InstalledDictionary
): String {
    val context = LocalContext.current
    return when (state) {
        BuiltInState.Idle -> stringResource(id = R.string.dictionary_builtin_state_idle)
        is BuiltInState.Extracting -> stringResource(
            id = R.string.dictionary_builtin_state_extracting,
            (state.progress * 100).roundToInt()
        )

        is BuiltInState.Ready -> stringResource(
            id = R.string.dictionary_builtin_ready,
            NumberFormat.getIntegerInstance().format(dictionary.wordCount),
            Formatter.formatShortFileSize(context, dictionary.sizeBytes),
            // 设计 §9：内置词库要能看出装的是哪一版资产（换 stardict.7z 时 +1）。
            DictionaryRegistry.ECDICT_ASSET_VERSION
        )

        is BuiltInState.Error -> stringResource(id = R.string.dictionary_builtin_state_error)
    }
}
