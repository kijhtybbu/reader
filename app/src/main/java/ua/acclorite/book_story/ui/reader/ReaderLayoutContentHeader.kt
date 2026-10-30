/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 */

package ua.acclorite.book_story.ui.reader

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import ua.acclorite.book_story.domain.model.book.NodeContent
import ua.acclorite.book_story.ui.common.components.common.StyledText
import ua.acclorite.book_story.ui.reader.model.ReaderNode

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ReaderLayoutContentHeader(
    header: ReaderNode.Header,
    header1TextStyle: TextStyle,
    header2TextStyle: TextStyle,
    header3TextStyle: TextStyle,
    header4TextStyle: TextStyle,
    header5TextStyle: TextStyle,
    header6TextStyle: TextStyle,
    sidePadding: Dp,
    textLayouts: ReaderTextLayoutRegistry
) {
    // fork: 标题也注册取词 —— 标题里的词和正文一样值得查，且不注册就等于「分页能查、连续不能查」。
    val textLayout = rememberReaderTextLayoutHandle(
        registry = textLayouts,
        nodeId = header.node.id
    )

    Column(
        Modifier
            .fillMaxWidth()
    ) {
        StyledText(
            text = header.title,
            modifier = Modifier
                .padding(horizontal = sidePadding)
                .fillMaxWidth()
                .then(textLayout.positionModifier),
            onTextLayout = textLayout::onTextLayout,
            style = when (header.level) {
                NodeContent.HeaderLevel.H1 -> header1TextStyle
                NodeContent.HeaderLevel.H2 -> header2TextStyle
                NodeContent.HeaderLevel.H3 -> header3TextStyle
                NodeContent.HeaderLevel.H4 -> header4TextStyle
                NodeContent.HeaderLevel.H5 -> header5TextStyle
                NodeContent.HeaderLevel.H6 -> header6TextStyle
            }
        )
    }
}