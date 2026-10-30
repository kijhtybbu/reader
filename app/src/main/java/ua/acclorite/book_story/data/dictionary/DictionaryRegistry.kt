/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * fork 新增：内置词典的运行时注册（第二阶段 S3）。
 *
 * 与 `UserFontRegistry` 同样是 object：它没有构造参数，也不该由 Hilt 注入 ——
 * `Application.onCreate` 里要能直接拿到它，而启动时还没有任何界面消费方。
 *
 * 三件事：
 *  1. 首次启动把 `assets/dictionaries/ecdict/stardict.7z` 解压到
 *     `filesDir/dictionaries/builtin/`（版本标记命中则跳过，见 [BuiltInDictionaryInstaller]）；
 *  2. 打开一次引擎并缓存，供后续取词直接使用（`openBuiltIn`）；
 *  3. 把「未开始 / 解压中 / 就绪 / 失败」暴露成 [builtInState]，设置页与取词卡片据此显示。
 *
 * 启动不阻塞：解压在 IO 线程跑，没就绪时 `openBuiltIn()` 返回 null。
 */

package ua.acclorite.book_story.data.dictionary

import android.content.Context
import android.content.res.AssetManager
import com.qiuminal.juicedict.engine.DictionaryEngine
import com.qiuminal.juicedict.engine.DictionaryEngines
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import ua.acclorite.book_story.core.data.FileData
import ua.acclorite.book_story.core.log.logE
import ua.acclorite.book_story.core.log.logI
import ua.acclorite.book_story.core.log.logW
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

private const val TAG = "DictionaryRegistry"

sealed interface BuiltInState {
    /** 还没开始，或引擎被 [DictionaryRegistry.close] 关掉了。 */
    data object Idle : BuiltInState

    /** [progress] 0..1，覆盖拷贝资产（前 10%）与解压校验（其余）。 */
    data class Extracting(val progress: Float) : BuiltInState

    /** 词典文件已就位且引擎已打开。 */
    data class Ready(val baseName: String, val directory: File, val wordCount: Int) : BuiltInState

    data class Error(val message: String) : BuiltInState
}

object DictionaryRegistry {

    /**
     * 资产版本。换 `stardict.7z` 时必须 +1，否则已装好的旧目录会被当成最新而跳过。
     * 版本标记落在词典目录的 `version` 文件里，与 [BuiltInDictionaryInstaller] 共用。
     *
     * 2：词条里加了 `<!--ecdict|…-->`（柯林斯星级/牛津/考试标签/词频）与
     * `<!--def-->`（英文释义起始）两个不可见标记，取词卡片据此分栏排版。
     */
    const val ECDICT_ASSET_VERSION = 2

    private const val ASSET_PATH = "dictionaries/ecdict/stardict.7z"

    /** 缓存里的资产副本名：固定名，进程中途被杀最多留一份，下次直接覆盖。 */
    private const val ASSET_CACHE_FILE = "ecdict-asset.7z"

    private const val ENGINE_ID = "builtin"

    /** 拷贝资产占进度的前 10%：20MB 的顺序拷贝比解压快一个数量级。 */
    private const val COPY_PROGRESS_SHARE = 0.1f

    private const val BUFFER_BYTES = 256 * 1024

    private val _builtInState = MutableStateFlow<BuiltInState>(BuiltInState.Idle)
    val builtInState: StateFlow<BuiltInState> = _builtInState.asStateFlow()

    /** 串行化安装：并发调用者排队，后来者看到已就绪直接返回，不会解压两遍。 */
    private val installMutex = Mutex()

    /**
     * 保护 [builtInEngine]。引擎本身**不是**线程安全的（见 `DictionaryEngines` 的 KDoc），
     * 调用方拿到之后还要按引擎自己加锁。
     */
    private val engineLock = Any()
    private var builtInEngine: DictionaryEngine? = null

