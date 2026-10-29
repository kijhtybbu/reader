/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * fork 新增：cmap 覆盖检测的回归测试。
 *
 * 样本是现场拼的 cmap 表（format 4 / 12），不依赖任何字体文件。重点是两条路径：
 * idDelta 直接映射，以及 idRangeOffset 指向 glyphIdArray（很多 CJK 字体用后者）。
 */

package ua.acclorite.book_story.data.storage.font

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

class FontCmapTableTest {

    private val han = 0x6C49
    private val ideographicFullStop = 0x3002

    private fun ByteArrayOutputStream.u16(value: Int) {
        write(value shr 8 and 0xFF)
        write(value and 0xFF)
    }

    private fun ByteArrayOutputStream.u32(value: Int) {
        write(value shr 24 and 0xFF)
        write(value shr 16 and 0xFF)
        write(value shr 8 and 0xFF)
        write(value and 0xFF)
    }

    /** cmap header + 一条记录（platform, encoding）指向 offset 12 的子表。 */
    private fun withHeader(platform: Int, encoding: Int, subtable: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.u16(0) // version
        out.u16(1) // numTables
        out.u16(platform)
        out.u16(encoding)
        out.u32(12)
        out.write(subtable)
        return out.toByteArray()
    }

    /**
     * format 4。每个 segment 是 (start, end, idDelta, idRangeOffset, glyphIdArray)。
     * idDelta 按 16 位有符号写。
     */
    private fun format4(
        segments: List<Format4Segment>
    ): ByteArray {
        val count = segments.size
        val glyphCount = segments.sumOf { it.glyphIdArray.size }
        val length = 14 + count * 2 * 4 + 2 + glyphCount * 2

        val out = ByteArrayOutputStream()
        out.u16(4)
        out.u16(length)
        out.u16(0)
        out.u16(count * 2)
        out.u16(0)
        out.u16(0)
        out.u16(0)

        segments.forEach { out.u16(it.end) }
        out.u16(0) // reservedPad
        segments.forEach { out.u16(it.start) }
        segments.forEach { out.u16(it.idDelta and 0xFFFF) }
        segments.forEach { out.u16(it.idRangeOffset) }
        segments.forEach { segment -> segment.glyphIdArray.forEach { out.u16(it) } }

        return out.toByteArray()
    }

    private fun format12(groups: List<Triple<Int, Int, Int>>): ByteArray {
        val length = 16 + groups.size * 12
        val out = ByteArrayOutputStream()
        out.u16(12)
        out.u16(0)
        out.u32(length)
        out.u32(0)
        out.u32(groups.size)
        groups.forEach { (start, end, startGlyph) ->
            out.u32(start)
            out.u32(end)
            out.u32(startGlyph)
        }
        return out.toByteArray()
    }

    private data class Format4Segment(
        val start: Int,
        val end: Int,
        val idDelta: Int = 0,
        val idRangeOffset: Int = 0,
        val glyphIdArray: List<Int> = emptyList()
    )

    @Test
    fun `format 4 direct delta maps both probes`() {
        val cmap = withHeader(
            platform = 3,
            encoding = 1,
            subtable = format4(
                listOf(
                    Format4Segment(han, han, idDelta = 5 - han),
                    Format4Segment(ideographicFullStop, ideographicFullStop, idDelta = 1)
                )
            )
        )

        assertTrue(FontCmapTable.hasAllGlyphs(cmap, intArrayOf(han, ideographicFullStop)))
    }

    @Test
    fun `format 4 glyph zero means the codepoint is absent`() {
        val cmap = withHeader(
            platform = 3,
            encoding = 1,
            subtable = format4(
                listOf(
                    Format4Segment(han, han, idDelta = 5 - han),
                    // delta 让它落到 glyph 0
                    Format4Segment(
                        ideographicFullStop,
                        ideographicFullStop,
                        idDelta = -ideographicFullStop
                    )
                )
            )
        )

        assertFalse(FontCmapTable.hasAllGlyphs(cmap, intArrayOf(han, ideographicFullStop)))
        assertTrue(FontCmapTable.hasAllGlyphs(cmap, intArrayOf(han)))
    }

    @Test
    fun `format 4 idRangeOffset reads from glyphIdArray`() {
        // 段只覆盖「汉」，idRangeOffset 指向 glyphIdArray 的第 0 项（= 7）
        val cmap = withHeader(
            platform = 3,
            encoding = 1,
            subtable = format4(
                listOf(
                    Format4Segment(
                        start = han,
                        end = han,
                        idDelta = 0,
                        idRangeOffset = 2,
                        glyphIdArray = listOf(7)
                    )
                )
            )
        )

        assertTrue(FontCmapTable.hasAllGlyphs(cmap, intArrayOf(han)))
        assertFalse(FontCmapTable.hasAllGlyphs(cmap, intArrayOf(ideographicFullStop)))
    }

    @Test
    fun `format 12 groups cover supplementary and BMP codepoints`() {
        val cmap = withHeader(
            platform = 3,
            encoding = 10,
            subtable = format12(
                listOf(
                    Triple(0x4E00, 0x9FFF, 1),
                    Triple(ideographicFullStop, ideographicFullStop, 3)
                )
            )
        )

        assertTrue(FontCmapTable.hasAllGlyphs(cmap, intArrayOf(han, ideographicFullStop)))
    }

    @Test
    fun `format 12 start glyph zero at the codepoint means absent`() {
        val cmap = withHeader(
            platform = 0,
            encoding = 4,
            subtable = format12(listOf(Triple(han, han, 0)))
        )

        assertFalse(FontCmapTable.hasAllGlyphs(cmap, intArrayOf(han)))
    }

    @Test
    fun `non unicode subtables are ignored`() {
        val direct = format4(listOf(Format4Segment(han, han, idDelta = 5 - han)))

        assertFalse(FontCmapTable.hasAllGlyphs(withHeader(3, 0, direct), intArrayOf(han)))
        assertFalse(FontCmapTable.hasAllGlyphs(withHeader(1, 0, direct), intArrayOf(han)))
    }

    @Test
    fun `unsupported subtable formats are treated as absent`() {
        val format0 = ByteArrayOutputStream().apply {
            u16(0)
            u16(262)
            u16(0)
            repeat(256) { write(0) }
        }.toByteArray()

        assertFalse(FontCmapTable.hasAllGlyphs(withHeader(3, 1, format0), intArrayOf(han)))
    }

    @Test
    fun `garbage and empty tables do not throw`() {
        assertFalse(FontCmapTable.hasAllGlyphs(ByteArray(0), intArrayOf(han)))
        assertFalse(FontCmapTable.hasAllGlyphs(byteArrayOf(0, 1, 2), intArrayOf(han)))
        assertFalse(FontCmapTable.hasAllGlyphs(ByteArray(64) { 0xFF.toByte() }, intArrayOf(han)))
    }

    @Test
    fun `an empty probe list is trivially satisfied`() {
        assertTrue(FontCmapTable.hasAllGlyphs(ByteArray(0), intArrayOf()))
    }
}
