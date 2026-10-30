/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * fork 新增：AI 结果缓存（第二阶段 S7，设计 §7.1）。
 *
 * 只存结果，不存正文原文：`cacheKey` 是正文的 SHA-256（见 `TranslationCacheKey`），
 * 同一段文字再出现时直接命中，[payload] 里没有全文以外的信息。
 *
 * [complete] 当前恒为 `true`：仓库只写完整结果，S8 也不会在半截时调 `put`。
 * 留这一列是为将来的半截结果预留标记：读取侧忽略 `complete = false` 的行，不用靠删表来清理。
 *
 * 索引：[lastAccessedAt] 给 LRU 清理与「最旧优先」删除用；[bookId] 给将来的「某本书的缓存」
 * 与按书清理用（本期只有仓库的 `clear()` 全清，索引先按设计 §7.1 建好）。
 */

package ua.acclorite.book_story.data.storage.room.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "translationcacheentity",
    indices = [
        Index(value = ["lastAccessedAt"]),
        Index(value = ["bookId"])
    ]
)
data class TranslationCacheEntity(
    @PrimaryKey val cacheKey: String,
    /** 缓存不随书删除而失效（设计 §7.2：同一段文字在不同书/章节共享缓存），所以只记来源，不做外键。 */
    val bookId: Int,
    val nodeId: Int,
    /** `TRANSLATION` / `GRAMMAR` / `WORD`，见 `TranslationKind.wire`。 */
    val kind: String,
    val model: String,
    val baseUrlHost: String,
    val targetLanguage: String,
    val promptVersion: Int,
    /** 译文正文或语法分析的原始 JSON。 */
    val payload: String,
    val complete: Boolean,
    val createdAt: Long,
    val lastAccessedAt: Long
)
