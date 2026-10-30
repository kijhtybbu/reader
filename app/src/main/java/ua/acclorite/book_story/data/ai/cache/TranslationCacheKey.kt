/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * fork 新增：AI 结果缓存键（第二阶段 S7，设计 §7.2）。
 *
 * 键 = SHA-256(`v<promptVersion>|<kind>|<targetLanguage>|<model>|<baseUrlHost>|<normalize(text)>`)。
 * 换模型、换服务商、换目标语言、改提示词都会自动 miss；同一段文字出现在不同书/章节时共享缓存。
 *
 * 纯 JVM：不碰 `android.*`，可以直接单元测试。
 *
 * 只放 host 不放完整 Base URL（设计 §7.2）：同一家服务的 `/v1` 与 `/v1/`、`http` 与 `https`
 * 是同一个模型同一个价格，不该各存一份译文。代价是同一主机上的两个不同网关（同 host 不同路径，
 * 例如自建 `…/openai/v1` 与 `…/deepseek/v1`）会共用缓存 —— 这是设计里接受的取舍。
 */

package ua.acclorite.book_story.data.ai.cache

import ua.acclorite.book_story.data.ai.AI_PROMPT_VERSION
import ua.acclorite.book_story.data.ai.aiBaseUrlHost
import java.security.MessageDigest

/** 缓存行的类型。`wire` 是落库的稳定字符串（`translationcacheentity.kind`）。 */
enum class TranslationKind(val wire: String) {
    TRANSLATION("TRANSLATION"),
    GRAMMAR("GRAMMAR"),
    WORD("WORD")
}

/**
 * 缓存键。
 *
 * @param text 参与计算的正文；只在键里出现它的哈希，原文不进库。
 * @param promptVersion 默认取 [AI_PROMPT_VERSION]，提示词一改旧缓存自动失效。
 */
fun translationCacheKey(
    text: String,
    kind: TranslationKind,
    targetLanguage: String,
    model: String,
    baseUrl: String,
    promptVersion: Int = AI_PROMPT_VERSION
): String {
    val material = "v$promptVersion" +
            "|${kind.wire}" +
            "|$targetLanguage" +
            "|$model" +
            "|${translationBaseUrlHost(baseUrl)}" +
            "|${normalizeCacheText(text)}"
    return sha256Hex(material)
}

/**
 * 键里的 host 与 `translationcacheentity.baseUrlHost` 用同一个值。
 *
 * 复用 [aiBaseUrlHost]（会先规范 Base URL：没写协议补 `https://`、补尾部 `/`），
 * 规范化本身不抛异常；解析不出 host（畸形地址）时返回空串而不是 null，键照样是确定的。
 * 空 Base URL 会被规范成默认地址，因此它的 host 是 `api.openai.com` —— 与实际发出的请求一致。
 */
fun translationBaseUrlHost(baseUrl: String): String = aiBaseUrlHost(baseUrl).orEmpty()

/**
 * 正文归一：trim + 统一换行。
 *
 * **不合并内部空白**：两个空格与一个空格是不同文本，合并会让译文张冠李戴。
 * `.trim()` 去掉首尾空白（含 `\r\n` 结尾残留下来的空行），换行统一成 `\n` 之后
 * 复制到设备的 CRLF 段落与原文的 LF 段落命中同一行缓存。
 */
fun normalizeCacheText(text: String): String =
    text.replace("\r\n", "\n").replace('\r', '\n').trim()

private const val HEX_DIGITS = "0123456789abcdef"

/**
 * 小写十六进制的 SHA-256。
 *
 * 不用 `"%02x".format(byte)`：`String.format` 用默认 Locale，部分 Locale 下数字会被本地化，
 * 同一个键在换系统语言后就变了。
 */
private fun sha256Hex(value: String): String {
    val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
    val builder = StringBuilder(bytes.size * 2)
    for (byte in bytes) {
        val value = byte.toInt() and 0xFF
        builder.append(HEX_DIGITS[value ushr 4]).append(HEX_DIGITS[value and 0x0F])
    }
    return builder.toString()
}
