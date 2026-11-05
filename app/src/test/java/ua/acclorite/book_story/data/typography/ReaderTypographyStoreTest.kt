/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * fork 新增：ReaderTypographyStore 的行为测试。
 *
 * 这些用例覆盖最容易回归的四件事：
 *  1. 第一次启用要快照全部 12 项（否则切回来会有字段悄悄跟随全局）；
 *  2. 切回全局只停用，不清记忆；再次启用恢复上次的本书值；
 *  3. 内存更新同步完成：连续滑块更新（含同一字段）保持先后顺序，
 *     reset 之后残留的回调不能复活记录；
 *  4. 磁盘写入合并：连续更新只落一次盘；坏 JSON 不当成空表直接覆盖，正常写入后自愈。
 */

package ua.acclorite.book_story.data.typography

import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ua.acclorite.book_story.data.storage.data_store.DataStore
import ua.acclorite.book_story.presentation.reader.model.ReaderTextAlignment

class ReaderTypographyStoreTest {

    private val uuid = "book-uuid"

    private val global = typographyValues(
        fontId = "global-font",
        fontThickness = 400,
        fontSize = 16,
        lineHeight = 4,
        textAlignment = ReaderTextAlignment.JUSTIFY
    )

    @Test
    fun `enabling the first time snapshots every field`() = runBlocking {
        val store = newStore()

        store.enable(uuid, global)
        val record = store.awaitRecord { it[uuid]?.enabled == true }[uuid]

        assertEquals(global.fontFamily.id, record?.fontFamilyId)
        assertEquals(global.fontThickness, record?.fontThickness)
        assertEquals(global.boldFontThickness, record?.boldFontThickness)
        assertEquals(global.italic, record?.italic)
        assertEquals(global.fontSize, record?.fontSize)
        assertEquals(global.lineHeight, record?.lineHeight)
        assertEquals(global.paragraphHeight, record?.paragraphHeight)
        assertEquals(global.letterSpacing, record?.letterSpacing)
        assertEquals(global.paragraphIndentation, record?.paragraphIndentation)
        assertEquals(global.textAlignment, record?.textAlignment)
        assertEquals(global.sidePadding, record?.sidePadding)
        assertEquals(global.verticalPadding, record?.verticalPadding)
    }

    @Test
    fun `disabling keeps the remembered values`() = runBlocking {
        val store = newStore()
        store.enable(uuid, global)
        store.update(uuid) { it.copy(fontSize = 30) }
        store.awaitRecord { it[uuid]?.fontSize == 30 }

        store.disable(uuid)
        val record = store.awaitRecord { it[uuid]?.enabled == false }[uuid]

        assertFalse(record?.enabled == true)
        assertEquals(30, record?.fontSize)
    }

    @Test
    fun `re-enabling restores the book values instead of the new global values`() = runBlocking {
        val store = newStore()
        store.enable(uuid, global)
        store.update(uuid) { it.copy(fontSize = 30, fontFamilyId = "book-font") }
        store.awaitRecord { it[uuid]?.fontSize == 30 }
        store.disable(uuid)
        store.awaitRecord { it[uuid]?.enabled == false }

        val newGlobal = global.copy(fontSize = 12, fontFamily = typographyFont("new-global"))
        store.enable(uuid, newGlobal)
        val record = store.awaitRecord { it[uuid]?.enabled == true }[uuid]

        assertEquals(30, record?.fontSize)
        assertEquals("book-font", record?.fontFamilyId)
    }

    @Test
    fun `rapid slider writes to different fields are all applied`() = runBlocking {
        val store = newStore()
        store.enable(uuid, global)

        // 内存更新是同步的，这里不等待中间结果，模拟滑块连续回调
        store.update(uuid) { it.copy(fontSize = 26) }
        store.update(uuid) { it.copy(lineHeight = 9) }
        store.update(uuid) { it.copy(textAlignment = ReaderTextAlignment.END) }

        val record = store.awaitRecord {
            it[uuid]?.fontSize == 26 &&
                    it[uuid]?.lineHeight == 9 &&
                    it[uuid]?.textAlignment == ReaderTextAlignment.END
        }[uuid]

        assertEquals(26, record?.fontSize)
        assertEquals(9, record?.lineHeight)
        assertEquals(ReaderTextAlignment.END, record?.textAlignment)
    }

    @Test
    fun `rapid slider writes to the same field keep the last value`() = runBlocking {
        val store = newStore()
        store.enable(uuid, global)

        store.update(uuid) { it.copy(fontSize = 17) }
        store.update(uuid) { it.copy(fontSize = 18) }
        store.update(uuid) { it.copy(fontSize = 19) }

        // 同步更新 + 主线程调用顺序 => 最后一次写入一定胜出，不会被 IO 线程重排
        assertEquals(19, store.records.value[uuid]?.fontSize)
    }

