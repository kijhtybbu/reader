/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * fork 新增：钉住「启动时关掉 Compose 新上下文菜单」这条不变量。
 *
 * 这个 flag 是长按取词的唯一开关：它为 true 时 `SelectionContainer` 走新上下文菜单、
 * 不再调用 `LocalTextToolbar.showMenu`，阅读页自定义工具栏（以及它上报的选区矩形）
 * 等于被架空。回归的表现是「长按选词没反应」，不是崩溃 —— JVM 层只能钉住赋值本身，
 * 交互仍要在真机上验（设计 §5.1、§A.7）。
 */

package ua.acclorite.book_story

import androidx.compose.foundation.ComposeFoundationFlags
import androidx.compose.foundation.ExperimentalFoundationApi
import org.junit.Assert.assertFalse
import org.junit.Test

@OptIn(ExperimentalFoundationApi::class)
class ComposeCompatibilityFlagsTest {

    @Test
    fun `the new context menu is disabled so the custom selection toolbar keeps working`() {
        applyComposeCompatibilityFlags()

        assertFalse(
            "Compose's new context menu must stay off: it bypasses LocalTextToolbar, " +
                "which silently disables long-press lookup and the selection toolbar actions.",
            ComposeFoundationFlags.isNewContextMenuEnabled
        )
    }
}
