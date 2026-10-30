/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * fork 新增：AI 请求的类型化错误（第二阶段 S6，设计 §6.3）。
 *
 * 只暴露错误类别与 HTTP 状态码；不把响应体、请求头或 Key 放进异常消息。
 * 取消不是错误：[AiTranslationRepository] 会让 CancellationException 原样传播，
 * [CANCELLED] 只用于 UI 侧兜底展示。
 */

package ua.acclorite.book_story.data.ai

enum class AiError {
    NO_KEY,
    INVALID_KEY,
    RATE_LIMIT,
    NOT_FOUND,
    TIMEOUT,
    NETWORK,
    SERVER,
    CANCELLED,
    UNKNOWN
}

class AiException(
    val error: AiError,
    val statusCode: Int? = null,
    cause: Throwable? = null
) : Exception(
    buildString {
        append("AI request failed: ")
        append(error.name)
        statusCode?.let { append(" (HTTP ").append(it).append(')') }
    },
    cause
)
