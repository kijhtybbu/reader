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
import ua.acclorite.book_story.ui.common.components.common.StyledText
import ua.acclorite.book_story.ui.reader.model.ReaderNode

@Composable
internal fun ReaderLayoutContentParagraph(
    paragraph: ReaderNode.Paragraph,
    paragraphTextStyle: TextStyle,
    horizontalAlignment: Alignment.Horizontal,
    sidePadding: Dp,
    /**
     * fork: 取词注册表。段落只负责把 `TextLayoutResult` 与窗口矩形登记进去 ——
     * 点击检测器不在这里（原来那个 combinedClickable 是「第二套检测器」，会把单击吞掉，
     * 让分区翻页在文字区域失效；S5 起连续布局的单击/双击/取词统一由内容层那一个处理器做）。
     */
    textLayouts: ReaderTextLayoutRegistry
) {
    val textLayout = rememberReaderTextLayoutHandle(
        registry = textLayouts,
        nodeId = paragraph.node.id
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = sidePadding),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = horizontalAlignment
    ) {
        StyledText(
            text = paragraph.text,
            modifier = textLayout.positionModifier,
            style = paragraphTextStyle,
            onTextLayout = textLayout::onTextLayout
        )
    }
}
