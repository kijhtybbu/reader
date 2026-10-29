/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 */

package ua.acclorite.book_story.ui.reader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ua.acclorite.book_story.domain.model.book.Book
import ua.acclorite.book_story.domain.model.book.CharsetConfidence

/**
 * fork 新增：TXT 编码告警的显隐规则。
 *
 * 缺陷背景：乱码是被**静默**缓存下来的（U+FFFD 也算可见字符），所以「什么时候必须告诉用户
 * 这次识别不可靠」本身就是一条需要钉住的规则 —— 判漏了就又回到静默乱码，
 * 判多了则每一本正常的书都挂着一条吓人的警告。
 */
class ReaderEncodingOptionTest {

    private fun book(
        filePath: String,
        charsetName: String? = null,
        confidence: CharsetConfidence? = null
    ) = Book.default.copy(
        filePath = filePath,
        charsetName = charsetName,
        charsetConfidence = confidence
    )

    @Test
    fun `fallback detection on a txt book must warn`() {
        assertTrue(
            shouldWarnAboutTxtCharset(
                book("/books/novel.txt", "GB18030", CharsetConfidence.FALLBACK)
            )
        )
    }

    @Test
    fun `likely and certain detection must not warn`() {
        assertFalse(
            shouldWarnAboutTxtCharset(
                book("/books/novel.txt", "GB18030", CharsetConfidence.LIKELY)
            )
        )
        assertFalse(
            shouldWarnAboutTxtCharset(
                book("/books/novel.txt", "UTF-8", CharsetConfidence.CERTAIN)
            )
        )
    }

    /**
     * 手动覆盖之后提示必须消失（用户在 ChangeBookCharsetUseCase 里被置为 CERTAIN），
     * 否则用户修好了书还一直看到「识别不可靠」。
     */
    @Test
    fun `manual override silences the warning`() {
        assertFalse(
            shouldWarnAboutTxtCharset(
                book("/books/novel.txt", "Big5", CharsetConfidence.CERTAIN)
            )
        )
    }

    @Test
    fun `never detected txt book does not warn`() {
        assertFalse(shouldWarnAboutTxtCharset(book("/books/novel.txt")))
    }

    @Test
    fun `non-txt books never warn and offer no encoding control`() {
        assertFalse(
            shouldWarnAboutTxtCharset(
                book("/books/novel.epub", "GB18030", CharsetConfidence.FALLBACK)
            )
        )
        assertFalse(
            shouldWarnAboutTxtCharset(
                book("/books/novel.pdf", "GB18030", CharsetConfidence.FALLBACK)
            )
        )
    }

    @Test
    fun `txt extension match is case insensitive`() {
        assertTrue(
            shouldWarnAboutTxtCharset(
                book("/books/novel.TXT", "GB18030", CharsetConfidence.FALLBACK)
            )
        )
    }
}
