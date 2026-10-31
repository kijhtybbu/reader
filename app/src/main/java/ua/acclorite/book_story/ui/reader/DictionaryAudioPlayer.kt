/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * fork 新增：词条自带音频的播放器（设计 §3.7）。
 *
 * 为什么是 `MediaPlayer` 而不是 `ExoPlayer`：发音是 cacheDir 里的几十 KB 单文件，只需要
 * 「播一段、可中断」；MediaPlayer 不需要新依赖，`AUDIOFOCUS_GAIN_TRANSIENT` 也足够表达
 * 「读一个词，读完就走」。
 *
 * 音频焦点（§3.7）：
 *  - 申请 `AUDIOFOCUS_GAIN_TRANSIENT`：发音是短暂的，不该像音乐播放器那样长期持有焦点；
 *  - 失去焦点（来电、导航播报、别的应用开播）一律 `stop()`，不降音继续播 —— 发音被压到
 *    听不清比直接停更糟。
 *
 * 格式限制：系统播不了 `.spx`（Speex）。这类文件 `prepare()` 会抛异常，[play] 因此返回 false，
 * 由 [DictionaryAudioController] 回落到系统 TTS 并提示（设计 §3.11）。
 *
 * [AudioOutput] 这个接口只为了让状态机（[DictionaryAudioController]）能在 JVM 上测：
 * 真实实现要真机才跑得起来，fake 记录调用顺序就能钉住「先停再播」「失败回落」。
 */

package ua.acclorite.book_story.ui.reader

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import ua.acclorite.book_story.core.log.logW
import java.io.File

private const val TAG = "DictionaryAudio"

/** 播放词条自带音频的出口。 */
internal interface AudioOutput {

    /**
     * 播放 [file]。
     *
     * @return false 表示这次播不了（文件被删、`.spx` 这类系统不支持的格式、拿不到音频焦点）。
     *   调用方据此试下一条候选或回落 TTS。
     */
    fun play(file: File): Boolean

    /** 停止播放并释放播放器（没有在播时是空操作）。 */
    fun stop()

    /** 释放所有系统资源（卡片销毁时）。 */
    fun release()
}

/**
 * `MediaPlayer` 实现。
 *
 * `play` 是**同步**的：`setDataSource` + `prepare` 走本地 cacheDir 上的小文件，耗时在毫秒级，
 * 而「格式不支持」只有在 `prepare` 抛异常时才知道 —— 换成 `prepareAsync` 就没法当场给出
 * false 让控制器回落 TTS。
 */
internal class DictionaryAudioPlayer(context: Context) : AudioOutput {

    private val appContext = context.applicationContext

    private val audioManager: AudioManager? =
        appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    private var player: MediaPlayer? = null
    private var focusRequest: AudioFocusRequest? = null

    /** 焦点丢失（所有负值都是丢失）就停。 */
    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        if (change < 0) stop()
    }

    override fun play(file: File): Boolean {
        stop()
        val mediaPlayer = MediaPlayer()
        return try {
            mediaPlayer.setAudioAttributes(AUDIO_ATTRIBUTES)
            mediaPlayer.setDataSource(file.absolutePath)
            mediaPlayer.prepare()
            if (!requestFocus()) {
                releaseQuietly(mediaPlayer)
                return false
            }
            mediaPlayer.setOnCompletionListener { stop() }
            mediaPlayer.setOnErrorListener { _, _, _ ->
                stop()
                true
            }
            mediaPlayer.start()
            player = mediaPlayer
            true
        } catch (e: Exception) {
            logW(TAG, "Could not play ${file.name}: ${e.message}")
            releaseQuietly(mediaPlayer)
            abandonFocus()
            false
        }
    }

    override fun stop() {
        val mediaPlayer = player
        player = null
        if (mediaPlayer != null) {
            runCatching { if (mediaPlayer.isPlaying) mediaPlayer.stop() }
            releaseQuietly(mediaPlayer)
        }
        abandonFocus()
    }

    override fun release() = stop()

    private fun requestFocus(): Boolean {
        // 没有音频服务（极少数定制 ROM）：照样播，只是不参与焦点。
        val manager = audioManager ?: return true
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(AUDIO_ATTRIBUTES)
            .setWillPauseWhenDucked(true)
            .setOnAudioFocusChangeListener(focusListener)
            .build()
        focusRequest = request
        return manager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonFocus() {
        val request = focusRequest ?: return
        focusRequest = null
        audioManager?.abandonAudioFocusRequest(request)
    }

    private fun releaseQuietly(mediaPlayer: MediaPlayer) {
        runCatching { mediaPlayer.reset() }
        runCatching { mediaPlayer.release() }
    }

    private companion object {
        val AUDIO_ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
    }
}
