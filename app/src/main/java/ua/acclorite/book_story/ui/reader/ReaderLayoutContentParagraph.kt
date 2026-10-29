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
import ua.acclorite.book_story.presentation.reader.ReaderEvent
import ua.acclorite.book_story.ui.common.components.common.StyledText
import ua.acclorite.book_story.ui.common.helpers.noRippleClickable
import ua.acclorite.book_story.ui.reader.model.ReaderNode

@Composable
fun ReaderLayoutContentParagraph(
    paragraph: ReaderNode.Paragraph,
    paragraphTextStyle: TextStyle,
    horizontalAlignment: Alignment.Horizontal,
    sidePadding: Dp
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
            // fork: 这里原本挂 combinedClickable（单击切菜单 + 双击翻译），
            // 那等于在正文上放了第二套点击检测器，会把单击吞掉，让「点右三分之一翻页」
            // 在文字区域失效。现在单击/双击统一由 ReaderLayoutPaged 的单个检测器处理。
            style = paragraphTextStyle
        )
    }
}