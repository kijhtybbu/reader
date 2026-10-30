/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * fork 新增：内置 ECDICT 安装器的回归（第二阶段 S3）。
 *
 * 大部分用例用**真实资产** `app/src/main/assets/dictionaries/ecdict/stardict.7z` 跑：Gradle 单元测试
 * 的工作目录就是 app 模块目录，所以路径是 `src/main/assets/...`。「官方数据能解出可查询的词典」
 * 只能由真资产证明，假数据证明不了；归档层面的边角行为（后缀大小写、换目录中断恢复）则用
 * 几 KB 的合成 7z，避免每次多解一份 90MB。
 */

package ua.acclorite.book_story.data.dictionary

import com.qiuminal.juicedict.engine.DictionaryEngines
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files

class BuiltInDictionaryInstallerTest {

    private val asset = File("src/main/assets/dictionaries/ecdict/stardict.7z")

    private fun tempRoot(): File = Files.createTempDirectory("ecdict-install").toFile().also {
        it.deleteOnExit()
    }

    /** 目录布局与真机一致：`<root>/dictionaries/builtin`，空间检查看的是 `dictionaries`。 */
    private fun targetUnder(root: File): File = File(File(root, "dictionaries"), "builtin")

    private fun assertCjk(text: String) {
        assertTrue(
            "expected Chinese in the rendered article, got: ${text.take(200)}",
            text.any { it.code in 0x4E00..0x9FFF }
        )
    }

    private fun ByteArrayOutputStream.writeIntBE(value: Int) {
        write((value ushr 24) and 0xff)
        write((value ushr 16) and 0xff)
        write((value ushr 8) and 0xff)
        write(value and 0xff)
    }

    /**
     * 合成一部只有一条词的最小 StarDict，按 [names] 打进 7z（几 KB）。
     * 归档层面的行为（后缀大小写、换目录中断恢复）不必每次都解 90MB 真实资产。
     */
    private fun writeSyntheticArchive(archive: File, names: List<String>) {
        assertEquals("expected .ifo/.idx/.dict names", 3, names.size)
        val body = "<b>hello</b> 你好。".toByteArray(StandardCharsets.UTF_8)
        val idx = ByteArrayOutputStream().apply {
            write("hello".toByteArray(StandardCharsets.UTF_8))
            write(0)
            writeIntBE(0)
            writeIntBE(body.size)
        }.toByteArray()
        val ifo = buildString {
            appendLine("StarDict's dict ifo file")
            appendLine("version=3.0.0")
            appendLine("bookname=tiny")
            appendLine("wordcount=1")
            appendLine("idxfilesize=${idx.size}")
            appendLine("sametypesequence=h")
        }.toByteArray(StandardCharsets.UTF_8)

        val entries = mapOf(names[0] to ifo, names[1] to idx, names[2] to body)
        SevenZOutputFile(archive).use { out ->
            for ((name, bytes) in entries) {
                val entry = SevenZArchiveEntry()
                entry.name = name
                entry.size = bytes.size.toLong()
                out.putArchiveEntry(entry)
                out.write(bytes)
                out.closeArchiveEntry()
            }
        }
    }

    @Test
    fun installsRealEcdictAndServesLookups() {
        assertTrue("asset is missing: ${asset.absolutePath}", asset.isFile)
        val root = tempRoot()
        val target = targetUnder(root)
        val progress = ArrayList<Float>()
        try {
            installAndAssertLookup(target, progress)
        } finally {
            // 解压结果 ~120MB（含引擎写的 .jidx），测完就删
            root.deleteRecursively()
        }
    }

