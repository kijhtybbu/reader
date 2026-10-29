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
        // fork: 必须 remember。BookStoryTheme 在配色动画期间每帧都会重组（animateColorScheme），
        // 而 MaterialTheme 是按引用比较 CompositionLocal 的 —— 每帧新建 Typography/Shapes 会让
        // 整棵文本子树每帧全量重组。上游的 `Shapes()` 也有同样问题，一并修。
        shapes = remember { Shapes() },
        typography = remember { appTypography() },
        content = content
    )
}