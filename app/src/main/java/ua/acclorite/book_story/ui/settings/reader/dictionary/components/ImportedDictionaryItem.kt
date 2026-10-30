/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * fork 新增：导入词典条目（第二阶段 S4）。
 *
 * 一行里三种操作互不遮挡：整行点击 = 设为优先词典（只影响同 rank 命中的先后），
 * 开关 = 是否参与取词，垃圾桶 = 删除文件。
 */

package ua.acclorite.book_story.ui.settings.reader.dictionary.components

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import ua.acclorite.book_story.R
import ua.acclorite.book_story.data.dictionary.DictionaryType
import ua.acclorite.book_story.data.dictionary.InstalledDictionary
import ua.acclorite.book_story.ui.common.components.common.IconButton
import ua.acclorite.book_story.ui.common.components.common.StyledText
import ua.acclorite.book_story.ui.common.helpers.noRippleClickable
import ua.acclorite.book_story.ui.theme.Spacing
import java.text.NumberFormat

@Composable
fun ImportedDictionaryItem(
    dictionary: InstalledDictionary,
    active: Boolean,
    onSelect: () -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    onDelete: () -> Unit
) {
    val context = LocalContext.current
    val enabledDescription = stringResource(id = R.string.dictionary_enabled_content_desc)
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
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                StyledText(
                    text = stringResource(
                        id = when (dictionary.type) {
                            DictionaryType.MDX -> R.string.dictionary_type_mdx
                            DictionaryType.STARDICT -> R.string.dictionary_type_stardict
                        }
                    ),
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                )
                StyledText(
                    text = stringResource(
                        id = R.string.dictionary_word_count,
                        NumberFormat.getIntegerInstance().format(dictionary.wordCount)
                    ),
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                )
                StyledText(
                    text = Formatter.formatShortFileSize(context, dictionary.sizeBytes),
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                )
            }
        }

        Switch(
            checked = dictionary.enabled,
            onCheckedChange = onEnabledChange,
            modifier = Modifier.semantics { contentDescription = enabledDescription },
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.secondary,
                checkedTrackColor = MaterialTheme.colorScheme.secondaryContainer
            )
        )

        IconButton(
            modifier = Modifier.size(24.dp),
            icon = Icons.Outlined.Delete,
            contentDescription = R.string.dictionary_delete,
            disableOnClick = true,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            onClick = onDelete
        )
    }
}
