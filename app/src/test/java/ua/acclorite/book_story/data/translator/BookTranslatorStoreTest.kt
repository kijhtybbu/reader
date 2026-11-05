/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * fork 新增：按书翻译器 Store 的行为测试。
 *
 * 覆盖：默认关闭、独立写入、合并落盘、删书记录清理、坏 JSON 自愈、
 * 以及「存量里是未知手势时回落关闭」这条迁移安全线。
 */

package ua.acclorite.book_story.data.translator

import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.CompletableDeferred
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
import ua.acclorite.book_story.presentation.reader.model.ReaderLookupGesture

class BookTranslatorStoreTest {

    private val uuid = "book-uuid"

    @Test
    fun `a book without a record is off by default`() = runBlocking {
        val store = newStore()

        val values = store.records.value[uuid].resolve()

        assertEquals(ReaderLookupGesture.OFF, values.lookupGesture)
        assertFalse(values.doubleClickTranslation)
    }

    @Test
    fun `setting the gesture creates a record and persists it`() = runBlocking {
        val dataStore = FakeDataStore()
        val store = newStore(dataStore)

        store.setLookupGesture(uuid, ReaderLookupGesture.LONG_PRESS)
        store.awaitRecord { it[uuid]?.lookupGesture == ReaderLookupGesture.LONG_PRESS.name }

        assertEquals(ReaderLookupGesture.LONG_PRESS, store.records.value[uuid].resolve().lookupGesture)
        dataStore.awaitEncoded { it.contains("LONG_PRESS") }

        val reloaded = newStore(dataStore)
        assertEquals(
            ReaderLookupGesture.LONG_PRESS,
            reloaded.records.value[uuid].resolve().lookupGesture
        )
    }

    @Test
    fun `the two settings are independent`() = runBlocking {
        val store = newStore()

        store.setLookupGesture(uuid, ReaderLookupGesture.SINGLE_TAP)
        store.setDoubleClickTranslation(uuid, true)

        val values = store.records.value[uuid].resolve()
        assertEquals(ReaderLookupGesture.SINGLE_TAP, values.lookupGesture)
        assertTrue(values.doubleClickTranslation)
    }

    @Test
    fun `turning both off keeps the record and disables the translator`() = runBlocking {
        val store = newStore()
        store.setLookupGesture(uuid, ReaderLookupGesture.LONG_PRESS)
        store.setDoubleClickTranslation(uuid, true)

        store.setLookupGesture(uuid, ReaderLookupGesture.OFF)
        store.setDoubleClickTranslation(uuid, false)

        val values = store.records.value[uuid].resolve()
        assertEquals(ReaderLookupGesture.OFF, values.lookupGesture)
        assertFalse(values.doubleClickTranslation)
    }

    @Test
    fun `rapid changes are coalesced into one disk write`() = runBlocking {
        val dataStore = FakeDataStore()
        val store = newStore(dataStore)

        store.setLookupGesture(uuid, ReaderLookupGesture.SINGLE_TAP)
        store.setLookupGesture(uuid, ReaderLookupGesture.LONG_PRESS)
        store.setDoubleClickTranslation(uuid, true)

        dataStore.awaitEncoded { it.contains("LONG_PRESS") && it.contains("true") }
        delay(300)
        assertEquals(1, dataStore.putCount)
    }

    @Test
    fun `removing a book deletes its record and persists the removal`() = runBlocking {
        val dataStore = FakeDataStore()
        val store = newStore(dataStore)
        store.setLookupGesture(uuid, ReaderLookupGesture.SINGLE_TAP)
        store.awaitRecord { it.containsKey(uuid) }

        store.remove(uuid)
        store.awaitRecord { !it.containsKey(uuid) }
        dataStore.awaitEncoded { !it.contains(uuid) }

        val reloaded = newStore(dataStore)
        assertFalse(reloaded.records.value.containsKey(uuid))
    }

