/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * fork 新增：导入进度与错误文案（第二阶段 S4）。
 *
 * 数据层只给 [DictionaryImportError] 枚举，这里映射成字符串资源 —— 错误码与文案分离，
 * 换语言不用改导入逻辑，测试也能只断言枚举。
 */

package ua.acclorite.book_story.ui.settings.reader.dictionary.components

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import ua.acclorite.book_story.R
import ua.acclorite.book_story.data.dictionary.DictionaryImportError
import ua.acclorite.book_story.data.dictionary.ImportState
import ua.acclorite.book_story.ui.common.components.common.StyledText
import ua.acclorite.book_story.ui.theme.Spacing
import kotlin.math.roundToInt

@Composable
fun DictionaryImportStatus(state: ImportState) {
    when (state) {
        ImportState.Idle -> Unit

        is ImportState.Copying -> {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.page, vertical = Spacing.s)
            ) {
                StyledText(
                    text = stringResource(
                        id = R.string.dictionary_import_copying,
                        (state.progress.coerceIn(0f, 1f) * 100).roundToInt()
                    ),
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                )
                LinearProgressIndicator(
                    progress = { state.progress.coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = Spacing.xs)
                )
            }
        }

        ImportState.Validating -> {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.page, vertical = Spacing.s)
            ) {
                StyledText(
                    text = stringResource(id = R.string.dictionary_import_validating),
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                )
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = Spacing.xs)
                )
            }
        }

        is ImportState.Done -> {
            StyledText(
                modifier = Modifier.padding(horizontal = Spacing.page, vertical = Spacing.s),
                text = stringResource(id = R.string.dictionary_import_done, state.dictionary.name),
                style = MaterialTheme.typography.bodySmall.copy(
                    color = MaterialTheme.colorScheme.primary
                )
            )
        }

        is ImportState.Error -> {
            StyledText(
                modifier = Modifier.padding(horizontal = Spacing.page, vertical = Spacing.s),
                text = stringResource(id = state.error.messageRes()),
                style = MaterialTheme.typography.bodySmall.copy(
                    color = MaterialTheme.colorScheme.error
                )
            )
        }
    }
}

@StringRes
internal fun DictionaryImportError.messageRes(): Int = when (this) {
    DictionaryImportError.UNREADABLE -> R.string.dictionary_import_error_unreadable
    DictionaryImportError.EMPTY_FILE -> R.string.dictionary_import_error_empty
    DictionaryImportError.TOO_LARGE -> R.string.dictionary_import_error_too_large
    DictionaryImportError.NO_SPACE -> R.string.dictionary_import_error_no_space
    DictionaryImportError.NOT_MDX -> R.string.dictionary_import_error_not_mdx
    DictionaryImportError.ENCRYPTED -> R.string.dictionary_import_error_encrypted
    DictionaryImportError.RESOURCE_PACK -> R.string.dictionary_import_error_resource_pack
    DictionaryImportError.CORRUPT -> R.string.dictionary_import_error_corrupt
    DictionaryImportError.INCOMPLETE -> R.string.dictionary_import_error_incomplete
    DictionaryImportError.MULTIPLE_DICTIONARIES -> R.string.dictionary_import_error_multiple
    DictionaryImportError.RESOURCE_PARTS_MISSING ->
        R.string.dictionary_import_error_resource_parts_missing

    DictionaryImportError.RESOURCE_CORRUPT -> R.string.dictionary_import_error_resource_corrupt
    DictionaryImportError.FAILED -> R.string.dictionary_import_error_failed
}
