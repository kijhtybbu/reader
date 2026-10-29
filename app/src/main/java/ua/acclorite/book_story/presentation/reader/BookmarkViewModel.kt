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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ua.acclorite.book_story.core.log.logW
import ua.acclorite.book_story.domain.model.bookmark.Bookmark
import ua.acclorite.book_story.domain.model.bookmark.bookmarkForNode
import ua.acclorite.book_story.domain.repository.BookmarkRepository
import javax.inject.Inject

private const val TAG = "BookmarkViewModel"

@HiltViewModel
class BookmarkViewModel @Inject constructor(
    private val bookmarkRepository: BookmarkRepository
) : ViewModel() {

    private val _bookmarks = MutableStateFlow<List<Bookmark>>(emptyList())
    val bookmarks: StateFlow<List<Bookmark>> = _bookmarks.asStateFlow()

    private var currentBookId: Int? = null

    // fork 新增：写锁。
    // StateFlow 本身线程安全，这把锁要保护的不是状态，而是「读快照 → 判断这一段有没有书签 → 写库」
    // 这个序列：连点两下时，第二下若读到第一下写库之前的旧快照，两次都会判定为"没有书签"，
    // 一次双击就落两条记录。
    private val writeMutex = Mutex()

    fun load(bookId: Int) {
        if (currentBookId == bookId) return
        currentBookId = bookId
        refresh()
    }

    fun refresh() {
        val bookId = currentBookId ?: return
        viewModelScope.launch {
            writeMutex.withLock { reload(bookId) }
        }
    }

    /**
     * 切换当前行的书签（段落级语义，见 [BookmarkRepository]）：
     * 这一段没有书签 → 新增；已有书签且就落在这一行（段内偏移相同）→ 删除；
     * 已有书签但落在别的行 → 把这条书签挪到当前这一行。
     */
    fun toggle(bookmark: Bookmark) {
        val bookId = currentBookId ?: return
        viewModelScope.launch {
            writeMutex.withLock {
                val current = bookmarkRepository.getBookmarks(bookId).getOrElse {
                    logW(TAG, "Could not read bookmarks of [$bookId] with error: ${it.message}")
                    return@withLock
                }
                val existing = bookmarkForNode(current, bookmark.nodeId)

                val result = when {
                    existing == null -> bookmarkRepository.addBookmark(bookmark)
                    // 同一行再点一次 = 取消这个书签
                    existing.nodeIntraOffset == bookmark.nodeIntraOffset ->
                        bookmarkRepository.deleteBookmark(existing.id)

                    // 挪到新的一行：删掉旧的再插新的。保留原来的 createdAt ——
                    // 列表按 nodeId 排序，「什么时候收藏的这一段」不该因为换了行而重置。
                    else -> bookmarkRepository.deleteBookmark(existing.id).fold(
                        onSuccess = {
                            bookmarkRepository.addBookmark(
                                bookmark.copy(createdAt = existing.createdAt)
                            )
                        },
                        onFailure = { Result.failure(it) }
                    )
                }
                result.onFailure {
                    logW(
                        TAG,
                        "Could not toggle bookmark of node [${bookmark.nodeId}] with error: ${it.message}"
                    )
                }

                reload(bookId)
            }
        }
    }

    fun delete(id: Int) {
        val bookId = currentBookId ?: return
        viewModelScope.launch {
            writeMutex.withLock {
                bookmarkRepository.deleteBookmark(id).onFailure {
                    logW(TAG, "Could not delete bookmark [$id] with error: ${it.message}")
                }
                reload(bookId)
            }
        }
    }

    // fork: 刷新失败时保留上一份列表，不要清空 —— 一次读库失败不该让书签面板凭空变空
    private suspend fun reload(bookId: Int) {
        bookmarkRepository.getBookmarks(bookId).fold(
            onSuccess = { _bookmarks.value = it },
            onFailure = {
                logW(TAG, "Could not reload bookmarks of [$bookId] with error: ${it.message}")
            }
        )
    }
}
