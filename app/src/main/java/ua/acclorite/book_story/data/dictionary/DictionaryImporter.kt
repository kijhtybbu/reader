/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * fork 新增：MDX / StarDict 词典导入（第二阶段 S4）。
 *
 * 两条路径共用同一套「暂存 → 校验 → 原子换目录」流程：
 *  1. 把选中的文件流式复制到 `filesDir/dictionaries/.staging/<uuid>/`（复制前先查体积与剩余空间）；
 *  2. 用 `DictionaryEngines.probeMdx` / `openMdx` / `openStarDict` 真正打开一次再关掉 ——
 *     只看扩展名会把「加密的 MDX」「MDD 资源包」「截断的 StarDict」都当成合法词典；
 *  3. 同一文件系统内 `rename` 到 `dictionaries/imported/<uuid>/`，再写目录索引。
 * 任何一步失败都删暂存目录，`imported/` 里不会出现半个词典。
 *
 * MDX 复制前还会用 `DictionaryEngines.probeMdxHead` 读前 1 MiB 预检，误选的大文件不会先
 * 被完整复制一遍再报错。
 *
 * 资源包走 [importMdxDirectory]（`OpenDocumentTree` 选整个目录）：
 *  - 目录里恰好一个 `.mdx`，同主干的 `<base>.mdd` / `<base>.N.mdd` 按编号 1..N 连续配对；
 *  - 总体积上限 [MAX_IMPORT_TOTAL_BYTES]（2 GiB）——真实的牛津高阶 9 是 54 MB mdx +
 *    154 MB + 518 MB + 1.14 GB 三个 mdd 分卷，[MAX_IMPORT_BYTES] 只用于单个 `.mdx`；
 *  - 复制顺序 mdx → 各 mdd，每个 mdd 复制完立刻用 `checkMdxResourcePack` 独立校验；
 *  - 「目录清单 → 导入计划」抽成无 `android.*` 的 [planMdxFolder]，单测直接覆盖。
 * 单文件入口保留给纯文本 MDX（没有同目录兄弟文件的授权，拿不到 `.mdd`）。
 *
 * 错误只以 [DictionaryImportError] 枚举向上暴露，文案由设置页映射到字符串资源。
 *
 * SAF 授权只在本次会话内有效，但导入是复制，之后只读应用私有目录，所以**不**调用
 * `takePersistableUriPermission`：除非 intent 带了
 * `FLAG_GRANT_PERSISTABLE_URI_PERMISSION`，它只会抛 `SecurityException`。
 */

package ua.acclorite.book_story.data.dictionary

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.anggrayudi.storage.file.DocumentFileCompat
import com.qiuminal.juicedict.engine.DictionaryEngines
import com.qiuminal.juicedict.engine.MdxPackCheck
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import ua.acclorite.book_story.core.data.FileData
import ua.acclorite.book_story.core.log.logW
import ua.acclorite.book_story.data.storage.file.CachedFile
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.UUID

private const val TAG = "DictionaryImporter"

/**
 * 导入失败的原因。数据层只给枚举，设置页负责映射成用户能读的字符串。
 * 分类按「用户下一步能做什么」拆：换文件、换目录、清空间、换一部没加密的词典。
 */
enum class DictionaryImportError {
    /** 选中的文件/目录打不开（授权失效、provider 报错）。 */
    UNREADABLE,

    /** 文件是 0 字节，或复制完一个字节都没有。 */
    EMPTY_FILE,

    /** 超过 [DictionaryImporter.MAX_IMPORT_BYTES]（单个 `.mdx`）或 [DictionaryImporter.MAX_IMPORT_TOTAL_BYTES]（整部词典）。 */
    TOO_LARGE,

    /** 目标分区剩余空间不够（按复制量的 1.5 倍估算，留给引擎的索引缓存）。 */
    NO_SPACE,

    /** 不是 MDict 文件。 */
    NOT_MDX,

    /** MDX 带密码（`encrypted` 位要求密码）。 */
    ENCRYPTED,

    /** 选中的是 `.mdd` 资源包，不是正文词典。 */
    RESOURCE_PACK,

    /** 头部合法但索引/正文读不出来，或词条数为 0。 */
    CORRUPT,

    /** StarDict 目录缺 `.ifo` / `.idx(.gz)` / `.dict(.dz)`。 */
    INCOMPLETE,

    /** 目录里有多部 StarDict，无法判断导入哪一部。 */
    MULTIPLE_DICTIONARIES,

    /** 其他 IO 失败（创建目录、rename 等）。 */
    FAILED,

    /**
     * `.mdd` 分卷编号不连续（有 `.2.mdd` 却没 `.1.mdd`），或有编号分卷却没主包 `<base>.mdd`。
     * 少一段会让图片/发音静默消失，所以整部词典拒绝导入，而不是装一个残缺的进去。
     */
    RESOURCE_PARTS_MISSING,

