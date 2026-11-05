/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * fork 新增：解析层的行为锁定。
 *
 * 这些用例对应产品语义：
 *  - 没有记录 / 未启用 → 全量全局；
 *  - 已启用 → 整组本书值，缺失字段回退全局（容错，而不是把坏字段当默认值）；
 *  - 字体 id 失效 → 由 resolveFont 回落默认字体；
 *  - 差异标记只比较「本书显式写入的字段」，且只在启用时计算。
 */

package ua.acclorite.book_story.data.typography

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ua.acclorite.book_story.presentation.reader.model.ReaderTextAlignment

class ReaderTypographyResolverTest {

    private val global = typographyValues(
        fontId = "global-font",
        fontThickness = 400,
        boldFontThickness = 300,
        italic = false,
        fontSize = 16,
        lineHeight = 4,
        paragraphHeight = 8,
        letterSpacing = 0,
        paragraphIndentation = 5,
        textAlignment = ReaderTextAlignment.JUSTIFY,
        sidePadding = 6,
        verticalPadding = 0
    )

    private val resolveFont: (String) -> ua.acclorite.book_story.ui.reader.model.FontWithName? = { id ->
        if (id == "missing") null else typographyFont(id)
    }

    private val fullBookRecord = global.copyValues(
        fontId = "book-font",
        fontThickness = 700,
        fontSize = 22,
        textAlignment = ReaderTextAlignment.START,
        sidePadding = 10
    ).toRecord(enabled = true)

    @Test
    fun `missing record resolves to global`() {
        val resolved = ReaderTypographyResolver.resolve(global, null, resolveFont)

        assertEquals(global, resolved)
    }

    @Test
    fun `disabled record resolves to global even though values are remembered`() {
        val disabled = fullBookRecord.copy(enabled = false)

        val resolved = ReaderTypographyResolver.resolve(global, disabled, resolveFont)

        assertEquals(global, resolved)
        assertTrue(ReaderTypographyResolver.modifiedFields(global, disabled, resolveFont).isEmpty())
    }

    @Test
    fun `enabled record replaces the whole set`() {
        val resolved = ReaderTypographyResolver.resolve(global, fullBookRecord, resolveFont)

        assertEquals("book-font", resolved.fontFamily.id)
        assertEquals(700, resolved.fontThickness)
        assertEquals(22, resolved.fontSize)
        assertEquals(ReaderTextAlignment.START, resolved.textAlignment)
        assertEquals(10, resolved.sidePadding)
        // 未覆盖的字段来自快照（本用例里快照就是全局值）
        assertEquals(global.lineHeight, resolved.lineHeight)
        assertEquals(global.italic, resolved.italic)
    }

    @Test
    fun `missing fields fall back to global one by one`() {
        // 模拟旧版本/部分损坏的记录：只写了字号
        val partial = BookTypographyRecord(enabled = true, fontSize = 28)

        val resolved = ReaderTypographyResolver.resolve(global, partial, resolveFont)

        assertEquals(28, resolved.fontSize)
        assertEquals(global.fontFamily, resolved.fontFamily)
        assertEquals(global.lineHeight, resolved.lineHeight)
        assertEquals(global.textAlignment, resolved.textAlignment)
        assertEquals(global.verticalPadding, resolved.verticalPadding)
    }

    @Test
    fun `invalid font id falls back to the global font`() {
        val record = BookTypographyRecord(enabled = true, fontFamilyId = "missing")

        val resolved = ReaderTypographyResolver.resolve(global, record, resolveFont)

        assertEquals(global.fontFamily, resolved.fontFamily)
        // 失效 id 不该再显示字体差异标记 —— 观感已经与全局一致
        assertFalse(
            ReaderTypographyField.FONT_FAMILY in
                    ReaderTypographyResolver.modifiedFields(global, record, resolveFont)
        )
    }

    @Test
    fun `modified fields are exactly the ones that differ from global`() {
        val record = fullBookRecord.copy(
            fontFamilyId = global.fontFamily.id,
            fontThickness = global.fontThickness,
            // 其余字段保留本书值
        )

        val modified = ReaderTypographyResolver.modifiedFields(global, record, resolveFont)

        assertFalse(ReaderTypographyField.FONT_FAMILY in modified)
        assertFalse(ReaderTypographyField.FONT_THICKNESS in modified)
        assertTrue(ReaderTypographyField.FONT_SIZE in modified)
        assertTrue(ReaderTypographyField.TEXT_ALIGNMENT in modified)
        assertTrue(ReaderTypographyField.SIDE_PADDING in modified)
    }

    @Test
    fun `all twelve fields can be reported as modified`() {
        val record = BookTypographyRecord(
            enabled = true,
            fontFamilyId = "book-font",
            fontThickness = 500,
            boldFontThickness = 400,
            italic = true,
            fontSize = 30,
            lineHeight = 10,
            paragraphHeight = 20,
            letterSpacing = 4,
            paragraphIndentation = 2,
            textAlignment = ReaderTextAlignment.END,
            sidePadding = 12,
            verticalPadding = 20
        )

        assertEquals(
            ReaderTypographyField.entries.toSet(),
            ReaderTypographyResolver.modifiedFields(global, record, resolveFont)
        )
    }

    @Test
    fun `snapshot writes all twelve fields`() {
        val record = global.toRecord(enabled = true)

        assertEquals(global.fontFamily.id, record.fontFamilyId)
        assertEquals(global.fontThickness, record.fontThickness)
        assertEquals(global.boldFontThickness, record.boldFontThickness)
        assertEquals(global.italic, record.italic)
        assertEquals(global.fontSize, record.fontSize)
        assertEquals(global.lineHeight, record.lineHeight)
        assertEquals(global.paragraphHeight, record.paragraphHeight)
        assertEquals(global.letterSpacing, record.letterSpacing)
        assertEquals(global.paragraphIndentation, record.paragraphIndentation)
        assertEquals(global.textAlignment, record.textAlignment)
        assertEquals(global.sidePadding, record.sidePadding)
        assertEquals(global.verticalPadding, record.verticalPadding)
    }
}

/**
 * 测试辅助：只改指定字段，其余沿用当前值，避免每个用例重复写 12 个参数。
 */
private fun ReaderTypographyValues.copyValues(
    fontId: String = fontFamily.id,
    fontThickness: Int = this.fontThickness,
    fontSize: Int = this.fontSize,
    textAlignment: ReaderTextAlignment = this.textAlignment,
    sidePadding: Int = this.sidePadding
): ReaderTypographyValues = copy(
    fontFamily = typographyFont(fontId),
    fontThickness = fontThickness,
    fontSize = fontSize,
    textAlignment = textAlignment,
    sidePadding = sidePadding
)
