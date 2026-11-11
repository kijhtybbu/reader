/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * fork 新增：居中翻译对话框（第二阶段 S8，设计 §0/§7.3/§8）。
 *
 * 两个标签：
 *  - 翻译：打开即流式输出（缓存命中直接显示）；可折叠原文、复制/分享、重新生成。
 *  - 语法：逐句分析（设计 §8.2）。整段只有一句时直接分析；多句时先列出句子，点哪句分析哪句，
 *    再点一次收起。结果是模型写的 Markdown，用 [MarkdownText] 渲染（没有 JSON、没有「开始分析」按钮）。
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
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import ua.acclorite.book_story.R
import ua.acclorite.book_story.data.ai.AiError
import ua.acclorite.book_story.presentation.reader.model.AsyncGrammar
import ua.acclorite.book_story.presentation.reader.model.AsyncText
import ua.acclorite.book_story.presentation.reader.model.TranslationState
import ua.acclorite.book_story.presentation.reader.model.TranslationTab
import ua.acclorite.book_story.ui.common.components.common.StyledText
import ua.acclorite.book_story.ui.common.helpers.showToast
import ua.acclorite.book_story.ui.settings.reader.ai.components.messageRes
import ua.acclorite.book_story.ui.theme.LocalReaderPalette

private val DialogMaxWidth = 600.dp

/** 加载中/刚打开时先用一张小卡片，内容到了再展开（宽度也跟着变）。 */
private val DialogCompactWidth = 360.dp

/** 设计 §0：对话框最多占屏幕高度的 80%；内容少时按内容高度收缩。 */
private const val DialogHeightFraction = 0.8f

/**
 * 「还没有任何内容」的状态：翻译标签的 Idle/Loading，或单句语法分析还在请求。
 * 这时卡片用紧凑尺寸，避免一打开就是一块 80% 高的空白。
 */
private fun TranslationState.isCompactLoading(): Boolean = when (activeTab) {
    TranslationTab.TRANSLATION ->
        translation is AsyncText.Idle || translation is AsyncText.Loading

    TranslationTab.GRAMMAR ->
        sentences.size <= 1 && (grammar is AsyncGrammar.Idle || grammar is AsyncGrammar.Loading)
}

/**
 * @param onSelectTab 切标签；翻译标签会立即请求，语法标签多句时等用户选句。
 * @param onSelectSentence 语法标签里点一句句子（再点一次收起）。
 * @param onRegenerate 错误态的「重试」与成功态的「重新生成/重新分析」共用。
 * @param onShare 交给阅读页既有的分享 Intent（`ReaderEffect.OnOpenShareApp`）。
 * @param onExternalTranslate 错误态回退到外部翻译应用（S8 之前双击的默认行为）。
 */
