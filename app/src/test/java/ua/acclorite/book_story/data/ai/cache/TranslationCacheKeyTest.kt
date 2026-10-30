/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * fork 新增：缓存键的单元测试（第二阶段 S7，设计 §7.2/§11）。
 */

package ua.acclorite.book_story.data.ai.cache

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ua.acclorite.book_story.data.ai.AI_PROMPT_VERSION

class TranslationCacheKeyTest {

    private val text = "Hello world."

    private fun key(
        text: String = this.text,
        kind: TranslationKind = TranslationKind.TRANSLATION,
        targetLanguage: String = "Simplified Chinese (zh-CN)",
        model: String = "gpt-4o-mini",
        baseUrl: String = "https://api.openai.com/v1/",
        promptVersion: Int = 1
    ) = translationCacheKey(
        text = text,
        kind = kind,
        targetLanguage = targetLanguage,
        model = model,
        baseUrl = baseUrl,
        promptVersion = promptVersion
    )

    @Test
    fun cacheKeyIsDeterministicLowerCaseHex() {
        val first = key()
        val second = key()

        assertEquals(first, second)
        assertEquals(64, first.length)
        assertTrue("expected lowercase hex, got $first", first.all { it in '0'..'9' || it in 'a'..'f' })
    }

    /**
     * 钉死键的拼接格式：改 `v` 前缀、分隔符、字段顺序都会让所有存量缓存静默失效，
     * 必须是显式的决定而不是「碰巧还一样」。
     */
    @Test
    fun cacheKeyPinsTheExactHashMaterial() {
        assertEquals(
            "dcad577a162d624c46e9f4903181b51690d1554c4c6526bcbd2d1c9b36f80eda",
            key()
        )
    }

    @Test
    fun textNormalizationTrimsAndUnifiesLineEndings() {
        assertEquals("a\nb", normalizeCacheText("  a\r\nb\r"))
        assertEquals("a\nb", normalizeCacheText("a\nb\n\n"))
        assertEquals(key(text = "a\r\nb"), key(text = "\n a\nb \r\n"))

        // 内部空白是文本身份的一部分，合并会让两段不同的话命中同一条译文。
        assertNotEquals(normalizeCacheText("a  b"), normalizeCacheText("a b"))
        assertNotEquals(key(text = "a  b"), key(text = "a b"))
        assertNotEquals(key(text = "a b"), key(text = "ab"))
    }

    @Test
    fun kindChangesTheKey() {
        val keys = TranslationKind.entries.map { key(kind = it) }

        assertEquals(3, keys.toSet().size)
        assertEquals("TRANSLATION", TranslationKind.TRANSLATION.wire)
        assertEquals("GRAMMAR", TranslationKind.GRAMMAR.wire)
        assertEquals("WORD", TranslationKind.WORD.wire)
    }

    @Test
    fun modelTargetLanguageAndPromptVersionChangeTheKey() {
        assertNotEquals(key(), key(model = "gpt-4o"))
        assertNotEquals(key(), key(targetLanguage = "English (en)"))
        assertNotEquals(key(), key(promptVersion = 2))
    }

    @Test
    fun promptVersionDefaultsToTheCompiledInVersion() {
        // 省略 promptVersion 时必须等于显式传 AI_PROMPT_VERSION：
        // 「改提示词 → 常量 +1 → 键全变 → 旧缓存自动失效」靠的就是这个默认值。
        assertEquals(
            key(promptVersion = AI_PROMPT_VERSION),
            translationCacheKey(
                text = text,
                kind = TranslationKind.TRANSLATION,
                targetLanguage = "Simplified Chinese (zh-CN)",
                model = "gpt-4o-mini",
                baseUrl = "https://api.openai.com/v1/"
            )
        )
    }

    @Test
    fun onlyTheBaseUrlHostEntersTheKey() {
        assertNotEquals(
            key(baseUrl = "https://api.openai.com/v1/"),
            key(baseUrl = "https://api.deepseek.com/v1/")
        )

        // 同一服务商的协议/端口/路径/大小写差异不应各存一份译文。
        assertEquals(key(baseUrl = "https://api.openai.com/v1/"), key(baseUrl = "http://API.openai.com:8443/v2/"))
        assertEquals(key(baseUrl = "https://api.openai.com/v1/"), key(baseUrl = "api.openai.com/v1"))
    }

    @Test
    fun emptyBaseUrlUsesTheDefaultHost() {
        // 空值在请求侧会被规范化成默认地址，键里的 host 必须与真正发出去的请求一致。
        assertEquals("api.openai.com", translationBaseUrlHost(""))
        assertEquals(key(baseUrl = ""), key(baseUrl = "https://api.openai.com/v1/"))
    }

    @Test
    fun malformedBaseUrlYieldsEmptyHostWithoutThrowing() {
        assertEquals("", translationBaseUrlHost("::::"))
        assertEquals("", translationBaseUrlHost("///"))
        assertEquals(key(baseUrl = "::::"), key(baseUrl = "///"))
        assertNotEquals(key(baseUrl = "::::"), key(baseUrl = "https://api.openai.com/v1/"))
    }

    @Test
    fun cjkAndEmojiAreHashedAsUtf8WithoutLoss() {
        val cjk = "他说：“这样可以吗？”"
        val emoji = "It's fine 👍🏽 — 好"

        val cjkKey = key(text = cjk)
        assertEquals(cjkKey, key(text = "  $cjk\r\n"))
        assertNotEquals(cjkKey, key(text = "他说：“这样可以吗?’"))

        assertNotEquals(cjkKey, key(text = emoji))
        assertEquals(key(text = emoji), key(text = emoji + "\r\n"))
        assertNotEquals(key(text = emoji), key(text = emoji.replace("🏽", "")))
    }
}
