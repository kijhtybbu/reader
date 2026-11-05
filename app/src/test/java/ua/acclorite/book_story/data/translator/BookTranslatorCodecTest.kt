/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * fork 新增：按书翻译器行为的编解码测试。
 *
 * 重点：缺失/非法值必须回落「关闭」，不能回落到旧的全局默认（单击 / 开启）——
 * 否则升级后所有书会悄悄重新启用翻译器，违背「默认关闭」。
 */

package ua.acclorite.book_story.data.translator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import ua.acclorite.book_story.presentation.reader.model.ReaderLookupGesture

class BookTranslatorCodecTest {

    @Test
    fun `records survive an encode-decode round trip`() {
        val source = mapOf(
            "uuid-a" to BookTranslatorRecord(
                lookupGesture = ReaderLookupGesture.LONG_PRESS.name,
                doubleClickTranslation = true
            ),
            "uuid-b" to BookTranslatorRecord(
                lookupGesture = ReaderLookupGesture.OFF.name,
                doubleClickTranslation = false
            )
        )

        assertEquals(source, BookTranslatorCodec.decode(BookTranslatorCodec.encode(source)))
    }

    @Test
    fun `missing fields stay null and resolve to off`() {
        val decoded = BookTranslatorCodec.decode(
            BookTranslatorCodec.encode(mapOf("uuid" to BookTranslatorRecord()))
        )?.get("uuid")

        assertNull(decoded?.lookupGesture)
        assertNull(decoded?.doubleClickTranslation)
        assertEquals(ReaderLookupGesture.OFF, decoded.resolve().lookupGesture)
        assertFalse(decoded.resolve().doubleClickTranslation)
    }

    @Test
    fun `unknown gesture resolves to off instead of single tap`() {
        val raw = """{"uuid": {"lookupGesture": "DOUBLE_WINK"}}"""

        val decoded = BookTranslatorCodec.decode(raw)?.get("uuid")

        assertEquals(ReaderLookupGesture.OFF, decoded.resolve().lookupGesture)
    }

    @Test
    fun `broken json returns null`() {
        assertNull(BookTranslatorCodec.decode("{not json"))
        assertNull(BookTranslatorCodec.decode("[]"))
    }

    @Test
    fun `blank or non-object entries are skipped`() {
        val raw = """
            {
              "": {"doubleClickTranslation": true},
              "valid": {"doubleClickTranslation": true},
              "broken": 42
            }
        """.trimIndent()

        val decoded = BookTranslatorCodec.decode(raw)

        assertEquals(setOf("valid"), decoded?.keys)
        assertEquals(true, decoded?.get("valid")?.doubleClickTranslation)
    }

    @Test
    fun `wrong field types are dropped per field`() {
        val raw = """
            {
              "uuid": {
                "lookupGesture": 3,
                "doubleClickTranslation": "yes"
              }
            }
        """.trimIndent()

        val decoded = BookTranslatorCodec.decode(raw)?.get("uuid")

        assertNull(decoded?.lookupGesture)
        assertNull(decoded?.doubleClickTranslation)
        assertEquals(ReaderLookupGesture.OFF, decoded.resolve().lookupGesture)
        assertFalse(decoded.resolve().doubleClickTranslation)
    }
}
