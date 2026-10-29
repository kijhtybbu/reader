/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 */

package ua.acclorite.book_story

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import ua.acclorite.book_story.core.crash.CrashHandler
import ua.acclorite.book_story.core.data.FileData
import ua.acclorite.book_story.ui.reader.data.UserFontRegistry

@HiltAndroidApp
class Application : Application() {
    override fun onCreate() {
        super.onCreate()
        Thread.setDefaultUncaughtExceptionHandler(CrashHandler(this))

        // fork: 同步加载用户字体索引，必须早于任何 SettingsManager 反序列化。
        // fontFamily 的 deserialize 在 DataStore 一 emit 时就按 id 查字体；索引若异步加载，
        // 重启后会查不到用户字体，静默回落默认字体。index.json 只有几 KB，同步读没有代价。
        UserFontRegistry.load(this)

        FileData.getCacheDirectory(this).let {
            if (it.exists()) it.deleteRecursively()
        }
    }
}