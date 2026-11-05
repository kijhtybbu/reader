/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 */

package ua.acclorite.book_story.domain.use_case.book

import ua.acclorite.book_story.core.log.logE
import ua.acclorite.book_story.core.log.logI
import ua.acclorite.book_story.core.log.logW
import ua.acclorite.book_story.data.translator.BookTranslatorStore
import ua.acclorite.book_story.data.typography.ReaderTypographyStore
import ua.acclorite.book_story.domain.model.book.Book
import ua.acclorite.book_story.domain.repository.BookRepository
import ua.acclorite.book_story.domain.repository.BookmarkRepository
import ua.acclorite.book_story.domain.repository.HistoryRepository
import ua.acclorite.book_story.domain.service.ImageHandler
import javax.inject.Inject

private const val TAG = "DeleteBook"

class DeleteBookUseCase @Inject constructor(
    private val bookRepository: BookRepository,
    private val historyRepository: HistoryRepository,
    private val bookmarkRepository: BookmarkRepository,
    private val imageHandler: ImageHandler,
    private val readerTypographyStore: ReaderTypographyStore,
    private val bookTranslatorStore: BookTranslatorStore
) {

    suspend operator fun invoke(book: Book) {
        logI(TAG, "Deleting [${book.title}].")

        // Deleting cover image
        book.coverImage?.let { imageHandler.deleteCover(it) }?.onFailure {
            logW(TAG, "Could not delete cover image with error: ${it.message}")
        }

        // Deleting history
        historyRepository.deleteHistoryForBook(bookId = book.id).onFailure {
            logW(TAG, "Could not delete history for [${book.title}] with error: ${it.message}")
        }

        // fork: 书签跟着书一起删。书表主键是 INTEGER PRIMARY KEY AUTOINCREMENT（见 schema 19），
        // id 不会被新书复用，所以孤儿书签不会「串」到别的书上 —— 这是数据卫生问题，不是正确性问题。
        // 仍然要删：否则表会随删书无限增长，而 BookmarkRepository.deleteBookmarksForBook 一直是死代码。
        bookmarkRepository.deleteBookmarksForBook(bookId = book.id).onFailure {
            logW(TAG, "Could not delete bookmarks for [${book.title}] with error: ${it.message}")
        }

        // Deleting book
        bookRepository.deleteBook(book).fold(
            onSuccess = {
                // fork: 按书排版记录跟着书一起删。不删的话 uuid 永远不会再被打开，
                // 记录会随删书数量无限堆积。
                readerTypographyStore.remove(book.uuid)
                // fork: 按书翻译器行为同样跟着书删。
                bookTranslatorStore.remove(book.uuid)
                logI(TAG, "Successfully deleted [${book.title}].")
            },
            onFailure = {
                logE(TAG, "Could not delete [${book.title}] with error: ${it.message}")
            }
        )
    }
}