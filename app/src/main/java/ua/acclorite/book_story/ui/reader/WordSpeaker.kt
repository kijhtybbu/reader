/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * fork 新增：取词卡片的系统 TTS 发音（第二阶段后续）。
 *
 * 只在用户第一次点喇叭时创建 TextToSpeech，避免每个进程启动都拉起 TTS 服务；
 * 卡片关闭时 shutdown，不在阅读页常驻。发音语言优先美音，系统没有英语语音包时
 * 通过 onUnavailable 给用户一个提示，而不是静默失败。
 */

package ua.acclorite.book_story.ui.reader

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

internal class WordSpeaker(context: Context) {

    private val appContext = context.applicationContext
    private var engine: TextToSpeech? = null
    private var ready = false
    private var pendingWord: String? = null
    private var pendingFailure: (() -> Unit)? = null

    /** 第一次调用会异步初始化；初始化中的重复点击只保留最后一个词。 */
    fun speak(word: String, onUnavailable: () -> Unit) {
        val normalized = word.trim()
        if (normalized.isEmpty()) return

        val current = engine
        if (current == null) {
            pendingWord = normalized
            pendingFailure = onUnavailable
            engine = TextToSpeech(appContext) { status ->
                val tts = engine
                if (tts != null) {
                    if (status != TextToSpeech.SUCCESS || !configure(tts)) {
                        ready = false
                        pendingFailure?.invoke()
                        pendingWord = null
                        pendingFailure = null
                    } else {
                        ready = true
                        pendingWord?.let { play(tts, it) }
                        pendingWord = null
                        pendingFailure = null
                    }
                }
            }
        } else if (ready) {
            play(current, normalized)
        } else {
            pendingWord = normalized
            pendingFailure = onUnavailable
        }
    }

    private fun configure(tts: TextToSpeech): Boolean {
        val language = when {
            tts.isLanguageAvailable(Locale.US) >= TextToSpeech.LANG_AVAILABLE -> Locale.US
            tts.isLanguageAvailable(Locale.ENGLISH) >= TextToSpeech.LANG_AVAILABLE -> Locale.ENGLISH
            else -> return false
        }
        if (tts.setLanguage(language) < TextToSpeech.LANG_AVAILABLE) return false
        tts.setSpeechRate(0.9f)
        tts.setPitch(1.0f)
        return true
    }

    private fun play(tts: TextToSpeech, word: String) {
        tts.speak(word, TextToSpeech.QUEUE_FLUSH, null, "lookup-word")
    }

    fun shutdown() {
        engine?.stop()
        engine?.shutdown()
        engine = null
        ready = false
        pendingWord = null
        pendingFailure = null
    }
}
