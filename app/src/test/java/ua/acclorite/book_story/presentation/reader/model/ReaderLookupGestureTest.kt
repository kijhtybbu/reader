/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * fork 新增：取词手势的存储名往返与回落语义。
 *
 * 语义变更（按书翻译器）：取词手势不再是全局设置，也没有「默认单击」——
 * 每本书默认 OFF；认不出的存量值也回落 OFF，避免升级后悄悄打开取词。
 */

package ua.acclorite.book_story.presentation.reader.model

import org.junit.Assert.assertEquals
import org.junit.Test
import ua.acclorite.book_story.data.translator.BookTranslatorRecord
import ua.acclorite.book_story.data.translator.resolve

class ReaderLookupGestureTest {

    @Test
    fun `every value round-trips through its stored name`() {
        ReaderLookupGesture.entries.forEach { gesture ->
            val record = BookTranslatorRecord(lookupGesture = gesture.name)

            assertEquals(gesture, record.resolve().lookupGesture)
        }
    }

    @Test
    fun `a missing record defaults to off`() {
        assertEquals(ReaderLookupGesture.OFF, (null as BookTranslatorRecord?).resolve().lookupGesture)
    }

    @Test
    fun `an unknown stored value falls back to off`() {
        listOf("DOUBLE_TAP", "", "single_tap").forEach { name ->
            val record = BookTranslatorRecord(lookupGesture = name)

            assertEquals(ReaderLookupGesture.OFF, record.resolve().lookupGesture)
        }
    }
}
