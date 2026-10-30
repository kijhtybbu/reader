/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * fork 新增：取词的词形归一（第二阶段 S5）。
 *
 * `TextLayoutResult.getWordBoundary` 交出来的是 ICU 断词结果，不是词典词头：
 *  - 西文会带上引号/标点（`“apple,”`、`(word)`）；
 *  - CJK 没有空格，一整句会被当成一个「词」，直接拿去查只会 miss。
 * 所以取词前必须归一，并且归一失败（纯标点、纯空白）必须返回 null —— 把空串丢给
 * `DictionaryRepository.lookup` 会白跑一次查询，弹出一个「未找到」卡片。
 *
 * 纯函数、无 android.*：JVM 单测直接覆盖（ReaderLookupWordTest）。
 */

package ua.acclorite.book_story.ui.reader

/** 归一后的词及其在原始片段里的位置（相对 [raw]）。 */
internal data class LookupWord(
    val text: String,
    val start: Int,
    val end: Int
)

/**
 * 词内允许的字符：字母（含 CJK）、数字、撇号、连字符。
 * 连字符保留是因为 `well-known` 这类词条真的按连字符收录；撇号同理（`don't`）。
 */
private fun isWordChar(c: Char): Boolean =
    c.isLetterOrDigit() || c == '\'' || c == '\u2019' || c == '-'

/** 允许出现在词内、但不能出现在词首尾的字符。 */
private fun isEdgeDecoration(c: Char): Boolean = c == '-' || c == '\'' || c == '\u2019'

/** CJK 词最长取 8 个字：再长就不是词条了，只会白白 miss 一次。 */
internal const val MAX_CJK_WORD_LENGTH = 8

private fun isCjk(c: Char): Boolean {
    val code = c.code
    return code in 0x3040..0x30FF ||   // 平假名/片假名
        code in 0x3400..0x4DBF ||      // CJK 扩展 A
        code in 0x4E00..0x9FFF ||      // CJK 基本区
        code in 0xF900..0xFAFF ||      // CJK 兼容表意
        code in 0xAC00..0xD7AF         // 谚文音节
}

/**
 * 把一段原始片段归一成词典查询词。
 *
 * 规则（设计 §5.1）：剥掉首尾的标点/引号，保留字母/数字/撇号/连字符；CJK 截到
 * [MAX_CJK_WORD_LENGTH] 个字；全是标点或空白时返回 null。
 *
 * @return null 表示「这里没有可查的词」。
 */
internal fun lookupWordIn(raw: String): LookupWord? {
    var start = 0
    var end = raw.length
    while (start < end && !isWordChar(raw[start])) start++
    while (end > start && !isWordChar(raw[end - 1])) end--
    // 连字符/撇号是词内字符，所以 `--word--` 会在上一轮被留下；首尾的它们不是词的一部分。
    while (start < end && isEdgeDecoration(raw[start])) start++
    while (end > start && isEdgeDecoration(raw[end - 1])) end--
    if (start >= end) return null

    var word = raw.substring(start, end)
    if (word.length > MAX_CJK_WORD_LENGTH && isCjk(word[0])) {
        word = word.take(MAX_CJK_WORD_LENGTH)
        end = start + word.length
    }
    // 剥完之后还可能是纯连字符/撇号（`--`、`''`），那不是词。
    if (word.none { it.isLetterOrDigit() }) return null
    return LookupWord(word, start, end)
}

/** [lookupWordIn] 的便捷版本：只要词本身。 */
internal fun normalizeLookupWord(raw: String): String? = lookupWordIn(raw)?.text

/**
 * 多词选区取词：只查选区里的**第一个词**。
 *
 * 工具栏「词典」只能拿到选中的整段文字。把 `hello world` 整串丢给 `lookupExact` 永远是
 * miss（词典不收录短语），用户看到的是「未找到」卡片；S5 之前是原样交给外部词典，行为变差。
 * 因此这里按同一套字符规则切出首个词（可能带 CJK 截断），真 miss 时再由卡片上的
 * 「用其他应用查询」回退到外部词典。
 *
 * @return null 表示选区里根本没有可查的词（纯标点/空白）。
 */
internal fun firstLookupWord(raw: String): String? {
    var start = 0
    while (start < raw.length && !isWordChar(raw[start])) start++
    if (start >= raw.length) return null
    var end = start
    while (end < raw.length && isWordChar(raw[end])) end++
    return normalizeLookupWord(raw.substring(start, end))
}

/** 引擎把 `entry://x` / Pango 蓝色 span 改写成 `juice://lookup/<urlencoded>`。 */
internal const val LOOKUP_LINK_SCHEME = "juice://lookup/"

/**
 * 从词条正文里的链接取出要查的词。不是取词链接、或解码后为空时返回 null。
 * 解码失败（畸形百分号）也返回 null：宁可这次不响应，也不要拿一串 `%E7` 去查。
 */
internal fun lookupWordFromLink(url: String): String? {
    if (!url.startsWith(LOOKUP_LINK_SCHEME)) return null
    val encoded = url.removePrefix(LOOKUP_LINK_SCHEME)
    if (encoded.isEmpty()) return null
    val decoded = try {
        java.net.URLDecoder.decode(encoded, "UTF-8")
    } catch (e: IllegalArgumentException) {
        return null
    }
    // 截断的百分号转义不会抛异常，只会解出替换字符（U+FFFD）：那不是词，别拿它去查。
    if (decoded.contains('\uFFFD')) return null
    return decoded.trim().takeIf { it.isNotEmpty() }
}
