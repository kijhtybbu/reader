/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * fork 新增：一次翻译/语法请求用到的配置快照（第二阶段 S8，设计 §7.3/§8）。
 *
 * 为什么要快照而不是每次现取：缓存键必须与真正发出的请求使用同一份
 * model/baseUrl/目标语言。设置页改完模型立刻打开对话框时，如果键和请求各取一次配置，
 * 会出现「用旧模型算键、用新模型发请求」，译文会写到一个下次再也命不中的键上。
 *
 * 代价：请求进行中改设置，这一次仍按旧配置走完（缓存键与请求仍然一致），下一次 open 生效。
 */

package ua.acclorite.book_story.data.ai

import ua.acclorite.book_story.data.settings.SettingsManager
import javax.inject.Inject
import javax.inject.Singleton

/**
 * @param model 已 trim；空值由设置层回落 [DEFAULT_AI_MODEL]。
 * @param baseUrl 已规范化（补协议、补尾部 `/`）。
 * @param targetLanguageName 提示词里的语言名，同时是缓存键的一部分；[AiTargetLanguage.FOLLOW_UI]
 *   已按界面语言解析。
 * @param grammarEnabled 关闭时对话框不显示语法标签（设置项 `ai_grammar_enabled`）。
 */
data class AiRequestConfig(
    val model: String,
    val baseUrl: String,
    val targetLanguage: AiTargetLanguage,
    val targetLanguageName: String,
    val grammarEnabled: Boolean
)

/** 从设置里取一次快照。[AiTranslationRepository] 仍按自己的 lambda 现取配置，两者同源。 */
@Singleton
class AiRequestConfigProvider @Inject constructor(
    private val settings: SettingsManager
) {
    fun current(): AiRequestConfig {
        val targetLanguage = settings.aiTargetLanguage.lastValue
        return AiRequestConfig(
            model = settings.aiModel.lastValue,
            baseUrl = settings.aiBaseUrl.lastValue,
            targetLanguage = targetLanguage,
            targetLanguageName = targetLanguage.promptName(settings.language.lastValue.locale),
            grammarEnabled = settings.aiGrammarEnabled.lastValue
        )
    }
}
