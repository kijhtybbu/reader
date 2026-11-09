/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * fork 新增：单词/句子收藏（全局收藏夹）。
 *
 * 一张表装两种收藏：`type` 区分 WORD / SENTENCE，`text` 分别是词头与句子原文。
 * 两种收藏的字段没有冲突（单词只用 phonetic/definition，句子只用 translation/grammar），
 * 拆两张表反而要维护两套 DAO 与两次迁移。
 *
 * 唯一索引 (type, text)：同一个词/同一句只能收藏一次。单词在仓库层统一小写后写入，
 * 避免 `Apple` / `apple` 变成两条。
 *
 * 句子收藏保存的是**语法分析时的快照**：`grammar` 是模型返回的 Markdown 原文，
 * `translation` 可能在收藏时还没请求过（用户只看了语法标签），
 * 这种情况下收藏页打开句子时会补一次翻译并回填到这里 —— 所以它是可空的。
 */

package ua.acclorite.book_story.data.storage.room.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "favoriteentity",
    indices = [
        Index(value = ["type", "text"], unique = true),
        Index(value = ["createdAt"])
    ]
)
data class FavoriteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** `WORD` / `SENTENCE`，见 [FavoriteEntityType]。 */
    val type: String,
    /** 单词的小写词头，或句子原文（已 trim）。 */
    val text: String,
    /** 单词音标；句子为 null。 */
    val phonetic: String? = null,
    /** 单词释义摘要（纯文本）；句子为 null。 */
    val definition: String? = null,
    /** 句子译文；单词为 null。未请求过翻译时也是 null。 */
    val translation: String? = null,
    /** 句子语法分析的 Markdown 原文；单词为 null。 */
    val grammar: String? = null,
    val createdAt: Long
)

/** 收藏类型在数据库里的稳定字符串；枚举名改动不影响已存的数据。 */
object FavoriteEntityType {
    const val WORD = "WORD"
    const val SENTENCE = "SENTENCE"
}