    /** `.mdd` 资源包打不开：不是 MDict 容器（误选 `.mdx`）或已截断/损坏。 */
    RESOURCE_CORRUPT
}

sealed interface ImportState {
    data object Idle : ImportState

    /** [progress] 0..1，按已声明体积估算；provider 不报体积时停在 0。 */
    data class Copying(val progress: Float) : ImportState

    data object Validating : ImportState

    data class Done(val dictionary: InstalledDictionary) : ImportState

    data class Error(val error: DictionaryImportError) : ImportState
}

object DictionaryImporter {

    /** 单部词典的体积上限。MDX 词典通常几十到几百 MB，512 MB 足够拦下误选的视频/整盘备份。 */
    const val MAX_IMPORT_BYTES = 512L * 1024 * 1024

    /**
     * 整部词典（`.mdx` + 全部 `.mdd` 分卷）的体积上限：2 GiB。
     *
     * [MAX_IMPORT_BYTES] 只能管单个 `.mdx`：真实的牛津高阶 9（第 9 版）是 54 MB 的 `.mdx`
     * 加上 154 MB 的 `.mdd`、518 MB 的 `.1.mdd`、1.14 GB 的 `.2.mdd`，合计约 1.85 GB ——
     * 分卷本来就比单文件上限大，用 512 MB 去拦会让设计自己列的真机矩阵（OALD9）导不进来。
     */
    const val MAX_IMPORT_TOTAL_BYTES = 2L * 1024 * 1024 * 1024

    private const val BUFFER_BYTES = 256 * 1024

    /** 复制前预检读的字节数：1 MiB 覆盖 MDX 头部与 key section 前导（见 `probeMdxHead`）。 */
    private const val HEAD_PROBE_BYTES = 1 shl 20

    /** 与内置安装器同一个系数：复制量 × 1.5 覆盖引擎随后写的 `.jidx` 索引缓存。 */
    private const val SPACE_NUMERATOR = 3L
    private const val SPACE_DENOMINATOR = 2L

    private val importMutex = Mutex()

    private val _state = MutableStateFlow<ImportState>(ImportState.Idle)
    val state: StateFlow<ImportState> = _state.asStateFlow()

    /** 清掉上一次的 [ImportState.Done] / [ImportState.Error]，不影响正在进行的导入。 */
    fun reset() {
        when (_state.value) {
            is ImportState.Done, is ImportState.Error -> _state.value = ImportState.Idle
            else -> Unit
        }
    }

    /**
     * 导入 `.mdx`。
     *
     * 只导入正文文件：单文件入口拿不到同目录兄弟文件的授权，`.mdd` 资源包走
     * [importMdxDirectory]（`OpenDocumentTree`）。
     *
     * @return null 表示成功（结果在 [state] 的 [ImportState.Done] 里），否则是失败原因。
     */
    suspend fun importMdx(context: Context, uri: Uri): DictionaryImportError? =
        withContext(Dispatchers.IO) {
            importMutex.withLock {
                _state.value = ImportState.Copying(0f)
                val appContext = context.applicationContext
                val dictionariesDir = FileData.getDictionariesDirectory(appContext)
                val stagingDir = File(dictionariesDir, "${DictionaryCatalog.STAGING_DIRECTORY}/${UUID.randomUUID()}")
                try {
                    val installed = installMdx(appContext, dictionariesDir, stagingDir, uri)
                    _state.value = ImportState.Done(installed)
                    null
                } catch (rejection: ImportRejection) {
                    _state.value = ImportState.Error(rejection.error)
                    rejection.error
                } catch (e: CancellationException) {
                    // 调用方（设置页的组合作用域）离开页面时会取消导入。暂存目录由 finally 清掉，
                    // 状态必须回到 Idle，否则下次进入设置页会永远停在「正在复制 0%」。
                    _state.value = ImportState.Idle
                    throw e
                } catch (e: Exception) {
                    logW(TAG, "Could not import the MDX dictionary: ${e.message}")
                    _state.value = ImportState.Error(DictionaryImportError.FAILED)
                    DictionaryImportError.FAILED
                } finally {
                    // rename 成功后暂存目录已经不存在，这里是失败/取消路径的清理。
                    if (stagingDir.exists()) stagingDir.deleteRecursively()
                }
            }
        }

