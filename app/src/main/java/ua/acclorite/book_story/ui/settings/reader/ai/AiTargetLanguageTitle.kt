/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 */

package ua.acclorite.book_story.ui.settings.reader.ai

import androidx.annotation.StringRes
import ua.acclorite.book_story.R
import ua.acclorite.book_story.data.ai.AiTargetLanguage

/** 数据层只给语言代码/提示词名；展示文案留在 UI 层。 */
@StringRes
internal fun AiTargetLanguage.titleRes(): Int = when (this) {
    AiTargetLanguage.FOLLOW_UI -> R.string.ai_target_follow_ui
    AiTargetLanguage.SIMPLIFIED_CHINESE -> R.string.ai_target_zh_cn
    AiTargetLanguage.TRADITIONAL_CHINESE -> R.string.ai_target_zh_tw
    AiTargetLanguage.ENGLISH -> R.string.ai_target_en
    AiTargetLanguage.JAPANESE -> R.string.ai_target_ja
    AiTargetLanguage.KOREAN -> R.string.ai_target_ko
}
