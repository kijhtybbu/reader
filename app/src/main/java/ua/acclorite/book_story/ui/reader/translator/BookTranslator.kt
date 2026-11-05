/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * fork 新增：当前书翻译器行为的组合层入口。
 *
 * 与排版那套一样，用 CompositionLocal 把「当前作用域」收敛成一个来源：
 *  - ProvideSettings 注入一个默认态（关闭、no-op），全局设置页不会渲染这两个开关；
 *  - ReaderScreen 用 rememberBookTranslator(book.uuid) 覆盖成本书态；
 *  - 阅读页与书内翻译器 Tab 都从 LocalBookTranslator 读写，保证只影响本书。
 */

package ua.acclorite.book_story.ui.reader.translator

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ua.acclorite.book_story.data.translator.BookTranslatorStore
import ua.acclorite.book_story.data.translator.BookTranslatorValues
import ua.acclorite.book_story.data.translator.resolve
import ua.acclorite.book_story.presentation.reader.model.ReaderLookupGesture

val LocalBookTranslatorStore = staticCompositionLocalOf<BookTranslatorStore> {
    error("No book translator store provided")
}

val LocalBookTranslator = compositionLocalOf<BookTranslatorState> {
    error("No book translator provided")
}

@Immutable
data class BookTranslatorState(
    val values: BookTranslatorValues,
    val actions: BookTranslatorActions
)

@Stable
interface BookTranslatorActions {
    fun setLookupGesture(gesture: ReaderLookupGesture)
    fun setDoubleClickTranslation(enabled: Boolean)
}

@Stable
private object NoOpBookTranslatorActions : BookTranslatorActions {
    override fun setLookupGesture(gesture: ReaderLookupGesture) = Unit
    override fun setDoubleClickTranslation(enabled: Boolean) = Unit
}

@Stable
private class BookTranslatorActionsImpl(
    private val store: BookTranslatorStore,
    private val bookUuid: String
) : BookTranslatorActions {
    override fun setLookupGesture(gesture: ReaderLookupGesture) =
        store.setLookupGesture(bookUuid, gesture)

    override fun setDoubleClickTranslation(enabled: Boolean) =
        store.setDoubleClickTranslation(bookUuid, enabled)
}

/**
 * 全局默认态：关闭、写入无操作。只用于 ProvideSettings 的兜底，
 * 书内两个开关由 ReaderScreen 覆盖后才有实际作用。
 */
@Composable
fun rememberGlobalBookTranslator(): BookTranslatorState = remember {
    BookTranslatorState(
        values = BookTranslatorValues(),
        actions = NoOpBookTranslatorActions
    )
}

/**
 * 本书态。没有记录时就是「关闭」——旧全局值不参与，保证升级后所有书默认关闭。
 */
@Composable
fun rememberBookTranslator(bookUuid: String): BookTranslatorState {
    val store = LocalBookTranslatorStore.current
    val records by store.records.collectAsStateWithLifecycle()

    val values = remember(records, bookUuid) {
        records[bookUuid].resolve()
    }
    val actions = remember(store, bookUuid) {
        BookTranslatorActionsImpl(store, bookUuid)
    }

    return remember(values, actions) {
        BookTranslatorState(values = values, actions = actions)
    }
}