    /**
     * 导入 `OpenDocumentTree` 选中的 MDX 目录：`.mdx` 加上同主干的 `.mdd` 资源包。
     *
     * 与 [importMdx] 的区别只在「从哪拿到文件清单」与「多拷了几个 `.mdd`」：校验、暂存、
     * `rename` 与写索引全部复用同一条 [install]。返回约定同 [importMdx]。
     */
    suspend fun importMdxDirectory(context: Context, treeUri: Uri): DictionaryImportError? =
        withContext(Dispatchers.IO) {
            importMutex.withLock {
                _state.value = ImportState.Copying(0f)
                val appContext = context.applicationContext
                val dictionariesDir = FileData.getDictionariesDirectory(appContext)
                val stagingDir = File(dictionariesDir, "${DictionaryCatalog.STAGING_DIRECTORY}/${UUID.randomUUID()}")
                try {
                    val installed = installMdxFolder(appContext, dictionariesDir, stagingDir, treeUri)
                    _state.value = ImportState.Done(installed)
                    null
                } catch (rejection: ImportRejection) {
                    _state.value = ImportState.Error(rejection.error)
                    rejection.error
                } catch (e: CancellationException) {
                    _state.value = ImportState.Idle
                    throw e
                } catch (e: Exception) {
                    logW(TAG, "Could not import the MDX folder: ${e.message}")
                    _state.value = ImportState.Error(DictionaryImportError.FAILED)
                    DictionaryImportError.FAILED
                } finally {
                    if (stagingDir.exists()) stagingDir.deleteRecursively()
                }
            }
        }

    /**
     * 导入 `OpenDocumentTree` 选中的 StarDict 目录（`.ifo` + `.idx(.gz)` + `.dict(.dz)`，
     * 可选 `.syn`）。返回约定同 [importMdx]。
     */
    suspend fun importStarDict(context: Context, treeUri: Uri): DictionaryImportError? =
        withContext(Dispatchers.IO) {
            importMutex.withLock {
                _state.value = ImportState.Copying(0f)
                val appContext = context.applicationContext
                val dictionariesDir = FileData.getDictionariesDirectory(appContext)
                val stagingDir = File(dictionariesDir, "${DictionaryCatalog.STAGING_DIRECTORY}/${UUID.randomUUID()}")
                try {
                    val installed = installStarDict(appContext, dictionariesDir, stagingDir, treeUri)
                    _state.value = ImportState.Done(installed)
                    null
                } catch (rejection: ImportRejection) {
                    _state.value = ImportState.Error(rejection.error)
                    rejection.error
                } catch (e: CancellationException) {
                    _state.value = ImportState.Idle
                    throw e
                } catch (e: Exception) {
                    logW(TAG, "Could not import the StarDict dictionary: ${e.message}")
                    _state.value = ImportState.Error(DictionaryImportError.FAILED)
                    DictionaryImportError.FAILED
                } finally {
                    if (stagingDir.exists()) stagingDir.deleteRecursively()
                }
            }
        }

    // ---- MDX -----------------------------------------------------------------

    private suspend fun installMdx(
        context: Context,
        dictionariesDir: File,
        stagingDir: File,
        uri: Uri
    ): InstalledDictionary {
        val source = CachedFile(context, uri)
        val declaredSize = source.size.takeIf { it > 0 }
        if (declaredSize != null && declaredSize > MAX_IMPORT_BYTES) {
            throw ImportRejection(DictionaryImportError.TOO_LARGE)
        }
        probeHead(source)
        prepareDirectories(dictionariesDir, stagingDir)
        if (declaredSize != null && !hasSpaceFor(dictionariesDir, declaredSize)) {
            throw ImportRejection(DictionaryImportError.NO_SPACE)
        }

        val baseName = sanitizeBaseName(source.name.substringBeforeLast('.'))
        val mdxFile = File(stagingDir, "$baseName.mdx")
        val copied = copy(source, mdxFile) { bytes ->
            if (bytes > MAX_IMPORT_BYTES) throw ImportRejection(DictionaryImportError.TOO_LARGE)
            if (declaredSize != null) {
                _state.value = ImportState.Copying((bytes.toFloat() / declaredSize).coerceIn(0f, 1f))
            }
        }
        if (copied == 0L) throw ImportRejection(DictionaryImportError.EMPTY_FILE)

        _state.value = ImportState.Validating
        val probe = DictionaryEngines.probeMdx(mdxFile)
            ?: throw ImportRejection(DictionaryImportError.NOT_MDX)
        val wordCount = openMdxWordCount(mdxFile)

        return install(
            context = context,
            dictionariesDir = dictionariesDir,
            stagingDir = stagingDir,
            type = DictionaryType.MDX,
            name = probe.title.takeIf { it.isNotBlank() } ?: baseName,
            baseName = baseName,
            wordCount = wordCount
        )
    }

