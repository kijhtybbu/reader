/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * fork 新增：取词手势设置的取值与回落（第二阶段 S5）。
 *
 * 回落方向是刻意的：认不出的存量值回到 SINGLE_TAP（默认行为），而不是 OFF。
 * 一个脏值不该静默把取词功能关掉，用户会以为功能坏了。
 */

package ua.acclorite.book_story.presentation.reader.model

import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderLookupGestureTest {

    @Test
    fun `every value round-trips through its stored name`() {
        ReaderLookupGesture.entries.forEach { gesture ->
            assertEquals(gesture, ReaderLookupGesture.fromName(gesture.name))
        }
    }

    @Test
    fun `the default is single tap`() {
        assertEquals(ReaderLookupGesture.SINGLE_TAP, ReaderLookupGesture.fromName(null))
    }

    @Test
    fun `an unknown stored value falls back to single tap`() {
        assertEquals(ReaderLookupGesture.SINGLE_TAP, ReaderLookupGesture.fromName("DOUBLE_TAP"))
        assertEquals(ReaderLookupGesture.SINGLE_TAP, ReaderLookupGesture.fromName(""))
        // 大小写不匹配也算认不出（存的是 name，不是小写形式）
        assertEquals(ReaderLookupGesture.SINGLE_TAP, ReaderLookupGesture.fromName("single_tap"))
    }
}
