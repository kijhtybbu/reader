/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 */

package ua.acclorite.book_story.domain.use_case.book

import ua.acclorite.book_story.core.log.logI
import ua.acclorite.book_story.domain.model.book.Book
import ua.acclorite.book_story.domain.model.book.CharsetConfidence
import ua.acclorite.book_story.domain.repository.BookRepository
import javax.inject.Inject

private const val TAG = "ChangeBookCharset"

/**
 * fork 新增：把用户选的 TXT 编码写回书，并作废已缓存的内容。
 *
 * 两步必须一起做：只改编码不清缓存，用户看到的仍是旧编码解出来的乱码，控制项看起来「没反应」；
 * 只清缓存不改编码，重新解析又按旧编码解一遍。
 */
class ChangeBookCharsetUseCase @Inject constructor(
    private val bookRepository: BookRepository
) {
    /**
     * @param charsetName 规范编码名（[java.nio.charset.Charset.name]）。null = 自动识别。
     * @return 更新后的书，调用方据此重新解析。
     */
    suspend operator fun invoke(book: Book, charsetName: String?): Result<Book> = runCatching {
        logI(TAG, "Changing charset of [${book.title}] to [${charsetName ?: "auto"}].")

        val updatedBook = book.copy(
            charsetName = charsetName,
            // fork: 手动选择就是「确定」—— 选中之后「识别不可靠」的提示必须消失，
            // 否则用户修好了书还一直看到警告。选「自动」则回到未识别状态。
            charsetConfidence = charsetName?.let { CharsetConfidence.CERTAIN }
        )

        bookRepository.updateBook(updatedBook).getOrThrow()
        bookRepository.clearBookContent(updatedBook).getOrThrow()

        updatedBook
    }
}