    /**
     * 复制前只读头部预检：误选的视频/整盘备份在复制 512 MB 之前就被拦下（设计 §4.3）。
     * `probeMdxHead` 只回答「是不是 MDict」，所以只有 NOT_MDX 能在这里判；加密/资源包/截断
     * 要读 key section 与偏移，仍由复制后的 `probeMdx` + `openMdx` 分类。
     */
    private fun probeHead(source: CachedFile) {
        val head = source.openInputStream()?.use { stream ->
            val buffer = ByteArray(HEAD_PROBE_BYTES)
            var read = 0
            while (read < HEAD_PROBE_BYTES) {
                val count = stream.read(buffer, read, HEAD_PROBE_BYTES - read)
                if (count == -1) break
                read += count
            }
            buffer.copyOf(read)
        } ?: throw ImportRejection(DictionaryImportError.UNREADABLE)

        if (head.isEmpty()) throw ImportRejection(DictionaryImportError.EMPTY_FILE)
        DictionaryEngines.probeMdxHead(head)
            ?: throw ImportRejection(DictionaryImportError.NOT_MDX)
    }

    /**
     * `openMdx` 是唯一能验证「加密位 / 资源包 / 截断 / 索引能否解析」的公开入口，
     * 校验通过后立刻关掉。上游的失败原因只有英文消息（引擎模块不依赖 Android 资源），
     * 这里按消息分类 —— 分类函数是纯的，单测直接覆盖（[classifyMdxFailure]）。
     */
    private fun openMdxWordCount(mdxFile: File): Int {
        val engine = try {
            DictionaryEngines.openMdx(
                id = "import",
                mdxFile = mdxFile,
                mddFiles = emptyList(),
                title = "",
                description = ""
            )
        } catch (e: Exception) {
            throw ImportRejection(classifyMdxFailure(e.message))
        }
        return try {
            engine.wordCount.takeIf { it > 0 } ?: throw ImportRejection(DictionaryImportError.CORRUPT)
        } finally {
            runCatching { engine.close() }
        }
    }

    /**
     * 目录导入的实际流程。文件清单先经过纯函数 [planMdxFolder] 变成「复制什么」的计划
     * （恰好一个 mdx / 分卷连续 / 体积），再按 mdx → `.mdd` 的顺序复制；每个 `.mdd` 复制完立刻
     * 用 [DictionaryEngines.checkMdxResourcePack] 单独校验 —— 否则一个加密的 `.2.mdd` 要到
     * 使用图片时才暴露，而那时用户只看到「缺图」。
     */
    private suspend fun installMdxFolder(
        context: Context,
        dictionariesDir: File,
        stagingDir: File,
        treeUri: Uri
    ): InstalledDictionary {
        val tree = try {
            DocumentFileCompat.fromUri(context, treeUri)
        } catch (e: Exception) {
            null
        } ?: throw ImportRejection(DictionaryImportError.UNREADABLE)
        if (!tree.isDirectory) throw ImportRejection(DictionaryImportError.INCOMPLETE)

        val files = try {
            tree.listFiles().filterNot { it.isDirectory }
        } catch (e: Exception) {
            throw ImportRejection(DictionaryImportError.UNREADABLE)
        }
        // 文件名可能为 null（provider 失约），这种条目既不可能是 mdx 也不能复制，直接跳过。
        val byName = files.mapNotNull { file -> file.name?.let { it to file } }.toMap()
        val plan = when (
            val result = planMdxFolder(byName.map { (name, file) -> name to file.length() })
        ) {
            is MdxFolderPlan.Rejection -> throw ImportRejection(result.error)
            is MdxFolderPlan.Ready -> result
        }

        val mdxSource = byName[plan.mdx] ?: throw ImportRejection(DictionaryImportError.UNREADABLE)
        // 与单文件入口一致：先读 1 MiB 头部，误选的视频/备份不会先拷 54 MB 才报「不是 MDict」。
        probeHead(CachedFile(context, mdxSource.uri))

        prepareDirectories(dictionariesDir, stagingDir)
        if (plan.totalBytes > 0 && !hasSpaceFor(dictionariesDir, plan.totalBytes)) {
            throw ImportRejection(DictionaryImportError.NO_SPACE)
        }

        val baseName = sanitizeBaseName(plan.base)
        val mdxFile = File(stagingDir, "$baseName.mdx")
        var copied = 0L
        copied += copy(mdxSource.uri, context, mdxFile) { bytes ->
            if (bytes > MAX_IMPORT_BYTES) throw ImportRejection(DictionaryImportError.TOO_LARGE)
            _state.value = copyingOf(copied + bytes, plan.totalBytes)
        }
        if (copied == 0L) throw ImportRejection(DictionaryImportError.EMPTY_FILE)

        for (mdd in plan.mdds) {
            val source = byName[mdd.name] ?: throw ImportRejection(DictionaryImportError.UNREADABLE)
            val target = File(stagingDir, mddFileName(baseName, mdd))
            val alreadyCopied = copied
            copied += copy(source.uri, context, target) { bytes ->
                // provider 不报体积（totalBytes 为 0）时预检拦不住，这里按实际字节把关。
                if (alreadyCopied + bytes > MAX_IMPORT_TOTAL_BYTES) {
                    throw ImportRejection(DictionaryImportError.TOO_LARGE)
                }
                _state.value = copyingOf(alreadyCopied + bytes, plan.totalBytes)
            }
            classifyPackFailure(DictionaryEngines.checkMdxResourcePack(target))
                ?.let { throw ImportRejection(it) }
        }

        _state.value = ImportState.Validating
        val probe = DictionaryEngines.probeMdx(mdxFile)
            ?: throw ImportRejection(DictionaryImportError.NOT_MDX)
        val wordCount = openMdxWordCount(mdxFile)

        return install(
            context = context,
            dictionariesDir = dictionariesDir,
            stagingDir = stagingDir,
            type = DictionaryType.MDX,
            name = probe.title.takeIf { it.isNotBlank() } ?: baseName,
            baseName = baseName,
            wordCount = wordCount
        )
    }

