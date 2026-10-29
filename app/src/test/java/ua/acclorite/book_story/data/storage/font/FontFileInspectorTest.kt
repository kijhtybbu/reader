/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * fork 新增：导入字体文件头校验的回归测试。
 *
 * 样本是现场拼的字节数组，不是二进制 fixture —— 每个断言都能直接看出「这 4 个字节是
 * 哪个魔数」。这里只测纯函数；真正建 Typeface / hasGlyph 需要 Android，放到设备上验证。
 */

package ua.acclorite.book_story.data.storage.font

import org.junit.Assert.assertEquals
import org.junit.Test

class FontFileInspectorTest {

    private val truetype = byteArrayOf(0x00, 0x01, 0x00, 0x00)
    private val otto = "OTTO".toByteArray(Charsets.US_ASCII)
    private val ttcf = "ttcf".toByteArray(Charsets.US_ASCII)
    private val woff = "wOFF".toByteArray(Charsets.US_ASCII)
    private val woff2 = "wOF2".toByteArray(Charsets.US_ASCII)

    /** 拼一个合法的 sfnt 头 + table directory（每个 tag 一条 16 字节记录，其余字段填 0）。 */
    private fun header(magic: ByteArray, tags: List<String> = emptyList()): ByteArray {
        val result = ByteArray(12 + tags.size * 16)
        magic.copyInto(result, 0)
        result[4] = ((tags.size shr 8) and 0xFF).toByte()
        result[5] = (tags.size and 0xFF).toByte()
        tags.forEachIndexed { index, tag ->
            tag.toByteArray(Charsets.US_ASCII).copyInto(result, 12 + index * 16)
        }
        return result
    }

    private fun inspect(
        extension: String,
        size: Long = 1024L,
        header: ByteArray
    ) = FontFileInspector.inspect(extension, size, header)

    @Test
    fun `TrueType magic with ttf extension is accepted`() {
        assertEquals(
            FontFileCheck.Accepted(FontContainer.TRUETYPE, variable = false),
            inspect("ttf", header = header(truetype))
        )
    }

    @Test
    fun `OTTO magic with otf extension is accepted as CFF OpenType`() {
        assertEquals(
            FontFileCheck.Accepted(FontContainer.OPENTYPE_CFF, variable = false),
            inspect("otf", header = header(otto))
        )
    }

    @Test
    fun `extension is case-insensitive and may keep the leading dot`() {
        assertEquals(
            FontFileCheck.Accepted(FontContainer.TRUETYPE, variable = false),
            inspect(".TTF", header = header(truetype))
        )
    }

    @Test
    fun `OTTO content behind a ttf name is accepted`() {
        // 很多字体发布时扩展名与容器不一致；magic 才是事实。
        assertEquals(
            FontFileCheck.Accepted(FontContainer.OPENTYPE_CFF, variable = false),
            inspect("ttf", header = header(otto))
        )
    }

    @Test
    fun `ttc magic is rejected even when the extension says ttf`() {
        assertEquals(
            FontFileCheck.Rejected(FontRejection.FONT_COLLECTION),
            inspect("ttf", header = header(ttcf))
        )
    }

    @Test
    fun `ttc extension is rejected before reading the header`() {
        assertEquals(
            FontFileCheck.Rejected(FontRejection.FONT_COLLECTION),
            inspect("ttc", header = header(truetype))
        )
    }

    @Test
    fun `wOFF magic is rejected with the conversion reason`() {
        assertEquals(
            FontFileCheck.Rejected(FontRejection.WOFF_REQUIRES_CONVERSION),
            inspect("woff", header = header(woff))
        )
    }

    @Test
    fun `wOF2 magic is rejected even when the extension lies`() {
        assertEquals(
            FontFileCheck.Rejected(FontRejection.WOFF_REQUIRES_CONVERSION),
            inspect("ttf", header = header(woff2))
        )
    }

    @Test
    fun `woff2 extension is rejected before reading the header`() {
        assertEquals(
            FontFileCheck.Rejected(FontRejection.WOFF_REQUIRES_CONVERSION),
            inspect("woff2", header = header(truetype))
        )
    }

    @Test
    fun `unknown extension is rejected`() {
        assertEquals(
            FontFileCheck.Rejected(FontRejection.UNSUPPORTED_EXTENSION),
            inspect("bin", header = header(truetype))
        )
    }

    @Test
    fun `unknown magic is rejected as not a font`() {
        assertEquals(
            FontFileCheck.Rejected(FontRejection.NOT_A_FONT),
            inspect("ttf", header = header("RIFF".toByteArray(Charsets.US_ASCII)))
        )
    }

    @Test
    fun `an empty file is rejected`() {
        assertEquals(
            FontFileCheck.Rejected(FontRejection.EMPTY_FILE),
            inspect("ttf", size = 0L, header = header(truetype))
        )
    }

    @Test
    fun `a file above 40 MB is rejected`() {
        assertEquals(
            FontFileCheck.Rejected(FontRejection.TOO_LARGE),
            inspect("ttf", size = FontFileInspector.MAX_BYTES + 1, header = header(truetype))
        )
    }

    @Test
    fun `exactly 40 MB is still accepted`() {
        assertEquals(
            FontFileCheck.Accepted(FontContainer.TRUETYPE, variable = false),
            inspect("ttf", size = FontFileInspector.MAX_BYTES, header = header(truetype))
        )
    }

    @Test
    fun `a truncated header is rejected instead of throwing`() {
        assertEquals(
            FontFileCheck.Rejected(FontRejection.NOT_A_FONT),
            inspect("ttf", header = byteArrayOf(0x00, 0x01, 0x00))
        )
    }

    @Test
    fun `an fvar table marks the font as variable`() {
        assertEquals(
            FontFileCheck.Accepted(FontContainer.TRUETYPE, variable = true),
            inspect("ttf", header = header(truetype, tags = listOf("cmap", "fvar")))
        )
    }

    @Test
    fun `a font without fvar is static`() {
        assertEquals(
            FontFileCheck.Accepted(FontContainer.TRUETYPE, variable = false),
            inspect("ttf", header = header(truetype, tags = listOf("cmap", "glyf", "head")))
        )
    }

    @Test
    fun `a table directory that does not fit in the header stays static`() {
        // numTables = 2 但没有足够的目录字节：保守地当成静态字体，不猜测。
        val truncated = header(truetype).copyOf(12).also {
            it[4] = 0
            it[5] = 2
        }

        assertEquals(
            FontFileCheck.Accepted(FontContainer.TRUETYPE, variable = false),
            inspect("ttf", header = truncated)
        )
    }

    @Test
    fun `zero magic is rejected`() {
        assertEquals(
            FontFileCheck.Rejected(FontRejection.NOT_A_FONT),
            inspect("ttf", header = header(ByteArray(4)))
        )
    }
}
