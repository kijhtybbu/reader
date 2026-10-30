/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * fork 新增：居中翻译对话框（第二阶段 S8，设计 §0/§7.3/§8）。
 *
 * 两个标签：
 *  - 翻译：打开即流式输出（缓存命中直接显示）；可折叠原文、复制/分享、重新生成。
 *  - 语法：**切到标签即分析**（没有「开始分析」按钮）；结构化展示 summary / sentences /
 *    vocabulary；JSON 不合法时退化成原始文本，JSON 合法但没有任何字段时只说明结果为空。
 *
 * 配色只用 `LocalReaderPalette`：对话框开在阅读页之上，夜间预设下不能闪白。
 * 尺寸按设计：最大宽 600dp、高 80%；内容内部滚动，长段落不会把按钮挤出屏幕。
 *
 * 关闭（返回/点外部/关闭按钮）都会取消请求；已完成的结果在缓存里，不受影响。
 * 外部翻译 Intent 只作为**翻译标签**错误态的「用其他应用翻译」回退保留：它只能翻译原文，
 * 给不了语法分析，所以语法标签的错误态只有一「重试」。
 */

package ua.acclorite.book_story.ui.reader

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import ua.acclorite.book_story.R
import ua.acclorite.book_story.data.ai.AiError
import ua.acclorite.book_story.presentation.reader.model.AsyncGrammar
import ua.acclorite.book_story.presentation.reader.model.AsyncText
import ua.acclorite.book_story.presentation.reader.model.GrammarAnalysis
import ua.acclorite.book_story.presentation.reader.model.TranslationState
import ua.acclorite.book_story.presentation.reader.model.TranslationTab
import ua.acclorite.book_story.ui.common.components.common.StyledText
import ua.acclorite.book_story.ui.common.helpers.showToast
import ua.acclorite.book_story.ui.settings.reader.ai.components.messageRes
import ua.acclorite.book_story.ui.theme.LocalReaderPalette

private val DialogMaxWidth = 600.dp

/** 设计 §0：对话框占屏幕高度的 80%。 */
private const val DialogHeightFraction = 0.8f

/**
 * @param onSelectTab 切标签；切到语法时模型会立即请求（缓存命中则直接展示）。
 * @param onRegenerate 错误态的「重试」与成功态的「重新生成」共用。
 * @param onShare 交给阅读页既有的分享 Intent（`ReaderEffect.OnOpenShareApp`）。
 * @param onExternalTranslate 错误态回退到外部翻译应用（S8 之前双击的默认行为）。
 */
