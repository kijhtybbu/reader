/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * fork 新增：词条自带音频的口音判定（设计 §3.8）。
 *
 * 口音没有元数据可读 —— MDD 里只有路径，作者怎么命名全靠自觉（`_us` / `__gb` / `\us\` /
 * `en-us` / `ame` 都见过）。所以这里只做一件事：把路径切成 token，按词表投票。
 * **判定失败不影响播放，只影响播哪一个**：认不出就 UNKNOWN，`pick` 继续往下回落，
 * 绝不会因为「不知道是英音还是美音」而不放音。
 *
 * 词表分两批：设计 §3.8 给的初始词表，以及**用本机真实牛津高阶 9 语料校准补上的**
 * `uss`/`ams`（美）、`gbs`/`brs`（英）—— 见 [US_TOKENS] / [UK_TOKENS] 的注释。
 *
 * 纯接口类文件：不引 android.*，JVM 单测直接覆盖（DictionaryAudioAccentTest）。
 * 设置项在 SettingsManager.dictionaryAudioAccent，UI chip 在词典管理页
 * （`DictionaryAudioAccentOption`，词典列表下方）。
 */

package ua.acclorite.book_story.ui.reader

/**
 * 用户在设置里选的口音偏好。
 *
 * 不能降到 `internal`：`SettingsManager.dictionaryAudioAccent` 是 public 属性，其类型参数
 * 引用了本枚举（Kotlin 不允许 public API 暴露 internal 类型）。
 */
enum class DictionaryAudioAccent {
    /** 不筛：按候选顺序播第一个（见 [pick] 的 KDoc）。 */
    AUTO,
    US,
    UK;

    companion object {
        /**
         * 解析设置里存的字符串（存的是 [name]）。认不出的值（旧版本残留、手改 DataStore、
         * 恢复备份）一律回落 [AUTO] —— 用 `valueOf` 会抛异常，把整个设置流打断。
         */
        fun fromName(name: String?): DictionaryAudioAccent =
            entries.firstOrNull { it.name == name } ?: AUTO
    }
}

/** 一条音频路径的口音归属。[UNKNOWN] = 词表没命中，不代表路径坏了。 */
internal enum class AudioAccent { US, UK, UNKNOWN }

/**
 * 口音词表，落到「分词后等价形式」。
 *
 * 设计 §3.8 的初始词表：
 * 美 `us|usa|_us|__us|us_|\us\|en-us|ame` → token `us` / `usa` / `ame`；
 * 英 `gb|uk|_gb|__gb|_uk|\gb\|en-gb|bre` → token `gb` / `uk` / `bre`。
 * 下划线/反斜杠/连字符版本全部由分词消掉（`_us`、`\us\`、`en-us` 都只剩一个 `us`/`gb`），
 * 所以词表只留真正不同的写法。
 *
 * 真实语料校准（设计 §3.8 要求「用真实 OALD9/字源 实测后补词表」，命名没有规范）：
 * 牛津高阶英汉双解词典（第 9 版）的发音资源里，词条音频是 `apple__gb_1.mp3` /
 * `apple__us_1.mp3`，例句音频却是 `_apple__brs_2.mp3`（英）、`_apple__uss_2.mp3`（美）、
 * `_apple__gbs_2.mp3`（英）、`_apple__ams_2.mp3`（美）—— 即 **`uss`/`ams` = 美音、
 * `gbs`/`brs` = 英音**（b/g = British，u/a = US；尾部 `s` 是 example/sentence）。
 * 只补这 4 个整 token，词表外的一律 UNKNOWN，不猜。
 */
private val US_TOKENS = setOf("us", "usa", "ame", "uss", "ams")
private val UK_TOKENS = setOf("gb", "uk", "bre", "gbs", "brs")

/** 分隔符 = 任何非 ASCII 字母数字的字符（`\` `/` `_` `-` `.` `:` 都在内，大小写先统一）。 */
private val TOKEN_SEPARATOR = Regex("[^a-z0-9]+")

private fun tokensOf(path: String): List<String> =
    path.lowercase().split(TOKEN_SEPARATOR).filter { it.isNotEmpty() }

/**
 * 判定一条音频路径属于哪种口音。
 *
 * 匹配单位是**完整 token**，不是子串：`business`、`used`、`gbu`、`usage` 都不会被当成 `us`，
 * `ambrose` / `amsterdam` 不会被当成 `ams`，`fuss` / `discuss` 不会被当成 `uss`，
 * `brson` / `webgbs` 也不会被当成 `brs` / `gbs` —— 这正是短 token 唯一安全的匹配方式。
 * 大小写不敏感；`\` 与 `/` 混用、`sound://` 前缀、`file:///` 前缀都不影响结果
 * （它们只是多几个不命中的 token）。
 *
 * 同一条路径里同时出现两个家族的 token（`\us\gb\x.mp3`，真实词典不会这么命名）时，
 * **从左到右第一个命中的 token 决定** —— 这里只需要确定性，不需要「更聪明」。
 */
