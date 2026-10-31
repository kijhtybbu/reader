/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * fork 新增：钉住「让阅读页屏幕方向设置在大屏上真的生效」这行 manifest property。
 *
 * `targetSdk = 36` 之后，Android 16 在最小宽度 ≥ 600dp 的屏幕上忽略
 * `screenOrientation` / `setRequestedOrientation()`（全屏与多窗口都一样），
 * 表现是阅读页设置的「锁定竖屏 / 横屏 / 自由」六个 chip 点了没反应 —— 不崩溃、不报错，
 * 只是静默失效（测试机 Lenovo TB322FC：1904×3040 @440dpi → sw≈692dp，正好踩中）。
 *
 * 唯一的临时解法是下面这条 opt-out；它**在 Android 17（API 37）被删除**，所以抬
 * targetSdk 时必须同时改掉大屏上的方向 UI，而不是指望它继续有效。
 * 详见 PROGRESS.md §3.17、UPSTREAM_DIFF.md §2.3、REBASE.md §4。
 */

package ua.acclorite.book_story

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ReaderOrientationManifestTest {

    private val manifest = File("src/main/AndroidManifest.xml").readText()

    @Test
    fun `main activity opts out of the android 16 large screen orientation override`() {
        val mainActivity = Regex(
            """<activity\b[^>]*android:name="\.presentation\.main\.MainActivity".*?</activity>""",
            RegexOption.DOT_MATCHES_ALL
        ).find(manifest)?.value

        assertTrue(
            "MainActivity 必须带着 android:window.PROPERTY_COMPAT_ALLOW_RESTRICTED_RESIZABILITY=" +
                "\"true\"：少了它，Android 16 在 sw>=600dp 的平板上会忽略 requestedOrientation，" +
                "阅读页的「锁定竖屏」等六个方向选项静默失效。详见 PROGRESS.md §3.17。",
            mainActivity != null &&
                mainActivity.contains("android.window.PROPERTY_COMPAT_ALLOW_RESTRICTED_RESIZABILITY") &&
                mainActivity.contains("""android:value="true"""")
        )
    }

    @Test
    fun `orientation setting is still applied from the reader screen`() {
        // property 只是让系统肯听话；真正下指令的那行不能在上游 rebase 时丢掉。
        val readerScreen = File("src/main/java/ua/acclorite/book_story/presentation/reader/ReaderScreen.kt")
            .readText()

        assertTrue(
            "ReaderScreen 必须继续把设置写进 activity.requestedOrientation" +
                "（并在离开阅读页时复位成 SCREEN_ORIENTATION_UNSPECIFIED）。",
            readerScreen.contains("activity.requestedOrientation = settings.screenOrientation.lastValue.code") &&
                readerScreen.contains("ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED")
        )
    }
}
