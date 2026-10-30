/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * fork 新增：翻译对话框的状态（第二阶段 S8，设计 §7.3/§8）。
 *
 * 两个标签各自持有独立的异步状态：翻译标签流式输出，语法标签一次性返回（Markdown 原文）。
 * 缓存命中标记（`fromCache`）放在各自的成功态里 —— 对话框要能分别告诉用户
 * 「这段译文来自缓存」与「这份语法分析来自缓存」。
 *
 * 语法标签逐句分析：`sentences` 是整段切句的结果，`selectedSentenceIndex` 是当前展开的句子，
 * `grammar` 只描述**这一句**的状态（全部收起时为 Idle）。两句以上的结果由模型内部按句缓存。
 *
 * 错误只带 [AiError] + HTTP 状态码，UI 侧再映射成字符串资源；原始异常消息
 * （可能含响应体）不进入状态。
 */

package ua.acclorite.book_story.presentation.reader.model

import androidx.compose.runtime.Immutable
import ua.acclorite.book_story.data.ai.AiError
import ua.acclorite.book_story.data.ai.AiTargetLanguage

enum class TranslationTab {
    TRANSLATION,
    GRAMMAR
}

sealed interface AsyncText {

    data object Idle : AsyncText

    /** 已发起请求（含读缓存的那一小段），还没有任何文字。 */
    data object Loading : AsyncText

    data class Streaming(val text: String) : AsyncText

    data class Success(val text: String, val fromCache: Boolean) : AsyncText

    data class Error(val error: AiError, val statusCode: Int?) : AsyncText
}

sealed interface AsyncGrammar {

    data object Idle : AsyncGrammar

    data object Loading : AsyncGrammar

    /**
     * @param text 模型返回的 Markdown 原文：UI 直接渲染，复制/分享也用这一份
     *   （渲染是有损的，分享出去要保持模型的排版）。
     */
    data class Success(val text: String, val fromCache: Boolean) : AsyncGrammar

    data class Error(val error: AiError, val statusCode: Int?) : AsyncGrammar
}

/**
 * @param originalText 双击时取的是 `NodeContent.Paragraph.text` 的**整段**正文，不是分页切片。
 * @param nodeId 段落节点 id；长按选区没有段落时是 null（缓存表里落 -1）。
 * @param targetLanguageName 提示词里的语言名，也是缓存键的一部分（FOLLOW_UI 已解析）。
 * @param grammarAvailable 跟随设置项 `ai_grammar_enabled`：关闭时不显示语法标签。
 * @param sentences 整段正文按句切分的结果（`splitIntoSentences`）；语法标签逐句分析用。
 * @param selectedSentenceIndex 语法标签当前展开的句子；null = 全部收起。
 * @param requestId 每次 open/dismiss 递增；迟到的响应靠它识别并丢弃。
 */
@Immutable
data class TranslationState(
    val visible: Boolean = false,
    val originalText: String = "",
    val bookId: Int = 0,
    val nodeId: Int? = null,
    val activeTab: TranslationTab = TranslationTab.TRANSLATION,
    val translation: AsyncText = AsyncText.Idle,
    val grammar: AsyncGrammar = AsyncGrammar.Idle,
    val sentences: List<String> = emptyList(),
    val selectedSentenceIndex: Int? = null,
    val targetLanguage: AiTargetLanguage = AiTargetLanguage.FOLLOW_UI,
    val targetLanguageName: String = "",
    val model: String = "",
    val grammarAvailable: Boolean = true,
    val requestId: Long = 0L
)
