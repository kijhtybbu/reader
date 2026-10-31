/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * fork 新增：已安装词典目录（第二阶段 S4）。
 *
 * 内置 ECDICT 由 [DictionaryRegistry] 安装，导入的 MDX/StarDict 由 [DictionaryImporter]
 * 落到 `filesDir/dictionaries/imported/<uuid>/`。这里把两者合成一份
 * `StateFlow<List<InstalledDictionary>>` 给设置页与（S5 的）取词卡片消费：
 *  - 内置条目从 [DictionaryRegistry.builtInState] 派生，`builtIn = true`，不可删除/禁用；
 *  - 导入条目来自 `dictionaries/index.json`，只存元数据（见 [DictionaryIndexCodec]）；
 *  - [refresh] 以磁盘为准重算：丢掉文件已不存在的条目，并把「目录在、索引里没有」的词典
 *    收养回来 —— 导入的原子 rename 与写索引之间进程被杀时，文件不会变成孤儿。
 *  - 启动时清掉 `.staging/` 下的半份复制（[sweepStaging]），不让 512 MB 的残留永久占地。
 *
 * 与 `UserFontRegistry` 一样是 object：`Application.onCreate` 里没有 Hilt 容器，
 * 而设置页的 `LocalSettings` 之外也没有别的注入点。
 */

package ua.acclorite.book_story.data.dictionary

import android.content.Context
import com.qiuminal.juicedict.engine.DictionaryEngines
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import ua.acclorite.book_story.core.data.FileData
import ua.acclorite.book_story.core.log.logW
import java.io.File
import java.io.IOException

private const val TAG = "DictionaryCatalog"

/** 词典的磁盘格式。引擎（`DictionaryEngines`）按它决定用哪个 open 入口。 */
enum class DictionaryType(val wire: String) {
    STARDICT("STARDICT"),
    MDX("MDX");

    companion object {
        /** 解析索引里的 `type`：只认稳定 [wire]，另兼容早期把枚举名写进磁盘的索引。 */
        fun fromWire(value: String): DictionaryType? =
            entries.firstOrNull { it.wire == value } ?: entries.firstOrNull { it.name == value }
    }
}

/**
 * 一部可用词典的运行时视图。[directory] 是词典文件所在目录，[baseName] 是引擎拼文件名
 * 用的主干（`ecdict` → `ecdict.ifo` / `.idx` / `.dict`）。
 *
 * [hasResources] 表示同目录下有没有 `<baseName>.mdd`（含 `.1.mdd` 分卷）：有资源的词条才走
 * WebView 渲染，纯文本仍然用结构化卡片。它是运行时由 [DictionaryEngines.mddFilesFor] 推导的，
 * 不进 `index.json` —— 加 `.mdd` 不需要迁移索引格式。
 */
data class InstalledDictionary(
    val id: String,
    val type: DictionaryType,
    val name: String,
    val baseName: String,
    val directory: File,
    val wordCount: Int,
    val sizeBytes: Long,
    val enabled: Boolean,
    val importedAt: Long,
    val builtIn: Boolean,
    val hasResources: Boolean = false
)

object DictionaryCatalog {

    /** 内置词典的固定 id，与 [DictionaryRegistry] 的引擎 id 一致。 */
    const val BUILT_IN_ID = "builtin"

    /** 内置词库的显示名。数据层固定英文：它是专有名词，不进翻译资源。 */
    const val BUILT_IN_NAME = "ECDICT"

    /** 导入词典的落盘目录（相对 `filesDir/dictionaries/`）。 */
    const val IMPORTED_DIRECTORY = "imported"

    /**
     * 导入暂存目录（相对 `filesDir/dictionaries/`）：每次导入写 `.staging/<uuid>/`，
     * 成功后 `rename` 到 [IMPORTED_DIRECTORY]。进程被杀留下的子目录由 [sweepStaging] 在启动时清掉。
     */
    internal const val STAGING_DIRECTORY = ".staging"

    internal const val INDEX_FILE = "index.json"
    private const val INDEX_TEMP_FILE = "index.json.tmp"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writeMutex = Mutex()

    private val _dictionaries = MutableStateFlow<List<InstalledDictionary>>(emptyList())
    val dictionaries: StateFlow<List<InstalledDictionary>> = _dictionaries.asStateFlow()

    private var root: File? = null

    @Volatile
    private var index: List<DictionaryIndexEntry> = emptyList()

