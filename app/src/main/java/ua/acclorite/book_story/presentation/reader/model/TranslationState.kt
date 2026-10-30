/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * fork 新增：翻译对话框的状态（第二阶段 S8，设计 §7.3/§8）。
 *
 * 两个标签各自持有独立的异步状态：翻译标签流式输出，语法标签一次性返回。
 * 缓存命中标记（`fromCache`）放在各自的成功态里 —— 对话框要能分别告诉用户
 * 「这段译文来自缓存」与「这份语法分析来自缓存」。
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
     * @param analysis 解析成功时为结构化结果；JSON 不合法时为 null，UI 退化成展示 [raw]。
     * @param raw 模型返回的原始 JSON，重新生成前一直保留（也能复制/分享）。
     */
    data class Success(
        val analysis: GrammarAnalysis?,
        val raw: String,
        val fromCache: Boolean
    ) : AsyncGrammar

    data class Error(val error: AiError, val statusCode: Int?) : AsyncGrammar
}

/**
 * @param originalText 双击时取的是 `NodeContent.Paragraph.text` 的**整段**正文，不是分页切片。
 * @param nodeId 段落节点 id；长按选区没有段落时是 null（缓存表里落 -1）。
 * @param targetLanguageName 提示词里的语言名，也是缓存键的一部分（FOLLOW_UI 已解析）。
 * @param grammarAvailable 跟随设置项 `ai_grammar_enabled`：关闭时不显示语法标签。
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
    val targetLanguage: AiTargetLanguage = AiTargetLanguage.FOLLOW_UI,
    val targetLanguageName: String = "",
    val model: String = "",
    val grammarAvailable: Boolean = true,
    val requestId: Long = 0L
)
