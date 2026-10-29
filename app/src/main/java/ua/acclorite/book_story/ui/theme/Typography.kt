/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * fork 新增：品牌排版层级。
 *
 * 上游 Theme.kt 直接写 `Typography()`，即 Material 3 默认值，等于没有排版决定。
 * 这里只收紧「大字号层级」的字距并加重字重 —— 大字号在默认字距下会显得松散，
 * 收紧后标题更「整块」，这是标题排版的通行做法。
 *
 * 刻意不动 title/body/label：它们是全 App 布局高度的依据（各处 .height()、列表行、
 * 顶栏都是按这几档估的），改动会牵连布局，收益却很小。
 * 正文观感属于阅读器范畴，应该在 Phase 2 的排版面板里调，而不是动全局 Typography。
 */

package ua.acclorite.book_story.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em

fun appTypography(): Typography {
    val base = Typography()
    return base.copy(
        displayLarge = base.displayLarge.copy(
            fontWeight = FontWeight.SemiBold,
            letterSpacing = (-0.02).em
        ),
        displayMedium = base.displayMedium.copy(
            fontWeight = FontWeight.SemiBold,
            letterSpacing = (-0.02).em
        ),
        displaySmall = base.displaySmall.copy(
            fontWeight = FontWeight.SemiBold,
            letterSpacing = (-0.01).em
        ),
        headlineLarge = base.headlineLarge.copy(
            fontWeight = FontWeight.SemiBold,
            letterSpacing = (-0.01).em
        ),
        headlineMedium = base.headlineMedium.copy(
            fontWeight = FontWeight.SemiBold,
            letterSpacing = (-0.01).em
        )
    )
}
