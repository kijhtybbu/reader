/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * fork 新增：用户字体索引 JSON 的往返测试。
 *
 * 索引是「重启后用户字体还在」的唯一依据，所以除了成功路径，重点测损坏/缺字段的降级：
 * 整个文件坏掉返回 null（调用方保留字体文件），单个条目缺字段只跳过该条目。
 */

package ua.acclorite.book_story.data.storage.font

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ua.acclorite.book_story.ui.reader.model.UserFontEntry

class UserFontIndexCodecTest {

    private val staticLatin = UserFontEntry(
        id = "user:0f3a",
        name = "Inter",
        fileName = "0f3a.ttf",
        size = 1_048_576L,
        variable = false,
        hasCjk = false,
        importedAt = 1_761_700_000_000L
    )

    private val variableCjk = UserFontEntry(
        id = "user:9c21",
        name = "霞鹜文楷",
        fileName = "9c21.otf",
        size = 18_874_368L,
        variable = true,
        hasCjk = true,
        importedAt = 1_761_700_001_000L
    )

    @Test
    fun `entries survive an encode-decode round trip`() {
        val decoded = UserFontIndexCodec.decode(
            UserFontIndexCodec.encode(listOf(staticLatin, variableCjk))
        )

        assertEquals(listOf(staticLatin, variableCjk), decoded)
    }

    @Test
    fun `an empty index round-trips to an empty list`() {
        val encoded = UserFontIndexCodec.encode(emptyList())

        assertEquals("[]", encoded)
        assertEquals(emptyList<UserFontEntry>(), UserFontIndexCodec.decode(encoded))
    }

    @Test
    fun `corrupt json returns null so the caller keeps the font files`() {
        assertNull(UserFontIndexCodec.decode("{ not json"))
        assertNull(UserFontIndexCodec.decode("[{\"id\":"))
    }

    @Test
    fun `a json object instead of an array returns null`() {
        assertNull(UserFontIndexCodec.decode("{\"fonts\":[]}"))
    }

    @Test
    fun `unknown fields are ignored`() {
        val raw = """
            [{"id":"user:1","name":"A","fileName":"1.ttf","size":10,
              "variable":false,"hasCjk":true,"importedAt":5,"futureField":"x"}]
        """.trimIndent()

        val decoded = UserFontIndexCodec.decode(raw)

        assertEquals(1, decoded?.size)
        assertEquals("user:1", decoded?.first()?.id)
    }

    @Test
    fun `an entry without a file name is skipped but the others are kept`() {
        val raw = """
            [{"id":"user:broken","name":"Broken","size":10},
             {"id":"user:ok","name":"Ok","fileName":"ok.ttf","size":20,
              "variable":false,"hasCjk":false,"importedAt":1}]
        """.trimIndent()

        val decoded = UserFontIndexCodec.decode(raw)

        assertEquals(1, decoded?.size)
        assertEquals("user:ok", decoded?.first()?.id)
    }

    @Test
    fun `missing optional flags default to static and non-CJK`() {
        val raw = """[{"id":"user:1","fileName":"1.ttf","size":10}]"""

        val entry = UserFontIndexCodec.decode(raw)?.single()

        assertEquals("user:1", entry?.id)
        assertEquals("user:1", entry?.name)
        assertEquals(false, entry?.variable)
        assertEquals(false, entry?.hasCjk)
        assertEquals(0L, entry?.importedAt)
    }

    @Test
    fun `a json null name falls back to the id`() {
        val raw = """[{"id":"user:1","name":null,"fileName":"1.ttf","size":10}]"""

        assertEquals("user:1", UserFontIndexCodec.decode(raw)?.single()?.name)
    }

    @Test
    fun `non-font entries are ignored`() {
        val decoded = UserFontIndexCodec.decode("""[1, "x", null, {"id":"user:1","fileName":"1.ttf","size":1}]""")

        assertEquals(1, decoded?.size)
        assertTrue(decoded!!.first().id.startsWith("user:"))
    }
}
