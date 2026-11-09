/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * fork 新增：收藏句子的详情对话框（译文 + 语法分析）。
 *
 * 语法分析是收藏时的快照，打开即显示；译文可能还没请求过，此时按需补一次并回填数据库。
 * 因此这个对话框没有「开始分析」之类的按钮：能显示的就直接显示，缺的只有译文。
 *
 * 配色只用 `LocalReaderPalette`（由收藏页从 App 主题派生并注入）：MarkdownText 内部也读它，
 * 深色主题下这里不能出现一块亮白卡片。
 */

package ua.acclorite.book_story.ui.favorites

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import ua.acclorite.book_story.R
import ua.acclorite.book_story.presentation.favorites.FavoriteSentenceDialogState
import ua.acclorite.book_story.presentation.favorites.FavoriteTranslation
import ua.acclorite.book_story.ui.common.components.common.StyledText
import ua.acclorite.book_story.ui.reader.MarkdownText
import ua.acclorite.book_story.ui.settings.reader.ai.components.messageRes
import ua.acclorite.book_story.ui.theme.LocalReaderPalette

private val DialogMaxWidth = 600.dp

/** 与翻译对话框一致：最多占屏幕高度的 80%，内容少时按内容收缩。 */
private const val DialogHeightFraction = 0.8f

@Composable
fun FavoriteSentenceDialog(
    state: FavoriteSentenceDialogState,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    onRemove: () -> Unit
) {
    val palette = LocalReaderPalette.current

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
            usePlatformDefaultWidth = false
        )
    ) {
        (LocalView.current.parent as? DialogWindowProvider)?.window?.setDimAmount(0.6f)

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            val maxHeight = maxHeight * DialogHeightFraction
            Surface(
                modifier = Modifier
                    .widthIn(max = DialogMaxWidth)
                    .fillMaxWidth()
                    .heightIn(max = maxHeight),
                shape = RoundedCornerShape(24.dp),
                color = palette.bar,
                contentColor = palette.onBar,
                shadowElevation = 8.dp
            ) {
                Column(Modifier.fillMaxWidth()) {
                    DialogHeader(onRemove = onRemove, onDismiss = onDismiss)
                    HorizontalDivider(color = palette.onBar.copy(alpha = 0.10f))

                    Column(
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 24.dp, vertical = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        SentenceCard(text = state.favorite.text)
                        TranslationSection(
                            translation = state.translation,
                            onRetry = onRetry
                        )
                        GrammarSection(grammar = state.favorite.grammar)
                    }
                }
            }
        }
    }
}

@Composable
private fun DialogHeader(onRemove: () -> Unit, onDismiss: () -> Unit) {
    val palette = LocalReaderPalette.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = 12.dp, top = 14.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        StyledText(
            text = stringResource(id = R.string.favorite_sentence_dialog_title),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleLarge.copy(
                color = palette.onBar,
                fontWeight = FontWeight.SemiBold
            )
        )
        // 详情里的五角星永远是实心（能打开就说明已收藏），点它 = 取消收藏并关闭。
        IconButton(onClick = onRemove) {
            Icon(
                imageVector = Icons.Filled.Star,
                contentDescription = stringResource(id = R.string.favorite_remove_content_desc),
                tint = palette.accent
            )
        }
        IconButton(onClick = onDismiss) {
            Icon(
                imageVector = Icons.Outlined.Close,
                contentDescription = stringResource(id = R.string.translation_dialog_close),
                tint = palette.onBarMuted
            )
        }
    }
}

/** 原句：完整显示（长句换行），可选中复制。 */
@Composable
private fun SentenceCard(text: String) {
    val palette = LocalReaderPalette.current

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = palette.accent.copy(alpha = 0.12f),
        contentColor = palette.onBar
    ) {
        SelectionContainer {
            StyledText(
                text = text,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                style = MaterialTheme.typography.bodyLarge.copy(color = palette.onBar)
            )
        }
    }
}

@Composable
private fun TranslationSection(
    translation: FavoriteTranslation,
    onRetry: () -> Unit
) {
    val palette = LocalReaderPalette.current

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SectionTitle(text = stringResource(id = R.string.favorite_sentence_translation))

        when (translation) {
            FavoriteTranslation.Loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    color = palette.accent,
                    strokeWidth = 2.dp
                )
                StyledText(
                    text = stringResource(id = R.string.favorite_sentence_translating),
                    modifier = Modifier.padding(start = 8.dp),
                    style = MaterialTheme.typography.bodyMedium.copy(color = palette.onBar)
                )
            }

            is FavoriteTranslation.Streaming -> SelectionContainer {
                StyledText(
                    text = translation.text,
                    style = MaterialTheme.typography.bodyLarge.copy(color = palette.onBar)
                )
            }

            is FavoriteTranslation.Success -> SelectionContainer {
                StyledText(
                    text = translation.text,
                    style = MaterialTheme.typography.bodyLarge.copy(color = palette.onBar)
                )
            }

            is FavoriteTranslation.Error -> {
                val base = stringResource(id = translation.error.messageRes())
                StyledText(
                    text = translation.statusCode?.let {
                        stringResource(id = R.string.ai_error_with_status, base, it)
                    } ?: base,
                    style = MaterialTheme.typography.bodyMedium.copy(color = palette.onBar)
                )
                TextButton(onClick = onRetry) {
                    StyledText(text = stringResource(id = R.string.retry))
                }
            }
        }
    }
}

@Composable
private fun GrammarSection(grammar: String?) {
    val palette = LocalReaderPalette.current

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SectionTitle(text = stringResource(id = R.string.favorite_sentence_grammar))

        if (grammar.isNullOrBlank()) {
            StyledText(
                text = stringResource(id = R.string.favorite_sentence_no_grammar),
                style = MaterialTheme.typography.bodyMedium.copy(
                    color = palette.onBarMuted,
                    textAlign = TextAlign.Start
                )
            )
        } else {
            SelectionContainer {
                MarkdownText(
                    markdown = grammar,
                    style = MaterialTheme.typography.bodyLarge.copy(color = palette.onBar)
                )
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    val palette = LocalReaderPalette.current

    StyledText(
        text = text,
        style = MaterialTheme.typography.labelMedium.copy(
            color = palette.accent,
            fontWeight = FontWeight.SemiBold
        )
    )
}
