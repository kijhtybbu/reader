/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * fork 新增：取词手势（第二阶段 S5，设计 §5.1）。
 *
 * 三个取值不是「三种选词方式」而是「点一下会发生什么」：
 *  - SINGLE_TAP：单击命中单词就取词，**空白区仍然是原来的翻页/菜单**。它确实接管了
 *    「点到文字」的那一部分单击 —— 设置页文案必须说清这一点，否则用户会以为翻页坏了。
 *  - LONG_PRESS：单击完全保持原样（分页翻页、连续切菜单），长按选中后再取词。
 *  - OFF：不取词，但工具栏的「词典」动作仍然可用（那是显式操作，不是误触）。
 */

package ua.acclorite.book_story.presentation.reader.model

import androidx.annotation.StringRes
import ua.acclorite.book_story.R

enum class ReaderLookupGesture(@StringRes val title: Int) {
    SINGLE_TAP(R.string.lookup_gesture_single_tap),
    LONG_PRESS(R.string.lookup_gesture_long_press),
    OFF(R.string.lookup_gesture_off);

    companion object {
        /**
         * 解析设置里存的字符串。旧值/被删掉的取值一律回落 [SINGLE_TAP]：
         * 取词是默认行为，存了个认不出的值不该静默把功能关掉。
         */
        fun fromName(name: String?): ReaderLookupGesture =
            entries.firstOrNull { it.name == name } ?: SINGLE_TAP
    }
}
