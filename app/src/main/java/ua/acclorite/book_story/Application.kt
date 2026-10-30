/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 */

package ua.acclorite.book_story

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import ua.acclorite.book_story.core.crash.CrashHandler
import ua.acclorite.book_story.core.data.FileData
import ua.acclorite.book_story.data.dictionary.DictionaryRegistry
import ua.acclorite.book_story.ui.reader.data.UserFontRegistry

@HiltAndroidApp
class Application : Application() {

    /** 进程级后台任务的作用域：`onCreate` 只跑一次，所以这里也只起一个安装任务。 */
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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

        // fork: 内置 ECDICT 的首次解压（第二阶段 S3）。刻意不阻塞启动 ——
        // 20MB 资产解压出 ~86MB 文件，在低端机上要几秒；未就绪时取词侧显示「词典准备中」。
        // 版本标记命中时这条路径只做一次打开引擎（读 .jidx 缓存）。
        applicationScope.launch { DictionaryRegistry.ensureBuiltIn(this@Application) }
    }
}