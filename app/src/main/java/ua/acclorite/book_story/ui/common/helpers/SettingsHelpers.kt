/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 */

package ua.acclorite.book_story.ui.common.helpers

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import ua.acclorite.book_story.data.settings.SettingsManager
import ua.acclorite.book_story.data.translator.BookTranslatorStore
import ua.acclorite.book_story.data.typography.ReaderTypographyStore
import ua.acclorite.book_story.ui.reader.translator.LocalBookTranslator
import ua.acclorite.book_story.ui.reader.translator.LocalBookTranslatorStore
import ua.acclorite.book_story.ui.reader.translator.rememberGlobalBookTranslator
import ua.acclorite.book_story.ui.reader.typography.LocalReaderTypography
import ua.acclorite.book_story.ui.reader.typography.LocalReaderTypographyStore
import ua.acclorite.book_story.ui.reader.typography.rememberGlobalReaderTypography

val LocalSettings = staticCompositionLocalOf<SettingsManager> {
    error("No settings provided")
}

@Composable
fun ProvideSettings(
    settings: SettingsManager,
    typographyStore: ReaderTypographyStore,
    translatorStore: BookTranslatorStore,
    content: @Composable () -> Unit
) {
    // fork: 排版有「全局 / 本书」两套值。App 根部先注入全局态，阅读页再用
    // rememberBookReaderTypography 覆盖成本书态；12 个排版控件只认这一个来源。
    val typography = rememberGlobalReaderTypography(settings)
    // fork: 翻译器行为按书、默认关闭。全局设置页不渲染这两个开关，这里只做兜底。
    val translator = rememberGlobalBookTranslator()

    CompositionLocalProvider(
        LocalSettings provides settings,
        LocalReaderTypographyStore provides typographyStore,
        LocalReaderTypography provides typography,
        LocalBookTranslatorStore provides translatorStore,
        LocalBookTranslator provides translator
    ) {
        content()
    }
}
