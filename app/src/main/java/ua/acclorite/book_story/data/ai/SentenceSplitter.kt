/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * fork 新增：把整段正文切成句子（第二阶段语法 UX，设计 §8.2）。
 *
 * 语法标签逐句分析：整段一次分析时，模型只能给一份笼统的说明；切成句子后每句单独请求、
 * 单独缓存，用户点到哪句就分析哪句。
 *
 * 用 `java.text.BreakIterator`（Android 与 JVM 都有，Android 上是 ICU 实现），不自己写标点规则：
 * 引号、省略号、CJK 全角标点都在平台规则里，手写版本只会在更多地方错。
 *
 * 已知限制：UAX#29 没有缩写词典，`Dr. Smith` 仍会在 `Dr.` 后断开
 * （`SentenceSplitterTest` 把这个行为钉住）。不在这里做后处理 —— 把两句误并成一句
 * 会让一次分析里塞两个主句，比多切一刀更糟。
 */

package ua.acclorite.book_story.data.ai

import java.text.BreakIterator
import java.util.Locale

/**
 * 切句。条目已 `trim`，空条目丢弃。
 *
 * @return 空白文本返回空列表；没有终止标点的文本仍返回整段（不会丢字）。
 */
fun splitIntoSentences(text: String): List<String> {
    if (text.isBlank()) return emptyList()

    val iterator = BreakIterator.getSentenceInstance(Locale.ENGLISH)
    iterator.setText(text)

    val sentences = ArrayList<String>()
    var start = iterator.first()
    var end = iterator.next()
    while (end != BreakIterator.DONE) {
        text.substring(start, end).trim().takeIf { it.isNotEmpty() }?.let(sentences::add)
        start = end
        end = iterator.next()
    }

    // 平台实现若没有把最后一段交给 `next()`，`start` 会停在末尾之前；补上剩下的非空白文字。
    if (start != BreakIterator.DONE && start < text.length) {
        text.substring(start).trim().takeIf { it.isNotEmpty() }?.let(sentences::add)
    }
    return sentences
}
