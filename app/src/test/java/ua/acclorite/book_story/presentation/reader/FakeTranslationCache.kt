/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * fork 新增：TranslationModel 测试用的内存缓存（第二阶段 S8）。
 *
 * 行为与 S7 的仓库保持一致的地方：`get` 只返回已写入的完整结果，`put` 覆盖同键。
 * 另外记录每次读写的键与参数，测试才能断言「缓存命中时零请求」「重新生成覆盖同一键」。
 */

package ua.acclorite.book_story.presentation.reader

import ua.acclorite.book_story.data.ai.cache.CacheStats
import ua.acclorite.book_story.data.ai.cache.CachedAiResult
import ua.acclorite.book_story.data.ai.cache.TranslationCacheRepository
import ua.acclorite.book_story.data.ai.cache.TranslationKind

internal class FakeTranslationCache : TranslationCacheRepository {

    data class PutCall(
        val key: String,
        val kind: TranslationKind,
        val bookId: Int,
        val nodeId: Int,
        val payload: String,
        val model: String,
        val baseUrl: String,
        val targetLanguage: String,
        val promptVersion: Int,
        val now: Long
    )

    val entries = mutableMapOf<String, CachedAiResult>()
    val puts = mutableListOf<PutCall>()
    val reads = mutableListOf<String>()

    var failReads = false
    var failPuts = false
    var failStats = false
    var failClear = false

    var clearCalls = 0
    var statsValue = CacheStats(count = 0, payloadBytes = 0L)

    override suspend fun get(key: String): CachedAiResult? {
        reads += key
        if (failReads) throw IllegalStateException("cache read failed")
        return entries[key]
    }

    override suspend fun put(
        key: String,
        kind: TranslationKind,
        bookId: Int,
        nodeId: Int,
        payload: String,
        model: String,
        baseUrl: String,
        targetLanguage: String,
        promptVersion: Int,
        now: Long
    ) {
        if (failPuts) throw IllegalStateException("cache write failed")
        puts += PutCall(
            key = key,
            kind = kind,
            bookId = bookId,
            nodeId = nodeId,
            payload = payload,
            model = model,
            baseUrl = baseUrl,
            targetLanguage = targetLanguage,
            promptVersion = promptVersion,
            now = now
        )
        entries[key] = CachedAiResult(
            payload = payload,
            model = model,
            targetLanguage = targetLanguage,
            createdAt = now
        )
    }

    override suspend fun clear() {
        clearCalls++
        if (failClear) throw IllegalStateException("cache clear failed")
        entries.clear()
        statsValue = CacheStats(count = 0, payloadBytes = 0L)
    }

    override suspend fun stats(): CacheStats {
        if (failStats) throw IllegalStateException("cache stats failed")
        // 显式 statsValue 优先；否则按实际 entries 推导，避免 put/clear 与 stats 不一致。
        return if (statsValue.count == 0 && entries.isNotEmpty()) {
            statsValue.copy(count = entries.size)
        } else {
            statsValue
        }
    }

    override suspend fun trim(maxEntries: Int, maxBytes: Long) = Unit
}
