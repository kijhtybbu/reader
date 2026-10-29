/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 */

package ua.acclorite.book_story.ui.reader

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import ua.acclorite.book_story.R
import ua.acclorite.book_story.data.parser.content.TXT_CHARSET_OVERRIDES
import ua.acclorite.book_story.domain.model.book.Book
import ua.acclorite.book_story.domain.model.book.CharsetConfidence
import ua.acclorite.book_story.presentation.reader.ReaderEvent
import ua.acclorite.book_story.ui.common.components.settings.ChipsWithTitle
import ua.acclorite.book_story.ui.common.model.ListItem
import ua.acclorite.book_story.ui.settings.components.SettingsSubcategoryNote

/**
 * fork 新增：TXT 编码的手动覆盖。
 *
 * 为什么放在阅读页设置的「更多」Tab：用户看到乱码的现场就是阅读页，救回来必须能原地完成；
 * 书详情是另一个页面，看完乱码再退出去那里改，等于让用户先认定这本书坏了。
 * 「更多」是排版与配色之外的杂项 Tab，本来就是这类「文件层面的怪问题」的落点。
 *
 * 提示与选项放在同一个组件里，所以 [CharsetConfidence.FALLBACK] 的警示不会和
 * 可以修好它的开关分离。
 */
@Composable
fun ReaderEncodingOption(
    book: Book,
    changeCharset: (ReaderEvent.OnChangeCharset) -> Unit
) {
    // fork: 编码只影响 TXT 的解析，EPUB/PDF/FB2 等不应看到这个开关。
    if (!book.isTxtFile()) return

    val autoTitle = stringResource(id = R.string.txt_encoding_auto)

    val chips = buildList {
        // 选中项反映的是「实际生效的编码」而不是用户上一次点了哪个：
        // 自动识别出 UTF-8 时，「自动」不再是选中项 —— 否则用户看到「自动」被选中，
        // 却无法知道书里到底按什么编码在解。
        add(
            ListItem<String?>(
                item = null,
                title = autoTitle,
                selected = book.charsetName == null
            )
        )
        TXT_CHARSET_OVERRIDES.forEach { charset ->
            add(
                ListItem<String?>(
                    item = charset.name(),
                    title = charset.name().uppercase(),
                    selected = charset.name().equals(book.charsetName, ignoreCase = true)
                )
            )
        }
    }

    ChipsWithTitle(
        title = stringResource(id = R.string.txt_encoding_option),
        chips = chips,
        onClick = { changeCharset(ReaderEvent.OnChangeCharset(it)) }
    )

    if (shouldWarnAboutTxtCharset(book)) {
        SettingsSubcategoryNote(
            text = stringResource(id = R.string.txt_encoding_fallback_note)
        )
    }
}

/**
 * fork: 什么时候必须告警抽成纯函数（与 `bookmarkButtonState` 同一做法）——
 * 「自动识别不可靠」这类只能意会不能验证的判断，至少要让它的显隐规则可以被 JVM 单测钉住。
 *
 * 只看落库的值，所以第二次、第三次打开这本书时提示照样在，不是一次性的 toast。
 */
internal fun shouldWarnAboutTxtCharset(book: Book): Boolean =
    book.isTxtFile() && book.charsetConfidence == CharsetConfidence.FALLBACK

private fun Book.isTxtFile(): Boolean =
    filePath.substringAfterLast('.', "").equals("txt", ignoreCase = true)
