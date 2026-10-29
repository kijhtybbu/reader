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

import org.junit.Assert.assertEquals
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
    fun `the first title after the toc finishes loading is suppressed`() {
        // 目录加载完成时章名从 null 变成有值，但这属于「首次进入」，不该弹
        assertFalse(shouldShowChapterHint(lastShown = null, newTitle = "第一章"))
    }

    // ---------------------------------------------------------------- 状态机

    @Test
    fun `transition records the first title without showing`() {
        val next = chapterHintTransition(ChapterHintState.Initial, "第一章")
        assertEquals("第一章", next.lastShown)
        assertFalse(next.visible)
    }

    @Test
    fun `transition shows on a real chapter change`() {
        val next = chapterHintTransition(ChapterHintState("第一章", visible = false), "第二章")
        assertEquals("第二章", next.lastShown)
        assertTrue(next.visible)
    }

    @Test
    fun `transition does not interrupt an in-flight hint for the same title`() {
        val next = chapterHintTransition(ChapterHintState("第二章", visible = true), "第二章")
        assertTrue("同一章名不应打断正在进行的显示", next.visible)
    }

    @Test
    fun `transition hides the pill when the title becomes null`() {
        // 真实路径：翻到首个标题之前的前言部分，或章名被清洗成空串。
        // 此时 LaunchedEffect 的 delay 已被取消，没有别人会关掉胶囊 —— 会永远挂在屏幕上。
        val next = chapterHintTransition(ChapterHintState("第二章", visible = true), null)
        assertFalse("标题变 null 时必须收起", next.visible)
        assertEquals("第二章", next.lastShown)
    }
}
