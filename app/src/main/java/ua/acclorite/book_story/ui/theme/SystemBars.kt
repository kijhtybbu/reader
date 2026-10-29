/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * fork 新增：系统栏图标明暗覆盖。
 *
 * 问题：`Theme.kt` 用 `!isDark`（App 主题）决定状态栏/导航栏图标是深是浅，
 * 但阅读页的系统栏区域现在被 `palette.bar`（预设派生色）覆盖。
 * 默认配置下就能踩到：手机深色模式 + 默认浅色预设 → 浅色栏底 + 浅色图标 ≈ 1.17:1。
 *
 * 机制：`Theme.kt` 持有 override 状态；阅读页通过 [SystemBarsAppearanceOverride] 写入，
 * 离开时置回 null，系统栏恢复跟随 App 主题。
 * Theme.kt 在组合期读取该状态（而不是在 SideEffect 里读），因此写入会触发重组并重新应用。
 */

package ua.acclorite.book_story.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * 系统栏图标外观覆盖。
 * `null` = 跟随 App 主题；`true` = 浅色栏底（深色图标）；`false` = 深色栏底（浅色图标）。
 */
val LocalSystemBarsOverride = staticCompositionLocalOf<MutableState<Boolean?>> {
    mutableStateOf(null)
}

/**
 * 在阅读页声明「系统栏底下是什么亮度」。
 *
 * @param lightBars 系统栏区域是否为浅色底（决定图标用深色）。
 *   传 `palette.bar.luminance() > 0.5f` 即可。
 */
@Composable
fun SystemBarsAppearanceOverride(lightBars: Boolean) {
    val holder = LocalSystemBarsOverride.current
    DisposableEffect(lightBars) {
        holder.value = lightBars
        onDispose { holder.value = null }
    }
}
