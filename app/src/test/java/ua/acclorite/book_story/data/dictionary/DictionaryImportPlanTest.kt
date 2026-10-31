/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * fork 新增：MDX 目录导入计划的回归（设计 §3.4）。
 *
 * `planMdxFolder` 是导入路径上唯一的「配对与限额」判断，而且它错一条的后果全是静默的：装进去
 * 一部缺 `.1.mdd` 的词典不会报错，只会在某个词条上缺图/缺音。所以这里把真机语料会遇到的形状
 * 全钉一遍：只有 mdx、mdx+mdd、多分卷、缺号、只有分卷没有主包、`.images.mdd`、0/多个 mdx、
 * 单文件超限、总量超限，以及长度接近真实牛津高阶 9（1.74 GiB）的那一组。
 *
 * 纯函数、无 `android.*`：不需要 Context/Robolectric。
 */

package ua.acclorite.book_story.data.dictionary

import com.qiuminal.juicedict.engine.DictionaryEngines
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

private const val MIB = 1024L * 1024

class DictionaryImportPlanTest {

    private fun plan(vararg files: Pair<String, Long>) = planMdxFolder(files.toList())

    private fun ready(plan: MdxFolderPlan): MdxFolderPlan.Ready {
        assertTrue("expected a ready plan, got $plan", plan is MdxFolderPlan.Ready)
        return plan as MdxFolderPlan.Ready
    }

    private fun error(plan: MdxFolderPlan): DictionaryImportError {
        assertTrue("expected a rejection, got $plan", plan is MdxFolderPlan.Rejection)
        return (plan as MdxFolderPlan.Rejection).error
    }

    @Test
    fun `an mdx without resource packs is enough`() {
        val result = ready(plan("dict.mdx" to 10 * MIB))

        assertEquals("dict", result.base)
        assertEquals("dict.mdx", result.mdx)
        assertTrue(result.mdds.isEmpty())
        assertEquals(10 * MIB, result.totalBytes)
    }

    @Test
    fun `the base mdd is picked up next to the mdx`() {
        val result = ready(plan("dict.mdx" to 10 * MIB, "dict.mdd" to 20 * MIB))

        assertEquals(listOf(MddSource("dict.mdd", null, 20 * MIB)), result.mdds)
        assertEquals(30 * MIB, result.totalBytes)
    }

    @Test
    fun `numbered packs follow the base pack in numeric order`() {
        val result = ready(
            plan(
                "dict.2.mdd" to 3 * MIB,
                "dict.mdd" to 1 * MIB,
                "dict.mdx" to 1 * MIB,
                "dict.1.mdd" to 2 * MIB,
            )
        )

        assertEquals(
            listOf("dict.mdd" to null, "dict.1.mdd" to 1, "dict.2.mdd" to 2),
            result.mdds.map { it.name to it.ordinal }
        )
        assertEquals(7 * MIB, result.totalBytes)
    }

    @Test
    fun `a gap in the numbering rejects the whole dictionary`() {
        // 有 .1.mdd 和 .3.mdd、没有 .2.mdd：装上就会静默丢一段图片。
        assertEquals(
            DictionaryImportError.RESOURCE_PARTS_MISSING,
            error(plan("dict.mdx" to 1 * MIB, "dict.mdd" to 1 * MIB, "dict.1.mdd" to 1 * MIB, "dict.3.mdd" to 1 * MIB))
        )
        // 从 2 开始同样缺 1。
        assertEquals(
            DictionaryImportError.RESOURCE_PARTS_MISSING,
            error(plan("dict.mdx" to 1 * MIB, "dict.mdd" to 1 * MIB, "dict.2.mdd" to 1 * MIB))
        )
    }

    @Test
    fun `numbered packs without the base pack are rejected`() {
        assertEquals(
            DictionaryImportError.RESOURCE_PARTS_MISSING,
            error(plan("dict.mdx" to 1 * MIB, "dict.1.mdd" to 1 * MIB))
        )
    }

    @Test
    fun `non numeric mdd suffixes are ignored`() {
        // 引擎的 `MdxResourceFiles.pairsFor` 只认纯数字编号，`.images.mdd` 它永远读不到；
        // 导入时把它算进总量或当成缺号，都会让「能用的词典」导不进来。
        val result = ready(plan("dict.mdx" to 1 * MIB, "dict.images.mdd" to 50 * MIB))

        assertTrue(result.mdds.isEmpty())
        assertEquals(1 * MIB, result.totalBytes)
    }

    @Test
    fun `unrelated files are ignored`() {
        val result = ready(
            plan(
                "oalecd9.css" to 24L * 1024,
                "readme.txt" to 100,
                "dict.mdx" to 1 * MIB,
                "dict.mdd" to 1 * MIB,
            )
        )

        assertEquals(listOf("dict.mdd"), result.mdds.map { it.name })
        assertEquals(2 * MIB, result.totalBytes)
    }

    @Test
    fun `a folder without an mdx is incomplete`() {
        assertEquals(
            DictionaryImportError.INCOMPLETE,
            error(plan("dict.mdd" to 1 * MIB, "notes.txt" to 10))
        )
        assertEquals(DictionaryImportError.INCOMPLETE, error(plan()))
    }

