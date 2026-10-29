/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 */

package ua.acclorite.book_story.data.parser.content

import ua.acclorite.book_story.domain.model.book.CharsetConfidence
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * fork 新增：TXT 编码探测。
 *
 * 上游用 `openInputStream()?.bufferedReader()` 无条件按 UTF-8 解码，GBK（中文网络小说的
 * 常见编码）正文会渲染成乱码，而且因为 U+FFFD 也算「可见字符」（[ua.acclorite.book_story.core.helpers.containsVisibleText]），
 * 乱码会被当成正常内容缓存下来，用户得不到任何提示。
 *
 * 判定顺序（每一步都比「统计式探测」更可靠，而且不需要任何词典或依赖）：
 * 1. BOM —— 文件自己声明了编码，[CharsetConfidence.CERTAIN]；
 * 2. 严格 UTF-8 解码（`CodingErrorAction.REPORT`）—— GBK/BIG5 的双字节序列几乎不可能
 *    恰好构成合法 UTF-8 多字节序列，所以「解得通」本身就是强证据，[CharsetConfidence.CERTAIN]；
 * 3. 严格 GB18030 解码 —— GB18030 是 GBK 的超集，GBK 文件也用它的解码器，
 *    [CharsetConfidence.LIKELY]；
 * 4. 严格 BIG5 解码，[CharsetConfidence.LIKELY]；
 * 5. 都不成立时按 GB18030 硬解，[CharsetConfidence.FALLBACK] —— 由 UI 明确告知用户。
 *
 * 为什么不用统计式探测（例如按常用字频率打分）：那需要一张字符频率表（体积、许可证、
 * 维护成本），而这里要区分的是 UTF-8 / GBK / BIG5 三种**互斥**的字节结构 ——
 * 严格解码的「能/不能」是二值判定，既不需要表，也可以在 JVM 单测里用真实字节断言。
 *
 * 已知局限：GB18030 与 BIG5 的字节空间重叠 —— 任何结构合法的 BIG5 双字节序列
 * （实测 13708/13708 个有效 BIG5 双字节组合）都能被 GB18030 严格解码，所以真实的 BIG5
 * 文件会落到第 3 步（GB18030，[CharsetConfidence.LIKELY]）而不是第 4 步。第 4 步只在
 * GB18030 拒绝该字节序列时才有机会命中。要自动区分 GBK 与 BIG5，需要按「常用字占比」
 * 做频率打分，那属于统计式探测，本 fork 不做 —— BIG5 提供手动覆盖即可。
 */
const val TXT_CHARSET_SAMPLE_SIZE = 100 * 1024

/**
 * @property charset 判定出的编码。
 * @property confidence 判定可信度，[CharsetConfidence.FALLBACK] 必须被告知用户。
 * @property hadBom 是否由 BOM 直接判定（此时被探测的样本开头带 BOM 字节）。
 */
data class CharsetGuess(
    val charset: Charset,
    val confidence: CharsetConfidence,
    val hadBom: Boolean
)

/**
 * fork 新增：手动覆盖可选的编码（「自动识别」由 UI 作为额外的一项提供，不属于这里）。
 *
 * 存的是 [Charset.name] 的规范名，与探测结果同一套拼写，避免同一编码出现两种写法的比较。
 * 顺序：先 UTF-8（无 BOM 的 UTF-8 最常见），再 GB18030/GBK（中文网络小说），最后其余。
 */
val TXT_CHARSET_OVERRIDES: List<Charset> = listOf(
    Charsets.UTF_8,
    Charset.forName("GB18030"),
    Charset.forName("GBK"),
    Charset.forName("Big5"),
    Charsets.UTF_16LE,
    Charsets.UTF_16BE
)

private val UTF8_BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
private val UTF16_LE_BOM = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
private val UTF16_BE_BOM = byteArrayOf(0xFE.toByte(), 0xFF.toByte())

private val GB18030: Charset = Charset.forName("GB18030")
private val BIG5: Charset = Charset.forName("Big5")

fun detectTxtCharset(sample: ByteArray): CharsetGuess {
    when {
        sample.startsWith(UTF8_BOM) -> return CharsetGuess(
            charset = Charsets.UTF_8,
            confidence = CharsetConfidence.CERTAIN,
            hadBom = true
        )

        sample.startsWith(UTF16_LE_BOM) -> return CharsetGuess(
            charset = Charsets.UTF_16LE,
            confidence = CharsetConfidence.CERTAIN,
            hadBom = true
        )

        sample.startsWith(UTF16_BE_BOM) -> return CharsetGuess(
            charset = Charsets.UTF_16BE,
            confidence = CharsetConfidence.CERTAIN,
            hadBom = true
        )
    }

    // fork: UTF-16 只能靠 BOM 判定 —— 任意一对字节都是合法 UTF-16 码元，
    // 没有 BOM 时它会对任何文件「解码成功」，放在这里只会抢走正确结果。

    if (strictlyDecodes(sample, Charsets.UTF_8)) {
        return CharsetGuess(Charsets.UTF_8, CharsetConfidence.CERTAIN, hadBom = false)
    }
    if (strictlyDecodes(sample, GB18030)) {
        return CharsetGuess(GB18030, CharsetConfidence.LIKELY, hadBom = false)
    }
    if (strictlyDecodes(sample, BIG5)) {
        return CharsetGuess(BIG5, CharsetConfidence.LIKELY, hadBom = false)
    }

    return CharsetGuess(GB18030, CharsetConfidence.FALLBACK, hadBom = false)
}

/**
 * fork 新增：正文解码前要丢掉的 BOM 字节数（0 表示没有 BOM）。
 *
 * 按**最终生效的编码**判断而不是只看探测结果：用户手动覆盖时没有探测结果，
 * 若不处理，强制选 UTF-8 的带 BOM 文件正文开头会多出一个 U+FEFF。
 */
fun txtBomPrefixLength(sample: ByteArray, charset: Charset): Int = when (charset.name()) {
    Charsets.UTF_8.name() -> if (sample.startsWith(UTF8_BOM)) UTF8_BOM.size else 0
    Charsets.UTF_16LE.name() -> if (sample.startsWith(UTF16_LE_BOM)) UTF16_LE_BOM.size else 0
    Charsets.UTF_16BE.name() -> if (sample.startsWith(UTF16_BE_BOM)) UTF16_BE_BOM.size else 0
    else -> 0
}

/**
 * 严格解码：[sample] 里出现任何畸形或无法映射的字节组合都算失败。
 *
 * `endOfInput = false` 是关键 —— [sample] 只是文件的前 100 KB，末尾可能正好截断一个
 * 多字节字符；把它当作「输入未尽」（UNDERFLOW）而不是「畸形」，否则所有中文文件都会被
 * 判成 [CharsetConfidence.FALLBACK]。
 */
private fun strictlyDecodes(sample: ByteArray, charset: Charset): Boolean {
    val decoder = charset.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)

    val input = ByteBuffer.wrap(sample)
    val output = CharBuffer.allocate(maxOf(1, sample.size))

    while (true) {
        val result = decoder.decode(input, output, false)
        if (result.isError) return false
        // UNDERFLOW：剩下的字节都是不完整的多字节序列，可以接受。
        if (!result.isOverflow) return true
        output.clear()
    }
}

private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
    if (size < prefix.size) return false
    return prefix.indices.all { this[it] == prefix[it] }
}