    @Test
    fun `update without an enabled record is ignored`() = runBlocking {
        val store = newStore()

        store.update(uuid) { it.copy(fontSize = 24) }

        assertNull(store.records.value[uuid])
    }

    @Test
    fun `update after reset cannot resurrect the record`() = runBlocking {
        val store = newStore()
        store.enable(uuid, global)

        store.reset(uuid)
        // reset 之后残留的 slider 回调
        store.update(uuid) { it.copy(fontSize = 24) }

        assertNull(store.records.value[uuid])
    }

    @Test
    fun `rapid updates are coalesced into one disk write`() = runBlocking {
        val dataStore = FakeDataStore()
        val store = newStore(dataStore)

        store.enable(uuid, global)
        store.update(uuid) { it.copy(fontSize = 17) }
        store.update(uuid) { it.copy(fontSize = 18) }
        store.update(uuid) { it.copy(fontSize = 19) }

        dataStore.awaitEncoded { it.contains("\"fontSize\":19") }
        // 再等一个合并窗口，确认没有迟到的额外写入
        delay(300)
        assertEquals(1, dataStore.putCount)
    }

    @Test
    fun `reset removes the record and persists the removal`() = runBlocking {
        val dataStore = FakeDataStore()
        val store = newStore(dataStore)
        store.enable(uuid, global)
        store.awaitRecord { it.containsKey(uuid) }

        store.reset(uuid)
        store.awaitRecord { !it.containsKey(uuid) }
        dataStore.awaitEncoded { !it.contains(uuid) }

        // 新实例从同一份存储加载，确认删除真的落盘
        val reloaded = newStore(dataStore)
        withTimeout(5_000) { reloaded.initialized.first { it } }
        assertFalse(reloaded.records.value.containsKey(uuid))
    }

    @Test
    fun `removeFontReferences clears only the matching font id`() = runBlocking {
        val store = newStore()
        store.enable(uuid, global)
        store.update(uuid) { it.copy(fontFamilyId = "user:dead") }
        store.enable("other", global)
        store.update("other") { it.copy(fontFamilyId = "user:alive") }
        store.awaitRecord {
            it[uuid]?.fontFamilyId == "user:dead" &&
                    it["other"]?.fontFamilyId == "user:alive"
        }

        store.removeFontReferences("user:dead")
        val records = store.awaitRecord { it[uuid]?.fontFamilyId == null }

        assertNull(records[uuid]?.fontFamilyId)
        assertEquals("user:alive", records["other"]?.fontFamilyId)
    }

    @Test
    fun `corrupt stored json starts empty and the next write self-heals`() = runBlocking {
        val dataStore = FakeDataStore()
        dataStore.values[BOOK_TYPOGRAPHY_KEY] = "{corrupt"

        val store = newStore(dataStore)
        withTimeout(5_000) { store.initialized.first { it } }
        assertTrue(store.records.value.isEmpty())

        store.enable(uuid, global)
        store.awaitRecord { it[uuid]?.enabled == true }
        dataStore.awaitEncoded { it.contains(uuid) && it.contains("fontSize") }
    }

    private suspend fun newStore(
        dataStore: FakeDataStore = FakeDataStore()
    ): ReaderTypographyStore {
        val store = ReaderTypographyStore(dataStore)
        withTimeout(5_000) { store.initialized.first { it } }
        return store
    }

    private suspend fun ReaderTypographyStore.awaitRecord(
        predicate: (Map<String, BookTypographyRecord>) -> Boolean
    ): Map<String, BookTypographyRecord> =
        withTimeout(5_000) { records.first(predicate) }
}

@Suppress("UNCHECKED_CAST")
private class FakeDataStore : DataStore {
    val values = mutableMapOf<Preferences.Key<*>, Any?>()
    var putCount = 0
        private set

    override suspend fun <T> getNullableData(key: Preferences.Key<T>): T? {
        return values[key] as T?
    }

    override suspend fun <T> putData(key: Preferences.Key<T>, value: T) {
        putCount++
        values[key] = value
    }
}

private suspend fun FakeDataStore.awaitEncoded(predicate: (String) -> Boolean) {
    withTimeout(5_000) {
        while (true) {
            val raw = values.values.filterIsInstance<String>().firstOrNull()
            if (raw != null && predicate(raw)) return@withTimeout
            delay(10)
        }
    }
}
