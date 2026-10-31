/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * fork 新增：导入错误分类与文件名净化的纯函数回归（第二阶段 S4）。
 *
 * 引擎模块不依赖 Android 资源，MDX 打开失败只能拿到上游 `require`/`error` 的英文消息；
 * 这里钉住「消息 → 错误码」的映射，避免以后升级引擎改了措辞却把加密词典报成「文件损坏」。
 */

package ua.acclorite.book_story.data.dictionary

import com.qiuminal.juicedict.engine.MdxPackCheck
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DictionaryImportErrorTest {

    @Test
    fun `resource packs are not dictionaries`() {
        assertEquals(
            DictionaryImportError.RESOURCE_PACK,
            classifyMdxFailure("res.mdx is a resource pack, not a dictionary")
        )
    }

    @Test
    fun `password protected files are reported as encrypted`() {
        assertEquals(
            DictionaryImportError.ENCRYPTED,
            classifyMdxFailure("password-protected MDict files are not supported")
        )
        // 加密先于「资源包」判定：加密的 `.mdd` 消息里同时也含 "resource pack"，
        // 归错类会把用户指向「换一个文件」而不是「换一部没加密的词典」。
        assertEquals(
            DictionaryImportError.ENCRYPTED,
            classifyMdxFailure("password-protected MDict resource packs are not supported")
        )
    }

    @Test
    fun `non mdict files are rejected as such`() {
        assertEquals(
            DictionaryImportError.NOT_MDX,
            classifyMdxFailure("not an MDict file: notes.mdx")
        )
    }

    @Test
    fun `truncated and unknown failures are corrupt`() {
        assertEquals(
            DictionaryImportError.CORRUPT,
            classifyMdxFailure("truncated MDict file broken.mdx")
        )
        assertEquals(DictionaryImportError.CORRUPT, classifyMdxFailure("something unexpected"))
        assertEquals(DictionaryImportError.CORRUPT, classifyMdxFailure(null))
    }

    /**
     * 资源包失败的两种措辞不能混：`is a resource pack, not a dictionary` 是「选错了文件」，
     * `not an MDict resource pack` 是「这个包根本读不出来」（导入时的 `RESOURCE_CORRUPT`）。
     */
    @Test
    fun `unreadable resource packs map to the corrupt error code`() {
        assertEquals(
            DictionaryImportError.RESOURCE_CORRUPT,
            classifyMdxFailure("not an MDict resource pack: broken.mdd")
        )
    }

    /** `.mdd` 校验结果 → 错误码：加密与「包用不了」是两种不同的下一步操作。 */
    @Test
    fun `resource pack checks are classified by what the user can do next`() {
        assertNull(classifyPackFailure(MdxPackCheck.Ok(resourceCount = 12)))
        assertEquals(DictionaryImportError.ENCRYPTED, classifyPackFailure(MdxPackCheck.Encrypted))
        assertEquals(
            DictionaryImportError.RESOURCE_CORRUPT,
            classifyPackFailure(MdxPackCheck.NotResourcePack)
        )
        assertEquals(
            DictionaryImportError.RESOURCE_CORRUPT,
            classifyPackFailure(MdxPackCheck.Corrupt("EOF"))
        )
    }

    @Test
    fun `base names keep letters and drop path separators`() {
        assertEquals("ecdict", sanitizeBaseName("ecdict"))
        assertEquals("漢字", sanitizeBaseName("漢字"))
        assertEquals("a_b", sanitizeBaseName("a/b"))
        assertEquals("a.b-c_d", sanitizeBaseName("a.b-c_d"))
        assertEquals("dictionary", sanitizeBaseName("///"))
        assertEquals("dictionary", sanitizeBaseName(""))
    }
}
