/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 */

package ua.acclorite.book_story.ui.theme

import android.app.Activity
import android.os.Build
import android.view.View
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import ua.acclorite.book_story.ui.theme.model.Theme
import ua.acclorite.book_story.ui.theme.model.ThemeContrast

@Composable
fun BookStoryTheme(
    theme: Theme,
    isDark: Boolean,
    isPureDark: Boolean,
    themeContrast: ThemeContrast,
    content: @Composable () -> Unit
) {
    val view = LocalView.current
    // 组合期读取（而非在 SideEffect 里读），这样阅读页写入覆盖时会触发重组并重新应用
    val systemBarsHolder = remember { mutableStateOf<Boolean?>(null) }
    val systemBarsOverride = systemBarsHolder.value
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window

            // Disabling Autofill
            window.decorView.importantForAutofill =
                View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS

            // Fix for nav bar being semi transparent in api 29+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                window.isNavigationBarContrastEnforced = false
            }

            // fork: 阅读页会用预设派生色覆盖系统栏区域，此时图标明暗必须跟着那块底色走
            val lightBars = systemBarsOverride ?: !isDark
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = lightBars
            WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = lightBars
        }
    }

    val colorScheme = colorScheme(
        theme = Theme.entries().find { it == theme } ?: Theme.entries().first(),
        darkTheme = isDark,
        isPureDark = isPureDark,
        themeContrast = themeContrast
    )
    val animatedColorScheme = animateColorScheme(targetColorScheme = colorScheme)

    CompositionLocalProvider(LocalSystemBarsOverride provides systemBarsHolder) {
    MaterialTheme(
        colorScheme = animatedColorScheme,
        // fork: 意在**降低分配**，不是为了减少重组。
        // 配色动画期间 animateColorScheme 逐帧插值 38 个 slot，BookStoryTheme 本来就会每帧重执行，
        // remember 改不了这一点。它省下的只是每帧两次 Shapes/Typography 实例分配
        //（上游 inline 的 Shapes() 同样有这个问题）。
        // 无 key：目前只依赖常量；若将来依赖设置项，必须补 key。
        shapes = remember { Shapes() },
        typography = remember { appTypography() },
        content = content
    )
    }
}