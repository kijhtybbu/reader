/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * fork 新增：AI 翻译的目标语言（第二阶段 S6，设计 §6.1）。
 *
 * 设置里存枚举名（稳定、与文案无关）；提示词里用 [promptName]。
 * [FOLLOW_UI] 不存具体语言：它在每次请求时按界面语言解析，用户换界面语言后立即生效。
 *
 * 自定义语言（设计 §6.1 的可选项）本期不做：枚举无法同时表达「预设」与「自由文本」，
 * 为一个可选功能引入第二份设置值不值得，留给需要时再评估。
 */

package ua.acclorite.book_story.data.ai

import java.util.Locale

enum class AiTargetLanguage(
    val code: String?,
    private val fixedPromptName: String?
) {
    FOLLOW_UI(null, null),
    SIMPLIFIED_CHINESE("zh-CN", "Simplified Chinese (zh-CN)"),
    TRADITIONAL_CHINESE("zh-TW", "Traditional Chinese (zh-TW)"),
    ENGLISH("en", "English (en)"),
    JAPANESE("ja", "Japanese (ja)"),
    KOREAN("ko", "Korean (ko)");

    /**
     * 写进提示词的语言名。[FOLLOW_UI] 按 [uiLocale] 解析，其余返回固定名称。
     * 固定名称带语言代码，避免模型把「中文」理解成繁体或反过来。
     */
    fun promptName(uiLocale: Locale = Locale.getDefault()): String =
        fixedPromptName ?: promptNameForLocale(uiLocale)

    companion object {
        /** 认不出的存量值回落 [FOLLOW_UI]：翻译总得有个目标语言，不能静默失效。 */
        fun fromName(name: String?): AiTargetLanguage =
            entries.firstOrNull { it.name == name } ?: FOLLOW_UI

        private fun promptNameForLocale(locale: Locale): String {
            val language = locale.language.lowercase(Locale.ROOT)
            return when (language) {
                "zh" -> if (locale.isTraditionalChineseLocale()) {
                    "Traditional Chinese (zh-TW)"
                } else {
                    "Simplified Chinese (zh-CN)"
                }

                "en" -> "English (en)"
                "ja" -> "Japanese (ja)"
                "ko" -> "Korean (ko)"
                else -> {
                    // 语言名拿不到（未定语言 / 脏 Locale）时不能拼出「Translate into  ()」，
                    // 提示词宁可变回英语，也不要给模型一个空目标语言。
                    val tag = locale.toLanguageTag()
                    val displayName = locale.getDisplayLanguage(Locale.ENGLISH)
                    if (tag.isBlank() || displayName.isBlank()) "English (en)" else "$displayName ($tag)"
                }
            }
        }

        private fun Locale.isTraditionalChineseLocale(): Boolean {
            return country.equals("TW", ignoreCase = true) ||
                country.equals("HK", ignoreCase = true) ||
                country.equals("MO", ignoreCase = true)
        }
    }
}
