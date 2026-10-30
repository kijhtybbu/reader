/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 */

package ua.acclorite.book_story.data.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiClientConfigTest {

    @Test
    fun emptyBaseUrlFallsBackToOpenAi() {
        assertEquals(DEFAULT_AI_BASE_URL, normalizeAiBaseUrl(""))
        assertEquals(DEFAULT_AI_BASE_URL, normalizeAiBaseUrl("   "))
    }

    @Test
    fun trailingSlashIsAddedAndExistingSlashIsPreserved() {
        assertEquals("https://example.test/v1/", normalizeAiBaseUrl(" https://example.test/v1 "))
        assertEquals("https://example.test/v1/", normalizeAiBaseUrl("https://example.test/v1/"))
        assertEquals("http://127.0.0.1:11434/v1/", normalizeAiBaseUrl("http://127.0.0.1:11434/v1"))
    }

    @Test
    fun missingSchemeIsAssumedHttps() {
        assertEquals("https://api.openai.com/v1/", normalizeAiBaseUrl("api.openai.com/v1"))
        assertEquals("https://api.openai.com/v1/", normalizeAiBaseUrl(" api.openai.com/v1/ "))
        // 已写协议的不能重复加。
        assertEquals("http://localhost:11434/v1/", normalizeAiBaseUrl("http://localhost:11434/v1"))
    }

    @Test
    fun loopbackHostsAreRecognized() {
        assertTrue(isLoopbackAiBaseUrl("http://localhost:11434/v1/"))
        assertTrue(isLoopbackAiBaseUrl("http://127.0.0.1:11434/v1"))
        assertTrue(isLoopbackAiBaseUrl("http://10.0.2.2:11434/v1/"))
        assertTrue(isLoopbackAiBaseUrl("localhost:11434/v1"))
        assertTrue(isLoopbackAiBaseUrl("HTTPS://LOCALHOST/v1/"))
    }

    @Test
    fun remoteHostsAreNotLoopback() {
        assertFalse(isLoopbackAiBaseUrl(DEFAULT_AI_BASE_URL))
        assertFalse(isLoopbackAiBaseUrl(""))
        assertFalse(isLoopbackAiBaseUrl("http://192.168.1.10:11434/v1/"))
        // 名字里带 localhost 不是本机地址；不要用前缀/包含匹配。
        assertFalse(isLoopbackAiBaseUrl("http://localhost.example.com/v1/"))
        assertFalse(isLoopbackAiBaseUrl("http://notlocalhost/v1/"))
    }
}
