/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 */

package ua.acclorite.book_story.data.ai

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class AiTargetLanguageTest {

    @Test
    fun fromNameFallsBackToFollowUi() {
        assertEquals(AiTargetLanguage.ENGLISH, AiTargetLanguage.fromName("ENGLISH"))
        assertEquals(AiTargetLanguage.FOLLOW_UI, AiTargetLanguage.fromName(null))
        assertEquals(AiTargetLanguage.FOLLOW_UI, AiTargetLanguage.fromName("deleted-value"))
    }

    @Test
    fun fixedLanguagesHaveStablePromptNames() {
        assertEquals("Simplified Chinese (zh-CN)", AiTargetLanguage.SIMPLIFIED_CHINESE.promptName())
        assertEquals("Traditional Chinese (zh-TW)", AiTargetLanguage.TRADITIONAL_CHINESE.promptName())
        assertEquals("English (en)", AiTargetLanguage.ENGLISH.promptName())
        assertEquals("Japanese (ja)", AiTargetLanguage.JAPANESE.promptName())
        assertEquals("Korean (ko)", AiTargetLanguage.KOREAN.promptName())
    }

    @Test
    fun followUiResolvesChineseVariantFromLocale() {
        assertEquals(
            "Simplified Chinese (zh-CN)",
            AiTargetLanguage.FOLLOW_UI.promptName(Locale.SIMPLIFIED_CHINESE)
        )
        assertEquals(
            "Traditional Chinese (zh-TW)",
            AiTargetLanguage.FOLLOW_UI.promptName(Locale.TRADITIONAL_CHINESE)
        )
        assertEquals(
            "Traditional Chinese (zh-TW)",
            AiTargetLanguage.FOLLOW_UI.promptName(Locale.forLanguageTag("zh-HK"))
        )
    }

    @Test
    fun followUiResolvesOtherLanguagesInEnglish() {
        assertEquals("Japanese (ja)", AiTargetLanguage.FOLLOW_UI.promptName(Locale.JAPANESE))
        assertEquals("Korean (ko)", AiTargetLanguage.FOLLOW_UI.promptName(Locale.KOREAN))
        assertEquals("French (fr-FR)", AiTargetLanguage.FOLLOW_UI.promptName(Locale.FRANCE))
    }

    @Test
    fun blankLanguageNameFallsBackToEnglish() {
        // 未定语言（Locale.forLanguageTag("") / "und"）拿不到语言名，不能拼出「Translate into  ()」。
        assertEquals("English (en)", AiTargetLanguage.FOLLOW_UI.promptName(Locale.forLanguageTag("")))
        assertEquals("English (en)", AiTargetLanguage.FOLLOW_UI.promptName(Locale.forLanguageTag("und")))
    }
}
