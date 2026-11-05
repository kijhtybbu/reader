/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * fork 新增：全局值 + 本书记录 → 当前生效排版。纯函数，便于单测。
 *
 * 语义（与面板上的开关对应）：
 *  - 没有记录，或记录 enabled=false → 完全使用全局值；
 *  - 记录 enabled=true → 整组使用本书记录；单个缺失/非法字段回退全局（容错，
 *    不改变「整组覆盖」的产品语义 —— 正常写入时 12 项始终是完整的）。
 *
 * 字体 id 通过 resolveFont 注入：解析器本身不依赖 FontRegistry / Compose，
 * 单测可以用假实现验证「字体被删后回落全局字体」的行为。返回 null 表示 id 已失效。
 */

package ua.acclorite.book_story.data.typography

import ua.acclorite.book_story.ui.reader.model.FontWithName

object ReaderTypographyResolver {

    fun resolve(
        global: ReaderTypographyValues,
        record: BookTypographyRecord?,
        resolveFont: (String) -> FontWithName?
    ): ReaderTypographyValues {
        if (record == null || !record.enabled) return global

        return ReaderTypographyValues(
            // 字体按 id 解析；id 失效（字体被删）时回落全局字体，而不是硬编码默认字体 ——
            // 否则本书会突然变成与全局完全不同的字形，差异标记也解释不了观感。
            fontFamily = record.fontFamilyId?.let(resolveFont) ?: global.fontFamily,
            fontThickness = record.fontThickness ?: global.fontThickness,
            boldFontThickness = record.boldFontThickness ?: global.boldFontThickness,
            italic = record.italic ?: global.italic,
            fontSize = record.fontSize ?: global.fontSize,
            lineHeight = record.lineHeight ?: global.lineHeight,
            paragraphHeight = record.paragraphHeight ?: global.paragraphHeight,
            letterSpacing = record.letterSpacing ?: global.letterSpacing,
            paragraphIndentation = record.paragraphIndentation ?: global.paragraphIndentation,
            textAlignment = record.textAlignment ?: global.textAlignment,
            sidePadding = record.sidePadding ?: global.sidePadding,
            verticalPadding = record.verticalPadding ?: global.verticalPadding
        )
    }

    /**
     * 与全局不同的字段集合。只在本书模式有意义 —— 全局模式下 UI 不显示标记，
     * 调用方仍会拿到空集合（record 为 null 或 enabled=false）。
     */
    fun modifiedFields(
        global: ReaderTypographyValues,
        record: BookTypographyRecord?,
        resolveFont: (String) -> FontWithName?
    ): Set<ReaderTypographyField> {
        if (record == null || !record.enabled) return emptySet()

        return buildSet {
            // 字体按「解析后的 id」比较：记录指向已删除的字体时，解析回落到全局字体，
            // 不应再显示差异标记（否则标记会一直亮着，而观感与全局一致）。
            if (record.fontFamilyId != null) {
                val resolvedId = record.fontFamilyId.let(resolveFont)?.id
                    ?: global.fontFamily.id
                if (resolvedId != global.fontFamily.id) {
                    add(ReaderTypographyField.FONT_FAMILY)
                }
            }
            if (record.fontThickness != null && record.fontThickness != global.fontThickness) {
                add(ReaderTypographyField.FONT_THICKNESS)
            }
            if (
                record.boldFontThickness != null &&
                record.boldFontThickness != global.boldFontThickness
            ) {
                add(ReaderTypographyField.BOLD_FONT_THICKNESS)
            }
            if (record.italic != null && record.italic != global.italic) {
                add(ReaderTypographyField.ITALIC)
            }
            if (record.fontSize != null && record.fontSize != global.fontSize) {
                add(ReaderTypographyField.FONT_SIZE)
            }
            if (record.lineHeight != null && record.lineHeight != global.lineHeight) {
                add(ReaderTypographyField.LINE_HEIGHT)
            }
            if (
                record.paragraphHeight != null &&
                record.paragraphHeight != global.paragraphHeight
            ) {
                add(ReaderTypographyField.PARAGRAPH_HEIGHT)
            }
            if (record.letterSpacing != null && record.letterSpacing != global.letterSpacing) {
                add(ReaderTypographyField.LETTER_SPACING)
            }
            if (
                record.paragraphIndentation != null &&
                record.paragraphIndentation != global.paragraphIndentation
            ) {
                add(ReaderTypographyField.PARAGRAPH_INDENTATION)
            }
            if (record.textAlignment != null && record.textAlignment != global.textAlignment) {
                add(ReaderTypographyField.TEXT_ALIGNMENT)
            }
            if (record.sidePadding != null && record.sidePadding != global.sidePadding) {
                add(ReaderTypographyField.SIDE_PADDING)
            }
            if (record.verticalPadding != null && record.verticalPadding != global.verticalPadding) {
                add(ReaderTypographyField.VERTICAL_PADDING)
            }
        }
    }
}
