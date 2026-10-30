/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 */

package ua.acclorite.book_story.ui.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import ua.acclorite.book_story.ui.reader.model.ReaderNode
import ua.acclorite.book_story.ui.theme.model.HorizontalAlignment

@Composable
internal fun ReaderLayoutContent(
    readerNode: ReaderNode,
    paragraphTextStyle: TextStyle,
    header1TextStyle: TextStyle,
    header2TextStyle: TextStyle,
    header3TextStyle: TextStyle,
    header4TextStyle: TextStyle,
    header5TextStyle: TextStyle,
    header6TextStyle: TextStyle,
    imagesCaptions: Boolean,
    imagesCornersRoundness: Dp,
    imagesAlignment: HorizontalAlignment,
    imagesWidth: Float,
    imagesColorEffects: ColorFilter?,
    imagesOriginalSize: Boolean,
    fontColor: Color,
    horizontalAlignment: Alignment.Horizontal,
    sidePadding: Dp,
    paragraphHeight: Dp,
    /**
     * fork: 取词的文本块注册表。文本块自己注册 `TextLayoutResult` 与窗口矩形，
     * 点击统一由连续布局内容层的那**一个**手势处理器查表 —— 段落不再各挂一个点击检测器。
     */
    textLayouts: ReaderTextLayoutRegistry
) {
    when (readerNode) {
        is ReaderNode.Header -> {
            ReaderLayoutContentHeader(
                header = readerNode,
                header1TextStyle = header1TextStyle,
                header2TextStyle = header2TextStyle,
                header3TextStyle = header3TextStyle,
                header4TextStyle = header4TextStyle,
                header5TextStyle = header5TextStyle,
                header6TextStyle = header6TextStyle,
                sidePadding = sidePadding,
                textLayouts = textLayouts
            )
        }

        is ReaderNode.Paragraph -> {
            ReaderLayoutContentParagraph(
                paragraph = readerNode,
                paragraphTextStyle = paragraphTextStyle,
                horizontalAlignment = horizontalAlignment,
                sidePadding = sidePadding,
                textLayouts = textLayouts
            )
        }

        is ReaderNode.Image -> {
            Column(verticalArrangement = Arrangement.spacedBy(paragraphHeight)) {
                ReaderLayoutContentImage(
                    image = readerNode,
                    fontColor = fontColor,
                    sidePadding = sidePadding,
                    imagesOriginalSize = imagesOriginalSize,
                    imagesCornersRoundness = imagesCornersRoundness,
                    imagesAlignment = imagesAlignment,
                    imagesWidth = imagesWidth,
                    imagesColorEffects = imagesColorEffects
                )

                if (imagesCaptions) {
                    ReaderLayoutContentParagraph(
                        paragraph = ReaderNode.Paragraph(
                            originalNode = readerNode.node,
                            text = buildAnnotatedString {
                                readerNode.caption?.let {
                                    withStyle(
                                        SpanStyle(
                                            fontStyle = FontStyle.Italic
                                        )
                                    ) { append(it) }
                                }
                            }
                        ),
                        paragraphTextStyle = paragraphTextStyle,
                        horizontalAlignment = horizontalAlignment,
                        sidePadding = sidePadding,
                        textLayouts = textLayouts
                    )
                }
            }
        }

        is ReaderNode.Separator -> {
            ReaderLayoutContentSeparator(
                sidePadding = sidePadding,
                fontColor = fontColor
            )
        }
    }
}
