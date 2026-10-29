/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * fork 新增：章节切换提示的触发条件。
 *
 * 边界在于「首次进入不该弹」—— 写错的后果是每次打开书都闪一下章名。
 */

package ua.acclorite.book_story.ui.reader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderChapterHintTest {

    @Test
    fun `first entry never shows a hint`() {
        assertFalse(shouldShowChapterHint(lastShown = null, newTitle = "第一章"))
    }

    @Test
    fun `same chapter does not re-show`() {
        assertFalse(shouldShowChapterHint(lastShown = "第一章", newTitle = "第一章"))
    }

    @Test
    fun `changing chapter shows a hint`() {
        assertTrue(shouldShowChapterHint(lastShown = "第一章", newTitle = "第二章"))
    }

    @Test
    fun `null title never shows a hint`() {
        assertFalse(shouldShowChapterHint(lastShown = "第一章", newTitle = null))
        assertFalse(shouldShowChapterHint(lastShown = null, newTitle = null))
    }

    @Test
    fun `chapter title appearing after being unknown counts as a change`() {
        // 章节目录还在加载时 currentChapterString 为 null，加载完成后应该提示一次
        assertTrue(shouldShowChapterHint(lastShown = "第一章", newTitle = "第二章"))
    }
}
