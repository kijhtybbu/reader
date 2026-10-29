/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * fork 新增：书签状态。
 *
 * 独立于 ReaderModel：书签是"旁挂"功能，不需要参与阅读进度那套状态机，
 * 拆开可以避免往那个已经很长的 model 里再塞逻辑。
 */

package ua.acclorite.book_story.presentation.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import ua.acclorite.book_story.domain.model.bookmark.Bookmark
import ua.acclorite.book_story.domain.repository.BookmarkRepository
import javax.inject.Inject

@HiltViewModel
class BookmarkViewModel @Inject constructor(
    private val bookmarkRepository: BookmarkRepository
) : ViewModel() {

    private val _bookmarks = MutableStateFlow<List<Bookmark>>(emptyList())
    val bookmarks: StateFlow<List<Bookmark>> = _bookmarks.asStateFlow()

    private var currentBookId: Int? = null

    fun load(bookId: Int) {
        if (currentBookId == bookId) return
        currentBookId = bookId
        refresh()
    }

    fun refresh() {
        val bookId = currentBookId ?: return
        viewModelScope.launch {
            _bookmarks.value = bookmarkRepository.getBookmarks(bookId).getOrElse { emptyList() }
        }
    }

    fun toggle(bookmark: Bookmark) {
        val existing = _bookmarks.value.firstOrNull {
            it.nodeId == bookmark.nodeId && it.nodeIntraOffset == bookmark.nodeIntraOffset
        }
        viewModelScope.launch {
            if (existing != null) {
                bookmarkRepository.deleteBookmark(existing.id)
            } else {
                bookmarkRepository.addBookmark(bookmark)
            }
            refresh()
        }
    }

    fun delete(id: Int) {
        viewModelScope.launch {
            bookmarkRepository.deleteBookmark(id)
            refresh()
        }
    }
}
