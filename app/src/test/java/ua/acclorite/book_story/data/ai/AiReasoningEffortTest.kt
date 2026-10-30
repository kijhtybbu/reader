/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * fork 新增：思考强度（`reasoning_effort`）的取值与回落（第二阶段 S6/A.9）。
 */

package ua.acclorite.book_story.data.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AiReasoningEffortTest {

    @Test
    fun `api values match the OpenAI compatible field`() {
        assertNull(AiReasoningEffort.DEFAULT.apiValue)
        assertEquals("none", AiReasoningEffort.OFF.apiValue)
        assertEquals("low", AiReasoningEffort.LOW.apiValue)
        assertEquals("medium", AiReasoningEffort.MEDIUM.apiValue)
        assertEquals("high", AiReasoningEffort.HIGH.apiValue)
    }

    @Test
    fun `names round trip through settings`() {
        AiReasoningEffort.entries.forEach { effort ->
            assertEquals(effort, AiReasoningEffort.fromName(effort.name))
        }
    }

    /** 脏值回落默认的 OFF（本机默认「不思考」），不是回落 DEFAULT（更慢）。 */
    @Test
    fun `unknown or missing values fall back to off`() {
        assertEquals(AiReasoningEffort.OFF, AiReasoningEffort.fromName(null))
        assertEquals(AiReasoningEffort.OFF, AiReasoningEffort.fromName(""))
        assertEquals(AiReasoningEffort.OFF, AiReasoningEffort.fromName("TURBO"))
    }
}
