/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * fork 新增：按书翻译器行为的 JSON 编解码。
 *
 * 与 ReaderTypographyCodec 同样的容错策略：整串坏掉返回 null（调用方保留原值），
 * 单条记录坏掉只跳过该条，单个字段类型不对只丢该字段（解析时回落「关闭」）。
 */

package ua.acclorite.book_story.data.translator

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

object BookTranslatorCodec {

    private const val KEY_LOOKUP_GESTURE = "lookupGesture"
    private const val KEY_DOUBLE_CLICK_TRANSLATION = "doubleClickTranslation"

    fun encode(records: Map<String, BookTranslatorRecord>): String = buildJsonObject {
        records.forEach { (uuid, record) ->
            put(
                uuid,
                buildJsonObject {
                    record.lookupGesture?.let { put(KEY_LOOKUP_GESTURE, it) }
                    record.doubleClickTranslation?.let {
                        put(KEY_DOUBLE_CLICK_TRANSLATION, it)
                    }
                }
            )
        }
    }.toString()

    /**
     * @return null 表示整个字符串不可解析（损坏）。调用方应保留原始字符串，
     * 下次正常写入时再覆盖。
     */
    fun decode(raw: String): Map<String, BookTranslatorRecord>? {
        val root = try {
            Json.parseToJsonElement(raw) as? JsonObject
        } catch (e: Exception) {
            null
        } ?: return null

        return root.mapNotNull { (uuid, element) ->
            val json = element as? JsonObject ?: return@mapNotNull null
            if (uuid.isBlank()) return@mapNotNull null

            uuid to BookTranslatorRecord(
                lookupGesture = json.stringOrNull(KEY_LOOKUP_GESTURE),
                doubleClickTranslation = json.booleanOrNull(KEY_DOUBLE_CLICK_TRANSLATION)
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

    private fun JsonObject.booleanOrNull(key: String): Boolean? =
        (get(key) as? JsonPrimitive)?.booleanOrNull
}
