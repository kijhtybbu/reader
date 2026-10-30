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
 * **`.mdd` 资源包不在本期导入**：MDX 入口只接受单个 `.mdx` 文件（`OpenDocument`），没有能同时
 * 拿到同目录兄弟文件的授权；资源渲染本身也是后续阶段的事（设计 §1.2 排除渲染，§4.3 记了这笔欠账）。
 * 因此导入的 MDX 在 `DictionaryRepository` 里 `mddFilesFor` 永远为空，这是有意的。
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
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

    /** 超过 [DictionaryImporter.MAX_IMPORT_BYTES]。 */
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
    FAILED
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
     * 只导入正文文件：`.mdd` 资源包本期不复制（见文件头注释与设计 §4.3 的欠账说明）。
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

    private fun copy(source: CachedFile, target: File, onBytes: (Long) -> Unit): Long {
        val input = source.openInputStream() ?: throw ImportRejection(DictionaryImportError.UNREADABLE)
        return input.use { stream -> writeTo(stream, target, onBytes) }
    }

    private fun copy(
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

    private fun writeTo(input: InputStream, target: File, onBytes: (Long) -> Unit): Long {
        var copied = 0L
        BufferedOutputStream(FileOutputStream(target), BUFFER_BYTES).use { output ->
            val buffer = ByteArray(BUFFER_BYTES)
            while (true) {
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
    message.contains("resource pack", ignoreCase = true) -> DictionaryImportError.RESOURCE_PACK
    message.contains("password", ignoreCase = true) -> DictionaryImportError.ENCRYPTED
    message.contains("not an MDict", ignoreCase = true) -> DictionaryImportError.NOT_MDX
    message.contains("truncated", ignoreCase = true) -> DictionaryImportError.CORRUPT
    else -> DictionaryImportError.CORRUPT
}

/** 文件名主干只留字母/数字/`.`/`_`/`-`：它要拼进文件路径，不能带分隔符。 */
internal fun sanitizeBaseName(raw: String): String {
    val cleaned = raw
        .replace(Regex("[^\\p{L}\\p{N}._-]"), "_")
        .trim('.', '_', '-')
        .take(64)
    return cleaned.ifBlank { "dictionary" }
}
