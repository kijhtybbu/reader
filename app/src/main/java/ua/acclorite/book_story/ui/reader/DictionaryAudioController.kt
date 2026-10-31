/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * fork 新增：卡片发音的状态机（设计 §3.7）。
 *
 * 卡片上有两条发音路径，来自两个完全不同的系统：
 *  - 词条自带的录音（`.mdd` 里的 `.mp3`）：落盘后交给 [AudioOutput]（`MediaPlayer`）；
 *  - 系统 TTS（[SpeechOutput]，`WordSpeaker`）：词条没有录音、或录音播不了时的回落。
 *
 * 两条路必须互斥，而且必须**先停再说**：先停的另一条路，后播的才不会和上一句叠在一起。
 * 这里的顺序约定：
 *  - 播文件前：停播放器 + 停 TTS；
 *  - 点 TTS 前：停播放器。
 *
 * 一个词条可能有多条候选（英音/美音/例句音），它们不保证都在包里、也不保证系统都能解
 * （`.spx` 播不了）。所以 [play] 依次试，全部失败才回落 TTS，并且用 [onAudioUnavailable]
 * 提示一条 toast —— 静默换成 TTS 会让用户以为点错了按钮。
 *
 * 无 `android.*`：JVM 单测覆盖（DictionaryAudioStateTest），fake 记录调用顺序。
 */

package ua.acclorite.book_story.ui.reader

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File

/** 系统朗读的出口（`WordSpeaker` 实现）。与 [AudioOutput] 一起让互斥顺序可测。 */
internal interface SpeechOutput {

    /** 朗读 [word]；TTS 不可用（没有语音包、初始化超时）时回调 [onUnavailable]。 */
    fun speak(word: String, onUnavailable: () -> Unit)

    /** 停掉当前朗读，保留引擎（下次点喇叭不用重新初始化）。 */
    fun stop()
}

/**
 * 卡片发音的状态机：串行化两条发音路径，处理候选回落与「连点不叠加」。
 *
 * @param scope 播放所在的协程作用域（卡片是 `rememberCoroutineScope()`）。取消它等于释放。
 * @param onSpeechUnavailable TTS 不可用（卡片据此弹「安装语音包」对话框）。
 * @param onAudioUnavailable 词条录音全部播不了（卡片据此弹 toast）。
 */
internal class DictionaryAudioController(
    private val scope: CoroutineScope,
    private val audio: AudioOutput,
    private val speech: SpeechOutput,
    private val onSpeechUnavailable: () -> Unit,
    private val onAudioUnavailable: () -> Unit
) {

    private var job: Job? = null

    /**
     * 播放词条自带的录音。
     *
     * @param paths 候选路径，已按口音偏好排好序（[audioPlaybackOrder]）。
     * @param loadFile 把路径落成本地文件（`DictionaryResourceStore.audioFile`）；缺失返回 null。
     *
     * 空列表直接走 TTS：这与「词条没有录音」是同一条路（旧行为），不弹 toast。
     */
    fun play(word: String, paths: List<String>, loadFile: suspend (path: String) -> File?) {
        // 连点不叠加：上一次还在加载/播放的这一律作废，下面的 audio.stop() 保证不会两段同响。
        job?.cancel()
        job = scope.launch {
            audio.stop()
            speech.stop()
            for (path in paths) {
                val file = loadFile(path) ?: continue
                if (audio.play(file)) return@launch
            }
            if (paths.isNotEmpty()) onAudioUnavailable()
            speakNow(word)
        }
    }

    /** 系统朗读：先停录音（否则 TTS 会和录音叠在一起）。 */
    fun speak(word: String) {
        job?.cancel()
        job = null
        speakNow(word)
    }

    /** 停止一切播放（切词条 / 换命中的词典 / 关卡片）。 */
    fun stop() {
        job?.cancel()
        job = null
        audio.stop()
        speech.stop()
    }

    /** 释放（卡片销毁）。 */
    fun release() {
        stop()
        audio.release()
    }

    private fun speakNow(word: String) {
        audio.stop()
        speech.speak(word, onSpeechUnavailable)
    }
}

/**
 * 播放顺序：口音偏好命中的那条排第一（[pick]），其余按 HTML 里的原顺序跟在后面。
 *
 * 为什么不是只播一条：单条候选照样可能播不了（包里没这个文件、`.spx` 系统不支持），
 * 有别的口音可试就没必要立刻回落到机器音；全失败才由 [DictionaryAudioController] 回落。
 */
internal fun audioPlaybackOrder(
    candidates: List<AudioCandidate>,
    preference: DictionaryAudioAccent
): List<String> {
    val preferred = pick(candidates, preference) { it.path } ?: return emptyList()
    return buildList(candidates.size) {
        add(preferred.path)
        candidates.forEach { candidate ->
            if (candidate.path != preferred.path) add(candidate.path)
        }
    }
}
