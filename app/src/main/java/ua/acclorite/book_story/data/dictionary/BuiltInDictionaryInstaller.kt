/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * fork 新增：内置 ECDICT 的解压安装器（第二阶段 S3）。
 *
 * 纯 JVM：不引用任何 `android.*`，所以「解压 → 校验 → 原子换目录」整条路径能在
 * app 模块的 JVM 单测里用真实资产跑一遍，而不是只在真机上靠肉眼确认。
 *
 * 调用方（`DictionaryRegistry`）负责：把资产 7z 拷到可读位置、删掉这份拷贝、
 * 缓存打开后的引擎实例。这里只管目录。
 */

package ua.acclorite.book_story.data.dictionary

import com.qiuminal.juicedict.engine.DictionaryEngines
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * 安装结果。[baseName] 是 StarDict 文件名主干（`ecdict` → `ecdict.ifo` / `.idx` / `.dict`），
 * 引擎按它拼文件名，所以必须跟着结果一起交给调用方。
 */
data class InstallResult(
    val baseName: String,
    val wordCount: Int,
    val directory: File,
    /** 版本标记命中且文件齐全，什么都没做。 */
    val skipped: Boolean,
)

/** 安装失败（资产损坏、空间不足、解压结果打不开词典）。消息可直接给用户看。 */
class DictionaryInstallException(message: String, cause: Throwable? = null) :
    Exception(message, cause)

object BuiltInDictionaryInstaller {

    /** 版本标记文件名，与词典文件同目录；内容见 [writeMarker]。 */
    const val MARKER_FILE = "version"

    /**
     * 从 7z 里取的文件类型。ECDICT 的 `.dict` 是明文，dictzip 版本会带 `.dict.dz`；
     * `.syn` 是可选别名表。其余条目（README、图片）一律不落盘。
     */
    private val DICTIONARY_SUFFIXES = listOf(".dict.dz", ".ifo", ".idx", ".dict", ".syn")

    private const val BUFFER_BYTES = 256 * 1024

    /**
     * 解压后引擎还会在同一目录写 `<base>.jidx` 索引缓存（实测约为解压量的 40%），
     * 换目录期间旧版本目录也还占着地方，所以按解压量的 1.5 倍加旧目录大小检查剩余空间。
     */
    private const val SPACE_NUMERATOR = 3L
    private const val SPACE_DENOMINATOR = 2L

    /**
     * 解压 [sevenZ] 到 [targetDir]，成功后 [targetDir] 里就是一部可直接交给
     * [DictionaryEngines.openStarDict] 的 StarDict，外加一个 [MARKER_FILE] 版本标记。
     *
     * [version] 与标记一致且文件齐全时直接返回 `skipped = true`，不碰磁盘上的文件
     * （mtime 不变）。换资产时必须由调用方递增版本号。
     *
     * 上次进程死在换目录中间（`<target>.old` 在、`<target>` 不在）时，先把旧目录搬回来再
     * 判断标记，所以恢复后通常直接 `skipped = true`。
     *
     * [onProgress] 在解压阶段按字节比例报 0..0.9，校验通过、目录就位后报 1.0；
     * 解压失败不会报 1.0。
     *
     * 失败（包括 `Error`）时临时目录被删掉，旧目录原样保留（先 rename 到一边，成功后才删）。
     * [sevenZ] 不会被删除 —— 它属于调用方。
     */
    fun install(
        sevenZ: File,
        targetDir: File,
        version: String,
        onProgress: (Float) -> Unit,
    ): InstallResult {
        recoverInterruptedSwap(targetDir)
        readMarker(targetDir)?.let { marker ->
            if (marker.version == version && hasDictionaryFiles(targetDir, marker.baseName)) {
                onProgress(1f)
                return InstallResult(
                    baseName = marker.baseName,
                    wordCount = marker.wordCount,
                    directory = targetDir,
                    skipped = true,
                )
            }
        }

        if (!sevenZ.isFile) {
            throw DictionaryInstallException("Dictionary archive is missing: ${sevenZ.absolutePath}")
        }

        val parent = targetDir.parentFile
            ?: throw DictionaryInstallException("Dictionary target has no parent: ${targetDir.absolutePath}")
        if (!parent.exists() && !parent.mkdirs()) {
            throw DictionaryInstallException("Could not create ${parent.absolutePath}")
        }

        val plan = planArchive(sevenZ)
        // `.old` 也要算：上一次进程死在换目录中间时，好词典还占着盘，直到这次装完才会删。
        val required = plan.totalBytes * SPACE_NUMERATOR / SPACE_DENOMINATOR +
            sizeOf(targetDir) + sizeOf(oldDirOf(targetDir))
        val available = parent.usableSpace
        if (available < required) {
            throw DictionaryInstallException(
                "Not enough space for the built-in dictionary: " +
                    "needs $required bytes, $available available"
            )
        }

        val tempDir = File(parent, "${targetDir.name}.tmp")
        tempDir.deleteRecursively()
        if (!tempDir.mkdirs()) {
            throw DictionaryInstallException("Could not create ${tempDir.absolutePath}")
        }

        try {
            extract(sevenZ, plan, tempDir, onProgress)

            if (!hasDictionaryFiles(tempDir, plan.baseName)) {
                throw DictionaryInstallException(
                    "Extracted dictionary ${plan.baseName} is incomplete: " +
                        "expected .ifo, .idx and .dict or .dict.dz"
                )
            }

            val engine = DictionaryEngines.openStarDict(ENGINE_ID, tempDir, plan.baseName)
                ?: throw DictionaryInstallException(
                    "Extracted dictionary ${plan.baseName} could not be opened"
                )
            val wordCount = try {
                engine.wordCount
            } finally {
                engine.close()
            }
            if (wordCount == 0) {
                throw DictionaryInstallException("Extracted dictionary ${plan.baseName} has no entries")
            }

            writeMarker(tempDir, Marker(version, plan.baseName, wordCount))
            swap(targetDir, tempDir)
            onProgress(1f)
            return InstallResult(
                baseName = plan.baseName,
                wordCount = wordCount,
                directory = targetDir,
                skipped = false,
            )
        } catch (t: Throwable) {
            // Error（OOM 等）同样要先清临时目录：否则约 120MB 会一直留到下一次安装才被覆盖。
            // 原样重抛 Error，其余按安装失败包装。
            tempDir.deleteRecursively()
            if (t is Error) throw t
            throw t as? DictionaryInstallException
                ?: DictionaryInstallException("Could not install the built-in dictionary: ${t.message}", t)
        }
    }