    /**
     * 同步读索引并订阅内置词典状态，只允许在 `Application.onCreate` 里调用一次。
     * 索引只有几 KB，同步读没有代价；真正的磁盘核对（[refresh]）放到 IO 线程。
     */
    fun load(context: Context) {
        if (root != null) return
        val dictionariesDir = FileData.getDictionariesDirectory(context)
        root = dictionariesDir
        index = readIndexFile(dictionariesDir)
        publish()

        scope.launch {
            DictionaryRegistry.builtInState.collect { publish() }
        }
        scope.launch {
            // 启动时还没有导入在跑（导入挂在设置页的作用域上），可以安全清理上次进程留下的暂存目录。
            sweepStaging(dictionariesDir)
            refresh(dictionariesDir)
        }
    }

    /** 新增（或替换同 id 的）导入词典元数据。调用方已经保证文件落在 [DictionaryIndexEntry.directory]。 */
    suspend fun add(context: Context, entry: DictionaryIndexEntry): InstalledDictionary? =
        withContext(Dispatchers.IO) {
            val dictionariesDir = directoryOf(context)
            writeMutex.withLock {
                val next = index.filterNot { it.id == entry.id } + entry
                persist(dictionariesDir, next)
                index = next
            }
            publish()
            dictionaries.value.firstOrNull { it.id == entry.id }
        }

    /**
     * 删除导入词典：先删文件，再删索引条目。反过来会让「索引没了、目录还在」变成孤儿目录，
     * 下一次 [refresh] 又把它收养回来。
     *
     * 内置词典返回 false（不可删除）。
     */
    suspend fun delete(context: Context, id: String): Boolean = withContext(Dispatchers.IO) {
        if (id == BUILT_IN_ID) return@withContext false

        val dictionariesDir = directoryOf(context)
        val entry = index.firstOrNull { it.id == id } ?: return@withContext false
        val directory = resolve(dictionariesDir, entry.directory)
        if (directory != null && directory.exists() && !directory.deleteRecursively()) {
            logW(TAG, "Could not delete the dictionary files at ${directory.absolutePath}")
            return@withContext false
        }
        writeMutex.withLock {
            val next = index.filterNot { it.id == id }
            persist(dictionariesDir, next)
            index = next
        }
        publish()
        true
    }

    /** 启用/禁用导入词典。内置词典返回 false（不可禁用）。 */
    suspend fun setEnabled(context: Context, id: String, enabled: Boolean): Boolean =
        withContext(Dispatchers.IO) {
            if (id == BUILT_IN_ID) return@withContext false

            val dictionariesDir = directoryOf(context)
            val updated = writeMutex.withLock {
                if (index.none { it.id == id }) return@withLock false
                val next = index.map { if (it.id == id) it.copy(enabled = enabled) else it }
                persist(dictionariesDir, next)
                index = next
                true
            }
            if (updated) publish()
            updated
        }

    /** 以磁盘为准重算导入词典：丢掉文件缺失的条目，收养未入索引的目录。 */
    suspend fun refresh(context: Context) = withContext(Dispatchers.IO) {
        refresh(directoryOf(context))
    }

    private suspend fun refresh(dictionariesDir: File) {
        writeMutex.withLock {
            val current = index
            val next = reconcileIndex(dictionariesDir, current)
            if (next != current) {
                persist(dictionariesDir, next)
                index = next
            }
        }
        publish()
    }

    private fun directoryOf(context: Context): File =
        root ?: FileData.getDictionariesDirectory(context)

    private fun publish() {
        val dictionariesDir = root ?: return
        val imported = index.mapNotNull { entry ->
            val directory = resolve(dictionariesDir, entry.directory) ?: return@mapNotNull null
            InstalledDictionary(
                id = entry.id,
                type = entry.type,
                name = entry.name,
                baseName = entry.baseName,
                directory = directory,
                wordCount = entry.wordCount,
                sizeBytes = entry.sizeBytes,
                enabled = entry.enabled,
                importedAt = entry.importedAt,
                builtIn = false,
                hasResources = hasResourcePacks(entry.type, directory, entry.baseName)
            )
        }
        _dictionaries.value = listOf(builtInEntry(dictionariesDir)) + imported
    }

    /**
     * 内置条目始终在列表里：安装中/失败时也要能显示状态。词条数与目录来自
     * [BuiltInState.Ready]；未就绪时 `wordCount = 0`，取词侧会因引擎未打开而跳过。
     */
    private fun builtInEntry(dictionariesDir: File): InstalledDictionary {
        val state = DictionaryRegistry.builtInState.value
        val ready = state as? BuiltInState.Ready
        val directory = ready?.directory ?: File(dictionariesDir, BUILT_IN_ID)
        val baseName = ready?.baseName ?: "ecdict"
        return InstalledDictionary(
            id = BUILT_IN_ID,
            type = DictionaryType.STARDICT,
            name = BUILT_IN_NAME,
            baseName = baseName,
            directory = directory,
            wordCount = ready?.wordCount ?: 0,
            sizeBytes = sizeOf(directory),
            enabled = true,
            importedAt = 0L,
            builtIn = true,
            hasResources = hasResourcePacks(DictionaryType.STARDICT, directory, baseName)
        )
    }

