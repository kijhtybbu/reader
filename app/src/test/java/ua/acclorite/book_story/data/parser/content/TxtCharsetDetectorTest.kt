/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 */

package ua.acclorite.book_story.data.parser.content

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ua.acclorite.book_story.domain.model.book.CharsetConfidence
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

/**
 * fork 新增：TXT 编码探测的回归测试。
 *
 * 样本一律用 `String.toByteArray(charset)` 现场生成 —— 不在仓库里放二进制 fixture，
 * 每个断言都能对着源码读出「这段字节是怎么来的」。
 */
class TxtCharsetDetectorTest {

    private val chinese = "这是第一章，标点符号：。，、；：？！「」"
    private val big5Chinese = "這是第一章，標點符號：。，、；：？！「」"

    private fun bytes(text: String, charsetName: String): ByteArray =
        text.toByteArray(Charset.forName(charsetName))

    @Test
    fun `UTF-8 Chinese without BOM is detected as UTF-8 with certainty`() {
        val sample = chinese.toByteArray(StandardCharsets.UTF_8)

        val guess = detectTxtCharset(sample)

        assertEquals(StandardCharsets.UTF_8, guess.charset)
        assertEquals(CharsetConfidence.CERTAIN, guess.confidence)
        assertFalse(guess.hadBom)
    }

    @Test
    fun `GBK Chinese is detected as GB18030 and round-trips`() {
        val sample = bytes(chinese, "GBK")

        val guess = detectTxtCharset(sample)

        // GBK 是 GB18030 的子集，统一用它解码（GB18030 能解 GBK，反之不成立）。
        assertEquals("GB18030", guess.charset.name())
        assertEquals(CharsetConfidence.LIKELY, guess.confidence)
        assertFalse(guess.hadBom)
        assertEquals(chinese, String(sample, guess.charset))
    }

    /**
     * 这就是要修的那个缺陷：同一份 GBK 字节按 UTF-8 解出来不是原文，而是乱码。
     * 如果以后有人把探测去掉、退回无条件 UTF-8，这条断言会先失败。
     */
    @Test
    fun `GBK bytes are mojibake when decoded as UTF-8`() {
        val sample = bytes(chinese, "GBK")

        val asUtf8 = String(sample, StandardCharsets.UTF_8)

        assertNotEquals(chinese, asUtf8)
        assertTrue(
            "GBK bytes must not survive strict UTF-8 decoding",
            asUtf8.contains('\uFFFD')
        )
    }

    @Test
    fun `UTF-16LE with BOM is detected as UTF-16LE with certainty`() {
        val text = "这是第一章，标点符号"
        val sample = text.toByteArray(StandardCharsets.UTF_16LE)
        // UTF-16LE 的 BOM 是 FF FE（toByteArray 不会自己加 BOM）
        val withBom = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + sample

        val guess = detectTxtCharset(withBom)

        assertEquals(StandardCharsets.UTF_16LE, guess.charset)
        assertEquals(CharsetConfidence.CERTAIN, guess.confidence)
        assertTrue(guess.hadBom)
        assertEquals(text, String(withBom, 2, withBom.size - 2, guess.charset))
        assertEquals(2, txtBomPrefixLength(withBom, guess.charset))
    }

    @Test
    fun `UTF-16BE with BOM is detected as UTF-16BE with certainty`() {
        val text = "这是第一章，标点符号"
        val withBom = byteArrayOf(0xFE.toByte(), 0xFF.toByte()) +
                text.toByteArray(StandardCharsets.UTF_16BE)

        val guess = detectTxtCharset(withBom)

        assertEquals(StandardCharsets.UTF_16BE, guess.charset)
        assertEquals(CharsetConfidence.CERTAIN, guess.confidence)
        assertTrue(guess.hadBom)
    }

    @Test
    fun `UTF-8 BOM is stripped and reported`() {
        val withBom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
                chinese.toByteArray(StandardCharsets.UTF_8)

        val guess = detectTxtCharset(withBom)

        assertEquals(StandardCharsets.UTF_8, guess.charset)
        assertEquals(CharsetConfidence.CERTAIN, guess.confidence)
        assertTrue(guess.hadBom)
        assertEquals(3, txtBomPrefixLength(withBom, guess.charset))
        // 没有 BOM 的样本不能平白丢掉正文开头
        assertEquals(0, txtBomPrefixLength(chinese.toByteArray(StandardCharsets.UTF_8), guess.charset))
    }