    @Test
    fun `corrupt stored json starts empty and the next write self-heals`() = runBlocking {
        val dataStore = FakeDataStore()
        dataStore.values[BOOK_TRANSLATOR_KEY] = "{corrupt"

        val store = newStore(dataStore)
        withTimeout(5_000) { store.initialized.first { it } }
        assertTrue(store.records.value.isEmpty())

        store.setDoubleClickTranslation(uuid, true)
        store.awaitRecord { it[uuid]?.doubleClickTranslation == true }
        dataStore.awaitEncoded { it.contains(uuid) }
    }

    @Test
    fun `mutations before the initial load are applied afterwards`() = runBlocking {
        val dataStore = FakeDataStore(loadGate = CompletableDeferred())
        val store = BookTranslatorStore(dataStore)

        // 读取还卡在 gate 上：写入必须先排队，不能先写空表
        store.setLookupGesture(uuid, ReaderLookupGesture.LONG_PRESS)
        store.setDoubleClickTranslation(uuid, true)
        assertTrue(store.records.value.isEmpty())

        dataStore.release()
        withTimeout(5_000) { store.initialized.first { it } }

        val values = store.records.value[uuid].resolve()
        assertEquals(ReaderLookupGesture.LONG_PRESS, values.lookupGesture)
        assertTrue(values.doubleClickTranslation)
        dataStore.awaitEncoded { it.contains("LONG_PRESS") && it.contains("true") }
    }

    @Test
    fun `blank book uuid is ignored`() = runBlocking {
        val store = newStore()

        store.setLookupGesture("", ReaderLookupGesture.SINGLE_TAP)
        store.setDoubleClickTranslation("", true)
        store.remove("")

        assertTrue(store.records.value.isEmpty())
    }

    @Test
    fun `an unknown stored gesture resolves to off`() = runBlocking {
        val dataStore = FakeDataStore()
        dataStore.values[BOOK_TRANSLATOR_KEY] =
            """{"$uuid": {"lookupGesture": "DOUBLE_WINK", "doubleClickTranslation": true}}"""

        val store = newStore(dataStore)

        val values = store.records.value[uuid].resolve()
        assertEquals(ReaderLookupGesture.OFF, values.lookupGesture)
        assertTrue(values.doubleClickTranslation)
    }

    @Test
    fun `a partially written record falls back to off for the missing field`() = runBlocking {
        val dataStore = FakeDataStore()
        dataStore.values[BOOK_TRANSLATOR_KEY] = """{"$uuid": {"doubleClickTranslation": true}}"""

        val store = newStore(dataStore)

        val values = store.records.value[uuid].resolve()
        assertEquals(ReaderLookupGesture.OFF, values.lookupGesture)
        assertTrue(values.doubleClickTranslation)
        assertNull(store.records.value[uuid]?.lookupGesture)
    }

    private suspend fun newStore(
        dataStore: FakeDataStore = FakeDataStore()
    ): BookTranslatorStore {
        val store = BookTranslatorStore(dataStore)
        withTimeout(5_000) { store.initialized.first { it } }
        return store
    }

    private suspend fun BookTranslatorStore.awaitRecord(
        predicate: (Map<String, BookTranslatorRecord>) -> Boolean
    ): Map<String, BookTranslatorRecord> =
        withTimeout(5_000) { records.first(predicate) }
}

@Suppress("UNCHECKED_CAST")
private class FakeDataStore(
    private val loadGate: CompletableDeferred<Unit>? = null
) : DataStore {
    val values = mutableMapOf<Preferences.Key<*>, Any?>()
    var putCount = 0
        private set

    override suspend fun <T> getNullableData(key: Preferences.Key<T>): T? {
        loadGate?.await()
        return values[key] as T?
    }

    override suspend fun <T> putData(key: Preferences.Key<T>, value: T) {
        putCount++
        values[key] = value
    }

    fun release() {
        loadGate?.complete(Unit)
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