    private fun readIndexFile(dictionariesDir: File): List<DictionaryIndexEntry> {
        val file = File(dictionariesDir, INDEX_FILE)
        if (!file.isFile) return emptyList()

        val raw = try {
            file.readText()
        } catch (e: IOException) {
            logW(TAG, "Could not read the dictionary index: ${e.message}")
            return emptyList()
        }

        val decoded = DictionaryIndexCodec.decode(raw)
        if (decoded == null) {
            // 不删文件：目录还在，refresh 会按磁盘内容重新收养，只是丢 enabled/name 等元数据。
            logW(TAG, "Dictionary index is corrupt; rebuilding it from the imported directories.")
            return emptyList()
        }
        return decoded
    }

    private fun persist(dictionariesDir: File, entries: List<DictionaryIndexEntry>) {
        if (!dictionariesDir.exists() && !dictionariesDir.mkdirs()) {
            logW(TAG, "Could not create the dictionaries directory: ${dictionariesDir.absolutePath}")
            return
        }
        val target = File(dictionariesDir, INDEX_FILE)
        val temp = File(dictionariesDir, INDEX_TEMP_FILE)
        try {
            temp.writeText(DictionaryIndexCodec.encode(entries))
            if (!temp.renameTo(target)) {
                target.delete()
                if (!temp.renameTo(target)) {
                    logW(TAG, "Could not persist the dictionary index.")
                }
            }
        } catch (e: IOException) {
            logW(TAG, "Could not persist the dictionary index: ${e.message}")
        }
    }
}

// ---- 纯文件层（无 android.*，JVM 单测直接调用）--------------------------------

/**
 * 导入/安装过程中的临时目录名：`.staging`（导入暂存）、`builtin.tmp` / `builtin.old`
 * （内置词典换目录的中间态）。枚举 `imported/` 时必须跳过它们，否则半个词典会被当成
 * 一部可用的词典收养。
 */
internal fun isTransientDirectoryName(name: String): Boolean =
    name.startsWith('.') || name.endsWith(".tmp") || name.endsWith(".old")

internal fun enumerateImportedDirectories(importedRoot: File): List<File> =
    importedRoot.listFiles()
        ?.filter { it.isDirectory && !isTransientDirectoryName(it.name) }
        ?.sortedBy { it.name }
        .orEmpty()

/**
 * 清掉 `dictionaries/.staging/` 下的所有子目录：那是被杀的进程留下的半份复制（可能几百 MB）。
 * 只在 `Application.onCreate`（[DictionaryCatalog.load]）调用 —— 导入在设置页进行，启动时不可能有
 * 活跃导入，所以不会删掉正在写的暂存目录。
 */
internal fun sweepStaging(dictionariesDir: File) {
    val stagingRoot = File(dictionariesDir, DictionaryCatalog.STAGING_DIRECTORY)
    if (!stagingRoot.isDirectory) return
    stagingRoot.listFiles()?.forEach { it.deleteRecursively() }
    if (stagingRoot.list()?.isEmpty() == true) stagingRoot.delete()
}

/**
 * 把索引条目与磁盘对齐：留下文件齐全的条目，再收养 `imported/` 下未入索引的目录。
 * 顺序稳定（索引顺序 + 目录名排序），[DictionaryRepository] 的「按目录顺序」排序依赖它。
 */
internal fun reconcileIndex(
    dictionariesDir: File,
    entries: List<DictionaryIndexEntry>
): List<DictionaryIndexEntry> {
    val kept = entries.mapNotNull { entry ->
        // 内置词典的 id 是保留值：索引里出现它会让列表出现两条 `builtin`，Compose 的
        // `key = { it.id }` 直接抛 IllegalArgumentException。
        if (entry.id == DictionaryCatalog.BUILT_IN_ID) return@mapNotNull null
        val directory = resolve(dictionariesDir, entry.directory) ?: return@mapNotNull null
        if (hasDictionaryFiles(entry.type, directory, entry.baseName)) entry else null
    }.distinctBy { it.id }
    val known = kept.mapTo(HashSet()) { it.id }
    // 目录名恰好叫 `builtin` 时不能收养：收养出来的 id 会与内置条目撞车（Compose 的
    // `key = { it.id }` 直接抛异常），而且 `DictionaryRepository` 也会把它当成内置词典。
    val adopted = enumerateImportedDirectories(File(dictionariesDir, DictionaryCatalog.IMPORTED_DIRECTORY))
        .filterNot { it.name in known || it.name == DictionaryCatalog.BUILT_IN_ID }
        .mapNotNull { adoptDirectory(it) }
    // 最后再按 id 去重：即使磁盘上出现同名目录/索引里已有同 id 条目，也不会把重复项交给 UI。
    return (kept + adopted).distinctBy { it.id }
}

