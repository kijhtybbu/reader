/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * fork 新增：用户字体索引的 JSON 编解码。
 *
 * 为什么不是 Room 表：SettingsManager.fontFamily 的 deserialize 是同步的，DataStore 一 emit
 * 就会查字体。索引放在 filesDir/fonts/index.json，Application.onCreate 里同步读进内存，
 * 才能保证「重启后用户选的字体还在」。用 kotlinx.serialization 的 JsonElement API 手写，
 * 不引入 serialization 插件（本仓库没启用编译器插件）。
 */

package ua.acclorite.book_story.data.storage.font

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put
import ua.acclorite.book_story.ui.reader.model.UserFontEntry

object UserFontIndexCodec {

    private const val KEY_ID = "id"
    private const val KEY_NAME = "name"
    private const val KEY_FILE_NAME = "fileName"
    private const val KEY_SIZE = "size"
    private const val KEY_VARIABLE = "variable"
    private const val KEY_HAS_CJK = "hasCjk"
    private const val KEY_IMPORTED_AT = "importedAt"

    fun encode(fonts: List<UserFontEntry>): String = buildJsonArray {
        fonts.forEach { font ->
            addJsonObject {
                put(KEY_ID, font.id)
                put(KEY_NAME, font.name)
                put(KEY_FILE_NAME, font.fileName)
                put(KEY_SIZE, font.size)
                put(KEY_VARIABLE, font.variable)
                put(KEY_HAS_CJK, font.hasCjk)
                put(KEY_IMPORTED_AT, font.importedAt)
            }
        }
    }.toString()

    /**
     * @return null 表示整个文件不可解析（损坏）；否则返回能解析出的条目（缺字段的条目被跳过）。
     * 调用方对 null 的处理必须是「当成没有用户字体」，而不是删文件 —— 字体文件本身还在，
     * 只是索引坏了，静默删数据会让用户重新导入。
     */
    fun decode(raw: String): List<UserFontEntry>? {
        val array = try {
            Json.parseToJsonElement(raw) as? JsonArray
        } catch (e: Exception) {
            null
        } ?: return null

        return array.mapNotNull { element ->
            val json = element as? JsonObject ?: return@mapNotNull null
            val id = json.stringOrNull(KEY_ID) ?: return@mapNotNull null
            val fileName = json.stringOrNull(KEY_FILE_NAME) ?: return@mapNotNull null
            val size = json.longOrNull(KEY_SIZE) ?: return@mapNotNull null
            UserFontEntry(
                id = id,
                name = json.stringOrNull(KEY_NAME) ?: id,
                fileName = fileName,
                size = size,
                variable = json.booleanOrNull(KEY_VARIABLE) ?: false,
                hasCjk = json.booleanOrNull(KEY_HAS_CJK) ?: false,
                importedAt = json.longOrNull(KEY_IMPORTED_AT) ?: 0L
            )
        }
    }

    private fun JsonObject.stringOrNull(key: String): String? =
        (get(key) as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.longOrNull(key: String): Long? =
        (get(key) as? JsonPrimitive)?.contentOrNull?.toLongOrNull()

    private fun JsonObject.booleanOrNull(key: String): Boolean? =
        (get(key) as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull()
}