@Composable
fun ReaderTranslationDialog(
    state: TranslationState,
    onSelectTab: (TranslationTab) -> Unit,
    onRegenerate: (TranslationTab) -> Unit,
    onDismiss: () -> Unit,
    onShare: (String) -> Unit,
    onExternalTranslate: (String) -> Unit
) {
    if (!state.visible) return

    val palette = LocalReaderPalette.current
    val context = LocalContext.current
    val clipboard = remember(context) {
        context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    }
    val copied = stringResource(id = R.string.copied)
    val copy: (String) -> Unit = remember(clipboard, copied, context) {
        { text ->
            clipboard?.setPrimaryClip(ClipData.newPlainText(null, text))
            copied.showToast(context, longToast = false)
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
            usePlatformDefaultWidth = false
        )
    ) {
        // 与项目里其它 Dialog 一致：对话框自己的窗口不跟随主题，调暗一点才有浮层感。
        (LocalView.current.parent as? DialogWindowProvider)?.window?.setDimAmount(0.6f)

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                modifier = Modifier
                    .widthIn(max = DialogMaxWidth)
                    .fillMaxWidth()
                    .fillMaxHeight(DialogHeightFraction),
                shape = RoundedCornerShape(24.dp),
                color = palette.bar,
                contentColor = palette.onBar,
                shadowElevation = 8.dp
            ) {
                Column(Modifier.fillMaxSize()) {
                    DialogHeader(onDismiss)

                    if (state.grammarAvailable) {
                        DialogTabs(activeTab = state.activeTab, onSelectTab = onSelectTab)
                    }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .padding(horizontal = 24.dp, vertical = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            MetaLine(model = state.model, targetLanguage = state.targetLanguageName)

                            when (state.activeTab) {
                                TranslationTab.TRANSLATION -> TranslationTabContent(
                                    state = state,
                                    onCopy = copy,
                                    onShare = onShare,
                                    onRetry = { onRegenerate(TranslationTab.TRANSLATION) },
                                    onExternalTranslate = onExternalTranslate
                                )

                                TranslationTab.GRAMMAR -> GrammarTabContent(
                                    state = state,
                                    onCopy = copy,
                                    onShare = onShare,
                                    onRetry = { onRegenerate(TranslationTab.GRAMMAR) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DialogHeader(onDismiss: () -> Unit) {
    val palette = LocalReaderPalette.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = 12.dp, top = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        StyledText(
            text = stringResource(id = R.string.translation_dialog_title),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleLarge.copy(
                color = palette.onBar,
                fontWeight = FontWeight.SemiBold
            )
        )
        IconButton(onClick = onDismiss) {
            Icon(
                imageVector = Icons.Outlined.Close,
                contentDescription = stringResource(id = R.string.translation_dialog_close),
                tint = palette.onBarMuted
            )
        }
    }
}

@Composable
private fun DialogTabs(
    activeTab: TranslationTab,
    onSelectTab: (TranslationTab) -> Unit
) {
    val palette = LocalReaderPalette.current
    val selectedIndex = if (activeTab == TranslationTab.GRAMMAR) 1 else 0

    PrimaryTabRow(
        selectedTabIndex = selectedIndex,
        containerColor = Color.Transparent,
        contentColor = palette.onBar,
        divider = {},
        indicator = {
            TabRowDefaults.PrimaryIndicator(
                Modifier.tabIndicatorOffset(selectedIndex, matchContentSize = true),
                color = palette.accent
            )
        }
    ) {
        Tab(
            selected = activeTab == TranslationTab.TRANSLATION,
            onClick = { onSelectTab(TranslationTab.TRANSLATION) },
            selectedContentColor = palette.accent,
            unselectedContentColor = palette.onBarMuted,
            text = {
                StyledText(
                    text = stringResource(id = R.string.translation_dialog_tab_translation),
                    maxLines = 1
                )
            }
        )
        Tab(
            selected = activeTab == TranslationTab.GRAMMAR,
            onClick = { onSelectTab(TranslationTab.GRAMMAR) },
            selectedContentColor = palette.accent,
            unselectedContentColor = palette.onBarMuted,
            text = {
                StyledText(
                    text = stringResource(id = R.string.translation_dialog_tab_grammar),
                    maxLines = 1
                )
            }
        )
    }
}

@Composable
private fun MetaLine(model: String, targetLanguage: String) {
    val palette = LocalReaderPalette.current
    StyledText(
        text = stringResource(id = R.string.translation_dialog_meta, model, targetLanguage),
        style = MaterialTheme.typography.labelMedium.copy(color = palette.onBarMuted)
    )
}

@Composable
private fun TranslationTabContent(
    state: TranslationState,
    onCopy: (String) -> Unit,
    onShare: (String) -> Unit,
    onRetry: () -> Unit,
    onExternalTranslate: (String) -> Unit
) {
    OriginalSection(text = state.originalText, requestId = state.requestId)

    when (val translation = state.translation) {
        AsyncText.Idle -> Unit

        AsyncText.Loading -> LoadingRow(
            text = stringResource(id = R.string.translation_dialog_translating)
        )

        is AsyncText.Streaming -> SelectableBody(text = translation.text)

        is AsyncText.Success -> {
            if (translation.fromCache) CacheIndicator()
            SelectableBody(text = translation.text)
            ActionRow {
                DialogAction(
                    text = stringResource(id = R.string.copy),
                    onClick = { onCopy(translation.text) }
                )
                DialogAction(
                    text = stringResource(id = R.string.share),
                    onClick = { onShare(translation.text) }
                )
                DialogAction(
                    text = stringResource(id = R.string.translation_dialog_regenerate),
                    onClick = onRetry
                )
            }
        }

        is AsyncText.Error -> {
            ErrorText(error = translation.error, statusCode = translation.statusCode)
            ActionRow {
                DialogAction(text = stringResource(id = R.string.retry), onClick = onRetry)
                DialogAction(
                    text = stringResource(id = R.string.translation_dialog_external),
                    onClick = { onExternalTranslate(state.originalText) }
                )
            }
        }
    }
}

@Composable
private fun GrammarTabContent(
    state: TranslationState,
    onCopy: (String) -> Unit,
    onShare: (String) -> Unit,
    onRetry: () -> Unit
) {
    when (val grammar = state.grammar) {
        AsyncGrammar.Idle, AsyncGrammar.Loading -> LoadingRow(
            text = stringResource(id = R.string.translation_dialog_analyzing)
        )

        is AsyncGrammar.Success -> {
            if (grammar.fromCache) CacheIndicator()

            val analysis = grammar.analysis
            when {
                // JSON 不合法：不藏结果，原样展示模型返回的内容。
                analysis == null -> {
                    MutedNote(text = stringResource(id = R.string.translation_dialog_raw_note))
                    SelectableBody(text = grammar.raw)
                }
                // JSON 合法但没有一个语法字段（例如 `{}`）：回显 `{}` 没有信息量，只说明结果为空。
                analysis.isEmpty -> {
                    MutedNote(text = stringResource(id = R.string.translation_dialog_empty_analysis))
                }
                else -> GrammarSections(analysis = analysis)
            }

            ActionRow {
                DialogAction(
                    text = stringResource(id = R.string.copy),
                    onClick = { onCopy(grammar.raw) }
                )
                DialogAction(
                    text = stringResource(id = R.string.share),
                    onClick = { onShare(grammar.raw) }
                )
                DialogAction(
                    text = stringResource(id = R.string.translation_dialog_regenerate),
                    onClick = onRetry
                )
            }
        }

        is AsyncGrammar.Error -> {
            ErrorText(error = grammar.error, statusCode = grammar.statusCode)
            ActionRow {
                // 没有外部回退：「用其他应用翻译」只能翻译原文，给不了语法分析。
                DialogAction(text = stringResource(id = R.string.retry), onClick = onRetry)
            }
        }
    }
}

@Composable
private fun GrammarSections(analysis: GrammarAnalysis) {
    val palette = LocalReaderPalette.current

    analysis.summary?.let { summary ->
        SectionTitle(text = stringResource(id = R.string.translation_dialog_summary))
        SelectableBody(text = summary)
    }

    if (analysis.sentences.isNotEmpty()) {
        SectionTitle(text = stringResource(id = R.string.translation_dialog_sentences))
        analysis.sentences.forEach { sentence ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                sentence.original?.let { SelectableBody(text = it, emphasized = true) }
                sentence.translation?.let {
                    StyledText(
                        text = it,
                        style = MaterialTheme.typography.bodyMedium.copy(color = palette.onBarMuted)
                    )
                }
                sentence.structure?.let {
                    StyledText(
                        text = it,
                        style = MaterialTheme.typography.bodyMedium.copy(color = palette.onBar)
                    )
                }
                sentence.points.forEach { point ->
                    Row {
                        StyledText(
                            text = "•",
                            modifier = Modifier.width(12.dp),
                            style = MaterialTheme.typography.bodyMedium.copy(color = palette.accent)
                        )
                        StyledText(
                            text = point,
                            style = MaterialTheme.typography.bodyMedium.copy(color = palette.onBar)
                        )
                    }
                }
            }
        }
    }

    if (analysis.vocabulary.isNotEmpty()) {
        SectionTitle(text = stringResource(id = R.string.translation_dialog_vocabulary))
        analysis.vocabulary.forEach { entry ->
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    entry.word?.let {
                        StyledText(
                            text = it,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.titleSmall.copy(
                                color = palette.onBar,
                                fontWeight = FontWeight.SemiBold
                            )
                        )
                    }
                    entry.pos?.let {
                        StyledText(
                            text = it,
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = palette.onBarMuted
                            )
                        )
                    }
                }
                entry.meaning?.let {
                    StyledText(
                        text = it,
                        style = MaterialTheme.typography.bodyMedium.copy(color = palette.onBar)
                    )
                }
            }
        }
    }
}

/** 原文可折叠：长段落占满整屏时，用户更想看译文。 */
@Composable
private fun OriginalSection(text: String, requestId: Long) {
    var expanded by remember(requestId) { mutableStateOf(false) }

    TextButton(onClick = { expanded = !expanded }) {
        StyledText(
            text = stringResource(
                id = if (expanded) R.string.translation_dialog_hide_original
                else R.string.translation_dialog_show_original
            )
        )
    }

    if (expanded) {
        SelectionContainer {
            StyledText(
                text = text,
                style = MaterialTheme.typography.bodySmall.copy(
                    color = LocalReaderPalette.current.onBarMuted
                )
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    StyledText(
        text = text,
        style = MaterialTheme.typography.titleSmall.copy(
            color = LocalReaderPalette.current.accent,
            fontWeight = FontWeight.SemiBold
        )
    )
}

@Composable
private fun MutedNote(text: String) {
    StyledText(
        text = text,
        style = MaterialTheme.typography.labelMedium.copy(
            color = LocalReaderPalette.current.onBarMuted
        )
    )
}

@Composable
private fun CacheIndicator() {
    StyledText(
        text = stringResource(id = R.string.translation_dialog_from_cache),
        style = MaterialTheme.typography.labelSmall.copy(
            color = LocalReaderPalette.current.accent
        )
    )
}

@Composable
private fun LoadingRow(text: String) {
    val palette = LocalReaderPalette.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(
            modifier = Modifier.size(16.dp),
            color = palette.accent,
            strokeWidth = 2.dp
        )
        StyledText(
            text = text,
            modifier = Modifier.padding(start = 8.dp),
            style = MaterialTheme.typography.bodyMedium.copy(color = palette.onBar)
        )
    }
}

@Composable
private fun SelectableBody(text: String, emphasized: Boolean = false) {
    val palette = LocalReaderPalette.current
    SelectionContainer {
        StyledText(
            text = text,
            style = if (emphasized) {
                MaterialTheme.typography.bodyLarge.copy(
                    color = palette.onBar,
                    fontWeight = FontWeight.Medium
                )
            } else {
                MaterialTheme.typography.bodyLarge.copy(color = palette.onBar)
            }
        )
    }
}

/**
 * 错误只显示类别 + HTTP 状态码（[AiError] 不含响应体，也不含 Key）。
 * 颜色用阅读色系而不是 `colorScheme.error`：夜间预设下主题错误色可能落在深色背景上看不清。
 */
@Composable
private fun ErrorText(error: AiError, statusCode: Int?) {
    val base = stringResource(id = error.messageRes())
    StyledText(
        text = statusCode?.let { stringResource(id = R.string.ai_error_with_status, base, it) }
            ?: base,
        style = MaterialTheme.typography.bodyMedium.copy(
            color = LocalReaderPalette.current.onBar
        )
    )
}

@Composable
private fun ActionRow(content: @Composable () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        content()
    }
}

@Composable
private fun DialogAction(text: String, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        StyledText(text = text)
    }
}
