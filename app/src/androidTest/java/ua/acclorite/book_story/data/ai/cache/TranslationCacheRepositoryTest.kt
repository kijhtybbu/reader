/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * fork 新增：AI 结果缓存仓库的仪器测试（第二阶段 S7，设计 §7.3/§11）。
 *
 * 用内存 Room 覆盖 put/get/touch/不完整行/clear/stats/LRU 两个上限。
 * 迁移 20→21 在 `TranslationCacheMigrationTest` 里用 `MigrationTestHelper` 单独验证。
 *
 * 运行：`./gradlew :app:connectedDebugAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.class=ua.acclorite.book_story.data.ai.cache.TranslationCacheRepositoryTest`
 */

package ua.acclorite.book_story.data.ai.cache

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import ua.acclorite.book_story.data.storage.room.AppDatabase
import ua.acclorite.book_story.data.storage.room.entity.TranslationCacheEntity

@RunWith(AndroidJUnit4::class)
class TranslationCacheRepositoryTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    private lateinit var database: AppDatabase
    private lateinit var repository: TranslationCacheRepository

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        repository = TranslationCacheRepositoryImpl(database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun putThenGetReturnsPayloadAndTouchesLastAccess() = runBlocking {
        repository.put(
            key = KEY,
            kind = TranslationKind.TRANSLATION,
            bookId = 7,
            nodeId = 12,
            payload = "你好，世界。",
            model = "gpt-4o-mini",
            baseUrl = "https://api.deepseek.com/v1/",
            targetLanguage = "Simplified Chinese (zh-CN)",
            promptVersion = 3,
            now = 1_000L
        )

        val entity = requireNotNull(database.translationCacheDao.get(KEY))
        assertEquals("TRANSLATION", entity.kind)
        assertEquals("api.deepseek.com", entity.baseUrlHost)
        assertEquals(7, entity.bookId)
        assertEquals(12, entity.nodeId)
        assertEquals(3, entity.promptVersion)
        assertEquals(1_000L, entity.createdAt)
        assertEquals(1_000L, entity.lastAccessedAt)
        assertTrue(entity.complete)

        val result = requireNotNull(repository.get(KEY))
        assertEquals("你好，世界。", result.payload)
        assertEquals("gpt-4o-mini", result.model)
        assertEquals("Simplified Chinese (zh-CN)", result.targetLanguage)
        assertEquals(1_000L, result.createdAt)
        assertTrue(result.fromCache)

        // touch 只在读取时推进 lastAccessedAt，createdAt 不动（LRU 按访问时间排序）。
        val touched = requireNotNull(database.translationCacheDao.get(KEY))
        assertTrue(
            "lastAccessedAt should advance on read, was ${touched.lastAccessedAt}",
            touched.lastAccessedAt > 1_000L
        )
        assertEquals(1_000L, touched.createdAt)

        // 落库的键与键构造器算出来的一致（否则读取路径永远 miss）。
        assertEquals(
            KEY,
            translationCacheKey(
                text = TEXT,
                kind = TranslationKind.TRANSLATION,
                targetLanguage = "Simplified Chinese (zh-CN)",
                model = "gpt-4o-mini",
                baseUrl = "https://api.deepseek.com/v1/",
                promptVersion = 3
            )
        )
    }

    @Test
    fun incompleteRowsAreIgnored() = runBlocking {
        database.translationCacheDao.put(
            TranslationCacheEntity(
                cacheKey = KEY,
                bookId = 1,
                nodeId = 2,
                kind = TranslationKind.TRANSLATION.wire,
                model = "gpt-4o-mini",
                baseUrlHost = "api.openai.com",
                targetLanguage = "English (en)",
                promptVersion = 1,
                payload = "half a transla",
                complete = false,
                createdAt = 500L,
                lastAccessedAt = 500L
            )
        )

        assertNull(repository.get(KEY))
        // 被忽略的行也不该被 touch：它可能是将来「半截结果」的诊断样本。
        assertEquals(500L, requireNotNull(database.translationCacheDao.get(KEY)).lastAccessedAt)
    }

    @Test
    fun putReplacesTheSameKey() = runBlocking {
        put(key = KEY, payload = "first", now = 100L)
        put(key = KEY, payload = "second", now = 200L)

        assertEquals(1, repository.stats().count)
        val result = requireNotNull(repository.get(KEY))
        assertEquals("second", result.payload)
        assertEquals(200L, result.createdAt)
    }

    @Test
    fun clearRemovesEverything() = runBlocking {
        put(key = KEY, payload = "one", now = 100L)
        put(key = "other", payload = "two", now = 200L)
        assertEquals(2, repository.stats().count)

        repository.clear()

        assertEquals(CacheStats(count = 0, payloadBytes = 0), repository.stats())
        assertNull(repository.get(KEY))
    }

    @Test
    fun statsCountBytesNotCharacters() = runBlocking {
        val payload = "你好" // 2 characters, 6 UTF-8 bytes

        put(key = KEY, payload = payload, now = 100L)

        val stats = repository.stats()
        assertEquals(1, stats.count)
        assertEquals(payload.toByteArray(Charsets.UTF_8).size.toLong(), stats.payloadBytes)
        assertEquals(6L, stats.payloadBytes)
    }

    @Test
    fun trimEntriesKeepsTheMostRecentRows() = runBlocking {
        put(key = "old", payload = "a", now = 100L)
        put(key = "middle", payload = "b", now = 200L)
        put(key = "new", payload = "c", now = 300L)

        repository.trim(maxEntries = 2, maxBytes = Long.MAX_VALUE)

        assertEquals(2, repository.stats().count)
        assertNull(database.translationCacheDao.get("old"))
        assertNotNull(database.translationCacheDao.get("middle"))
        assertNotNull(database.translationCacheDao.get("new"))
    }

    @Test
    fun trimBytesDeletesOldestUntilUnderBudget() = runBlocking {
        put(key = "old", payload = "a".repeat(100), now = 100L)
        put(key = "middle", payload = "b".repeat(100), now = 200L)
        put(key = "new", payload = "c".repeat(100), now = 300L)

        repository.trim(maxEntries = 20_000, maxBytes = 250L)

        assertEquals(2, repository.stats().count)
        assertNull(database.translationCacheDao.get("old"))
        assertEquals(200L, repository.stats().payloadBytes)
    }

    @Test
    fun trimBytesStopsAtTheFirstRowOverBudget() = runBlocking {
        put(key = "old", payload = "a".repeat(100), now = 100L)
        put(key = "middle", payload = "b".repeat(100), now = 200L)
        put(key = "new", payload = "c".repeat(100), now = 300L)

        repository.trim(maxEntries = 20_000, maxBytes = 150L)

        assertEquals(1, repository.stats().count)
        assertNotNull(database.translationCacheDao.get("new"))
    }

    @Test
    fun trimBytesKeepsAtLeastTheNewestRow() = runBlocking {
        put(key = KEY, payload = "x".repeat(5_000), now = 100L)

        // 单行就超过预算时保留它：删掉只会让下次访问重新请求、写回、再被删。
        repository.trim(maxEntries = 20_000, maxBytes = 10L)

        assertEquals(1, repository.stats().count)
        assertNotNull(repository.get(KEY))
    }

    @Test
    fun deleteOldestDeletesExactlyTheRequestedRows() = runBlocking {
        put(key = "old", payload = "a", now = 100L)
        put(key = "middle", payload = "b", now = 200L)
        put(key = "new", payload = "c", now = 300L)

        assertEquals(2, database.translationCacheDao.deleteOldest(2))

        assertEquals(1, repository.stats().count)
        assertNotNull(database.translationCacheDao.get("new"))
    }

    @Test
    fun zeroLimitDoesNotWipeTheCache() = runBlocking {
        put(key = "old", payload = "a", now = 100L)
        put(key = "middle", payload = "b", now = 200L)
        put(key = "new", payload = "c", now = 300L)

        // `LIMIT 0` 的保留集是空集，等于把整个缓存删光；DAO 按至少 1 处理。
        assertEquals(2, database.translationCacheDao.trimToEntries(0))
        assertEquals(1, repository.stats().count)
        assertNotNull(database.translationCacheDao.get("new"))

        put(key = "middle", payload = "b", now = 200L)
        put(key = "old", payload = "a", now = 100L)

        assertEquals(1, database.translationCacheDao.deleteOldest(0))
        assertEquals(2, repository.stats().count)
        assertNull(database.translationCacheDao.get("old"))
    }

    private suspend fun put(key: String, payload: String, now: Long) {
        repository.put(
            key = key,
            kind = TranslationKind.TRANSLATION,
            bookId = 1,
            nodeId = 2,
            payload = payload,
            model = "gpt-4o-mini",
            baseUrl = "https://api.openai.com/v1/",
            targetLanguage = "English (en)",
            promptVersion = 1,
            now = now
        )
    }

    private companion object {
        const val TEXT = "Hello world."
        val KEY = translationCacheKey(
            text = TEXT,
            kind = TranslationKind.TRANSLATION,
            targetLanguage = "Simplified Chinese (zh-CN)",
            model = "gpt-4o-mini",
            baseUrl = "https://api.deepseek.com/v1/",
            promptVersion = 3
        )
    }
}