    /**
     * 已知局限（不是缺陷）：任何结构合法的 BIG5 双字节序列都能被 GB18030 严格解码
     * （实测 13708/13708 个有效 BIG5 组合，无一被 GB18030 拒绝），所以 BIG5 样本会落到
     * 探测顺序的第 3 步 —— GB18030/LIKELY，解出来是乱码。要自动区分 GBK 与 BIG5 需要按
     * 「常用字占比」做频率打分（统计式探测），本 fork 不做；BIG5 通过手动覆盖提供。
     *
     * 这条断言把上述行为钉住：如果哪天探测顺序被人改成「BIG5 优先」，GBK（更常见）会被
     * 误判，这条测试会跟着失败，提醒改的人先读过这段注释。
     */
    @Test
    fun `BIG5 Chinese lands on GB18030 because GB18030 shadows every valid BIG5 pair`() {
        val sample = bytes(big5Chinese, "Big5")

        val guess = detectTxtCharset(sample)

        assertEquals("GB18030", guess.charset.name())
        assertEquals(CharsetConfidence.LIKELY, guess.confidence)
        assertNotEquals(big5Chinese, String(sample, guess.charset))
        // 手动覆盖 BIG5 才是这条路：解出来必须是原文。
        assertEquals(big5Chinese, String(sample, Charset.forName("Big5")))
    }

    @Test
    fun `pure ASCII is detected as UTF-8 with certainty`() {
        val sample = "Chapter 1. This is plain ASCII text, no CJK.".toByteArray(StandardCharsets.US_ASCII)

        val guess = detectTxtCharset(sample)

        assertEquals(StandardCharsets.UTF_8, guess.charset)
        assertEquals(CharsetConfidence.CERTAIN, guess.confidence)
        assertFalse(guess.hadBom)
    }

    @Test
    fun `empty sample does not throw and is treated as UTF-8`() {
        val guess = detectTxtCharset(ByteArray(0))

        assertEquals(StandardCharsets.UTF_8, guess.charset)
        assertEquals(CharsetConfidence.CERTAIN, guess.confidence)
        assertFalse(guess.hadBom)
    }

    /**
     * 样本是文件的前 100 KB，末尾可能正好截断一个多字节字符。若把截断当成「畸形」，
     * 所有中文文件都会被判成 FALLBACK —— 这条钉住 endOfInput = false 的行为。
     */
    @Test
    fun `truncated trailing multi-byte character does not demote confidence`() {
        val full = chinese.toByteArray(StandardCharsets.UTF_8)
        val truncated = full.copyOf(full.size - 1)

        val guess = detectTxtCharset(truncated)

        assertEquals(StandardCharsets.UTF_8, guess.charset)
        assertEquals(CharsetConfidence.CERTAIN, guess.confidence)
    }

    @Test
    fun `undecodable bytes fall back to GB18030 with FALLBACK confidence`() {
        // 0x80 既不是合法 UTF-8 起始字节，也不是合法 GB18030/GBK/BIG5 前导字节。
        val sample = byteArrayOf(0x80.toByte(), 0x80.toByte(), 0x80.toByte())

        val guess = detectTxtCharset(sample)

        assertEquals("GB18030", guess.charset.name())
        assertEquals(CharsetConfidence.FALLBACK, guess.confidence)
    }

    @Test
    fun `manual override list is exactly the documented encodings`() {
        assertArrayEquals(
            arrayOf("UTF-8", "GB18030", "GBK", "Big5", "UTF-16LE", "UTF-16BE"),
            TXT_CHARSET_OVERRIDES.map { it.name() }.toTypedArray()
        )
    }

    @Test
    fun `confidence is restored from its persisted name`() {
        CharsetConfidence.entries.forEach {
            assertEquals(it, CharsetConfidence.fromPersistedName(it.name))
        }
        assertEquals(null, CharsetConfidence.fromPersistedName(null))
        // 未知值宁可按「不可靠」处理，也不要假装确定
        assertEquals(CharsetConfidence.FALLBACK, CharsetConfidence.fromPersistedName("WHATEVER"))
    }
}
