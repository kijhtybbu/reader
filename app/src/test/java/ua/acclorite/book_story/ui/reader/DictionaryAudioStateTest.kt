/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * fork 新增：卡片发音状态机（设计 §3.7）。
 *
 * 两条发音路径（词条自带录音 / 系统 TTS）必须互斥，顺序错了就是「点了喇叭听到上一句」。
 * 真实实现要真机（MediaPlayer、音频焦点），所以这里全部用 fake，只钉行为：
 *  - 连点不叠加：新的一次播放一定先 stop 再 play；
 *  - 播放中关卡片：迟到的文件不能再播；
 *  - 候选全失败：回落 TTS，并给出 toast（不能静默换成机器音）；
 *  - 播放顺序：口音偏好只决定先试哪一条，其余候选仍然可播。
 */

package ua.acclorite.book_story.ui.reader

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class DictionaryAudioStateTest {

    /** 两条路共用一个事件列表：断言的就是它们的**顺序**。 */
    private class EventLog {
        val events = mutableListOf<String>()
    }

    private class FakeAudioOutput(
        private val log: EventLog,
        private val playable: (File) -> Boolean = { true }
    ) : AudioOutput {
        override fun play(file: File): Boolean {
            log.events += "play:${file.name}"
            return playable(file)
        }

        override fun stop() {
            log.events += "audio.stop"
        }

        override fun release() {
            log.events += "audio.release"
        }
    }

    private class FakeSpeech(private val log: EventLog) : SpeechOutput {
        var unavailable = false

        override fun speak(word: String, onUnavailable: () -> Unit) {
            log.events += "speak:$word"
            if (unavailable) onUnavailable()
        }

        override fun stop() {
            log.events += "tts.stop"
        }
    }

    private fun file(path: String): File = File(path)

    @Test
    fun `consecutive clicks stop the previous recording before playing`() = runTest {
        val log = EventLog()
        val controller = DictionaryAudioController(
            scope = this,
            audio = FakeAudioOutput(log),
            speech = FakeSpeech(log),
            onSpeechUnavailable = {},
            onAudioUnavailable = {}
        )

        controller.play("apple", listOf("apple__gb_1.mp3")) { file(it) }
        advanceUntilIdle()
        controller.play("apple", listOf("apple__gb_1.mp3")) { file(it) }
        advanceUntilIdle()

        // 每组都是「先停录音 + 停 TTS，再播」：第二次不会与第一次叠在一起
        assertEquals(
            listOf(
                "audio.stop", "tts.stop", "play:apple__gb_1.mp3",
                "audio.stop", "tts.stop", "play:apple__gb_1.mp3"
            ),
            log.events
        )
    }

    @Test
    fun `releasing while the recording is loading drops the late file`() = runTest {
        val log = EventLog()
        val controller = DictionaryAudioController(
            scope = this,
            audio = FakeAudioOutput(log),
            speech = FakeSpeech(log),
            onSpeechUnavailable = {},
            onAudioUnavailable = {}
        )
        val gate = CompletableDeferred<File?>()

        controller.play("apple", listOf("apple__gb_1.mp3")) { gate.await() }
        runCurrent()
        // 还在等文件：此时关卡片
        controller.release()
        gate.complete(file("apple__gb_1.mp3"))
        advanceUntilIdle()

        assertFalse(log.events.any { it.startsWith("play:") })
        assertEquals("audio.release", log.events.last())
    }

    @Test
    fun `a recording that cannot be played falls back to the system voice with a hint`() = runTest {
        val log = EventLog()
        var unavailableHints = 0
        val controller = DictionaryAudioController(
            scope = this,
            audio = FakeAudioOutput(log) { false },
            speech = FakeSpeech(log),
            onSpeechUnavailable = {},
            onAudioUnavailable = { unavailableHints++ }
        )

        controller.play("apple", listOf("a.mp3", "b.mp3")) { file(it) }
        advanceUntilIdle()

        assertEquals(
            listOf(
                "audio.stop", "tts.stop",
                "play:a.mp3", "play:b.mp3",
                // 回落到 TTS 前再停一次录音：两条路永不重叠
                "audio.stop", "speak:apple"
            ),
            log.events
        )
        assertEquals(1, unavailableHints)
    }

    @Test
    fun `a missing resource is skipped without falling back yet`() = runTest {
        val log = EventLog()
        var unavailableHints = 0
        val controller = DictionaryAudioController(
            scope = this,
            audio = FakeAudioOutput(log),
            speech = FakeSpeech(log),
            onSpeechUnavailable = {},
            onAudioUnavailable = { unavailableHints++ }
        )

        // 第一条在包里不存在（真实语料里 alway 都在）：直接试下一条，不弹 toast
        controller.play("apple", listOf("missing.mp3", "present.mp3")) { path ->
            if (path == "missing.mp3") null else file(path)
        }
        advanceUntilIdle()

        assertEquals(
            listOf("audio.stop", "tts.stop", "play:present.mp3"),
            log.events
        )
        assertEquals(0, unavailableHints)
    }

    @Test
    fun `TTS unavailable is forwarded to the card`() = runTest {
        val log = EventLog()
        var speechUnavailable = 0
        val speech = FakeSpeech(log).apply { unavailable = true }
        val controller = DictionaryAudioController(
            scope = this,
            audio = FakeAudioOutput(log),
            speech = speech,
            onSpeechUnavailable = { speechUnavailable++ },
            onAudioUnavailable = {}
        )

        controller.speak("apple")

        // 先停录音再点 TTS：两条路互斥
        assertEquals(listOf("audio.stop", "speak:apple"), log.events)
        assertEquals(1, speechUnavailable)
    }

    @Test
    fun `the accent preference only reorders the candidates`() {
        // 真实牛津高阶 9 的候选顺序（6 个文件都在包里）：gb/us 是词条音，brs/uss/gbs/ams 是例句音。
        val paths = listOf(
            "apple__gb_1.mp3",
            "apple__us_1.mp3",
            "_apple__brs_2.mp3",
            "_apple__uss_2.mp3",
            "_apple__gbs_2.mp3",
            "_apple__ams_2.mp3"
        )
        val candidates = paths.map { path ->
            AudioCandidate(path = path, url = dictAudioUrl("oald", path))
        }

        // AUTO：按 HTML 顺序（= 词条自身的链接顺序），不猜口音
        assertEquals(paths, audioPlaybackOrder(candidates, DictionaryAudioAccent.AUTO))

        // 选了美音/英音：命中的那条排第一，其余仍按原顺序跟在后面（播不了还有别的可试）
        assertEquals(
            listOf(
                "apple__us_1.mp3",
                "apple__gb_1.mp3",
                "_apple__brs_2.mp3",
                "_apple__uss_2.mp3",
                "_apple__gbs_2.mp3",
                "_apple__ams_2.mp3"
            ),
            audioPlaybackOrder(candidates, DictionaryAudioAccent.US)
        )
        assertEquals("apple__gb_1.mp3", audioPlaybackOrder(candidates, DictionaryAudioAccent.UK).first())

        // 没有候选：空列表（调用方据此直接用 TTS）
        assertTrue(audioPlaybackOrder(emptyList(), DictionaryAudioAccent.US).isEmpty())
    }
}
