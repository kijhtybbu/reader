/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 */

package ua.acclorite.book_story

import android.app.Application
import androidx.compose.foundation.ComposeFoundationFlags
import androidx.compose.foundation.ExperimentalFoundationApi
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import ua.acclorite.book_story.core.crash.CrashHandler
import ua.acclorite.book_story.core.data.FileData
import ua.acclorite.book_story.data.dictionary.DictionaryCatalog
import ua.acclorite.book_story.data.dictionary.DictionaryRegistry
import ua.acclorite.book_story.ui.reader.data.UserFontRegistry

@HiltAndroidApp
class Application : Application() {

    /** 进程级后台任务的作用域：`onCreate` 只跑一次，所以这里也只起一个安装任务。 */
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        // 必须早于任何 Compose 代码加载（官方 flag 文档：加载之后再改是未定义行为）。
        applyComposeCompatibilityFlags()
        super.onCreate()
        Thread.setDefaultUncaughtExceptionHandler(CrashHandler(this))

        // fork: 同步加载用户字体索引，必须早于任何 SettingsManager 反序列化。
        // fontFamily 的 deserialize 在 DataStore 一 emit 时就按 id 查字体；索引若异步加载，
        // 重启后会查不到用户字体，静默回落默认字体。index.json 只有几 KB，同步读没有代价。
        UserFontRegistry.load(this)

        // fork: 导入词典目录（第二阶段 S4）。同步读几 KB 的 index.json，随后的磁盘核对
        // （丢弃已删除的条目、收养未入索引的目录）在 IO 线程上跑，不阻塞启动。
        DictionaryCatalog.load(this)

        FileData.getCacheDirectory(this).let {
            if (it.exists()) it.deleteRecursively()
        }

        // fork: 内置 ECDICT 的首次解压（第二阶段 S3）。刻意不阻塞启动 ——
        // 20MB 资产解压出 ~86MB 文件，在低端机上要几秒；未就绪时取词侧显示「词典准备中」。
        // 版本标记命中时这条路径只做一次打开引擎（读 .jidx 缓存）。
        applicationScope.launch { DictionaryRegistry.ensureBuiltIn(this@Application) }
    }
}

/**
 * 关掉 Compose 1.9 默认启用的「新上下文菜单」。
 *
 * 为什么必须关：`SelectionContainer` 在 flag 打开时走 `toolbarRequester`（新上下文菜单），
 * 完全不读 `LocalTextToolbar` —— 阅读页自定义的 `SelectionToolbar` 收不到 `showMenu`，
 * 于是长按取词（`onSelectionSettled`）永远不触发，选区的翻译/词典项也一并消失。
 * flag 为 false 时 `SelectionManager.updateSelectionTextToolbar()` 重新调用
 * `textToolbar.showMenu(...)`，回到 1.8 及之前的行为（设计 §5.1、§A.8）。
 *
 * 关掉的是全局 flag：应用内所有文本输入/选区都回落到旧的上下文菜单
 * （平台 ActionMode / 旧 DrawableMenu），即升级 Compose 1.9 之前的既有行为。
 * 等这里迁移到新上下文菜单 API（`text.contextmenu.provider`）后再删掉本函数。
 */
@OptIn(ExperimentalFoundationApi::class)
internal fun applyComposeCompatibilityFlags() {
    ComposeFoundationFlags.isNewContextMenuEnabled = false
}
