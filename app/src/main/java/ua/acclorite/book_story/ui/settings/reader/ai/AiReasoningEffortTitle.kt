/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * fork 新增：思考强度选项的文案（`reasoning_effort`）。数据层只放发给接口的值。
 */

package ua.acclorite.book_story.ui.settings.reader.ai

import androidx.annotation.StringRes
import ua.acclorite.book_story.R
import ua.acclorite.book_story.data.ai.AiReasoningEffort

@StringRes
internal fun AiReasoningEffort.titleRes(): Int = when (this) {
    AiReasoningEffort.DEFAULT -> R.string.ai_reasoning_default
    AiReasoningEffort.OFF -> R.string.ai_reasoning_off
    AiReasoningEffort.LOW -> R.string.ai_reasoning_low
    AiReasoningEffort.MEDIUM -> R.string.ai_reasoning_medium
    AiReasoningEffort.HIGH -> R.string.ai_reasoning_high
}