    private fun copyingOf(copiedBytes: Long, totalBytes: Long): ImportState.Copying =
        ImportState.Copying(
            if (totalBytes > 0) (copiedBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f
        )

    // ---- StarDict ------------------------------------------------------------

    private suspend fun installStarDict(
        context: Context,
        dictionariesDir: File,
        stagingDir: File,
        treeUri: Uri
    ): InstalledDictionary {
        // 授权可能在选完目录之后失效（或 provider 直接抛 SecurityException），统一算「读不到」。
        val tree = try {
            DocumentFileCompat.fromUri(context, treeUri)
        } catch (e: Exception) {
            null
        } ?: throw ImportRejection(DictionaryImportError.UNREADABLE)
        if (!tree.isDirectory) throw ImportRejection(DictionaryImportError.INCOMPLETE)

        val files = try {
            tree.listFiles().filterNot { it.isDirectory }
        } catch (e: Exception) {
            throw ImportRejection(DictionaryImportError.UNREADABLE)
        }
        val ifos = files.filter { it.name?.endsWith(".ifo", ignoreCase = true) == true }
        if (ifos.isEmpty()) throw ImportRejection(DictionaryImportError.INCOMPLETE)
        if (ifos.size > 1) throw ImportRejection(DictionaryImportError.MULTIPLE_DICTIONARIES)

        val ifo = ifos.single()
        val ifoName = ifo.name ?: throw ImportRejection(DictionaryImportError.UNREADABLE)
        val baseName = ifoName.dropLast(".ifo".length)

        val index = files.firstOrNull { it.matches(baseName, ".idx", ".idx.gz") }
        val data = files.firstOrNull { it.matches(baseName, ".dict", ".dict.dz") }
        if (index == null || data == null) throw ImportRejection(DictionaryImportError.INCOMPLETE)
        val syn = files.firstOrNull { it.matches(baseName, ".syn") }

        val selected = listOfNotNull(ifo, index, data, syn)
        val declaredSize = selected.sumOf { it.length() }.takeIf { it > 0 }
        if (declaredSize != null && declaredSize > MAX_IMPORT_BYTES) {
            throw ImportRejection(DictionaryImportError.TOO_LARGE)
        }
        prepareDirectories(dictionariesDir, stagingDir)
        if (declaredSize != null && !hasSpaceFor(dictionariesDir, declaredSize)) {
            throw ImportRejection(DictionaryImportError.NO_SPACE)
        }

        var copied = 0L
        for (file in selected) {
            val name = file.name ?: continue
            // 输出统一成小写后缀：`Tiny.IFO` / `tiny.IDX` 也要落成引擎认的 `Tiny.ifo` / `Tiny.idx`。
            val suffix = name.substring(baseName.length).lowercase()
            val target = File(stagingDir, baseName + suffix)
            copied += copy(file.uri, context, target) { bytes ->
                if (copied + bytes > MAX_IMPORT_BYTES) {
                    throw ImportRejection(DictionaryImportError.TOO_LARGE)
                }
                if (declaredSize != null) {
                    _state.value = ImportState.Copying(
                        ((copied + bytes).toFloat() / declaredSize).coerceIn(0f, 1f)
                    )
                }
            }
        }
        if (copied == 0L) throw ImportRejection(DictionaryImportError.EMPTY_FILE)

        _state.value = ImportState.Validating
        val engine = DictionaryEngines.openStarDict("import", stagingDir, baseName)
            ?: throw ImportRejection(DictionaryImportError.CORRUPT)
        val wordCount = try {
            engine.wordCount.takeIf { it > 0 } ?: throw ImportRejection(DictionaryImportError.CORRUPT)
        } finally {
            runCatching { engine.close() }
        }

        val metadata = readIfoMetadata(File(stagingDir, "$baseName.ifo"))
        return install(
            context = context,
            dictionariesDir = dictionariesDir,
            stagingDir = stagingDir,
            type = DictionaryType.STARDICT,
            name = metadata?.first?.takeIf { it.isNotBlank() } ?: baseName,
            baseName = baseName,
            wordCount = wordCount
        )
    }

    private fun DocumentFile.matches(baseName: String, vararg suffixes: String): Boolean {
        val name = name ?: return false
        return suffixes.any { name.equals(baseName + it, ignoreCase = true) }
    }

    // ---- 共用：暂存 → 原子换目录 → 写索引 ---------------------------------------

    /**
     * [stagingDir] 已经校验过，`rename` 到 `imported/<uuid>/` 后写目录索引。
     * 索引写入失败不影响导入结果（文件在磁盘上，下一次 `refresh` 会收养）。
     */
    private suspend fun install(
        context: Context,
        dictionariesDir: File,
        stagingDir: File,
        type: DictionaryType,
        name: String,
        baseName: String,
        wordCount: Int
    ): InstalledDictionary {
        val id = UUID.randomUUID().toString()
        val targetDir = File(File(dictionariesDir, DictionaryCatalog.IMPORTED_DIRECTORY), id)
        if (!stagingDir.renameTo(targetDir)) {
            throw ImportRejection(DictionaryImportError.FAILED)
        }

        val entry = DictionaryIndexEntry(
            id = id,
            type = type,
            name = name,
            baseName = baseName,
            directory = "${DictionaryCatalog.IMPORTED_DIRECTORY}/$id",
            wordCount = wordCount,
            sizeBytes = sizeOf(targetDir),
            enabled = true,
            importedAt = System.currentTimeMillis()
        )
        return DictionaryCatalog.add(context, entry)
            ?: throw ImportRejection(DictionaryImportError.FAILED)
    }

    private fun prepareDirectories(dictionariesDir: File, stagingDir: File) {
        if (!dictionariesDir.exists() && !dictionariesDir.mkdirs()) {
            throw ImportRejection(DictionaryImportError.FAILED)
        }
        val imported = File(dictionariesDir, DictionaryCatalog.IMPORTED_DIRECTORY)
        if (!imported.exists() && !imported.mkdirs()) {
            throw ImportRejection(DictionaryImportError.FAILED)
        }
        if (!stagingDir.mkdirs()) throw ImportRejection(DictionaryImportError.FAILED)
    }

    private fun hasSpaceFor(dictionariesDir: File, bytes: Long): Boolean =
        dictionariesDir.usableSpace >= bytes * SPACE_NUMERATOR / SPACE_DENOMINATOR

    private suspend fun copy(source: CachedFile, target: File, onBytes: (Long) -> Unit): Long {
        val input = source.openInputStream() ?: throw ImportRejection(DictionaryImportError.UNREADABLE)
        return input.use { stream -> writeTo(stream, target, onBytes) }
    }

    private suspend fun copy(
        uri: Uri,
        context: Context,
        target: File,
        onBytes: (Long) -> Unit
    ): Long {
        val input = try {
            context.contentResolver.openInputStream(uri)
        } catch (e: Exception) {
            null
        } ?: throw ImportRejection(DictionaryImportError.UNREADABLE)
        return input.use { stream -> writeTo(stream, target, onBytes) }
    }

    /**
     * 复制循环。每读一块检查一次取消：离开设置页时 `CancellationException` 必须在下一个
     * 256 KB 之内抛出来，否则 512 MB 的词典会一直复制完才响应（`finally` 里的暂存目录清理
     * 也就跟着拖到最后）。`ensureActive` 只抛取消，不吞其它异常。
     */
    private suspend fun writeTo(input: InputStream, target: File, onBytes: (Long) -> Unit): Long {
        var copied = 0L
        BufferedOutputStream(FileOutputStream(target), BUFFER_BYTES).use { output ->
            val buffer = ByteArray(BUFFER_BYTES)
            while (true) {
                currentCoroutineContext().ensureActive()
                val read = input.read(buffer)
                if (read == -1) break
                copied += read
                onBytes(copied)
                output.write(buffer, 0, read)
            }
        }
        return copied
    }
}

/** 复制/校验循环里的失败信号：把「哪种错误」从深层 IO 代码带到导入入口统一落状态。 */
private class ImportRejection(val error: DictionaryImportError) : Exception()

/**
 * MDX 打开失败的消息 → 错误码。引擎模块不依赖 Android 资源，只能按上游 `require` /
 * `error` 的固定消息分类；消息措辞变了会落到 [DictionaryImportError.CORRUPT]，
 * 仍然是一个可展示的明确错误，不会把坏词典装进去。
 */
internal fun classifyMdxFailure(message: String?): DictionaryImportError = when {
    message == null -> DictionaryImportError.CORRUPT
    // 顺序重要：两种资源包措辞都含 "resource pack"，加密位在引擎构造时先判，
    // 所以带 password 的消息一定是加密而不是「选错了文件」。
    message.contains("password", ignoreCase = true) -> DictionaryImportError.ENCRYPTED
    // `not an MDict resource pack` = 根本读不出头部，归「资源包坏了」；
    // `is a resource pack, not a dictionary` = 选错了文件，归 `RESOURCE_PACK`。
    message.contains("not an MDict resource pack", ignoreCase = true) ->
        DictionaryImportError.RESOURCE_CORRUPT

    message.contains("resource pack", ignoreCase = true) -> DictionaryImportError.RESOURCE_PACK
    message.contains("not an MDict", ignoreCase = true) -> DictionaryImportError.NOT_MDX
    message.contains("truncated", ignoreCase = true) -> DictionaryImportError.CORRUPT
    else -> DictionaryImportError.CORRUPT
}

/**
 * `.mdd` 校验结果 → 导入错误码；[MdxPackCheck.Ok] 返回 null（不是失败）。
 *
 * 加密与「包用不了」必须分开：前者要去找一部没加密的词典，后者只能重下/重拷这个包。
 * 误选的 `.mdx`（[MdxPackCheck.NotResourcePack]）与截断的包（[MdxPackCheck.Corrupt]）对用户
 * 是同一件事 —— 这个 `.mdd` 用不了 —— 所以共用 `RESOURCE_CORRUPT` 的文案。
 */
internal fun classifyPackFailure(check: MdxPackCheck): DictionaryImportError? = when (check) {
    is MdxPackCheck.Ok -> null
    MdxPackCheck.Encrypted -> DictionaryImportError.ENCRYPTED
    MdxPackCheck.NotResourcePack, is MdxPackCheck.Corrupt -> DictionaryImportError.RESOURCE_CORRUPT
}

/** 文件名主干只留字母/数字/`.`/`_`/`-`：它要拼进文件路径，不能带分隔符。 */
internal fun sanitizeBaseName(raw: String): String {
    val cleaned = raw
        .replace(Regex("[^\\p{L}\\p{N}._-]"), "_")
        .trim('.', '_', '-')
        .take(64)
    return cleaned.ifBlank { "dictionary" }
}

// ---- MDX 目录：纯清单层（无 android.*，JVM 单测直接调用）----------------------

/**
 * 目录里一个要复制的 `.mdd` 分卷。
 *
 * [ordinal] 是文件名里的编号（`<base>.2.mdd` → 2）；null 表示主包 `<base>.mdd`，它没有编号、
 * 但在复制与引擎搜索顺序里都排在最前。保留原始文件名（而不是只存编号）是因为复制阶段要靠它
 * 在同目录里找回对应的 `DocumentFile`。
 */
internal data class MddSource(val name: String, val ordinal: Int?, val sizeBytes: Long)

/**
 * 「目录文件清单 → 复制计划」。独立成纯类型是为了能单测：配对规则（恰好一个 `.mdx`、分卷连续、
 * 体积上限）错一条的后果都是「装进去一部缺图/缺音的词典」，而这在真机上很难发现。
 */
internal sealed interface MdxFolderPlan {
    /**
     * [base] 是 `.mdx` 的文件名主干（未净化），[mdds] 按复制顺序排列（主包在前，然后编号升序），
     * [totalBytes] 是所有文件声明体积之和（provider 不报体积时可能偏小甚至为 0）。
     */
    data class Ready(
        val base: String,
        val mdx: String,
        val mdds: List<MddSource>,
        val totalBytes: Long
    ) : MdxFolderPlan

