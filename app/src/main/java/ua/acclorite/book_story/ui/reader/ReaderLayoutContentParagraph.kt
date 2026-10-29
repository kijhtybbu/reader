/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 */

package ua.acclorite.book_story.ui.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import ua.acclorite.book_story.ui.common.helpers.noRippleClickable
import ua.acclorite.book_story.presentation.reader.ReaderEvent
import ua.acclorite.book_story.ui.common.components.common.StyledText
import ua.acclorite.book_story.ui.common.helpers.noRippleClickable
import ua.acclorite.book_story.ui.reader.model.ReaderNode

@Composable
fun ReaderLayoutContentParagraph(
    paragraph: ReaderNode.Paragraph,
    paragraphTextStyle: TextStyle,
    horizontalAlignment: Alignment.Horizontal,
    sidePadding: Dp,
    /**
     * fork: 只有连续布局传（分页布局的单击/双击统一由 Pager 上的单一检测器处理，
     * 这里再挂一个会把单击吞掉，让分区翻页在文字区域失效）。
     */
    onClick: (() -> Unit)? = null,
    onDoubleClick: (() -> Unit)? = null
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = sidePadding),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = horizontalAlignment
    ) {
        StyledText(
            text = paragraph.text,
            // fork: 分页布局不挂点击（统一由 Pager 检测器处理）；连续布局仍需挂，
            // 因为它没有分区翻页，且原来就靠这个 combinedClickable 做单击切菜单 + 双击翻译。
            modifier = if (onClick != null || onDoubleClick != null) {
                Modifier.noRippleClickable(
                    onClick = onClick ?: {},
                    onDoubleClick = onDoubleClick ?: {}
                )
            } else {
                Modifier
            },
            style = paragraphTextStyle
        )
    }
}