    private fun installAndAssertLookup(target: File, progress: MutableList<Float>) {
        val result = BuiltInDictionaryInstaller.install(asset, target, version = "1") { progress.add(it) }

        assertFalse("first install must not be skipped", result.skipped)
        assertEquals("ecdict", result.baseName)
        assertEquals(target, result.directory)
        assertTrue("expected the full ECDICT, got ${result.wordCount} words", result.wordCount > 700_000)

        for (name in listOf("ecdict.ifo", "ecdict.idx", "ecdict.dict")) {
            assertTrue("$name must be extracted", File(target, name).isFile)
        }
        val marker = File(target, BuiltInDictionaryInstaller.MARKER_FILE).readText()
        assertTrue("version marker must carry the version, got: $marker", marker.contains("version=1"))
        assertTrue(
            "version marker must carry the word count, got: $marker",
            marker.contains("wordCount=${result.wordCount}")
        )

        assertEquals("progress must end at 1.0, got $progress", 1f, progress.last())
        assertTrue("progress must stay in 0..1, got $progress", progress.all { it in 0f..1f })

        // 解压出来的目录必须能被适配层直接打开，并且真查得到词
        val engine = DictionaryEngines.openStarDict("builtin", target, result.baseName)
        assertNotNull("extracted dictionary must open", engine)
        val opened = engine ?: error("openStarDict returned null")
        try {
            assertEquals(result.wordCount, opened.wordCount)

            val hits = opened.lookupSmart("apple", 5)
            assertTrue("apple must be found, got $hits", hits.isNotEmpty())
            assertEquals("apple", hits.first().word)
            val html = opened.article(hits.first()).toHtml()
            assertTrue("article must render HTML, got: $html", html.isNotEmpty())
            assertCjk(html)
        } finally {
            opened.close()
        }
    }