    data class Rejection(val error: DictionaryImportError) : MdxFolderPlan
}

/**
 * 把目录里的文件清单（文件名 + 声明体积）变成导入计划。
 *
 * 规则与引擎的 [com.qiuminal.juicedict.engine.DictionaryEngines.mddFilesFor] 保持一致 ——
 * 严格执行这一条很重要：导入时配错对，使用者（引擎）不会报错，只会缺资源。
 *  - 恰好一个 `.mdx`：0 个 → [DictionaryImportError.INCOMPLETE]，多个 →
 *    [DictionaryImportError.MULTIPLE_DICTIONARIES]；扩展名大小写不敏感。
 *  - 资源包 `<base>.mdd` 与 `<base>.N.mdd`（N 为纯数字，大小写不敏感）。编号必须是 1..N 连续：
 *    缺号或有编号分卷却没有主包 → [DictionaryImportError.RESOURCE_PARTS_MISSING]，
 *    否则用户会得到一部「部分图片/发音丢失」的词典，而且没有任何提示。
 *  - `<base>.images.mdd` 这类非数字后缀忽略（引擎也不会读它们）。
 *  - `.mdx` 单独超过 [DictionaryImporter.MAX_IMPORT_BYTES]、或总量超过
 *    [DictionaryImporter.MAX_IMPORT_TOTAL_BYTES] → [DictionaryImportError.TOO_LARGE]。
 */
internal fun planMdxFolder(files: List<Pair<String, Long>>): MdxFolderPlan {
    // `name.length > MDX_SUFFIX.length` 排除名字就叫 `.mdx` 的文件：它的主干是空串，拼不出任何文件名。
    val mdxFiles = files.filter { (name, _) ->
        name.length > MDX_SUFFIX.length && name.endsWith(MDX_SUFFIX, ignoreCase = true)
    }
    if (mdxFiles.isEmpty()) return MdxFolderPlan.Rejection(DictionaryImportError.INCOMPLETE)
    if (mdxFiles.size > 1) {
        return MdxFolderPlan.Rejection(DictionaryImportError.MULTIPLE_DICTIONARIES)
    }

    val (mdxName, mdxSize) = mdxFiles.single()
    val mdxBytes = mdxSize.coerceAtLeast(0)
    if (mdxBytes > DictionaryImporter.MAX_IMPORT_BYTES) {
        return MdxFolderPlan.Rejection(DictionaryImportError.TOO_LARGE)
    }
    val base = mdxName.dropLast(MDX_SUFFIX.length)

    val basePack = files.firstOrNull { (name, _) -> name.equals("$base$MDD_SUFFIX", ignoreCase = true) }
    val numbered = files
        .mapNotNull { (name, size) ->
            val ordinal = mddOrdinalOf(name, base) ?: return@mapNotNull null
            ordinal to MddSource(name, ordinal, size.coerceAtLeast(0))
        }
    // 编号重复（`.1.mdd` + `.01.mdd`）同样会被下面的连续性检查拦下：ordinals 会变成 [1, 1]。
    val ordinals = numbered.map { it.first }.sorted()

    if (ordinals.isNotEmpty()) {
        val missingParts = basePack == null || ordinals != (1..ordinals.size).toList()
        if (missingParts) {
            return MdxFolderPlan.Rejection(DictionaryImportError.RESOURCE_PARTS_MISSING)
        }
    }

    val mdds = listOfNotNull(
        basePack?.let { (name, size) -> MddSource(name, null, size.coerceAtLeast(0)) }
    ) + numbered.sortedBy { it.first }.map { it.second }

    val totalBytes = mdxBytes + mdds.sumOf { it.sizeBytes }
    if (totalBytes > DictionaryImporter.MAX_IMPORT_TOTAL_BYTES) {
        return MdxFolderPlan.Rejection(DictionaryImportError.TOO_LARGE)
    }

    return MdxFolderPlan.Ready(
        base = base,
        mdx = mdxName,
        mdds = mdds,
        totalBytes = totalBytes
    )
}

/**
 * 暂存目录里的目标文件名。统一用小写后缀与净化后的主干、编号只留数字部分：引擎的
 * `MdxResourceFiles.pairsFor` 按 `<base>.mdd` / `<base>.N.mdd` **精确**（区分大小写）配对，
 * 源目录里的 `Tiny.MDD` / `Tiny.01.mdd` 必须落成引擎认的写法。
 */
internal fun mddFileName(baseName: String, part: MddSource): String =
    if (part.ordinal == null) "$baseName$MDD_SUFFIX" else "$baseName.${part.ordinal}$MDD_SUFFIX"

/** `<base>.<纯数字>.mdd` 里的编号；不匹配（含非数字后缀）返回 null。 */
private fun mddOrdinalOf(name: String, base: String): Int? {
    if (!name.endsWith(MDD_SUFFIX, ignoreCase = true)) return null
    // 至少要有 `<base>.` + 一个数字 + `.mdd`。
    if (name.length <= base.length + 1 + MDD_SUFFIX.length) return null
    if (!name.startsWith("$base.", ignoreCase = true)) return null
    val ordinal = name.substring(base.length + 1, name.length - MDD_SUFFIX.length)
    if (ordinal.isEmpty() || !ordinal.all { it in '0'..'9' }) return null
    return ordinal.toIntOrNull()
}

private const val MDX_SUFFIX = ".mdx"
private const val MDD_SUFFIX = ".mdd"
