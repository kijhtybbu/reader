/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * fork 新增：把「翻译这段文字」的回调打包成一份，穿过 ReaderContent → ReaderScaffold → 两种布局
 * （第二阶段 S8，设计 §8）。
 *
 * 与 ReaderLookupActions 同样的理由：两种布局已经有十几个回调参数，而且双击段落与
 * 选区工具栏的「翻译」必须走同一个入口 —— 只有这样才能共用同一个 [TranslationModel]，
 * 缓存优先、去重、取消都在模型里。
 *
 * 双击取的是整段正文：分页布局里 `ReaderPageItem` 持有的是这一页的切片偏移，
 * `ReaderNode.text` 虽然也是整段，但设计明确要求用 `NodeContent.Paragraph.text`，
 * 这里显式取它，避免以后有人把切片塞进 ReaderNode。
 */

package ua.acclorite.book_story.ui.reader

import androidx.compose.runtime.Immutable
import ua.acclorite.book_story.domain.model.book.NodeContent
import ua.acclorite.book_story.ui.reader.model.ReaderNode

@Immutable
class ReaderTranslationActions(
    /**
     * 触发翻译。
     *
     * - 双击段落：`text` 是整段正文，`nodeId` 是段落节点 id；
     * - 选区工具栏：`text` 是选中文字，`nodeId` 为 null。
     */
    val onTranslate: (text: String, nodeId: Int?) -> Unit
)

/** 双击命中的段落 → 整段正文 + 节点 id。 */
internal fun ReaderNode.Paragraph.wholeParagraphText(): String =
    (originalNode.content as? NodeContent.Paragraph)?.text ?: text.text
