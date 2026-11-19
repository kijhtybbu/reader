/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * fork 新增：用户字体的 Typeface 解析与缓存。
 *
 * 三个约束决定了这里的写法：
 *  1. `Font(file = ...)` 构造即建 Typeface（AndroidPreloadedFont 的 init），不能按 9 个字重
 *     各 new 一个；这里按 (file, weight, italic) 用 LruCache 只建需要的那一份。
 *  2. 20 MB 的 CJK 字体首次加载 100–300 ms，必须在 Dispatchers.Default 上建，
 *     不能在组合/测量线程同步建（否则切字体那一下掉帧）。
 *  3. `FontFamily(typeface)` 没有回退链，拉丁字库导入后中文会变豆腐块。minSdk 29 的
 *     CustomFallbackBuilder 是唯一能把系统字体串到后面的 API，所以每个 Typeface 都带
 *     "sans-serif" 系统回退。
 *
 * 静态字体只从文件建一次基础 Typeface（baseTypefaces），字重变化走 Typeface.create，
 * 不再重新读文件 —— 否则拖动字重滑块会把 20 MB 的文件读 9 遍。
 */

package ua.acclorite.book_story.ui.reader.data

import android.content.Context
import android.graphics.Typeface
import android.graphics.fonts.Font
import android.graphics.fonts.FontFamily
import android.util.LruCache
import androidx.compose.ui.text.font.FontFamily as ComposeFontFamily
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ua.acclorite.book_story.core.data.FileData
import ua.acclorite.book_story.core.log.logW
import ua.acclorite.book_story.data.storage.font.FontCmapTable
import ua.acclorite.book_story.ui.reader.model.UserFontEntry
import java.io.File
import java.io.RandomAccessFile

private const val TAG = "UserFontTypefaces"

/** 「汉」「。」：只查汉字会漏掉缺中文标点的字库，两者都要。 */
private val CJK_PROBES = intArrayOf(0x6C49, 0x3002)

/** cmap 表本身很小；超过这个值说明目录已经坏了。 */
private const val MAX_CMAP_BYTES = 8 * 1024 * 1024

/** 系统回退族。中文环境落到系统内置 CJK 字体；拉丁环境落到系统无衬线体。 */
private const val SYSTEM_FALLBACK_FAMILY = "sans-serif"

object UserFontTypefaces {

    private data class Key(
        val fileName: String,
        val weight: Int,
        val italic: Boolean
    )

    /**
     * 12 份：设置页最多展示 [ua.acclorite.book_story.ui.reader.data.UserFontRegistry.MAX_FONTS] 个
     * 用户字体，同时可见的那几个还会各自按字重/斜体分桶。缓存过大没有意义 ——
     * 每个 FontFamily 背后是一份 mmap 的字体文件。
     */
    private val cache = object : LruCache<Key, ComposeFontFamily>(12) {}

    /**
     * 静态字体的基础 Typeface（按文件名），容量与 [UserFontRegistry.MAX_FONTS] 对齐：
     * 设置页可以同时展示 10 个字体，4 份会让「拖动字重滑块」反复重读 20 MB 的字库。
     * 字重变化不再读文件。
     */
    private val baseTypefaces = object : LruCache<String, Typeface>(10) {}

    fun cached(entry: UserFontEntry, weight: Int, italic: Boolean): ComposeFontFamily? =
        cache.get(Key(entry.fileName, weight, italic))

    suspend fun resolve(
        context: Context,
        entry: UserFontEntry,
        weight: Int,
        italic: Boolean
    ): ComposeFontFamily = withContext(Dispatchers.Default) {
        val key = Key(entry.fileName, weight, italic)
        cache.get(key)?.let { return@withContext it }

        val file = File(FileData.getFontsDirectory(context), entry.fileName)
        // fork: 字体文件可能在索引加载后被删（用户清数据/系统清理）。解析失败必须回落默认
        // 字体，不能把异常抛进 produceState 的协程把阅读页打断。
        val family = try {
            buildFontFamily(file, entry.variable, weight)
        } catch (e: Exception) {
            logW(TAG, "Could not build typeface for ${entry.fileName}: ${e.message}")
            ComposeFontFamily.Default
        }
        cache.put(key, family)
        family
    }

    fun invalidate(entry: UserFontEntry) {
        cache.snapshot().keys
            .filter { it.fileName == entry.fileName }
            .forEach { cache.remove(it) }
        baseTypefaces.remove(entry.fileName)
    }

