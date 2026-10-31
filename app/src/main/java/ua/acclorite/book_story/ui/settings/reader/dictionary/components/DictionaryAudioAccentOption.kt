/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * fork 新增：词条自带发音的口音偏好（设计 §3.8）。
 *
 * 与 `LookupGestureOption` 同一个形状：`ChipsWithTitle` + 一条说明。放在词典管理页而不是
 * 阅读设置里，因为它只影响「有真人录音的词典播哪一条」。
 *
 * 枚举 → 文案的映射写在这里（而不是 `DictionaryAudioAccent.kt`）：数据层/分类器不依赖
 * Android 资源，JVM 单测才能只断言枚举与挑选结果。
 */

package ua.acclorite.book_story.ui.settings.reader.dictionary.components

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import ua.acclorite.book_story.R
import ua.acclorite.book_story.ui.common.components.settings.ChipsWithTitle
import ua.acclorite.book_story.ui.common.helpers.LocalSettings
import ua.acclorite.book_story.ui.common.model.ListItem
import ua.acclorite.book_story.ui.reader.DictionaryAudioAccent
import ua.acclorite.book_story.ui.settings.components.SettingsSubcategoryNote

@Composable
fun DictionaryAudioAccentOption() {
    val settings = LocalSettings.current

    Column {
        ChipsWithTitle(
            title = stringResource(id = R.string.dictionary_audio_accent_title),
            chips = DictionaryAudioAccent.entries.map { accent ->
                ListItem(
                    item = accent,
                    title = stringResource(accent.title),
                    selected = accent == settings.dictionaryAudioAccent.value
                )
            },
            onClick = { settings.dictionaryAudioAccent.update(it) }
        )

        SettingsSubcategoryNote(text = stringResource(id = R.string.dictionary_audio_accent_note))
    }
}

@get:StringRes
private val DictionaryAudioAccent.title: Int
    get() = when (this) {
        DictionaryAudioAccent.AUTO -> R.string.dictionary_audio_accent_auto
        DictionaryAudioAccent.US -> R.string.dictionary_audio_accent_us
        DictionaryAudioAccent.UK -> R.string.dictionary_audio_accent_uk
    }
