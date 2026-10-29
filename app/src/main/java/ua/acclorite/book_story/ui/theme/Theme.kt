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
import androidx.compose.runtime.SideEffect
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

            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !isDark
            WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = !isDark
        }
    }

    val colorScheme = colorScheme(
        theme = Theme.entries().find { it == theme } ?: Theme.entries().first(),
        darkTheme = isDark,
        isPureDark = isPureDark,
        themeContrast = themeContrast
    )
    val animatedColorScheme = animateColorScheme(targetColorScheme = colorScheme)

    MaterialTheme(
        colorScheme = animatedColorScheme,
        // fork: 意在**降低分配**，不是为了减少重组。
        // 配色动画期间（animateColorScheme 逐帧插值全部 slot）BookStoryTheme 本来就会每帧重执行，
        // 且文本子树会因 LocalContentColor 变化而重组 —— 这一点 remember 改不了。
        // 真正省下的只是每帧两次 Shapes/Typography 实例分配（上游 inline 的 Shapes() 同样有这问题）。
        // 无 key：目前只依赖常量；若将来依赖设置项，必须补 key。
        shapes = remember { Shapes() },
        typography = remember { appTypography() },
        content = content
    )
}