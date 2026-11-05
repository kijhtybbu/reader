/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * fork 新增：按书排版记录的存储（DataStore 单键 JSON）。
 *
 * 并发模型（review 后重写）：
 *  - 内存更新必须同步完成。`enable/disable/reset/update` 在调用线程上直接改 StateFlow，
 *    否则「点切换 → 下一次滑块事件」之间会有一段状态还没更新的窗口，事件会被写到错误
 *    的作用域（全局 vs 本书），reset 后还可能把刚删掉的记录复活。
 *  - 磁盘写入异步且合并。整张表是一个 JSON 字符串，滑块按触摸事件频率回调，
 *    每个事件都写一次会把所有书的记录重写一遍；所以只在停止操作 200ms 后写最新快照。
 *  - 写盘不持锁。快照在锁内取，I/O 在锁外做，避免磁盘慢时阻塞 UI 回调。
 *  - 读取完成前到达的写入先排队，读完再按顺序应用到内存，绝不先写空表覆盖磁盘。
 */

package ua.acclorite.book_story.data.typography

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
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "ReaderTypographyStore"

/**
 * 持久化合并窗口。内存状态立即更新（UI 要跟手），磁盘只在停止操作后写一次。
 */
private const val PERSIST_DEBOUNCE_MS = 200L

internal val BOOK_TYPOGRAPHY_KEY =
    stringPreferencesKey("reader_typography_by_book")

@Singleton
class ReaderTypographyStore @Inject constructor(
    private val dataStore: DataStore
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * 保护 `_records`、`loaded`、`pendingMutations`、`persistJob`。
     * 只锁内存字段，绝不在锁内做磁盘 I/O。
     */
    private val lock = Any()

    private val _records = MutableStateFlow<Map<String, BookTypographyRecord>>(emptyMap())
    val records = _records.asStateFlow()

    private val _initialized = MutableStateFlow(false)
    val initialized = _initialized.asStateFlow()

    private var loaded = false
    private var persistJob: Job? = null
    private var pendingPersist = false

    /** 读取完成前到达的写入，按到达顺序在 load 后应用。 */
    private val pendingMutations =
        mutableListOf<(Map<String, BookTypographyRecord>) -> Map<String, BookTypographyRecord>>()

    init {
        scope.launch {
            load()
        }
    }

    private suspend fun load() {
        val raw = dataStore.getNullableData(BOOK_TYPOGRAPHY_KEY)
        val decoded = raw?.let { ReaderTypographyCodec.decode(it) }
        if (raw != null && decoded == null) {
            // 坏数据不当成「没有设置」清空：保留原始字符串，下次正常写入才覆盖。
            logW(TAG, "Could not decode book typography; continuing with empty table.")
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
        logI(TAG, "Loaded book typography for ${_records.value.size} book(s).")

        if (shouldPersist) schedulePersist()
    }

    /**
     * 打开本书自定义。已有记录（哪怕当前 disabled）直接复用记忆值；
     * 第一次打开则用传入的全局快照补齐全部字段 —— 整组覆盖的语义要求完整快照。
     */
    fun enable(bookUuid: String, snapshot: ReaderTypographyValues) {
        mutate { records ->
            val record = records[bookUuid]?.copy(enabled = true)
                ?: snapshot.toRecord(enabled = true)
            records + (bookUuid to record)
        }
    }

    /** 切回全局：保留记录（记忆），只是不再生效。 */
    fun disable(bookUuid: String) {
        mutate { records ->
            val record = records[bookUuid] ?: return@mutate records
            records + (bookUuid to record.copy(enabled = false))
        }
    }

    /**
     * 修改单项（或整组）。
     *
     * 只接受已存在的、已启用的记录：记录不存在或已停用时忽略写入。
     * 这样 `reset` 之后残留的 slider 回调不会凭空创建一条只填了一个字段的记录
     * （那会违反「第一次启用必须 12 项完整快照」的约定），也不会把书切回本书模式。
     */
    fun update(
        bookUuid: String,
        transform: (BookTypographyRecord) -> BookTypographyRecord
    ) {
        mutate { records ->
            val existing = records[bookUuid] ?: return@mutate records
            if (!existing.enabled) return@mutate records
            records + (bookUuid to transform(existing))
        }
    }

    /** 恢复全局默认：删除整条记录。 */
    fun reset(bookUuid: String) {
        mutate { records -> records - bookUuid }
    }

    /** 书被删除。 */
    fun remove(bookUuid: String) {
        mutate { records -> records - bookUuid }
    }

    /**
     * 用户字体被删除后清理引用。不清理的话本书记录仍指向死 id：解析会回落全局字体，
     * 但差异标记会一直亮着（记录 id 与全局 id 不同），与实际观感不一致。
     */
    fun removeFontReferences(fontId: String) {
        mutate { records ->
            var changed = false
            val next = records.mapValues { (_, record) ->
                if (record.fontFamilyId == fontId) {
                    changed = true
                    record.copy(fontFamilyId = null)
                } else {
                    record
                }
            }
            if (changed) next else records
        }
    }

    /**
     * 同步应用一次内存变更，然后安排合并落盘。
     * 调用方都在主线程（UI 回调），同步更新同时保证了同一字段连续拖动的先后顺序。
     */
    private fun mutate(
        transform: (Map<String, BookTypographyRecord>) -> Map<String, BookTypographyRecord>
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

    /**
     * 合并写：取消上一次待写任务，200ms 后用「当前内存表」落盘。
     *
     * 写盘在锁外进行；写的是执行时的 `_records.value`，所以合并窗口内即使又有新更新，
     * 最终落盘的也是最新状态。写失败只记日志，不让磁盘错误拖垮阅读页。
     */
    private fun schedulePersist() {
        synchronized(lock) {
            persistJob?.cancel()
            persistJob = scope.launch {
                delay(PERSIST_DEBOUNCE_MS)

                val snapshot = synchronized(lock) { _records.value }
                runCatching {
                    dataStore.putData(
                        BOOK_TYPOGRAPHY_KEY,
                        ReaderTypographyCodec.encode(snapshot)
                    )
                }.onFailure {
                    logW(TAG, "Could not persist book typography: ${it.message}")
                }
            }
        }
    }
}