/**
 * 词典目录里有没有 `.mdd` 资源包（MDX 才有；StarDict 的资源页不在本期范围）。
 * 每次 [DictionaryCatalog.publish] 重新推导：导入/删除 `.mdd` 后会跟着变，不需要写索引。
 */
internal fun hasResourcePacks(
    type: DictionaryType,
    directory: File,
    baseName: String
): Boolean = type == DictionaryType.MDX &&
    DictionaryEngines.mddFilesFor(directory, baseName).isNotEmpty()

private fun hasDictionaryFiles(type: DictionaryType, directory: File, baseName: String): Boolean =
    when (type) {
        DictionaryType.STARDICT ->
            File(directory, "$baseName.ifo").isFile &&
                (File(directory, "$baseName.idx").isFile || File(directory, "$baseName.idx.gz").isFile) &&
                (File(directory, "$baseName.dict").isFile || File(directory, "$baseName.dict.dz").isFile)

        DictionaryType.MDX -> File(directory, "$baseName.mdx").isFile
    }

/** 从目录内容重建一条元数据；认不出格式（或文件不齐）时返回 null，目录原样保留。 */
private fun adoptDirectory(directory: File): DictionaryIndexEntry? {
    val files = directory.listFiles()?.filter { it.isFile }.orEmpty()

    val ifo = files.firstOrNull { it.name.endsWith(".ifo", ignoreCase = true) }
    if (ifo != null) {
        val baseName = ifo.name.dropLast(".ifo".length)
        if (!hasDictionaryFiles(DictionaryType.STARDICT, directory, baseName)) return null
        val metadata = readIfoMetadata(ifo)
        return DictionaryIndexEntry(
            id = directory.name,
            type = DictionaryType.STARDICT,
            name = metadata?.first?.takeIf { it.isNotBlank() } ?: baseName,
            baseName = baseName,
            directory = "${DictionaryCatalog.IMPORTED_DIRECTORY}/${directory.name}",
            wordCount = metadata?.second ?: 0,
            sizeBytes = sizeOf(directory),
            enabled = true,
            importedAt = directory.lastModified()
        )
    }

    val mdx = files.firstOrNull { it.name.endsWith(".mdx", ignoreCase = true) } ?: return null
    val probe = DictionaryEngines.probeMdx(mdx) ?: return null
    val baseName = mdx.name.dropLast(".mdx".length)
    return DictionaryIndexEntry(
        id = directory.name,
        type = DictionaryType.MDX,
        name = probe.title.takeIf { it.isNotBlank() } ?: baseName,
        baseName = baseName,
        directory = "${DictionaryCatalog.IMPORTED_DIRECTORY}/${directory.name}",
        wordCount = probe.entryCount.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
        sizeBytes = sizeOf(directory),
        enabled = true,
        importedAt = directory.lastModified()
    )
}

/** `.ifo` 里的 `bookname` 与 `wordcount`；解析失败按「没有元数据」处理，不阻止收养。 */
internal fun readIfoMetadata(ifo: File): Pair<String, Int>? {
    val lines = try {
        ifo.readLines()
    } catch (e: IOException) {
        return null
    }
    var name = ""
    var wordCount = 0
    for (line in lines) {
        val separator = line.indexOf('=')
        if (separator <= 0) continue
        when (line.substring(0, separator).trim()) {
            "bookname" -> name = line.substring(separator + 1).trim()
            "wordcount" -> wordCount = line.substring(separator + 1).trim().toIntOrNull() ?: 0
        }
    }
    return name to wordCount
}

/**
 * 把索引里的相对路径解析成 [root] 内的绝对路径。索引可能被改坏（`../`），
 * 越界一律当作无效条目 —— 否则一次删除就会删到词典目录之外。
 */
internal fun resolve(root: File, relative: String): File? {
    val target = File(root, relative)
    return try {
        val rootPath = root.canonicalFile.path
        val targetPath = target.canonicalFile.path
        if (targetPath == rootPath || targetPath.startsWith(rootPath + File.separator)) {
            target.canonicalFile
        } else {
            null
        }
    } catch (e: IOException) {
        null
    }
}

internal fun sizeOf(directory: File): Long =
    if (!directory.exists()) 0L else directory.walkTopDown().filter { it.isFile }.sumOf { it.length() }
