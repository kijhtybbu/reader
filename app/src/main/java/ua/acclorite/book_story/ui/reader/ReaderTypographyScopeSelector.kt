/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * fork 新增：排版作用域选择器（全局 / 本书）。
 *
 * 这是「按书覆盖」的唯一开关：
 *  - 切到「本书」第一次会快照当前全局值，之后整组独立于全局；
 *  - 切回「全局」只停用，不删除记录，再切回来能恢复记忆值；
 *  - 只在有本书记录时显示「恢复全局默认」，删除记录并回到全局（会二次确认）。
 *
 * 排版 Tab 里固定在样例预览上方（滚动不消失），边距所在的 Tab 1 放紧凑版 ——
 * 否则在 Tab 1 拖边距时无法判断改的是全局还是本书。
 */

package ua.acclorite.book_story.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import ua.acclorite.book_story.R
import ua.acclorite.book_story.data.typography.ReaderTypographyField
import ua.acclorite.book_story.data.typography.ReaderTypographyMode
import ua.acclorite.book_story.ui.common.components.common.StyledText
import ua.acclorite.book_story.ui.common.components.dialog.Dialog
import ua.acclorite.book_story.ui.reader.typography.LocalReaderTypography

@Composable
fun ReaderTypographyScopeSelector(compact: Boolean = false) {
    val typography = LocalReaderTypography.current
    var showResetDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = if (compact) 4.dp else 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = typography.mode == ReaderTypographyMode.GLOBAL,
                onClick = {
                    typography.actions.setMode(ReaderTypographyMode.GLOBAL)
                },
                label = {
                    StyledText(text = stringResource(id = R.string.typography_scope_global))
                }
            )
            FilterChip(
                selected = typography.mode == ReaderTypographyMode.BOOK,
                onClick = {
                    typography.actions.setMode(ReaderTypographyMode.BOOK)
                },
                label = {
                    StyledText(text = stringResource(id = R.string.typography_scope_book))
                }
            )
        }

        // 状态文案与重置按钮单独占一行：窄屏 / 大字号下两个 chip 与文字按钮
        // 挤在同一行会把「恢复全局默认」推到屏幕外。文案用 weight 收缩并最多两行。
        Spacer(modifier = Modifier.height(2.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            StyledText(
                text = if (typography.mode == ReaderTypographyMode.GLOBAL) {
                    stringResource(id = R.string.typography_scope_global_note)
                } else if (typography.modifiedFields.isEmpty()) {
                    stringResource(id = R.string.typography_scope_book_note)
                } else {
                    stringResource(
                        id = R.string.typography_scope_book_modified_note,
                        typography.modifiedFields.size
                    )
                },
                modifier = Modifier.weight(1f),
                maxLines = 2,
                style = MaterialTheme.typography.bodySmall.copy(
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            )

            if (typography.hasBookRecord) {
                // 紧凑版用图标按钮，避免 Tab 1 的窄高度里再放一行文字。
                if (compact) {
                    IconButton(onClick = { showResetDialog = true }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.Undo,
                            contentDescription = stringResource(
                                id = R.string.typography_reset_to_global
                            ),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                } else {
                    TextButton(onClick = { showResetDialog = true }) {
                        StyledText(
                            text = stringResource(id = R.string.typography_reset_to_global),
                            maxLines = 1,
                            style = MaterialTheme.typography.labelLarge.copy(
                                color = MaterialTheme.colorScheme.primary
                            )
                        )
                    }
                }
            }
        }
    }

    if (showResetDialog) {
        Dialog(
            title = stringResource(id = R.string.typography_reset_to_global_dialog_title),
            icon = Icons.AutoMirrored.Outlined.Undo,
            description = stringResource(id = R.string.typography_reset_to_global_dialog_text),
            onDismiss = { showResetDialog = false },
            withContent = false,
            actionEnabled = true,
            onAction = {
                showResetDialog = false
                typography.actions.resetToGlobal()
            }
        )
    }
}

/**
 * 包一层差异标记。12 个选项控件本身保持不动，全局设置页取到的 modified 恒为 false。
 */
@Composable
fun ModifiedTypographyOption(
    field: ReaderTypographyField,
    content: @Composable () -> Unit
) {
    val typography = LocalReaderTypography.current
    TypographyOptionMarker(modified = typography.isModified(field), content = content)
}

/**
 * 与全局不同的字段标记。
 *
 * 不改 12 个选项控件本身：只在外面套一层 Box，本书模式下命中 modifiedFields 时
 * 在左侧 18dp 留白里画一个小圆点。全局设置页的 modifiedFields 恒为空，所以同一批
 * 控件在设置页不会出现任何标记。
 */
@Composable
fun TypographyOptionMarker(
    modified: Boolean,
    content: @Composable () -> Unit
) {
    Box(modifier = Modifier.fillMaxWidth()) {
        content()

        if (modified) {
            // 放在 18dp 左侧留白里：滑块/分段控件的标题与轨道都不会被遮住。
            // 圆点本身是纯视觉信息，单独给 TalkBack 一个说明。
            val description = stringResource(id = R.string.typography_modified_from_global_desc)
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 6.dp, top = 12.dp)
                    .size(6.dp)
                    .background(color = MaterialTheme.colorScheme.primary, shape = CircleShape)
                    .semantics { contentDescription = description }
            )
        }
    }
}
