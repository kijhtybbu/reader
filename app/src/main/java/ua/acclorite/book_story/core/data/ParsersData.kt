/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 */

package ua.acclorite.book_story.core.data

object ParsersData {
    // fork: 2 → 3。Jsoup 文档解析器修了「标题内嵌 <b>/<i>/<a> 时把格式标记原样显示出来」
    // （例如 <h1><b>20</b></h1> 显示成 {{B-START}}20{{B-END}}）。提版本号让所有
    // EPUB/HTML/XML 的已缓存内容失效，用户重新打开书时自动按修复后的解析器重建，
    // 不必删书重导。
    private const val JSOUP_PARSER = 3
    private const val EPUB_PARSER = 1
    private const val PDF_PARSER = 1
    private const val HTML_PARSER = 1
    // fork: 1 → 2。TXT 解析器不再无条件按 UTF-8 解码（新增编码探测）。
    // 提版本号让已经缓存的旧内容失教：已导入的 GBK 书在下次打开时会重新解析，
    // 从而自动修好乱码 —— 不必等用户自己找到手动编码选项。
    private const val TXT_PARSER = 2
    private const val XML_PARSER = 1

    fun getCurrentParserVersion(parser: String): Int {
        when (parser.lowercase().trim()) {
            "epub" -> {
                return EPUB_PARSER + JSOUP_PARSER
            }

            "pdf" -> {
                return PDF_PARSER
            }

            "html" -> {
                return HTML_PARSER + JSOUP_PARSER
            }

            "txt" -> {
                return TXT_PARSER
            }

            "xml" -> {
                return XML_PARSER + JSOUP_PARSER
            }

            else -> {
                error("Incorrect parser.")
            }
        }
    }
}