    /**
     * 确保内置词典可用。已就绪时立即返回；否则拷贝资产、解压、打开引擎并更新 [builtInState]。
     * 失败不抛异常（启动路径上没人接），状态变成 [BuiltInState.Error]，可再次调用重试。
     */
    suspend fun ensureBuiltIn(context: Context) = withContext(Dispatchers.IO) {
        installMutex.withLock {
            if (isEngineOpen()) return@withLock

            val appContext = context.applicationContext
            val targetDir = FileData.getBuiltInDictionaryDirectory(appContext)
            val assetCopy = File(appContext.cacheDir, ASSET_CACHE_FILE)
            try {
                _builtInState.value = BuiltInState.Extracting(0f)
                copyAsset(appContext, assetCopy) { fraction ->
                    _builtInState.value = BuiltInState.Extracting(fraction * COPY_PROGRESS_SHARE)
                }

                val result = BuiltInDictionaryInstaller.install(
                    sevenZ = assetCopy,
                    targetDir = targetDir,
                    version = ECDICT_ASSET_VERSION.toString(),
                    onProgress = { fraction ->
                        _builtInState.value = BuiltInState.Extracting(
                            COPY_PROGRESS_SHARE + fraction * (1f - COPY_PROGRESS_SHARE)
                        )
                    },
                )

                // 安装器只为校验开过一次引擎（顺带写好 .jidx），这里开的是常驻实例，
                // 走刚写好的索引缓存，比首次解析快得多。
                val engine = DictionaryEngines.openStarDict(ENGINE_ID, result.directory, result.baseName)
                if (engine == null) {
                    _builtInState.value = BuiltInState.Error(
                        "Could not open the built-in dictionary at ${result.directory.absolutePath}"
                    )
                    logE(TAG, "Could not open the installed dictionary ${result.baseName}")
                    return@withLock
                }
                synchronized(engineLock) {
                    val previous = builtInEngine
                    builtInEngine = engine
                    // 旧引擎关不掉不能拖垮安装：实例已经被新引擎取代，最坏情况只是多占一个 fd
                    runCatching { previous?.close() }
                        .onFailure { logW(TAG, "Could not close the previous engine: ${it.message}") }
                }
                _builtInState.value = BuiltInState.Ready(result.baseName, result.directory, result.wordCount)
                logI(
                    TAG,
                    "Built-in dictionary ready: ${result.baseName}, ${result.wordCount} words, " +
                        "installed=${!result.skipped}"
                )
            } catch (t: Throwable) {
                // 安装器会把 Error（OOM 等）原样重抛，但它已先清掉临时目录；这里仍要接住，
                // 因为 [ensureBuiltIn] 的约定是「启动路径上不抛异常」，失败只落成 Error 状态，
                // 下次调用可以重试。协程取消不属于安装失败，照常向上传递。
                if (t is CancellationException) throw t
                val reason = t.message ?: t.javaClass.simpleName
                logE(TAG, "Could not prepare the built-in dictionary: $reason")
                _builtInState.value = BuiltInState.Error(reason)
            } finally {
                // 资产副本只是解压的输入，装完就没用了；7z 本身在 APK 里。
                if (assetCopy.exists() && !assetCopy.delete()) {
                    logW(TAG, "Could not delete the cached archive: ${assetCopy.absolutePath}")
                }
            }
        }
    }

    /** 已打开的引擎，未就绪时 null。调用方用完**不要** close —— 实例归本注册表所有。 */
    fun openBuiltIn(): DictionaryEngine? = synchronized(engineLock) { builtInEngine }

    /**
     * 关闭缓存的引擎。只用于进程/测试收尾：关掉之后 [openBuiltIn] 返回 null，
     * 状态回到 [BuiltInState.Idle]，再调 [ensureBuiltIn] 会重新打开（不会重新解压）。
     */
    fun close() {
        val engine = synchronized(engineLock) {
            val current = builtInEngine
            builtInEngine = null
            current
        }
        runCatching { engine?.close() }
            .onFailure { logW(TAG, "Could not close the built-in engine: ${it.message}") }
        _builtInState.value = BuiltInState.Idle
    }

    private fun isEngineOpen(): Boolean = synchronized(engineLock) { builtInEngine != null }

    /**
     * 把 APK 里的 7z 拷到 cacheDir：`SevenZFile` 只吃 `File`，读不了 asset 流。
     *
     * 用 `context.cacheDir` 而不是 `FileData.getCacheDirectory()`（= `filesDir/app_cache`）：
     * 这份拷贝只是解压的输入，装完就删，系统清理缓存时丢掉也无所谓；放进 filesDir 反而会被
     * 当作用户数据（备份、占用统计）。`filesDir` 只留给解压后的词典本身。
     *
     * `.7z` 不在 aapt 的 `noCompress` 默认列表里，所以资产通常是 deflate 过的，
     * `openFd` 拿不到长度；拿不到就只在结束时由安装器报进度 —— 20MB 的顺序拷贝
     * 本身只有几十毫秒，中间值没有观测意义。
     */
    private fun copyAsset(context: Context, target: File, onProgress: (Float) -> Unit) {
        val total = runCatching {
            context.assets.openFd(ASSET_PATH).use { it.length }
        }.getOrNull()?.takeIf { it > 0 }

        var copied = 0L
        try {
            context.assets.open(ASSET_PATH, AssetManager.ACCESS_STREAMING).use { input ->
                BufferedOutputStream(FileOutputStream(target), BUFFER_BYTES).use { output ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        copied += read
                        if (total != null) {
                            onProgress((copied.toFloat() / total).coerceIn(0f, 1f))
                        }
                    }
                }
            }
        } catch (e: IOException) {
            throw DictionaryInstallException("Could not copy the bundled dictionary: ${e.message}", e)
        }
    }
}
