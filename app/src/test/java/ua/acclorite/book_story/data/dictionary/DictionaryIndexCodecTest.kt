/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * fork 新增：导入词典索引编解码的回归（第二阶段 S4）。
 *
 * 索引是唯一记录「哪部词典是用户导入的、是否启用」的地方，坏一次就等于用户重新导入一遍。
 * 这里钉住：字段完整往返、空列表、整个文件损坏时返回 null（调用方据此保留文件重建）、
 * 单条缺字段/类型未知时跳过而不拖垮整份索引、保留/重复 id 被丢弃（列表 `key` 不能重复）。
 */

package ua.acclorite.book_story.data.dictionary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DictionaryIndexCodecTest {

    private val mdxEntry = DictionaryIndexEntry(
        id = "a1b2",
        type = DictionaryType.MDX,
        name = "汉英大词典",
        baseName = "hanying",
        directory = "imported/a1b2",
        wordCount = 123_456,
        sizeBytes = 98_765_432L,
        enabled = false,
        importedAt = 1_700_000_000_000L
    )

    private val starDictEntry = DictionaryIndexEntry(
        id = "c3d4",
        type = DictionaryType.STARDICT,
        name = "tiny",
        baseName = "tiny",
        directory = "imported/c3d4",
        wordCount = 2,
        sizeBytes = 512L,
        enabled = true,
        importedAt = 1L
    )

    @Test
    fun `round trip keeps every field`() {
        val encoded = DictionaryIndexCodec.encode(listOf(mdxEntry, starDictEntry))

        assertEquals(listOf(mdxEntry, starDictEntry), DictionaryIndexCodec.decode(encoded))
    }

    @Test
    fun `empty index round trips`() {
        assertEquals(emptyList<DictionaryIndexEntry>(), DictionaryIndexCodec.decode("[]"))
        assertEquals("[]", DictionaryIndexCodec.encode(emptyList()))
    }

    @Test
    fun `corrupt json is rejected as a whole`() {
        assertNull(DictionaryIndexCodec.decode(""))
        assertNull(DictionaryIndexCodec.decode("not json"))
        assertNull(DictionaryIndexCodec.decode("{\"id\":\"a1b2\"}"))
        assertNull(DictionaryIndexCodec.decode("[{\"id\":"))
    }

    @Test
    fun `entries with missing fields are skipped`() {
        val raw = """
            [
              {"id":"ok","type":"MDX","name":"n","baseName":"b","directory":"imported/ok",
               "wordCount":1,"sizeBytes":2,"enabled":true,"importedAt":3},
              {"id":"no-type","name":"n","baseName":"b","directory":"imported/x"},
              {"type":"MDX","name":"n","baseName":"b","directory":"imported/x"},
              {"id":"no-dir","type":"MDX","baseName":"b"}
            ]
        """.trimIndent()

        val decoded = DictionaryIndexCodec.decode(raw)

        assertEquals(listOf("ok"), decoded?.map { it.id })
    }

    @Test
    fun `unknown dictionary type is skipped`() {
        val raw = """
            [{"id":"x","type":"DICTD","name":"n","baseName":"b","directory":"imported/x"}]
        """.trimIndent()

        assertTrue(DictionaryIndexCodec.decode(raw)?.isEmpty() == true)
    }

    @Test
    fun `type is persisted as a stable wire string and legacy enum names still decode`() {
        val encoded = DictionaryIndexCodec.encode(listOf(mdxEntry, starDictEntry))

        assertTrue(encoded.contains("\"type\":\"MDX\""))
        assertTrue(encoded.contains("\"type\":\"STARDICT\""))
        // 早期索引把枚举名写进磁盘；两类字符串目前相同，但解析必须两边都认。
        val legacy = """
            [{"id":"x","type":"STARDICT","baseName":"b","directory":"imported/x"}]
        """.trimIndent()
        assertEquals(DictionaryType.STARDICT, DictionaryIndexCodec.decode(legacy)?.single()?.type)
    }

    @Test
    fun `builtin and duplicate ids are dropped`() {
        val raw = """
            [
              {"id":"builtin","type":"STARDICT","baseName":"b","directory":"imported/x"},
              {"id":"dup","type":"MDX","baseName":"b","directory":"imported/y"},
              {"id":"dup","type":"MDX","baseName":"b","directory":"imported/z"}
            ]
        """.trimIndent()

        assertEquals(listOf("dup"), DictionaryIndexCodec.decode(raw)?.map { it.id })
        assertEquals("imported/y", DictionaryIndexCodec.decode(raw)?.single()?.directory)
    }

    @Test
    fun `optional fields fall back to safe defaults`() {
        val raw = """
            [{"id":"x","type":"STARDICT","baseName":"b","directory":"imported/x"}]
        """.trimIndent()

        val entry = DictionaryIndexCodec.decode(raw)?.single()
        assertEquals("b", entry?.name)
        assertEquals(0, entry?.wordCount)
        assertEquals(0L, entry?.sizeBytes)
        assertEquals(true, entry?.enabled)
        assertEquals(0L, entry?.importedAt)
    }
}