    // ---- 版本标记 ------------------------------------------------------------

    private data class Marker(val version: String, val baseName: String, val wordCount: Int)

    /**
     * 标记是几行 `key=value`：版本（决定要不要重装）、文件主干（决定查哪些文件）、
     * 词条数（跳过安装时不用再开一次词典就知道数量）。解析失败一律当作没有标记 ——
     * 重装一遍的代价远小于信任一份读不懂的标记。
     */
    private fun readMarker(dir: File): Marker? {
        val file = File(dir, MARKER_FILE)
        if (!file.isFile) return null
        val values = try {
            file.readLines().mapNotNull { line ->
                val separator = line.indexOf('=')
                if (separator <= 0) null
                else line.substring(0, separator).trim() to line.substring(separator + 1).trim()
            }.toMap()
        } catch (e: IOException) {
            return null
        }
        val baseName = values["baseName"]?.takeIf { it.isNotEmpty() } ?: return null
        val wordCount = values["wordCount"]?.toIntOrNull() ?: return null
        val version = values["version"] ?: return null
        return Marker(version, baseName, wordCount)
    }

    private fun writeMarker(dir: File, marker: Marker) {
        File(dir, MARKER_FILE).writeText(
            buildString {
                appendLine("version=${marker.version}")
                appendLine("baseName=${marker.baseName}")
                appendLine("wordCount=${marker.wordCount}")
            }
        )
    }

    private fun hasDictionaryFiles(dir: File, baseName: String): Boolean =
        File(dir, "$baseName.ifo").isFile &&
            File(dir, "$baseName.idx").isFile &&
            (File(dir, "$baseName.dict").isFile || File(dir, "$baseName.dict.dz").isFile)

    // ---- 解压 ----------------------------------------------------------------

    /** 一个待解压条目：归档里的主干、解压后的文件名（后缀小写）、解压后的字节数。 */
    private data class Candidate(val stem: String, val outputName: String, val size: Long)

    /**
     * 要解压的条目（7z 里的原始名字 → 解压后的文件名）与总字节数，用于进度与空间检查。
     * 输出名统一成小写 StarDict 后缀：归档里写成 `Tiny.IFO` 的条目也必须落成 `tiny.ifo`，
     * 否则计划阶段（按 `ignoreCase` 匹配）会通过，校验阶段（[hasDictionaryFiles] 只认小写）
     * 却报「解压结果不完整」，把大小写问题伪装成坏资产。
     */
    private data class Plan(val baseName: String, val entries: Map<String, String>, val totalBytes: Long)