    @Test
    fun secondInstallWithSameVersionIsSkippedAndKeepsFiles() {
        assertTrue("asset is missing: ${asset.absolutePath}", asset.isFile)
        val root = tempRoot()
        val target = targetUnder(root)
        try {
            val first = BuiltInDictionaryInstaller.install(asset, target, version = "1") {}
            assertFalse(first.skipped)

            val files = listOf("ecdict.ifo", "ecdict.idx", "ecdict.dict", BuiltInDictionaryInstaller.MARKER_FILE)
            val before = files.associateWith { File(target, it).lastModified() }
            // mtime 只有秒级以上的分辨率也不影响：第二次安装根本不写文件
            Thread.sleep(20)

            val second = BuiltInDictionaryInstaller.install(asset, target, version = "1") {}
            assertTrue("same version must be skipped", second.skipped)
            assertEquals(first.wordCount, second.wordCount)
            assertEquals(first.baseName, second.baseName)
            assertEquals(first.directory, second.directory)
            assertEquals(
                "skipped install must not touch the files",
                before,
                files.associateWith { File(target, it).lastModified() }
            )

            // 版本变化必须重装（换资产时靠它触发）
            val third = BuiltInDictionaryInstaller.install(asset, target, version = "2") {}
            assertFalse("a new version must reinstall", third.skipped)
            assertTrue(
                File(target, BuiltInDictionaryInstaller.MARKER_FILE).readText().contains("version=2")
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun corruptArchiveThrowsAndLeavesNothingBehind() {
        val root = tempRoot()
        val target = targetUnder(root)
        val corrupt = File(root, "corrupt.7z")
        try {
            // 随机字节：不是 7z 签名，SevenZFile 打开就失败
            val random = ByteArray(64 * 1024)
            java.util.Random(42).nextBytes(random)
            corrupt.writeBytes(random)

            val thrown = runCatching {
                BuiltInDictionaryInstaller.install(corrupt, target, version = "1") {}
            }.exceptionOrNull()

            assertTrue(
                "expected DictionaryInstallException, got $thrown",
                thrown is DictionaryInstallException
            )
            assertFalse("a failed install must not leave the target behind", target.exists())
            assertFalse(
                "a failed install must not leave the temp directory behind",
                File(target.parentFile, "${target.name}.tmp").exists()
            )
            assertFalse(
                "a failed install must not leave the old directory behind",
                File(target.parentFile, "${target.name}.old").exists()
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun mixedCaseArchiveSuffixesAreNormalizedToLowercase() {
        val root = tempRoot()
        val target = targetUnder(root)
        val archive = File(root, "mixed.7z")
        try {
            writeSyntheticArchive(archive, listOf("tiny.IFO", "tiny.Idx", "tiny.DICT"))

            val result = BuiltInDictionaryInstaller.install(archive, target, version = "1") {}

            assertFalse(result.skipped)
            assertEquals("tiny", result.baseName)
            assertEquals(1, result.wordCount)
            // 计划阶段按 ignoreCase 匹配，落盘必须是引擎认的小写后缀
            for (name in listOf("tiny.ifo", "tiny.idx", "tiny.dict")) {
                assertTrue("$name must be extracted with a lowercase suffix", File(target, name).isFile)
            }
            assertTrue(
                "the extracted dictionary must open",
                DictionaryEngines.openStarDict("builtin", target, result.baseName) != null
            )
        } finally {
            root.deleteRecursively()
        }
    }

    /**
     * 进程死在 `swap` 的两次 rename 之间：好词典在 `<target>.old`、`<target>` 不存在。
     * 下次启动必须把它搬回来并直接跳过安装（不重解、mtime 不变）。
     */
    @Test
    fun interruptedSwapIsRecoveredFromOldDirectory() {
        val root = tempRoot()
        val target = targetUnder(root)
        val archive = File(root, "tiny.7z")
        try {
            writeSyntheticArchive(archive, listOf("tiny.ifo", "tiny.idx", "tiny.dict"))
            val first = BuiltInDictionaryInstaller.install(archive, target, version = "1") {}
            assertFalse(first.skipped)

            val previous = File(target.parentFile, "${target.name}.old")
            assertTrue("could not simulate the interrupted swap", target.renameTo(previous))
            val idxMtime = File(previous, "tiny.idx").lastModified()

            val recovered = BuiltInDictionaryInstaller.install(archive, target, version = "1") {}

            assertTrue("a recovered dictionary must be reused, not re-extracted", recovered.skipped)
            assertEquals(first.wordCount, recovered.wordCount)
            assertTrue("the recovered directory must be back at the target", target.isDirectory)
            assertFalse("the .old directory must be gone after recovery", previous.exists())
            assertEquals(
                "recovery must not rewrite the dictionary",
                idxMtime,
                File(target, "tiny.idx").lastModified()
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun failedInstallKeepsThePreviousDictionary() {
        assertTrue("asset is missing: ${asset.absolutePath}", asset.isFile)
        val root = tempRoot()
        val target = targetUnder(root)
        val corrupt = File(root, "corrupt.7z")
        try {
            val first = BuiltInDictionaryInstaller.install(asset, target, version = "1") {}
            assertFalse(first.skipped)
            val files = listOf("ecdict.ifo", "ecdict.idx", "ecdict.dict")
            val before = files.associateWith { File(target, it).lastModified() }

            corrupt.writeBytes(ByteArray(1024) { 0x5A })
            val thrown = runCatching {
                BuiltInDictionaryInstaller.install(corrupt, target, version = "2") {}
            }.exceptionOrNull()
            assertTrue("expected DictionaryInstallException, got $thrown", thrown is DictionaryInstallException)

            // 新版本装失败：旧版本必须原样可用，标记也还是旧的
            assertEquals(
                "a failed install must keep the previous dictionary",
                before,
                files.associateWith { File(target, it).lastModified() }
            )
            assertTrue(
                "the marker must still describe the old version",
                File(target, BuiltInDictionaryInstaller.MARKER_FILE).readText().contains("version=1")
            )
            assertFalse(
                "a failed install must not leave the temp directory behind",
                File(target.parentFile, "${target.name}.tmp").exists()
            )
        } finally {
            root.deleteRecursively()
        }
    }
}
