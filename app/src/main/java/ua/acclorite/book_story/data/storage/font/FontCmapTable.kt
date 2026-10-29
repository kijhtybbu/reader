/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * fork 新增：字体 cmap 覆盖检测。
 *
 * 为什么不用 `Paint.hasGlyph`：实测在 API 36 上，Raleway（cmap 里只有拉丁）用
 * `Typeface.createFromFile` + `Paint.hasGlyph("汉")` 会返回 true —— hasGlyph 会顺着
 * 系统回退链找字，回答的是「这台设备能不能显示汉」，而不是「这个字体文件有没有汉」。
 * 徽标要回答的是后者，所以直接解析 cmap。
 *
 * 只支持 format 4（BMP）与 format 12（UCS-4）：CJK 字体只用这两种；其余格式保守返回 false。
 */

package ua.acclorite.book_story.data.storage.font

object FontCmapTable {

    fun hasAllGlyphs(cmap: ByteArray, codePoints: IntArray): Boolean =
        codePoints.all { hasGlyph(cmap, it) }

    private fun hasGlyph(cmap: ByteArray, codePoint: Int): Boolean {
        if (codePoint < 0) return false

        val subtableCount = readUShort(cmap, 2)
        if (subtableCount <= 0) return false

        for (index in 0 until subtableCount) {
            val record = 4 + index * 8
            if (record + 8 > cmap.size) return false

            val platformId = readUShort(cmap, record)
            val encodingId = readUShort(cmap, record + 2)
            if (!isUnicodeSubtable(platformId, encodingId)) continue

            val offset = readUInt(cmap, record + 4)
            if (offset < 0 || offset + 2 > cmap.size) continue

            if (subtableHasGlyph(cmap, offset, codePoint)) return true
        }
        return false
    }

    private fun isUnicodeSubtable(platformId: Int, encodingId: Int): Boolean =
        platformId == 0 || (platformId == 3 && (encodingId == 1 || encodingId == 10))

    private fun subtableHasGlyph(cmap: ByteArray, offset: Int, codePoint: Int): Boolean {
        return when (readUShort(cmap, offset)) {
            4 -> format4HasGlyph(cmap, offset, codePoint)
            12 -> format12HasGlyph(cmap, offset, codePoint)
            else -> false
        }
    }

    /**
     * format 4：segCount 个 [start, end] 段；idRangeOffset == 0 时 glyph 由 idDelta 直接算出，
     * 否则从 glyphIdArray 里取。两种路径都必须处理，很多 CJK 字体用后者。
     */
    private fun format4HasGlyph(cmap: ByteArray, offset: Int, codePoint: Int): Boolean {
        if (codePoint > 0xFFFF) return false

        val segCountX2 = readUShort(cmap, offset + 6)
        if (segCountX2 <= 0 || segCountX2 % 2 != 0) return false
        val segCount = segCountX2 / 2

        val endCodes = offset + 14
        val startCodes = endCodes + segCount * 2 + 2 // + reservedPad
        val idDeltas = startCodes + segCount * 2
        val idRangeOffsets = idDeltas + segCount * 2
        if (idRangeOffsets + segCount * 2 > cmap.size) return false

        for (segment in 0 until segCount) {
            val endCode = readUShort(cmap, endCodes + segment * 2)
            val startCode = readUShort(cmap, startCodes + segment * 2)
            if (codePoint < startCode || codePoint > endCode) continue

            val idDelta = readShort(cmap, idDeltas + segment * 2)
            val rangeOffset = readUShort(cmap, idRangeOffsets + segment * 2)
            if (rangeOffset == 0) {
                return ((codePoint + idDelta) and 0xFFFF) != 0
            }

            val glyphAddress =
                idRangeOffsets + segment * 2 + rangeOffset + (codePoint - startCode) * 2
            if (glyphAddress + 2 > cmap.size) return false

            val glyph = readUShort(cmap, glyphAddress)
            return glyph != 0 && (((glyph + idDelta) and 0xFFFF) != 0)
        }
        return false
    }

    /** format 12：nGroups 个 [startChar, endChar] 段，glyph = startGlyph + 偏移。 */
    private fun format12HasGlyph(cmap: ByteArray, offset: Int, codePoint: Int): Boolean {
        if (offset + 16 > cmap.size) return false

        val groupCount = readUInt(cmap, offset + 12)
        if (groupCount <= 0 || groupCount > 1_000_000) return false

        for (group in 0 until groupCount) {
            val record = offset + 16 + group * 12
            if (record + 12 > cmap.size) return false

            val startChar = readUInt(cmap, record)
            val endChar = readUInt(cmap, record + 4)
            val startGlyph = readUInt(cmap, record + 8)
            if (startChar < 0 || endChar < startChar || startGlyph < 0) continue

            if (codePoint in startChar..endChar) {
                return startGlyph + (codePoint - startChar) != 0
            }
        }
        return false
    }

    private fun readUShort(bytes: ByteArray, offset: Int): Int {
        if (offset < 0 || offset + 2 > bytes.size) return -1
        return ((bytes[offset].toInt() and 0xFF) shl 8) or
                (bytes[offset + 1].toInt() and 0xFF)
    }

    private fun readShort(bytes: ByteArray, offset: Int): Int {
        val value = readUShort(bytes, offset)
        if (value < 0) return 0
        return if (value and 0x8000 != 0) value - 0x10000 else value
    }

    private fun readUInt(bytes: ByteArray, offset: Int): Int {
        if (offset < 0 || offset + 4 > bytes.size) return -1
        return ((bytes[offset].toInt() and 0xFF) shl 24) or
                ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
                ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
                (bytes[offset + 3].toInt() and 0xFF)
    }
}