    /**
     * 只读归档头（不解压内容，实测 20MB 的 ECDICT 只要几十毫秒）就能拿到条目名与大小：
     * 空间检查必须在写任何字节之前做完。
     */
    private fun planArchive(sevenZ: File): Plan {
        val candidates = LinkedHashMap<String, Candidate>() // 条目名 -> 候选
        var baseName: String? = null
        try {
            SevenZFile.builder().setFile(sevenZ).get().use { archive ->
                while (true) {
                    val entry = archive.nextEntry ?: break
                    if (entry.isDirectory) continue
                    val name = entry.name.substringAfterLast('/')
                    val suffix = DICTIONARY_SUFFIXES.firstOrNull { name.endsWith(it, ignoreCase = true) }
                        ?: continue
                    val stem = name.dropLast(suffix.length)
                    if (stem.isEmpty()) continue
                    if (suffix.equals(".ifo", ignoreCase = true)) {
                        if (baseName != null && baseName != stem) {
                            throw DictionaryInstallException(
                                "Dictionary archive holds more than one StarDict: $baseName, $stem"
                            )
                        }
                        baseName = stem
                    }
                    candidates[entry.name] = Candidate(
                        stem = stem,
                        outputName = stem + suffix.lowercase(),
                        size = entry.size.coerceAtLeast(0L),
                    )
                }
            }
        } catch (e: IOException) {
            throw DictionaryInstallException("Could not read the dictionary archive: ${e.message}", e)
        }

        val base = baseName
            ?: throw DictionaryInstallException("Dictionary archive contains no StarDict .ifo")
        val entries = candidates.filterValues { it.stem == base }.mapValues { it.value.outputName }
        val hasIndex = entries.values.any { it == "$base.idx" }
        val hasData = entries.values.any { it == "$base.dict" || it == "$base.dict.dz" }
        if (!hasIndex || !hasData) {
            throw DictionaryInstallException("Dictionary archive is missing $base.idx or $base.dict")
        }
        return Plan(
            baseName = base,
            entries = entries,
            totalBytes = candidates.values.filter { it.stem == base }.sumOf { it.size },
        )
    }

    private fun extract(sevenZ: File, plan: Plan, dir: File, onProgress: (Float) -> Unit) {
        var written = 0L
        var reported = -1f
        onProgress(0f)
        try {
            SevenZFile.builder().setFile(sevenZ).get().use { archive ->
                while (true) {
                    val entry = archive.nextEntry ?: break
                    if (entry.isDirectory) continue
                    val outputName = plan.entries[entry.name] ?: continue

                    val target = File(dir, outputName)
                    BufferedOutputStream(FileOutputStream(target), BUFFER_BYTES).use { output ->
                        val buffer = ByteArray(BUFFER_BYTES)
                        while (true) {
                            val read = archive.read(buffer, 0, buffer.size)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            written += read
                            // 0.9 封顶：剩下的 0.1 留给打开词典（要建索引，不是瞬间完成）
                            val fraction = if (plan.totalBytes > 0) {
                                (written.toFloat() / plan.totalBytes * EXTRACT_PROGRESS).coerceAtMost(EXTRACT_PROGRESS)
                            } else {
                                EXTRACT_PROGRESS
                            }
                            if (fraction - reported >= 0.01f) {
                                reported = fraction
                                onProgress(fraction)
                            }
                        }
                    }
                }
            }
        } catch (e: IOException) {
            throw DictionaryInstallException("Could not extract the dictionary archive: ${e.message}", e)
        }
    }

    // ---- 原子换目录 ----------------------------------------------------------

    private fun oldDirOf(targetDir: File): File {
        val parent = targetDir.parentFile
            ?: throw DictionaryInstallException("Dictionary target has no parent: ${targetDir.absolutePath}")
        return File(parent, "${targetDir.name}.old")
    }

    /**
     * 进程死在 [swap] 的两步 rename 之间：好词典在 `<target>.old`，`<target>` 不存在。
     * 先把它搬回去 —— 标记与文件都齐时 [install] 会直接跳过，完全不用重装；
     * 搬不回去也不报错，最坏情况是重装一遍，体积已由空间检查算进 `.old`。
     */
    private fun recoverInterruptedSwap(targetDir: File) {
        if (targetDir.exists()) return
        val previous = oldDirOf(targetDir)
        if (!previous.isDirectory) return
        val marker = readMarker(previous) ?: return
        if (!hasDictionaryFiles(previous, marker.baseName)) return
        previous.renameTo(targetDir)
    }

    /**
     * 旧目录先 rename 到 `<target>.old`，新目录再 rename 到 `<target>`：同一文件系统内
     * 的 rename 是原子的，所以任何一步失败都不会出现「半个词典」。失败时把旧目录搬回去。
     */
    private fun swap(targetDir: File, tempDir: File) {
        val previous = oldDirOf(targetDir)
        previous.deleteRecursively()

        val hadPrevious = targetDir.exists()
        if (hadPrevious && !targetDir.renameTo(previous)) {
            throw DictionaryInstallException(
                "Could not move the previous dictionary aside: ${targetDir.absolutePath}"
            )
        }
        if (!tempDir.renameTo(targetDir)) {
            if (hadPrevious && !previous.renameTo(targetDir)) {
                throw DictionaryInstallException(
                    "Could not move the new dictionary into place: ${targetDir.absolutePath}; " +
                        "the previous one is at ${previous.absolutePath}"
                )
            }
            throw DictionaryInstallException(
                "Could not move the new dictionary into place: ${targetDir.absolutePath}"
            )
        }
        previous.deleteRecursively()
    }

    private fun sizeOf(dir: File): Long =
        if (!dir.exists()) 0L else dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    private const val ENGINE_ID = "builtin"

    /** 解压阶段占进度的比例，剩下的留给校验（打开词典、建索引）。 */
    private const val EXTRACT_PROGRESS = 0.9f
}
