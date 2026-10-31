/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * fork 新增：音频口音分类器与选取回落（设计 §3.8）。
 *
 * 钉住三件事：
 *  - 各词典家族真实出现过的命名（`_us` / `__gb` / `\us\` / `en-us` / `ame` / `bre`，以及从本机
 *    真实牛津高阶 9 校准出的 `uss`/`ams`/`gbs`/`brs`）都分类正确；
 *  - 短 token 只按 token 边界匹配：`business` / `used` / `gbu` / `ambrose` / `discuss` 不能被误判；
 *  - 回落链「偏好命中 → 另一种口音 → UNKNOWN → 列表第一个」，AUTO 走列表顺序。
 */

package ua.acclorite.book_story.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DictionaryAudioAccentTest {

    /* ------ classifyAccent：词表 ------ */

    @Test
    fun `US family names classify as US`() {
        val paths = listOf(
            "sound://us/apple.mp3",          // 目录式
            "\\us\\apple.mp3",               // 反斜杠目录式（设计词表里的 `\us\`）
            "apple_us.mp3",                  // 后缀式
            "apple__us.mp3",                 // 双下划线
            "apple_us_1.mp3",                // `us` + 序号
            "sound://en-us/apple.mp3",       // 语言标签
            "\\ame\\apple.wav",              // ame / bre 家族
            "apple_USA.mp3"                  // 大小写不敏感 + usa
        )
        paths.forEach { assertEquals(it, AudioAccent.US, classifyAccent(it)) }
    }

    @Test
    fun `UK family names classify as UK`() {
        val paths = listOf(
            "sound://gb/apple.mp3",
            "\\gb\\apple.mp3",
            "apple__gb.mp3",
            "apple_uk.mp3",
            "\\uk\\apple.mp3",
            "sound://en-gb/apple.mp3",
            "\\bre\\apple.wav",
            "Apple_GB.mp3"
        )
        paths.forEach { assertEquals(it, AudioAccent.UK, classifyAccent(it)) }
    }

    @Test
    fun `real OALD9 example-sentence families classify correctly`() {
        // 本机真实牛津高阶 9 的 6 个命名家族（词条音频 + 例句音频）。
        // 前两个在设计词表里；后四个是实测校准出来的，不补就会全部掉进 UNKNOWN。
        val us = listOf(
            "apple__us_1.mp3",
            "_apple__uss_2.mp3",
            "_apple__ams_2.mp3",
            "_abandon__ams_5.mp3",
            "_abandon__uss_4.mp3"
        )
        val uk = listOf(
            "apple__gb_1.mp3",
            "_apple__brs_2.mp3",
            "_apple__gbs_2.mp3",
            "_abandon__brs_6.mp3",
            "_abandon__gbs_3.mp3"
        )
        us.forEach { assertEquals(it, AudioAccent.US, classifyAccent(it)) }
        uk.forEach { assertEquals(it, AudioAccent.UK, classifyAccent(it)) }
        // 带 sound:// 前缀 / 反斜杠目录的同一批名字也要稳
        assertEquals(AudioAccent.UK, classifyAccent("sound://_apple__brs_2.mp3"))
        assertEquals(AudioAccent.US, classifyAccent("\\sound\\_apple__ams_2.mp3"))
    }

    @Test
    fun `paths without an accent token are unknown`() {
        val paths = listOf(
            "sound://apple.mp3",
            "\\sound\\apple.mp3",
            "file:///data/dict/apple.mp3",
            "\\pic\\apple.png",
            ""
        )
        paths.forEach { assertEquals(it, AudioAccent.UNKNOWN, classifyAccent(it)) }
    }

    @Test
    fun `short tokens do not match inside longer words`() {
        // 这就是「必须按 token 边界匹配」的理由：子串匹配会把下面全部误判成英/美音。
        val paths = listOf(
            "sound://business.mp3",
            "used.mp3",
            "usage_1.mp3",
            "gbu.mp3",
            "\\gbu\\apple.mp3",
            "gust.mp3",
            "\\amber\\apple.mp3",   // ame 是 bre 的对照，amber 不该命中
            "\\breath\\apple.mp3",
            // 校准补上的 uss/ams/gbs/brs 同样只按 token 边界匹配
            "ambrose.mp3",          // 含 brs
            "\\ambrose\\apple.mp3",
            "amsterdam.mp3",        // 含 ams
            "discuss.mp3",          // 含 uss
            "fuss_1.mp3",
            "brson.mp3",            // 含 brs
            "\\brson\\apple.mp3",
            "gbson.mp3",            // 含 gbs
            "webgbs.mp3"
        )
        paths.forEach { assertEquals(it, AudioAccent.UNKNOWN, classifyAccent(it)) }
    }

    @Test
    fun `a path with both families resolves left to right`() {
        // 真实词典不会这样命名；这里只要确定性，别在运行时随机换口音。
        assertEquals(AudioAccent.US, classifyAccent("\\us\\gb\\apple.mp3"))
        assertEquals(AudioAccent.UK, classifyAccent("\\gb\\us\\apple.mp3"))
    }

    /* ------ pick：回落顺序 ------ */

    @Test
    fun `pick prefers the requested accent`() {
        val paths = listOf("sound://gb/apple.mp3", "sound://us/apple.mp3", "sound://apple.mp3")
        assertEquals("sound://us/apple.mp3", pick(paths, DictionaryAudioAccent.US))
        assertEquals("sound://gb/apple.mp3", pick(paths, DictionaryAudioAccent.UK))
    }

    @Test
    fun `pick falls back to the other accent when the requested one is missing`() {
        val paths = listOf("sound://apple.mp3", "sound://gb/apple.mp3")
        // 要美音但只有英音：播英音，而不是退到 UNKNOWN 的那条
        assertEquals("sound://gb/apple.mp3", pick(paths, DictionaryAudioAccent.US))
        // 反向同理
        assertEquals(
            "sound://us/apple.mp3",
            pick(listOf("sound://apple.mp3", "sound://us/apple.mp3"), DictionaryAudioAccent.UK)
        )
    }

    @Test
    fun `pick falls back to an unknown accent rather than dropping audio`() {
        val paths = listOf("sound://apple.mp3", "\\pic\\apple.png")
        assertEquals("sound://apple.mp3", pick(paths, DictionaryAudioAccent.US))
        assertEquals("sound://apple.mp3", pick(paths, DictionaryAudioAccent.UK))
    }

    @Test
    fun `pick falls back to the first candidate when nothing is classified`() {
        val paths = listOf("\\pic\\apple.png")
        assertEquals("\\pic\\apple.png", pick(paths, DictionaryAudioAccent.US))
    }

    @Test
    fun `AUTO keeps the candidate order`() {
        // AUTO 不分类、不筛：第一个候选就是答案，即使列表里同时有两种口音。
        assertEquals(
            "sound://gb/apple.mp3",
            pick(listOf("sound://gb/apple.mp3", "sound://us/apple.mp3"), DictionaryAudioAccent.AUTO)
        )
        assertEquals(
            "sound://apple.mp3",
            pick(listOf("sound://apple.mp3", "sound://us/apple.mp3"), DictionaryAudioAccent.AUTO)
        )
        assertEquals("\\pic\\apple.png", pick(listOf("\\pic\\apple.png"), DictionaryAudioAccent.AUTO))
    }

    @Test
    fun `pick works on the real OALD9 candidate list`() {
        // 真实词条的候选顺序：词条音频（gb/us）在前，例句音频（brs/uss/gbs/ams）在后。
        val paths = listOf(
            "apple__gb_1.mp3",
            "apple__us_1.mp3",
            "_apple__brs_2.mp3",
            "_apple__uss_2.mp3",
            "_apple__gbs_2.mp3",
            "_apple__ams_2.mp3"
        )
        assertEquals("apple__us_1.mp3", pick(paths, DictionaryAudioAccent.US))
        assertEquals("apple__gb_1.mp3", pick(paths, DictionaryAudioAccent.UK))
        assertEquals("apple__gb_1.mp3", pick(paths, DictionaryAudioAccent.AUTO))
    }

    @Test
    fun `pick returns null for an empty list`() {
        assertNull(pick(emptyList<String>(), DictionaryAudioAccent.AUTO))
        assertNull(pick(emptyList<String>(), DictionaryAudioAccent.US))
        assertNull(pick(emptyList<String>(), DictionaryAudioAccent.UK))
        assertNull(pick(emptyList<Candidate>(), DictionaryAudioAccent.US) { it.path })
    }

    @Test
    fun `generic pick classifies through pathOf`() {
        val candidates = listOf(
            Candidate("sound://apple.mp3", "id-0"),
            Candidate("sound://us/apple.mp3", "id-1"),
            Candidate("sound://gb/apple.mp3", "id-2")
        )
        assertEquals("id-1", pick(candidates, DictionaryAudioAccent.US) { it.path }?.id)
        assertEquals("id-2", pick(candidates, DictionaryAudioAccent.UK) { it.path }?.id)
        assertEquals("id-0", pick(candidates, DictionaryAudioAccent.AUTO) { it.path }?.id)
    }

    private data class Candidate(val path: String, val id: String)

    /* ------ fromName ------ */

    @Test
    fun `every value round-trips through its stored name`() {
        DictionaryAudioAccent.entries.forEach { accent ->
            assertEquals(accent, DictionaryAudioAccent.fromName(accent.name))
        }
    }

    @Test
    fun `a dirty stored value falls back to AUTO`() {
        assertEquals(DictionaryAudioAccent.AUTO, DictionaryAudioAccent.fromName(null))
        assertEquals(DictionaryAudioAccent.AUTO, DictionaryAudioAccent.fromName(""))
        assertEquals(DictionaryAudioAccent.AUTO, DictionaryAudioAccent.fromName("BRITISH"))
        // 存的是 name，不是小写形式；大小写不匹配也算认不出
        assertEquals(DictionaryAudioAccent.AUTO, DictionaryAudioAccent.fromName("us"))
    }
}