@Composable
fun ReaderTranslationDialog(
    state: TranslationState,
    onSelectTab: (TranslationTab) -> Unit,
    onSelectSentence: (Int) -> Unit,
    onRegenerate: (TranslationTab) -> Unit,
    onDismiss: () -> Unit,
    onShare: (String) -> Unit,
    onExternalTranslate: (String) -> Unit,
    /** 已收藏的句子（trim 后的原文），五角星状态由它驱动。 */
    favoriteSentences: Set<String> = emptySet(),
    /** 点某一句话末尾的五角星。 */
    onToggleFavoriteSentence: (sentence: String) -> Unit = { _ -> }
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

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            val compact = state.isCompactLoading()
            val maxHeight = maxHeight * DialogHeightFraction
            Surface(
                modifier = Modifier
                    .widthIn(max = if (compact) DialogCompactWidth else DialogMaxWidth)
                    .fillMaxWidth()
                    .heightIn(max = maxHeight)
                    // 加载完成/流式增长时卡片平滑长高，不会「啪」一下跳成大块。
                    .animateContentSize(),
                shape = RoundedCornerShape(24.dp),
                color = palette.bar,
                contentColor = palette.onBar,
                shadowElevation = 8.dp
            ) {
                Column(Modifier.fillMaxWidth()) {
                    DialogHeader(
                        model = state.model,
                        targetLanguage = state.targetLanguageName,
                        onDismiss = onDismiss
                    )

                    if (state.grammarAvailable) {
                        DialogTabs(activeTab = state.activeTab, onSelectTab = onSelectTab)
                    }

                    HorizontalDivider(color = palette.onBar.copy(alpha = 0.10f))

                    // 内容短时卡片跟着收缩（fill = false）；超过 80% 高度后只有正文滚动。
                    Column(
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 24.dp, vertical = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
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
                                onSelectSentence = onSelectSentence,
                                onReanalyze = { onRegenerate(TranslationTab.GRAMMAR) },
                                favoriteSentences = favoriteSentences,
                                onToggleFavoriteSentence = onToggleFavoriteSentence
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DialogHeader(model: String, targetLanguage: String, onDismiss: () -> Unit) {
    val palette = LocalReaderPalette.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = 12.dp, top = 14.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            StyledText(
                text = stringResource(id = R.string.translation_dialog_title),
                style = MaterialTheme.typography.titleLarge.copy(
                    color = palette.onBar,
                    fontWeight = FontWeight.SemiBold
                )
            )
            // 模型与目标语言从滚动正文挪到标题下面：固定在顶部，不跟着译文滚走。
            if (model.isNotBlank() || targetLanguage.isNotBlank()) {
                StyledText(
                    text = stringResource(
                        id = R.string.translation_dialog_meta,
                        model.ifBlank { "-" },
                        targetLanguage
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelSmall.copy(color = palette.onBarMuted)
                )
            }
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
private fun TranslationTabContent(
    state: TranslationState,
    onCopy: (String) -> Unit,
    onShare: (String) -> Unit,
    onRetry: () -> Unit,
    onExternalTranslate: (String) -> Unit
) {
    OriginalSection(text = state.originalText, requestId = state.requestId)

    when (val translation = state.translation) {
        // 刚打开/请求发出前也按 loading 画：以前这里是空白，用户只看到一块空卡片。
        AsyncText.Idle, AsyncText.Loading -> LoadingPanel(
            text = stringResource(id = R.string.translation_dialog_translating),
            thinkingChars = state.thinkingChars
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
    onSelectSentence: (Int) -> Unit,
    onReanalyze: () -> Unit,
    favoriteSentences: Set<String>,
    onToggleFavoriteSentence: (String) -> Unit
) {
    val palette = LocalReaderPalette.current

    if (state.sentences.isEmpty()) {
        GrammarResult(
            grammar = state.grammar,
            thinkingChars = state.thinkingChars,
            onCopy = onCopy,
            onShare = onShare,
            onReanalyze = onReanalyze
        )
        return
    }

    if (state.sentences.size > 1 && state.selectedSentenceIndex == null) {
        StyledText(
            text = stringResource(id = R.string.translation_dialog_sentence_pick),
            style = MaterialTheme.typography.bodyMedium.copy(color = palette.onBarMuted)
        )
    }

    state.sentences.forEachIndexed { index, sentence ->
        val expanded = state.selectedSentenceIndex == index
        SentenceCard(
            index = index,
            sentence = sentence,
            expanded = expanded,
            isFavorite = favoriteSentences.contains(sentence),
            onToggleFavorite = { onToggleFavoriteSentence(sentence) },
            onClick = { onSelectSentence(index) }
        )
        // 分析结果放在句子卡片**外面**：以前它挂在同一张卡里，句子和分析共用一层底色、
        // 只靠内边距分隔，长句子加长分析就糊成一片（用户反馈「分不清哪个是句子哪个是分析」）。
        // 现在：句子 = 一张卡（完整显示、选中态染色），分析 = 另一张带标题和分隔线的卡。
        if (expanded) {
            if (state.sentences.size <= 1) {
                // 单句没有「哪一句」的问题，直接展示分析，不再多一层「分析」标题卡。
                GrammarResult(
                    grammar = state.grammar,
                    thinkingChars = state.thinkingChars,
                    onCopy = onCopy,
                    onShare = onShare,
                    onReanalyze = onReanalyze
                )
            } else {
                GrammarAnalysis(
                    grammar = state.grammar,
                    thinkingChars = state.thinkingChars,
                    onCopy = onCopy,
                    onShare = onShare,
                    onReanalyze = onReanalyze
                )
            }
        }
    }
}

/**
 * 句子卡片：整行可点（再点一次收起）。
 *
 * 句子**不截断**：以前 `maxLines = 2`，长句直接被省略号吃掉，用户看不出要分析的是哪半句。
 * 分析结果不在这张卡里（见 [GrammarAnalysis]），点选范围只有句子本身 ——
 * 否则选中、复制分析里的文字会顺手把卡片收起。
 */
@Composable
private fun SentenceCard(
    index: Int,
    sentence: String,
    expanded: Boolean,
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
    onClick: () -> Unit
) {
    val palette = LocalReaderPalette.current

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (expanded) palette.accent.copy(alpha = 0.12f)
        else palette.onBar.copy(alpha = 0.04f),
        contentColor = palette.onBar
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                // 右侧留给五角星；点星星不能顺带展开/收起这句话（IconButton 会吃掉点击）。
                .padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
            verticalAlignment = Alignment.Top
        ) {
            StyledText(
                text = "${index + 1}.",
                modifier = Modifier.width(24.dp),
                style = MaterialTheme.typography.labelMedium.copy(
                    color = palette.accent,
                    fontWeight = FontWeight.SemiBold
                )
            )
            StyledText(
                text = sentence,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium.copy(color = palette.onBar)
            )
            // 收藏放在句子末尾：收藏的是「这一句」，不是下面那份分析。
            IconButton(
                modifier = Modifier.size(40.dp),
                onClick = onToggleFavorite
            ) {
                Icon(
                    imageVector = if (isFavorite) Icons.Filled.Star else Icons.Outlined.StarBorder,
                    contentDescription = stringResource(
                        id = if (isFavorite) R.string.favorite_remove_content_desc
                        else R.string.favorite_add_content_desc
                    ),
                    modifier = Modifier.size(20.dp),
                    tint = if (isFavorite) palette.accent else palette.onBarMuted
                )
            }
        }
    }
}

/**
 * 一句的分析结果。
 *
 * 与句子卡片刻意长得不一样：独立卡片 + 标题行（「分析」）+ 发丝分隔线，
 * 一眼能分出「原文那一句」和「模型给出的分析」。
 */
@Composable
private fun GrammarAnalysis(
    grammar: AsyncGrammar,
    thinkingChars: Int,
    onCopy: (String) -> Unit,
    onShare: (String) -> Unit,
    onReanalyze: () -> Unit
) {
    val palette = LocalReaderPalette.current

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = palette.onBar.copy(alpha = 0.02f),
        contentColor = palette.onBar
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            StyledText(
                text = stringResource(id = R.string.translation_dialog_analysis),
                style = MaterialTheme.typography.labelMedium.copy(
                    color = palette.accent,
                    fontWeight = FontWeight.SemiBold
                )
            )
            HorizontalDivider(color = palette.onBar.copy(alpha = 0.10f))
            GrammarResult(
                grammar = grammar,
                thinkingChars = thinkingChars,
                onCopy = onCopy,
                onShare = onShare,
                onReanalyze = onReanalyze
            )
        }
    }
}

/** 选中句子的语法状态：loading、流式 Markdown、完整结果（可复制/分享/重新分析）、错误 + 重试。 */
@Composable
private fun GrammarResult(
    grammar: AsyncGrammar,
    thinkingChars: Int,
    onCopy: (String) -> Unit,
    onShare: (String) -> Unit,
    onReanalyze: () -> Unit
) {
    when (grammar) {
        // Idle 只会出现一瞬间（选中句子之后、请求发出之前），按 loading 画。
        AsyncGrammar.Idle, AsyncGrammar.Loading -> LoadingRow(
            text = stringResource(id = R.string.translation_dialog_analyzing),
            thinkingChars = thinkingChars
        )

        // 流式：先渲染已到达的部分。没写完的 Markdown（半截加粗/列表）会自然退化成纯文本；
        // 不显示复制/分享/重新分析 —— 内容还不完整，拿它分享等于把半截答案发出去。
        is AsyncGrammar.Streaming -> SelectionContainer {
            MarkdownText(
                markdown = grammar.text,
                style = MaterialTheme.typography.bodyLarge.copy(
                    color = LocalReaderPalette.current.onBar
                )
            )
        }

        is AsyncGrammar.Success -> {
            if (grammar.fromCache) CacheIndicator()
            SelectionContainer {
                MarkdownText(
                    markdown = grammar.text,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        color = LocalReaderPalette.current.onBar
                    )
                )
            }
            ActionRow {
                // 复制/分享用模型返回的 Markdown 原文，而不是渲染后的纯文本：排版要跟着走。
                DialogAction(
                    text = stringResource(id = R.string.copy),
                    onClick = { onCopy(grammar.text) }
                )
                DialogAction(
                    text = stringResource(id = R.string.share),
                    onClick = { onShare(grammar.text) }
                )
                DialogAction(
                    text = stringResource(id = R.string.translation_dialog_reanalyze),
                    onClick = onReanalyze
                )
            }
        }

        is AsyncGrammar.Error -> {
            ErrorText(error = grammar.error, statusCode = grammar.statusCode)
            ActionRow {
                // 没有外部回退：「用其他应用翻译」只能翻译原文，给不了语法分析。
                DialogAction(text = stringResource(id = R.string.retry), onClick = onReanalyze)
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
private fun CacheIndicator() {
    StyledText(
        text = stringResource(id = R.string.translation_dialog_from_cache),
        style = MaterialTheme.typography.labelSmall.copy(
            color = LocalReaderPalette.current.accent
        )
    )
}

@Composable
private fun LoadingRow(text: String, thinkingChars: Int = 0) {
    val palette = LocalReaderPalette.current
    val label = waitingLabel(text, thinkingChars)
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(
            modifier = Modifier.size(16.dp),
            color = palette.accent,
            strokeWidth = 2.dp
        )
        StyledText(
            text = label,
            modifier = Modifier.padding(start = 8.dp),
            style = MaterialTheme.typography.bodyMedium.copy(color = palette.onBar)
        )
    }
}

/**
 * 加载态的「已经等了多久」。
 *
 * 推理模型（DeepSeek / o 系列等）会先思考十几秒才吐第一段正文，界面一直停在「分析中」
 * 看起来像卡死。超过 [WAITING_HINT_DELAY_SECONDS] 秒就把文案换成「等待模型响应… N 秒」，
 * 让用户知道请求还活着、慢在模型侧（实测首字延迟见 PROGRESS.md）。
 */
@Composable
private fun waitingLabel(text: String, thinkingChars: Int): String {
    var seconds by remember(text) { mutableIntStateOf(0) }
    LaunchedEffect(text) {
        while (true) {
            delay(1_000)
            seconds++
        }
    }
    return when {
        // 推理模型的思考是**流式**的（实测语法一次 8.6k 字思考、35 秒后才出第一段正文）：
        // 字数在涨就说明请求活着，比光转圈更有说服力。
        thinkingChars > 0 -> stringResource(id = R.string.translation_dialog_thinking, thinkingChars)
        seconds >= WAITING_HINT_DELAY_SECONDS ->
            stringResource(id = R.string.translation_dialog_waiting, seconds)

        else -> text
    }
}

private const val WAITING_HINT_DELAY_SECONDS = 3

/** 紧凑加载态：居中、留一点上下空间，配小卡片看起来不空。 */
@Composable
private fun LoadingPanel(text: String, thinkingChars: Int = 0) {
    val palette = LocalReaderPalette.current
    val label = waitingLabel(text, thinkingChars)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 18.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(18.dp),
            color = palette.accent,
            strokeWidth = 2.dp
        )
        StyledText(
            text = label,
            modifier = Modifier.padding(start = 10.dp),
            style = MaterialTheme.typography.bodyMedium.copy(color = palette.onBar)
        )
    }
}

@Composable
private fun SelectableBody(text: String) {
    val palette = LocalReaderPalette.current
    SelectionContainer {
        StyledText(
            text = text,
            style = MaterialTheme.typography.bodyLarge.copy(color = palette.onBar)
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
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        itemVerticalAlignment = Alignment.CenterVertically
    ) {
        content()
    }
}

@Composable
private fun DialogAction(text: String, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        StyledText(text = text)
    }
}
