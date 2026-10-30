/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 */

package ua.acclorite.book_story.ui.reader

import androidx.compose.ui.text.TextLinkStyles
import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderLookupHtmlTest {

    @Test
    fun `exchange codes become readable word forms`() {
        val html = normalizeDictionaryHtml(
            "<b>result</b><br>n. 结果<br><small>s:results/p:resulted/d:resulted/i:resulting</small>"
        )

        assertEquals(
            "<b>result</b><br>n. 结果<br><small>词形：复数 results · 过去式 resulted · 过去分词 resulted · 现在分词 resulting</small>",
            html
        )
    }

    @Test
    fun `literal and real newlines become line breaks`() {
        val html = normalizeDictionaryHtml("apple<br>n. 苹果\\n[医] 苹果<br>n. fruit")

        assertEquals("apple<br>n. 苹果<br>[医] 苹果<br>n. fruit", html)
    }
}
