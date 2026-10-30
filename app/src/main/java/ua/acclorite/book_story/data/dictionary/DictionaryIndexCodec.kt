/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * fork 新增：导入词典元数据索引的 JSON 编解码（第二阶段 S4）。
 *
 * 与用户字体索引（`UserFontIndexCodec`）同样是手写的 JsonElement 编解码，不启用
 * kotlinx.serialization 编译器插件。索引放在 `filesDir/dictionaries/index.json`，
 * 只存元数据：词典文件本身按 `type` + `baseName` 命名（`<base>.ifo/.idx/.dict`、
 * `<base>.mdx`），所以不再把文件名列表抄一遍 —— 抄一份就多一处会和磁盘不一致的状态。
 */

package ua.acclorite.book_story.data.dictionary

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * 一条导入词典的持久化元数据。
 *
 * [directory] 是相对 `filesDir/dictionaries/` 的路径（`imported/<uuid>`），
 * 不存绝对路径：`filesDir` 会随重装/迁移变化，存绝对路径重启后全部失效。
 */
data class DictionaryIndexEntry(
    val id: String,
    val type: DictionaryType,
    val name: String,
    val baseName: String,
    val directory: String,
    val wordCount: Int,
    val sizeBytes: Long,
    val enabled: Boolean,
    val importedAt: Long
)

object DictionaryIndexCodec {

    private const val KEY_ID = "id"
    private const val KEY_TYPE = "type"
    private const val KEY_NAME = "name"
    private const val KEY_BASE_NAME = "baseName"
    private const val KEY_DIRECTORY = "directory"
    private const val KEY_WORD_COUNT = "wordCount"
    private const val KEY_SIZE = "sizeBytes"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_IMPORTED_AT = "importedAt"

    fun encode(entries: List<DictionaryIndexEntry>): String = buildJsonArray {
        entries.forEach { entry ->
            addJsonObject {
                put(KEY_ID, entry.id)
                put(KEY_TYPE, entry.type.wire)
                put(KEY_NAME, entry.name)
                put(KEY_BASE_NAME, entry.baseName)
                put(KEY_DIRECTORY, entry.directory)
                put(KEY_WORD_COUNT, entry.wordCount)
                put(KEY_SIZE, entry.sizeBytes)
                put(KEY_ENABLED, entry.enabled)
                put(KEY_IMPORTED_AT, entry.importedAt)
            }
        }
    }.toString()

    /**
     * @return null 表示整个文件不可解析（损坏）；否则返回能解析出的条目，缺字段或
     * 类型未知的条目被跳过。调用方对 null 的处理必须是「当作没有导入词典」而不是删文件：
     * 词典目录还在，下一次 [DictionaryCatalog.refresh] 会按目录内容重新收养它们。
     *
     * id 为 [DictionaryCatalog.BUILT_IN_ID] 或重复的条目一律丢弃：设置页用 `key = { it.id }`
     * 渲染列表，重复/保留 id 会让组合直接抛异常。
     */
    fun decode(raw: String): List<DictionaryIndexEntry>? {
        val array = try {
            Json.parseToJsonElement(raw) as? JsonArray
        } catch (e: Exception) {
            null
        } ?: return null

        val seen = HashSet<String>()
        return array.mapNotNull { element ->
            val json = element as? JsonObject ?: return@mapNotNull null
            val id = json.stringOrNull(KEY_ID) ?: return@mapNotNull null
            val type = json.stringOrNull(KEY_TYPE)
                ?.let(DictionaryType::fromWire)
                ?: return@mapNotNull null
            val baseName = json.stringOrNull(KEY_BASE_NAME) ?: return@mapNotNull null
            val directory = json.stringOrNull(KEY_DIRECTORY) ?: return@mapNotNull null
            DictionaryIndexEntry(
                id = id,
                type = type,
                name = json.stringOrNull(KEY_NAME) ?: baseName,
                baseName = baseName,
                directory = directory,
                wordCount = json.intOrNull(KEY_WORD_COUNT) ?: 0,
                sizeBytes = json.longOrNull(KEY_SIZE) ?: 0L,
                enabled = json.booleanOrNull(KEY_ENABLED) ?: true,
                importedAt = json.longOrNull(KEY_IMPORTED_AT) ?: 0L
            )
        }.filter { entry ->
            entry.id != DictionaryCatalog.BUILT_IN_ID && seen.add(entry.id)
        }
    }

    private fun JsonObject.stringOrNull(key: String): String? =
        (get(key) as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.intOrNull(key: String): Int? =
        (get(key) as? JsonPrimitive)?.contentOrNull?.toIntOrNull()

    private fun JsonObject.longOrNull(key: String): Long? =
        (get(key) as? JsonPrimitive)?.contentOrNull?.toLongOrNull()

    private fun JsonObject.booleanOrNull(key: String): Boolean? =
        (get(key) as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull()
}
