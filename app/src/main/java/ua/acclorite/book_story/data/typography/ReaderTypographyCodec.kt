/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * fork 新增：按书排版记录的 JSON 编解码。
 *
 * 为什么不是 Room 表：这是纯设置数据，没有联表查询需求；放进现有 DataStore 的
 * 一个字符串键里，既不用再加一次数据库迁移，也和 SettingsManager 的存储位置一致。
 * 用 kotlinx.serialization 的 JsonElement API 手写 —— 仓库没有启用 serialization
 * 编译器插件（与 UserFontIndexCodec 的做法保持一致）。
 *
 * 解码容错是硬要求：一本书记录坏了不能影响别的书；单个字段类型不对只丢该字段，
 * 由解析层回退到全局值。
 */

package ua.acclorite.book_story.data.typography

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import ua.acclorite.book_story.presentation.reader.model.ReaderTextAlignment

object ReaderTypographyCodec {

    private const val KEY_ENABLED = "enabled"
    private const val KEY_FONT_FAMILY = "fontFamily"
    private const val KEY_FONT_THICKNESS = "fontThickness"
    private const val KEY_BOLD_FONT_THICKNESS = "boldFontThickness"
    private const val KEY_ITALIC = "italic"
    private const val KEY_FONT_SIZE = "fontSize"
    private const val KEY_LINE_HEIGHT = "lineHeight"
    private const val KEY_PARAGRAPH_HEIGHT = "paragraphHeight"
    private const val KEY_LETTER_SPACING = "letterSpacing"
    private const val KEY_PARAGRAPH_INDENTATION = "paragraphIndentation"
    private const val KEY_TEXT_ALIGNMENT = "textAlignment"
    private const val KEY_SIDE_PADDING = "sidePadding"
    private const val KEY_VERTICAL_PADDING = "verticalPadding"

    fun encode(records: Map<String, BookTypographyRecord>): String = buildJsonObject {
        records.forEach { (uuid, record) ->
            put(
                uuid,
                buildJsonObject {
                    put(KEY_ENABLED, record.enabled)
                    // 可空字段直接省略，解码时自然回退到全局值，不写 `null` 占位。
                    record.fontFamilyId?.let { put(KEY_FONT_FAMILY, it) }
                    record.fontThickness?.let { put(KEY_FONT_THICKNESS, it) }
                    record.boldFontThickness?.let { put(KEY_BOLD_FONT_THICKNESS, it) }
                    record.italic?.let { put(KEY_ITALIC, it) }
                    record.fontSize?.let { put(KEY_FONT_SIZE, it) }
                    record.lineHeight?.let { put(KEY_LINE_HEIGHT, it) }
                    record.paragraphHeight?.let { put(KEY_PARAGRAPH_HEIGHT, it) }
                    record.letterSpacing?.let { put(KEY_LETTER_SPACING, it) }
                    record.paragraphIndentation?.let { put(KEY_PARAGRAPH_INDENTATION, it) }
                    record.textAlignment?.let { put(KEY_TEXT_ALIGNMENT, it.name) }
                    record.sidePadding?.let { put(KEY_SIDE_PADDING, it) }
                    record.verticalPadding?.let { put(KEY_VERTICAL_PADDING, it) }
                }
            )
        }
    }.toString()

    /**
     * @return null 表示整个字符串不可解析（损坏）。调用方应当把它当成「没有按书设置」，
     * 而不是清空存储 —— 与 UserFontIndexCodec 的策略一致：坏数据先保命，等下一次
     * 正常写入再覆盖。
     */
    fun decode(raw: String): Map<String, BookTypographyRecord>? {
        val root = try {
            Json.parseToJsonElement(raw) as? JsonObject
        } catch (e: Exception) {
            null
        } ?: return null

        return root.mapNotNull { (uuid, element) ->
            val json = element as? JsonObject ?: return@mapNotNull null
            if (uuid.isBlank()) return@mapNotNull null

            uuid to BookTypographyRecord(
                enabled = json.booleanOrNull(KEY_ENABLED) ?: false,
                fontFamilyId = json.stringOrNull(KEY_FONT_FAMILY),
                fontThickness = json.intOrNull(KEY_FONT_THICKNESS),
                boldFontThickness = json.intOrNull(KEY_BOLD_FONT_THICKNESS),
                italic = json.booleanOrNull(KEY_ITALIC),
                fontSize = json.intOrNull(KEY_FONT_SIZE),
                lineHeight = json.intOrNull(KEY_LINE_HEIGHT),
                paragraphHeight = json.intOrNull(KEY_PARAGRAPH_HEIGHT),
                letterSpacing = json.intOrNull(KEY_LETTER_SPACING),
                paragraphIndentation = json.intOrNull(KEY_PARAGRAPH_INDENTATION),
                // 认不出的对齐值按「未设置」处理，回退全局；不能 valueOf，否则一本书
                // 的脏值会让整张表都解不出来。
                textAlignment = json.stringOrNull(KEY_TEXT_ALIGNMENT)?.let { name ->
                    ReaderTextAlignment.entries.find { it.name == name }
                },
                sidePadding = json.intOrNull(KEY_SIDE_PADDING),
                verticalPadding = json.intOrNull(KEY_VERTICAL_PADDING)
            )
        }.toMap()
    }

    private fun JsonObject.stringOrNull(key: String): String? {
        // 只接受 JSON 字符串：数字/布尔被 contentOrNull 转成文本会混进枚举/字体 id，
        // 看起来像合法值，实际是坏数据。
        val primitive = get(key) as? JsonPrimitive ?: return null
        if (!primitive.isString) return null
        return primitive.contentOrNull?.takeIf { it.isNotBlank() }
    }

    private fun JsonObject.intOrNull(key: String): Int? =
        (get(key) as? JsonPrimitive)?.intOrNull

    private fun JsonObject.booleanOrNull(key: String): Boolean? =
        (get(key) as? JsonPrimitive)?.booleanOrNull
}
