/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * fork 新增：收藏夹的领域模型。
 *
 * 单词与句子分开建模：收藏页的两个标签各自只关心自己那几个字段，
 * 共用一个带一堆可空字段的模型会让「单词为什么会有 grammar」变成到处 if 的疑问。
 */

package ua.acclorite.book_story.domain.model.favorite

/** 收藏页的标签。 */
enum class FavoriteTab {
    WORDS,
    SENTENCES
}

/**
 * @param word 已归一（trim + 小写）的词头，也是收藏的唯一键。
 * @param definition 收藏时的释义摘要（纯文本），列表直接显示，不再回查词典。
 */
data class FavoriteWord(
    val id: Long,
    val word: String,
    val phonetic: String?,
    val definition: String?,
    val createdAt: Long
)

/**
 * @param text 句子原文（trim），也是收藏的唯一键。
 * @param translation 补过的译文；还没请求过时为 null。
 * @param grammar 语法分析的 Markdown 原文。
 */
data class FavoriteSentence(
    val id: Long,
    val text: String,
    val translation: String?,
    val grammar: String?,
    val createdAt: Long
)

/**
 * 从取词卡片上收藏一个词时真正需要落库的三样东西。
 *
 * 单独抽出来是因为卡片的 `LookupState.Success` 里有 HTML、命中列表、资源标记等一堆
 * 只属于「展示」的东西；收藏只需要词头、音标与一段纯文本释义。
 */
data class FavoriteWordDraft(
    val word: String,
    val phonetic: String?,
    val definition: String?
)
