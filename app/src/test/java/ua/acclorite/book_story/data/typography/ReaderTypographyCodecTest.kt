/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * fork 新增：按书排版 JSON 的往返与容错测试。
 *
 * 重点是「一本书坏掉不能拖垮整张表」：整个字符串坏掉返回 null（调用方保留原值），
 * 单条记录坏掉只跳过该条，单个字段类型不对只丢该字段（解析层会回退到全局）。
 */

package ua.acclorite.book_story.data.typography

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ua.acclorite.book_story.presentation.reader.model.ReaderTextAlignment

class ReaderTypographyCodecTest {

    private val fullRecord = BookTypographyRecord(
        enabled = true,
        fontFamilyId = "user:0f3a",
        fontThickness = 500,
        boldFontThickness = 200,
        italic = true,
        fontSize = 20,
        lineHeight = 6,
        paragraphHeight = 10,
        letterSpacing = 2,
        paragraphIndentation = 4,
        textAlignment = ReaderTextAlignment.CENTER,
        sidePadding = 8,
        verticalPadding = 12
    )

    @Test
    fun `records survive an encode-decode round trip`() {
        val source = mapOf("uuid-a" to fullRecord, "uuid-b" to BookTypographyRecord())

        val decoded = ReaderTypographyCodec.decode(ReaderTypographyCodec.encode(source))

        assertEquals(source, decoded)
    }

    @Test
    fun `null fields are omitted and decode as null`() {
        val partial = BookTypographyRecord(enabled = true, fontSize = 22)

        val decoded = ReaderTypographyCodec.decode(
            ReaderTypographyCodec.encode(mapOf("uuid" to partial))
        )?.get("uuid")

        assertEquals(partial, decoded)
        // 未写入的字段必须保持 null，解析层才知道要回退到全局
        assertNull(decoded?.fontFamilyId)
        assertNull(decoded?.textAlignment)
    }

    @Test
    fun `broken json returns null instead of an empty table`() {
        // 返回 null 与「空表」有区别：调用方不应把损坏的原始字符串当成没有设置而覆盖掉。
        assertNull(ReaderTypographyCodec.decode("{not json"))
        assertNull(ReaderTypographyCodec.decode("[]"))
    }

    @Test
    fun `blank or non-object entries are skipped but the rest survives`() {
        val raw = """
            {
              "": {"enabled": true, "fontSize": 18},
              "valid": {"enabled": true, "fontSize": 18},
              "broken": 42
            }
        """.trimIndent()

        val decoded = ReaderTypographyCodec.decode(raw)

        assertEquals(setOf("valid"), decoded?.keys)
        assertEquals(18, decoded?.get("valid")?.fontSize)
    }

    @Test
    fun `unknown alignment falls back to null instead of failing the table`() {
        val raw = """{"uuid": {"enabled": true, "textAlignment": "DIAGONAL"}}"""

        val decoded = ReaderTypographyCodec.decode(raw)

        assertNull(decoded?.get("uuid")?.textAlignment)
    }

    @Test
    fun `wrong field types are dropped per field`() {
        val raw = """
            {
              "uuid": {
                "enabled": true,
                "fontSize": "big",
                "lineHeight": 7,
                "italic": "yes"
              }
            }
        """.trimIndent()

        val decoded = ReaderTypographyCodec.decode(raw)?.get("uuid")

        assertNull(decoded?.fontSize)
        assertEquals(7, decoded?.lineHeight)
        assertNull(decoded?.italic)
        assertTrue(decoded?.enabled == true)
    }
}
