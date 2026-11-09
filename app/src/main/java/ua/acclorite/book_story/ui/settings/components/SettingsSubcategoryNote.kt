/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 */

package ua.acclorite.book_story.ui.settings.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ua.acclorite.book_story.R
import ua.acclorite.book_story.ui.common.components.common.StyledText

@Composable
fun SettingsSubcategoryNote(
    text: String,
    horizontalPadding: Dp = 18.dp,
    verticalPadding: Dp = 8.dp
) {
    // 提示块：浅灰色（深色主题下深灰）容器 + 中性文字色。
    // 只把文字改成次要色还不够醒目（设置项说明也是次要色），加一层容器才能让人
    // 一眼看出「这是一条提示，不是可点击的设置项」。
    //
    // 以前用的是 secondaryContainer：在 Material You 下它会跟着主题取色（粉/绿/红），
    // 一条纯说明性质的提示看起来像警告或可点击的设置项。用户要的是「浅灰」，所以这里
    // 刻意不用主题容器色（surfaceContainerHigh 在红/粉主题下仍是浅红/浅粉），
    // 而是按明暗给一个中性的灰：浅色主题浅灰、深色主题深灰，不带任何强调色相。
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val containerColor = if (dark) Color(0xFF2B2B2B) else Color(0xFFF2F2F2)
    val contentColor = if (dark) Color(0xFFD8D8D8) else Color(0xFF4A4A4A)

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = horizontalPadding, vertical = verticalPadding),
        shape = MaterialTheme.shapes.medium,
        color = containerColor,
        contentColor = contentColor
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            // fork: 图标与文字同一行、图标在行首，不再单独占一行。
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                modifier = Modifier.size(20.dp),
                imageVector = Icons.Outlined.Info,
                contentDescription = stringResource(id = R.string.note_content_desc),
                tint = contentColor
            )
            StyledText(
                modifier = Modifier.weight(1f),
                text = text,
                style = MaterialTheme.typography.bodySmall.copy(
                    color = contentColor
                )
            )
        }
    }
}
