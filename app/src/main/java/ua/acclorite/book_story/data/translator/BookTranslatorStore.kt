/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * fork 新增：按书翻译器行为的存储（DataStore 单键 JSON）。
 *
 * 与 ReaderTypographyStore 保持同一套并发模型（评审后定稿）：
 *  - 内存同步更新：滑块/开关回调后立刻生效，避免「切书/连续操作」时状态落后；
 *  - 磁盘 200ms 合并、锁外写盘、写失败只记日志；
 *  - 读取完成前的写入先排队，读完后按顺序应用，绝不先写空表覆盖磁盘。
 *
 * 没有「启用」字段：产品语义是「取词手势 OFF + 双击翻译 false = 不启用」。
 */

package ua.acclorite.book_story.data.translator

import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import ua.acclorite.book_story.core.log.logI
import ua.acclorite.book_story.core.log.logW
import ua.acclorite.book_story.data.storage.data_store.DataStore
import ua.acclorite.book_story.presentation.reader.model.ReaderLookupGesture
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "BookTranslatorStore"

private const val PERSIST_DEBOUNCE_MS = 200L

internal val BOOK_TRANSLATOR_KEY =
    stringPreferencesKey("book_translator_by_book")

@Singleton
class BookTranslatorStore @Inject constructor(
    private val dataStore: DataStore
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val lock = Any()

    private val _records = MutableStateFlow<Map<String, BookTranslatorRecord>>(emptyMap())
    val records = _records.asStateFlow()

    private val _initialized = MutableStateFlow(false)
    val initialized = _initialized.asStateFlow()

    private var loaded = false
    private var persistJob: Job? = null
    private var pendingPersist = false

    private val pendingMutations =
        mutableListOf<(Map<String, BookTranslatorRecord>) -> Map<String, BookTranslatorRecord>>()

    init {
        scope.launch {
            load()
        }
    }

    private suspend fun load() {
        val raw = dataStore.getNullableData(BOOK_TRANSLATOR_KEY)
        val decoded = raw?.let { BookTranslatorCodec.decode(it) }
        if (raw != null && decoded == null) {
            logW(TAG, "Could not decode book translator settings; continuing with empty table.")
        }

        val shouldPersist = synchronized(lock) {
            if (loaded) return@synchronized false

            var records = decoded ?: emptyMap()
            pendingMutations.forEach { records = it(records) }
            pendingMutations.clear()

            _records.value = records
            loaded = true
            val persist = pendingPersist
            pendingPersist = false
            persist
        }

        _initialized.value = true
        logI(TAG, "Loaded translator settings for ${_records.value.size} book(s).")

        if (shouldPersist) schedulePersist()
    }

    fun setLookupGesture(bookUuid: String, gesture: ReaderLookupGesture) {
        // 书实体还没加载时 uuid 是空串；写进去会在表里留下一个永远打不开的 "" 键。
        if (bookUuid.isBlank()) return
        mutate { records ->
            val record = records[bookUuid] ?: BookTranslatorRecord()
            records + (bookUuid to record.copy(lookupGesture = gesture.name))
        }
    }

    fun setDoubleClickTranslation(bookUuid: String, enabled: Boolean) {
        if (bookUuid.isBlank()) return
        mutate { records ->
            val record = records[bookUuid] ?: BookTranslatorRecord()
            records + (bookUuid to record.copy(doubleClickTranslation = enabled))
        }
    }

    /** 书被删除。 */
    fun remove(bookUuid: String) {
        if (bookUuid.isBlank()) return
        mutate { records -> records - bookUuid }
    }

    private fun mutate(
        transform: (Map<String, BookTranslatorRecord>) -> Map<String, BookTranslatorRecord>
    ) {
        val applied = synchronized(lock) {
            if (!loaded) {
                pendingMutations += transform
                pendingPersist = true
                return
            }

            val current = _records.value
            val next = transform(current)
            if (next == current) return

            _records.value = next
            true
        }

        if (applied) schedulePersist()
    }

    private fun schedulePersist() {
        synchronized(lock) {
            persistJob?.cancel()
            persistJob = scope.launch {
                delay(PERSIST_DEBOUNCE_MS)

                val snapshot = synchronized(lock) { _records.value }
                runCatching {
                    dataStore.putData(
                        BOOK_TRANSLATOR_KEY,
                        BookTranslatorCodec.encode(snapshot)
                    )
                }.onFailure {
                    logW(TAG, "Could not persist translator settings: ${it.message}")
                }
            }
        }
    }
}
