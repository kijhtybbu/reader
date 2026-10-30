/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * fork 新增：OpenAI 兼容接口的连接配置（第二阶段 S6，设计 §6.1/§6.3）。
 *
 * Base URL 在这里统一规范化：设置页存的是规范化后的值，仓库每次调用前再规范化一次，
 * 这样恢复备份/手改 DataStore 留下的「没有尾部斜杠」也不会拼出 `/v1chat/completions`。
 * 没写协议的输入（`api.openai.com/v1`）补 `https://`：不补的话 Ktor 拿到的是相对路径，
 * 只会抛一个被映射成 UNKNOWN 的异常，用户看不出是地址写错了。
 *
 * 本机地址（[LOOPBACK_AI_HOSTS]）单独判一次：本地 Ollama/LM Studio 这类 OpenAI 兼容服务
 * 通常不校验 Key，[AiTranslationRepository] 允许它们留空，而公网地址仍然必须有 Key。
 */

package ua.acclorite.book_story.data.ai

import java.util.Locale

const val DEFAULT_AI_BASE_URL = "https://api.openai.com/v1/"
const val DEFAULT_AI_MODEL = "gpt-4o-mini"

/** 与 `res/xml/network_security_config.xml` 放行明文的域名保持一致。 */
private val LOOPBACK_AI_HOSTS = setOf("localhost", "127.0.0.1", "10.0.2.2")

/** [baseUrl] 已规范化、[model] 已 trim；API Key 不在这里（它在 [AiKeyStore] 里）。 */
data class AiClientConfig(
    val baseUrl: String,
    val model: String,
    /** 稳定会话 ID，作为 `x-opencode-session` 发送；普通 OpenAI 兼容服务会忽略。 */
    val sessionId: String = ""
)

fun normalizeAiBaseUrl(value: String): String {
    val trimmed = value.trim()
    if (trimmed.isEmpty()) return DEFAULT_AI_BASE_URL
    val withScheme = if (trimmed.contains("://")) trimmed else "https://$trimmed"
    return if (withScheme.endsWith('/')) withScheme else "$withScheme/"
}

/** 本机/模拟器回环地址：这些地址上的服务可以不配 Key。 */
fun isLoopbackAiBaseUrl(baseUrl: String): Boolean = aiBaseUrlHost(baseUrl) in LOOPBACK_AI_HOSTS

/** 只用于判断「是不是本机地址」；解析不出 host 就当作远程（远程必须有 Key）。 */
internal fun aiBaseUrlHost(baseUrl: String): String? {
    val authority = normalizeAiBaseUrl(baseUrl)
        .substringAfter("://", "")
        .takeWhile { it != '/' && it != '?' && it != '#' }
        .substringAfterLast('@')
    val host = if (authority.startsWith('[')) {
        authority.substringBefore(']').removePrefix("[")
    } else {
        authority.substringBefore(':')
    }
    return host.lowercase(Locale.ROOT).ifEmpty { null }
}
