/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * fork 新增：导入字体的文件头校验。
 *
 * 为什么是纯函数：导入失败的原因（扩展名、体积、容器格式）必须逐条可测，
 * 而真正建 Typeface / hasGlyph 需要 Android 运行时。把「能不能收」与「收了之后
 * 能不能渲染」分开，JVM 单测才能覆盖全部分支。
 */

package ua.acclorite.book_story.data.storage.font

/** 收进来的字体容器类型。 */
enum class FontContainer {
    TRUETYPE,
    OPENTYPE_CFF
}

/** 拒收原因。UI 逐条映射成可操作的提示文案。 */
enum class FontRejection {
    EMPTY_FILE,
    TOO_LARGE,
    UNSUPPORTED_EXTENSION,
    NOT_A_FONT,
    FONT_COLLECTION,
    WOFF_REQUIRES_CONVERSION,
    LIMIT_REACHED,
    UNREADABLE
}

sealed interface FontFileCheck {
    data class Accepted(
        val container: FontContainer,
        val variable: Boolean
    ) : FontFileCheck

    data class Rejected(val reason: FontRejection) : FontFileCheck
}

object FontFileInspector {

    /** 40 MB：CJK 全量字体一般 10–30 MB，超过这个量级说明是成套字库或损坏文件。 */
    const val MAX_BYTES = 40L * 1024L * 1024L

    private const val MAGIC_TRUETYPE = 0x00010000
    private const val MAGIC_OTTO = 0x4F54544F
    private const val MAGIC_TTC = 0x74746366
    private const val MAGIC_WOFF = 0x774F4646
    private const val MAGIC_WOFF2 = 0x774F4632

    private const val SFNT_HEADER_BYTES = 12
    private const val SFNT_RECORD_BYTES = 16
    private const val TAG_FVAR = 0x66766172

    /**
     * @param extension 文件扩展名，可带前导点、大小写不敏感。
     * @param sizeBytes 实际拷贝下来的字节数（不信任 SAF 报告的 size）。
     * @param header 文件开头 [HEADER_BYTES] 字节，不足时原样传入。
     */
    fun inspect(extension: String, sizeBytes: Long, header: ByteArray): FontFileCheck {
        if (sizeBytes <= 0L) return FontFileCheck.Rejected(FontRejection.EMPTY_FILE)
        if (sizeBytes > MAX_BYTES) return FontFileCheck.Rejected(FontRejection.TOO_LARGE)

        val ext = extension.trim().removePrefix(".").lowercase()
        when (ext) {
            "ttc" -> return FontFileCheck.Rejected(FontRejection.FONT_COLLECTION)
            "woff", "woff2" -> return FontFileCheck.Rejected(FontRejection.WOFF_REQUIRES_CONVERSION)
            "ttf", "otf" -> Unit
            else -> return FontFileCheck.Rejected(FontRejection.UNSUPPORTED_EXTENSION)
        }

        val container = when (readInt(header, 0)) {
            MAGIC_TRUETYPE -> FontContainer.TRUETYPE
            MAGIC_OTTO -> FontContainer.OPENTYPE_CFF
            MAGIC_TTC -> return FontFileCheck.Rejected(FontRejection.FONT_COLLECTION)
            MAGIC_WOFF, MAGIC_WOFF2 ->
                return FontFileCheck.Rejected(FontRejection.WOFF_REQUIRES_CONVERSION)
            else -> return FontFileCheck.Rejected(FontRejection.NOT_A_FONT)
        }

        return FontFileCheck.Accepted(
            container = container,
            variable = hasVariationTable(header)
        )
    }

    /**
     * sfnt table directory 里是否有 `fvar`。
     *
     * 判据是整张目录而不是 `wght` 轴：一个可变字体可能只有 `wdth` 轴，但我们只需要知道
     * 「是否能按轴重建」来决定索引里的 variable 标记。目录本身在文件最前面（12 + 16 × numTables），
     * [HEADER_BYTES] 足够覆盖正常字体的目录；覆盖不到时保守地当作静态字体。
     */
    private fun hasVariationTable(header: ByteArray): Boolean {
        if (header.size < SFNT_HEADER_BYTES) return false
        val numTables = readUShort(header, 4)
        if (numTables == 0) return false
        val directoryEnd = SFNT_HEADER_BYTES + numTables * SFNT_RECORD_BYTES
        if (directoryEnd > header.size) return false

        for (index in 0 until numTables) {
            val recordOffset = SFNT_HEADER_BYTES + index * SFNT_RECORD_BYTES
            if (readInt(header, recordOffset) == TAG_FVAR) return true
        }
        return false
    }

    private fun readInt(bytes: ByteArray, offset: Int): Int {
        if (offset + 4 > bytes.size) return -1
        return ((bytes[offset].toInt() and 0xFF) shl 24) or
                ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
                ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
                (bytes[offset + 3].toInt() and 0xFF)
    }

    private fun readUShort(bytes: ByteArray, offset: Int): Int {
        if (offset + 2 > bytes.size) return 0
        return ((bytes[offset].toInt() and 0xFF) shl 8) or
                (bytes[offset + 1].toInt() and 0xFF)
    }

    /**
     * 导入时读取的文件头长度。最少要覆盖 sfnt header + 正常字体的 table directory。
     */
    const val HEADER_BYTES = 4096
}