    /**
     * 建一份带系统回退链的 FontFamily。
     *
     * 可变字体：用 `wght` 轴把字重真正写进 Typeface（Font.Builder.setWeight 同时告诉
     * FontFamily「我是这个字重」，避免再次被合成加粗）。
     * 静态字体：文件只有一份 face，`Font.Builder.setWeight` 只是元数据；基础 Typeface
     * 只建一次，然后用 `Typeface.create(base, weight, false)` 让系统在 ≥700 时做合成粗体
     * （100–600 显示字体自带字重）。这条 API 就是 Compose 的 fontSynthesis 内部用的那条。
     *
     * 斜体不在这里做：单 face 字体没有 italic 字型，Android 也不会对自定义字体做伪斜体，
     * 斜体由 TextStyle / SpanStyle 的 TextGeometricTransform（skewX）合成，
     * 见 readerParagraphTextStyle 与 rememberReaderNodes。
     * Key 里仍保留 italic，是为了让「按 (file, weight, italic) 缓存」的约定成立；
     * 两个斜体标记指向的是同一份字节。
     */
    private fun buildFontFamily(
        file: File,
        variable: Boolean,
        weight: Int
    ): ComposeFontFamily {
        val typeface = if (variable) {
            buildVariableTypeface(file, weight)
        } else {
            val base = baseTypefaces.get(file.name)
                ?: buildFallbackTypeface(Font.Builder(file).build())
                    .also { baseTypefaces.put(file.name, it) }
            Typeface.create(base, weight, false)
        }
        return ComposeFontFamily(typeface)
    }

    private fun buildVariableTypeface(file: File, weight: Int): Typeface {
        val font = Font.Builder(file)
            .setWeight(weight)
            .setFontVariationSettings("'wght' $weight")
            .build()
        return buildFallbackTypeface(font)
    }

    private fun buildFallbackTypeface(font: Font): Typeface =
        Typeface.CustomFallbackBuilder(FontFamily.Builder(font).build())
            .setSystemFallback(SYSTEM_FALLBACK_FAMILY)
            .build()

    /**
     * 导入校验：这个文件能不能真的当字体打开，以及它自不自带中文字形。
     *
     * **会抛异常**：文件头魔数对、内容是垃圾的下载残骸（伪造 sfnt 头的 HTML/JSON）只有
     * 在这里才能拦下来，调用方据此落成
     * [ua.acclorite.book_story.data.storage.font.FontRejection.NOT_A_FONT]。
     * 渲染路径上需要「不能崩」的版本见 [hasCjkGlyphs]。
     *
     * 中文字形不用 `Paint.hasGlyph` 判定：它会顺着系统回退链找字。实测 API 36 上 Raleway
     * （cmap 里只有拉丁）也会返回 true，徽标就成了摆设。这里直接读 cmap 的格式 4/12 子表，
     * 回答的是「文件里有没有这两个码位」。
     */
    fun validateAndCheckCjk(file: File): Boolean {
        // Font.Builder 会解析 sfnt 目录与表头：魔数对但结构坏的文件在这里就抛。
        Font.Builder(file).build()
        val cmap = readCmapTable(file)
            ?: throw IllegalArgumentException("Font has no readable cmap table: ${file.name}")
        return FontCmapTable.hasAllGlyphs(cmap, CJK_PROBES)
    }

    /** [validateAndCheckCjk] 的不抛异常版本：读不了就当「没有中文字形」。 */
    fun hasCjkGlyphs(file: File): Boolean = try {
        validateAndCheckCjk(file)
    } catch (e: Exception) {
        logW(TAG, "Could not read cmap of ${file.name}: ${e.message}")
        false
    }

    private fun readCmapTable(file: File): ByteArray? = RandomAccessFile(file, "r").use { raf ->
        val header = ByteArray(12)
        raf.readFully(header)
        val numTables = ((header[4].toInt() and 0xFF) shl 8) or
                (header[5].toInt() and 0xFF)
        if (numTables <= 0) return null

        val directory = ByteArray(numTables * 16)
        raf.readFully(directory)

        for (index in 0 until numTables) {
            val record = index * 16
            val isCmap = directory[record] == 'c'.code.toByte() &&
                    directory[record + 1] == 'm'.code.toByte() &&
                    directory[record + 2] == 'a'.code.toByte() &&
                    directory[record + 3] == 'p'.code.toByte()
            if (!isCmap) continue

            val offset = readUInt(directory, record + 8)
            val length = readUInt(directory, record + 12)
            if (offset < 0 || length <= 0 || length > MAX_CMAP_BYTES) return null

            val table = ByteArray(length)
            raf.seek(offset.toLong())
            raf.readFully(table)
            return table
        }
        null
    }

    private fun readUInt(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 24) or
                ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
                ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
                (bytes[offset + 3].toInt() and 0xFF)
}