internal fun classifyAccent(path: String): AudioAccent =
    tokensOf(path).firstNotNullOfOrNull { token ->
        when (token) {
            in US_TOKENS -> AudioAccent.US
            in UK_TOKENS -> AudioAccent.UK
            else -> null
        }
    } ?: AudioAccent.UNKNOWN

/** [AUTO] 没有具体口音可筛，返回 null。 */
private val DictionaryAudioAccent.preferredAccent: AudioAccent?
    get() = when (this) {
        DictionaryAudioAccent.AUTO -> null
        DictionaryAudioAccent.US -> AudioAccent.US
        DictionaryAudioAccent.UK -> AudioAccent.UK
    }

/** 另一种口音；[AudioAccent.UNKNOWN] 没有「另一半」。 */
private val AudioAccent.other: AudioAccent?
    get() = when (this) {
        AudioAccent.US -> AudioAccent.UK
        AudioAccent.UK -> AudioAccent.US
        AudioAccent.UNKNOWN -> null
    }

/**
 * 从候选里挑一条要播的音频（设计 §3.8 的回落顺序）。
 *
 * 回落顺序（[preference] 为 [DictionaryAudioAccent.US] / [DictionaryAudioAccent.UK] 时）：
 * **偏好命中 → 另一种口音 → UNKNOWN → 列表第一个**。
 * 也就是说这条链永远返回非 null（只要列表非空）：「认不出/没有偏好口音」绝不等于不放音，
 * 最后一步直接退回候选顺序，交给播放器自己判断能不能播。
 *
 * [DictionaryAudioAccent.AUTO] 视为**无偏好**：不分类、不筛，直接返回 [candidates] 的第一个
 * （即候选列表顺序 = 词条自身的链接顺序）。AUTO 下刻意不做「有美音就选美音」的猜测，
 * 否则用户没选口音时听到哪一个就取决于词表运气；要美音就显式选 [DictionaryAudioAccent.US]。
 *
 * @return null 仅当 [candidates] 为空。
 */
internal fun <T> pick(
    candidates: List<T>,
    preference: DictionaryAudioAccent,
    pathOf: (T) -> String
): T? {
    if (candidates.isEmpty()) return null
    val preferred = preference.preferredAccent ?: return candidates.first()

    // 每条候选只分类一次，之后按口音分区查找。
    val classified = candidates.map { classifyAccent(pathOf(it)) }
    for (accent in listOfNotNull(preferred, preferred.other, AudioAccent.UNKNOWN)) {
        val index = classified.indexOfFirst { it == accent }
        if (index >= 0) return candidates[index]
    }
    // 新增 AudioAccent 取值（第三/第四种口音）而它既不是 preferred、preferred.other 也不是
    // UNKNOWN 时才会走到这里 —— 保留这行防御，保证「列表非空就一定有返回值」。
    return candidates.first()
}

/** [pick] 的路径版：候选就是音频路径本身。 */
internal fun pick(paths: List<String>, preference: DictionaryAudioAccent): String? =
    pick(paths, preference) { it }
