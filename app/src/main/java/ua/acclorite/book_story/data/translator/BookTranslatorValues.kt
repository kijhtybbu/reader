/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * fork 新增：按书的翻译器行为（取词手势 / 双击翻译）。
 *
 * 产品语义（已与用户确认）：
 *  - 翻译器行为只在书内设置，且只对本书生效；
 *  - 所有书默认关闭：取词手势 OFF、双击翻译 false。旧全局值不迁移、不自动启用；
 *  - 两者都关闭 = 本书不使用翻译器（选文本后的复制/翻译工具栏仍按原有行为保留）；
 *  - 词典与 AI/Key 仍是全局共享配置，不在这里存。
 */

package ua.acclorite.book_story.data.translator

import androidx.compose.runtime.Immutable
import ua.acclorite.book_story.presentation.reader.model.ReaderLookupGesture

@Immutable
data class BookTranslatorValues(
    val lookupGesture: ReaderLookupGesture = ReaderLookupGesture.OFF,
    val doubleClickTranslation: Boolean = false
)

/**
 * 某本书的行为记录。字段可空，便于容错解码；缺失字段按「关闭」处理，
 * 而不是回退到旧的全局值 —— 那会让升级后的书悄悄重新启用翻译器。
 */
@Immutable
data class BookTranslatorRecord(
    val lookupGesture: String? = null,
    val doubleClickTranslation: Boolean? = null
)

/**
 * 解析本书记录。认不出的手势值一律回落 OFF —— 这里的默认值必须是「关闭」，
 * 不能沿用旧的「取词默认开启」语义。
 */
fun BookTranslatorRecord?.resolve(): BookTranslatorValues {
    if (this == null) return BookTranslatorValues()

    return BookTranslatorValues(
        lookupGesture = lookupGesture?.let { name ->
            ReaderLookupGesture.entries.find { it.name == name }
        } ?: ReaderLookupGesture.OFF,
        doubleClickTranslation = doubleClickTranslation ?: false
    )
}