    @Test
    fun `two dictionaries in one folder cannot be imported`() {
        assertEquals(
            DictionaryImportError.MULTIPLE_DICTIONARIES,
            error(plan("a.mdx" to 1 * MIB, "b.mdx" to 1 * MIB))
        )
    }

    @Test
    fun `extensions are matched case insensitively`() {
        val result = ready(plan("DICT.MDX" to 1 * MIB, "DICT.MDD" to 1 * MIB, "DICT.1.MDD" to 1 * MIB))

        assertEquals("DICT", result.base)
        assertEquals("DICT.MDX", result.mdx)
        assertEquals(
            listOf("DICT.MDD" to null, "DICT.1.MDD" to 1),
            result.mdds.map { it.name to it.ordinal }
        )
    }

    @Test
    fun `an mdx over the single file limit is too large`() {
        assertEquals(
            DictionaryImportError.TOO_LARGE,
            error(plan("dict.mdx" to DictionaryImporter.MAX_IMPORT_BYTES + 1))
        )
    }

    @Test
    fun `resource packs are capped by the total limit not the single file limit`() {
        // 512 MB 只能管 .mdx：真实分卷本来就比它大（OALD9 的 .1.mdd 是 518 MB）。
        val result = ready(
            plan(
                "dict.mdx" to 400 * MIB,
                "dict.mdd" to 600 * MIB,
                "dict.1.mdd" to 700 * MIB,
            )
        )
        assertEquals(1700 * MIB, result.totalBytes)
    }

    @Test
    fun `the total over two gibibytes is too large`() {
        assertEquals(
            DictionaryImportError.TOO_LARGE,
            error(
                plan(
                    "dict.mdx" to 100 * MIB,
                    "dict.mdd" to 1024 * MIB,
                    "dict.1.mdd" to 1024 * MIB,
                )
            )
        )
    }

    /**
     * 真实牛津高阶 9（第 9 版）的目录：54 MB mdx + 154 MB .mdd + 518 MB .1.mdd + 1.14 GB .2.mdd，
     * 合计 1,864,845,709 字节（≈1.74 GiB）。设计文档把 OALD9 列进真机矩阵，所以这一组必须能导。
     */
    @Test
    fun `the real OALD9 layout fits under the total limit`() {
        val result = ready(
            plan(
                "牛津高阶英汉双解词典(第9版)_V2.0.mdx" to 54_005_743L,
                "牛津高阶英汉双解词典(第9版)_V2.0.mdd" to 153_904_036L,
                "牛津高阶英汉双解词典(第9版)_V2.0.1.mdd" to 518_002_352L,
                "牛津高阶英汉双解词典(第9版)_V2.0.2.mdd" to 1_138_933_578L,
                // 同目录还放着一个词典自带的 CSS：它不在导入范围内，也不能影响体积判断。
                "oalecd9.css" to 24_498L,
            )
        )

        assertEquals("牛津高阶英汉双解词典(第9版)_V2.0", result.base)
        assertEquals(3, result.mdds.size)
        assertEquals(1_864_845_709L, result.totalBytes)
        assertTrue(
            "the OALD9 total must stay under the 2 GiB cap",
            result.totalBytes < DictionaryImporter.MAX_IMPORT_TOTAL_BYTES
        )
    }

    @Test
    fun `staging file names are what the engine pairs on`() {
        // 引擎按 `<base>.mdd` / `<base>.N.mdd` 精确配对，所以目标名要统一小写后缀、编号去掉前导零。
        assertEquals("dict.mdd", mddFileName("dict", MddSource("DICT.MDD", null, 0)))
        assertEquals("dict.1.mdd", mddFileName("dict", MddSource("DICT.01.MDD", 1, 0)))
        assertEquals("漢字", sanitizeBaseName("漢字"))
        assertEquals(
            "牛津高阶英汉双解词典_第9版__V2.0",
            sanitizeBaseName("牛津高阶英汉双解词典(第9版)_V2.0")
        )
    }

    @Test
    fun `the staging names are the ones the engine actually pairs`() {
        // 上面只断言了字符串。真正的不变量是「导入落盘的名字，引擎能按顺序配对出来」——
        // 这是「导入成功但读不到资源」的唯一防线，所以在这里跑一次真实的 pairsFor。
        val dir = Files.createTempDirectory("dict-pairing").toFile()
        try {
            val plan = planMdxFolder(
                listOf(
                    "Dict.MDX" to 10L,
                    "Dict.MDD" to 20L,
                    "Dict.01.mdd" to 30L,
                    "Dict.2.MDD" to 40L
                )
            ) as MdxFolderPlan.Ready
            val base = sanitizeBaseName(plan.base)
            File(dir, "$base.mdx").createNewFile()
            plan.mdds.forEach { part -> File(dir, mddFileName(base, part)).createNewFile() }

            assertEquals(
                listOf("$base.mdd", "$base.1.mdd", "$base.2.mdd"),
                DictionaryEngines.mddFilesFor(dir, base).map { it.name }
            )
        } finally {
            dir.deleteRecursively()
        }
    }
}
