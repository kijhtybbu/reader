/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 */

package ua.acclorite.book_story.domain.model.book

/**
 * fork 新增：TXT 编码判定的可信度。
 *
 * 为什么要持久化它，而不只是持久化编码名：自动识别失败（[FALLBACK]）时书里的正文是乱码，
 * 用户必须能在第二次、第三次打开时仍然看到「这次识别不可靠」的提示 —— 只存编码名的话，
 * 持久化后的行与「识别成功」完全相同，提示只会出现一次。
 */
enum class CharsetConfidence {
    /** BOM、或严格 UTF-8 解码通过（GBK 字节几乎不可能通过严格 UTF-8）。 */
    CERTAIN,

    /** 严格 GB18030 / BIG5 解码通过。 */
    LIKELY,

    /** 没有一种候选编码能严格解码，只能按 GB18030 硬解 —— 正文可能是乱码。 */
    FALLBACK;

    companion object {
        /**
         * 从数据库里的字符串还原。null = 从未识别过（或用户选的是「自动」且尚未重新解析）；
         * 无法识别的值按 [FALLBACK] 处理 —— 宁可多提示一次，也不要把不可靠的结果当成确定的。
         */
        fun fromPersistedName(name: String?): CharsetConfidence? {
            if (name == null) return null
            return entries.firstOrNull { it.name == name.trim().uppercase() } ?: FALLBACK
        }
    }
}
