/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * fork 新增：AI 结果缓存 DAO（第二阶段 S7，设计 §7.3）。
 *
 * 用 abstract class：`trimToBytes` 需要「先读每行大小、再分批删」的组合操作，必须在同一个事务里，
 * 而 Room 对 Kotlin 接口默认方法生成的事务包装器并不可靠（同 `BookmarkDao` 的理由）。
 */
package ua.acclorite.book_story.data.storage.room.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import ua.acclorite.book_story.data.storage.room.entity.TranslationCacheEntity

/** 一次删除最多带的键数量：SQLite 的 `IN (...)` 参数个数有上限（老版本 999），分批更稳。 */
private const val DELETE_BATCH_SIZE = 500

/** [TranslationCacheDao.entriesByRecency] 的投影：只取清理要用的两列，不把 payload 读进内存。 */
data class CachedPayloadSize(
    val cacheKey: String,
    val payloadBytes: Long
)

/** [TranslationCacheDao.stats] 的投影：条数与字节数来自同一次查询。 */
data class CacheUsage(
    val count: Int,
    val payloadBytes: Long
)

@Dao
abstract class TranslationCacheDao {

    @Query("SELECT * FROM translationcacheentity WHERE cacheKey = :cacheKey")
    abstract suspend fun get(cacheKey: String): TranslationCacheEntity?

    @Query("UPDATE translationcacheentity SET lastAccessedAt = :now WHERE cacheKey = :cacheKey")
    abstract suspend fun touch(cacheKey: String, now: Long): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun put(entity: TranslationCacheEntity)

    @Query("DELETE FROM translationcacheentity")
    abstract suspend fun clear(): Int

    /**
     * 条数与 payload 的 UTF-8 字节总量，一次查询取回。
     *
     * 分两次查（`COUNT(*)` + `SUM(...)`）时，并发的 `put`/`trim` 会让两个数字来自不同快照。
     * 字节数必须 `CAST(payload AS BLOB)`：SQLite 的 `LENGTH()` 对 TEXT 返回的是**字符数**，
     * 一段中文按字符数算只有实际体积的 1/3，200MB 的预算会被撑成 600MB。
     */
    @Query(
        """
        SELECT COUNT(*) AS count,
               COALESCE(SUM(LENGTH(CAST(payload AS BLOB))), 0) AS payloadBytes
        FROM translationcacheentity
        """
    )
    abstract suspend fun stats(): CacheUsage

    /**
     * 删除最久未访问的 [limit] 行，返回实际删除条数。
     *
     * [limit] 按至少 1 处理：`LIMIT 0` 的保留集是空集，等于把整个缓存删光，
     * 统一按至少 1 处理，避免 `LIMIT 0` 的保留集为空、把整个缓存删光（上限现在是常量，将来可能变成设置项）。
     */
    suspend fun deleteOldest(limit: Int): Int = deleteOldestRows(limit.coerceAtLeast(1))

    @Query(
        """
        DELETE FROM translationcacheentity WHERE cacheKey IN (
            SELECT cacheKey FROM translationcacheentity
            ORDER BY lastAccessedAt ASC, cacheKey ASC
            LIMIT :limit
        )
        """
    )
    abstract suspend fun deleteOldestRows(limit: Int): Int

    /** 只保留最近访问的 [limit] 行（[limit] 同样按至少 1 处理，见 [deleteOldest]），返回删除条数。 */
    suspend fun trimToEntries(limit: Int): Int = trimToEntriesRows(limit.coerceAtLeast(1))

    @Query(
        """
        DELETE FROM translationcacheentity WHERE cacheKey NOT IN (
            SELECT cacheKey FROM translationcacheentity
            ORDER BY lastAccessedAt DESC, cacheKey DESC
            LIMIT :limit
        )
        """
    )
    abstract suspend fun trimToEntriesRows(limit: Int): Int

    @Query(
        """
        SELECT cacheKey, LENGTH(CAST(payload AS BLOB)) AS payloadBytes
        FROM translationcacheentity
        ORDER BY lastAccessedAt DESC, cacheKey DESC
        """
    )
    abstract suspend fun entriesByRecency(): List<CachedPayloadSize>

    @Query("DELETE FROM translationcacheentity WHERE cacheKey IN (:cacheKeys)")
    abstract suspend fun deleteByKeys(cacheKeys: List<String>): Int

    /**
     * 从最久未访问的一端删到 payload 总量不超过 [maxBytes]，返回实际删除条数。
     *
     * 不用窗口函数（`SUM(...) OVER (ORDER BY ...)`）：不同 Android 版本的 SQLite 版本不一致，
     * 窗口函数要 3.25+，minSdk 29 上不能保证；也不用「自连接按行累加」的写法 —— 2 万行是 O(n²)。
     * 这里先一次读出 (key, 字节数) 的 2 万行投影算出切点，再分批删。
     *
     * 至少保留最新的一行：单行就超过预算时（例如超长段落的语法 JSON）把它删掉只会让下次访问
     * 重新请求、再写回、再被删，来回计费；留一行是 LRU 的常识下限。
     */
    @Transaction
    open suspend fun trimToBytes(maxBytes: Long): Int {
        val entries = entriesByRecency()
        var running = 0L
        var cutoff = entries.size
        for (index in entries.indices) {
            running += entries[index].payloadBytes
            if (running > maxBytes) {
                cutoff = index
                break
            }
        }
        cutoff = maxOf(cutoff, 1)
        if (cutoff >= entries.size) return 0

        var deleted = 0
        for (batch in entries.subList(cutoff, entries.size).chunked(DELETE_BATCH_SIZE)) {
            deleted += deleteByKeys(batch.map { it.cacheKey })
        }
        return deleted
    }
}
