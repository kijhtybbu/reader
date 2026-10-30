/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * fork 新增：词典目录与磁盘对齐的回归（第二阶段 S4）。
 *
 * 四条不变量：
 *  - `.tmp` / `.old` / `.staging` 这类中间态目录永远不能被当成一部词典收养（S3 遗留）；
 *  - 索引里指向已删除文件的条目要丢掉，否则设置页会列出一部打不开的词典；
 *  - 目录在、索引里没有的词典要收养回来（原子 rename 与写索引之间进程被杀）；
 *  - 保留 id（`builtin`）与重复 id 的条目要丢掉，否则 Compose 的 `key = { it.id }` 会抛异常。
 * 另外钉住相对路径不能逃出词典目录（索引被改坏时删除操作会删到外面去），以及启动时清掉
 * 上次进程留下的 `.staging/` 半份复制。
 */

package ua.acclorite.book_story.data.dictionary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files

class DictionaryCatalogTest {

    /**
     * 临时目录必须在 `finally` 里递归删掉：`deleteOnExit()` 对非空目录不生效，
     * 每跑一次测试就会往 `/tmp` 里漏一套 fixture。
     */
    private fun withTempDictionariesDir(block: (File) -> Unit) {
        val dir = Files.createTempDirectory("dictionary-catalog").toFile()
        try {
            block(dir)
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun writeIntBE(out: ByteArrayOutputStream, value: Int) {
        out.write((value ushr 24) and 0xff)
        out.write((value ushr 16) and 0xff)
        out.write((value ushr 8) and 0xff)
        out.write(value and 0xff)
    }

    /** 在 [dir] 里写一部最小 StarDict：`<base>.ifo` / `.idx` / `.dict`。 */
    private fun writeStarDict(dir: File, base: String, entries: List<Pair<String, String>>) {
        assertTrue(dir.mkdirs() || dir.isDirectory)

        val dict = ByteArrayOutputStream()
        val idx = ByteArrayOutputStream()
        for ((word, body) in entries) {
            val bytes = body.toByteArray(StandardCharsets.UTF_8)
            idx.write(word.toByteArray(StandardCharsets.UTF_8))
            idx.write(0)
            writeIntBE(idx, dict.size())
            writeIntBE(idx, bytes.size)
            dict.write(bytes)
        }
        File(dir, "$base.dict").writeBytes(dict.toByteArray())
        File(dir, "$base.idx").writeBytes(idx.toByteArray())
        File(dir, "$base.ifo").writeText(
            buildString {
                appendLine("StarDict's dict ifo file")
                appendLine("version=3.0.0")
                appendLine("bookname=$base")
                appendLine("wordcount=${entries.size}")
                appendLine("idxfilesize=${idx.size()}")
                appendLine("sametypesequence=h")
            }
        )
    }

    private fun entry(id: String, directory: String, baseName: String) = DictionaryIndexEntry(
        id = id,
        type = DictionaryType.STARDICT,
        name = baseName,
        baseName = baseName,
        directory = directory,
        wordCount = 1,
        sizeBytes = 42,
        enabled = true,
        importedAt = 1L
    )

    @Test
    fun `transient directories are never adopted`() = withTempDictionariesDir { root ->
        val imported = File(root, DictionaryCatalog.IMPORTED_DIRECTORY)
        writeStarDict(File(imported, "good"), "tiny", listOf("hello" to "<b>hello</b>"))
        writeStarDict(File(imported, "good.tmp"), "tiny", listOf("hello" to "<b>hello</b>"))
        writeStarDict(File(imported, "good.old"), "tiny", listOf("hello" to "<b>hello</b>"))
        writeStarDict(File(imported, ".staging"), "tiny", listOf("hello" to "<b>hello</b>"))

        val entries = reconcileIndex(root, emptyList())

        assertEquals(listOf("good"), entries.map { it.id })
        assertEquals(DictionaryType.STARDICT, entries.single().type)
        assertEquals("tiny", entries.single().baseName)
        assertEquals(1, entries.single().wordCount)
        assertEquals("imported/good", entries.single().directory)
        assertTrue("adopted entry must stay enabled", entries.single().enabled)
    }

    @Test
    fun `entries whose files disappeared are dropped`() = withTempDictionariesDir { root ->
        assertTrue(reconcileIndex(root, listOf(entry("gone", "imported/gone", "gone"))).isEmpty())
    }

    @Test
    fun `indexed entries are kept when their files are present`() = withTempDictionariesDir { root ->
        writeStarDict(
            File(File(root, DictionaryCatalog.IMPORTED_DIRECTORY), "kept"),
            "kept",
            listOf("hello" to "<b>hello</b>")
        )
        val kept = entry("kept", "imported/kept", "kept").copy(
            name = "Kept dictionary",
            sizeBytes = 42,
            enabled = false,
            importedAt = 7L
        )

        assertEquals(listOf(kept), reconcileIndex(root, listOf(kept)))
    }

    @Test
    fun `builtin and duplicate ids are dropped while reconciling`() = withTempDictionariesDir { root ->
        writeStarDict(
            File(File(root, DictionaryCatalog.IMPORTED_DIRECTORY), "dup"),
            "dup",
            listOf("hello" to "<b>hello</b>")
        )

        val entries = reconcileIndex(
            root,
            listOf(
                entry("dup", "imported/dup", "dup"),
                entry("dup", "imported/dup", "dup"),
                entry(DictionaryCatalog.BUILT_IN_ID, "imported/dup", "dup")
            )
        )

        assertEquals(listOf("dup"), entries.map { it.id })
    }

    @Test
    fun `a directory literally named builtin is never adopted`() = withTempDictionariesDir { root ->
        // 进程被杀/手工改坏都可能留下 `imported/builtin/`：收养出来的 id 会和内置条目撞车，
        // Compose 的 key = { it.id } 直接抛异常，仓库也会把它当内置词典去查。
        writeStarDict(
            File(File(root, DictionaryCatalog.IMPORTED_DIRECTORY), DictionaryCatalog.BUILT_IN_ID),
            "builtin",
            listOf("hello" to "<b>hello</b>")
        )

        assertTrue(reconcileIndex(root, emptyList()).isEmpty())
    }

    @Test
    fun `staging leftovers are swept and imported files are kept`() =
        withTempDictionariesDir { root ->
            val leftover = File(root, "${DictionaryCatalog.STAGING_DIRECTORY}/abandoned")
            writeStarDict(leftover, "half", listOf("hello" to "<b>hello</b>"))
            val kept = File(File(root, DictionaryCatalog.IMPORTED_DIRECTORY), "kept")
            writeStarDict(kept, "kept", listOf("hello" to "<b>hello</b>"))

            sweepStaging(root)

            assertFalse("the half-copied staging directory must be deleted", leftover.exists())
            assertFalse(File(root, DictionaryCatalog.STAGING_DIRECTORY).exists())
            assertTrue(File(kept, "kept.ifo").isFile)
        }

    @Test
    fun `relative paths cannot escape the dictionaries directory`() =
        withTempDictionariesDir { root ->
            val outside = File(root.parentFile, "outside-${root.name}")
            assertNull(resolve(root, "../${outside.name}"))
            assertEquals(File(root, "imported/x").canonicalFile, resolve(root, "imported/x"))
        }
}
