/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * fork 新增：AI 结果缓存仓库（第二阶段 S7，设计 §7.3）。
 *
 * 只缓存**完整**结果：`put` 恒写 `complete = true`，读取侧忽略 `complete = false` 的行。
 * 流式中途取消/失败由调用方（S8 的 `TranslationModel`）决定不调用 `put`，所以库里不会出现半截译文。
 *
 * 读写约定：
 * - 命中时 `touch(lastAccessedAt)`，LRU 清理按它排序（不用 `createdAt`：老结果被反复读取时不该先被删）。
 * - `put` 用 `NonCancellable`：用户关掉对话框（协程被取消）时，已经拿到的完整结果仍然写进去，
 *   否则「刚看完的译文」下次还要再计费请求一次。
 * - 「重新生成」不在仓库里实现：重新请求后用 `put` 覆盖同一键即可（`put` 是 `REPLACE`）；
 *   仓库只提供 `clear()` 全清，没有按单键删除的 API。
 */

package ua.acclorite.book_story.data.ai.cache

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import ua.acclorite.book_story.data.storage.room.AppDatabase
import ua.acclorite.book_story.data.storage.room.entity.TranslationCacheEntity
import javax.inject.Inject

/** 默认上限（设计 §7.3）：2 万条或 200MB，超过就按 LRU 清理。 */
const val DEFAULT_CACHE_MAX_ENTRIES = 20_000
const val DEFAULT_CACHE_MAX_BYTES = 200L * 1024 * 1024

/** 缓存的占用情况，设置页直接显示这两项。 */
data class CacheStats(
    val count: Int,
    val payloadBytes: Long
)

/**
 * 一次缓存命中。
 *
 * [fromCache] 恒为 `true`：这个类型只由仓库的读取路径产生，网络路径的结果由上层自己组装
 * （`fromCache = false`），S8 的 UI 靠它区分「缓存命中」与「刚请求到」。
 */
data class CachedAiResult(
    val payload: String,
    val model: String,
    val targetLanguage: String,
    val createdAt: Long,
    val fromCache: Boolean = true
)

interface TranslationCacheRepository {

    /** 命中返回结果并 touch；未命中、或行标记为不完整时返回 null。 */
    suspend fun get(key: String): CachedAiResult?

    /** 写入一条完整结果，覆盖同键旧值。[now] 同时写进 `createdAt` 与 `lastAccessedAt`。 */
    suspend fun put(
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
    )

    /** 清空全部缓存（设置页的「清除翻译缓存」）。 */
    suspend fun clear()

    suspend fun stats(): CacheStats

    /** 按 LRU 清理到两个上限之内；[maxEntries] 先裁条数，再按字节裁。 */
    suspend fun trim(
        maxEntries: Int = DEFAULT_CACHE_MAX_ENTRIES,
        maxBytes: Long = DEFAULT_CACHE_MAX_BYTES
    )
}

class TranslationCacheRepositoryImpl @Inject constructor(
    private val database: AppDatabase
) : TranslationCacheRepository {

    /** 现取 DAO：`AppDatabase` 是懒打开的，构造期访问 DAO 会在数据库还没打开时就初始化它。 */
    private val dao get() = database.translationCacheDao

    override suspend fun get(key: String): CachedAiResult? {
        val entity = dao.get(key) ?: return null
        if (!entity.complete) return null

        dao.touch(key, System.currentTimeMillis())
        return CachedAiResult(
            payload = entity.payload,
            model = entity.model,
            targetLanguage = entity.targetLanguage,
            createdAt = entity.createdAt
        )
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
        val entity = TranslationCacheEntity(
            cacheKey = key,
            bookId = bookId,
            nodeId = nodeId,
            kind = kind.wire,
            model = model,
            baseUrlHost = translationBaseUrlHost(baseUrl),
            targetLanguage = targetLanguage,
            promptVersion = promptVersion,
            payload = payload,
            complete = true,
            createdAt = now,
            lastAccessedAt = now
        )
        withContext(NonCancellable) {
            dao.put(entity)
            // fork: 写入后立刻按 LRU 裁回默认上限，缓存才不会无限增长。
            // 清理失败（例如数据库被关闭）不该让已经拿到手的完整结果丢掉，所以吞掉异常。
            runCatching { trim() }
        }
    }

    override suspend fun clear() {
        dao.clear()
    }

    override suspend fun stats(): CacheStats {
        val usage = dao.stats()
        return CacheStats(count = usage.count, payloadBytes = usage.payloadBytes)
    }

    override suspend fun trim(maxEntries: Int, maxBytes: Long) {
        dao.trimToEntries(maxEntries)
        dao.trimToBytes(maxBytes)
    }
}
