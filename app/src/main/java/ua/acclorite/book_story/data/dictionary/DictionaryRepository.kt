/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * fork 新增：取词查询仓库（第二阶段 S4）。
 *
 * 设计 §4.3 的落点：内置 ECDICT + 已启用的导入词典，一次查询合并排序；正文按命中地址
 * 单独取。三件事在这里收口：
 *
 *  1. **每引擎一把锁**。上游 `DictionaryEngine` 并不是线程安全的（`StarDictIndex` 是普通
 *     `HashMap`，`DictPlainReader`/`DictZipReader` 的 seek+read 非原子），S3 的
 *     `DictionaryRegistry.openBuiltIn()` 因此只交出裸引擎。锁按词典 id 放在进程级 map 里，
 *     即使将来出现多个仓库实例也仍然串行。注意这只锁住**仓库自己**的调用 ——
 *     `DictionaryRegistry.openBuiltIn()` 本身仍是裸引擎；S3 留下的「锁由调用方负责」这条接缝
 *     是靠「S5 起取词路径只走本仓库、`openBuiltIn()` 的唯一调用点就在 `engineFor`」关掉的。
 *  2. **引擎 LRU**。内置实例归 [DictionaryRegistry] 所有（这里只借不关）；导入词典最多缓存
 *     2 个实例，超出的关闭，删除/禁用后在下一次查询时关闭并丢弃。关闭前先拿该引擎自己的锁，
 *     否则可能关掉一个并发 `lookup`/`article` 正在读的 reader。
 *  3. **排序**。优先词典（[activeDictionaryId]）先查，其余按目录顺序；精确 > 前缀 > 模糊；
 *     只要有任何精确/前缀结果，模糊结果整批丢弃（设计 §4.3）。同级之间保持词典目录顺序
 *     （[DictionaryCatalog] 的顺序），不在同一级里再按名字打乱。
 *
 * 构造参数是为了单测能注入假引擎：默认值走 [DictionaryCatalog] 与 `DictionaryEngines`。
 * 进程内只有一个实例（`AppModule.provideDictionaryRepository` 是 `@Singleton`）；多实例虽然
 * 不会破坏线程安全（锁是进程级的），但会各自缓存一份导入词典实例。
 */

package ua.acclorite.book_story.data.dictionary

import com.qiuminal.juicedict.engine.DictHit
import com.qiuminal.juicedict.engine.DictionaryEngine
import com.qiuminal.juicedict.engine.DictionaryEngines
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** 一次查询命中的词条。[rank] 见 [DictionaryHit.RANK_EXACT] 等常量。 */
data class DictionaryHit(
    val dictId: String,
    val dictName: String,
    val word: String,
    val offset: Long,
    val size: Int,
    val preview: String,
    val rank: Int
) {
    companion object {
        const val RANK_EXACT = 0
        const val RANK_PREFIX = 1
        const val RANK_FUZZY = 2
    }
}

class DictionaryRepository(
    private val dictionaries: () -> List<InstalledDictionary> = {
        DictionaryCatalog.dictionaries.value
    },
    /**
     * 「优先词典」id（`SettingsManager.activeDictionaryId`），S5 构造仓库时注入。
     * null 表示没有偏好：直接按目录顺序查。
     */
    private val activeDictionaryId: () -> String? = { null },
    private val opener: (InstalledDictionary) -> DictionaryEngine? = ::openDictionaryEngine
) {

    private val cacheLock = Mutex()

    /** accessOrder = true：迭代顺序即 LRU 顺序，[engineFor] 从头部淘汰最久未用的实例。 */
    private val importedEngines = LinkedHashMap<String, DictionaryEngine>(4, 0.75f, true)

    /**
     * 查询 [query]。[limit] 是最终条数上限，同时也是每个引擎的候选上限。
     *
     * 查询顺序由 [orderedForLookup] 决定：优先词典先查、其余按目录顺序；rank 仍然优先于
     * 词典顺序（精确命中在别的词典里也会排到前面）。
     *
     * 返回的 `preview` 只对最终入选的条目取正文（最多 [limit] 次 article 读取），
     * 中间候选不带 preview —— 否则 3 部词典 × 5 个候选就是 15 次正文解压。
     */
    suspend fun lookup(query: String, limit: Int = 5): List<DictionaryHit> {
        val q = query.trim()
        if (q.isEmpty() || limit <= 0) return emptyList()

        val ranked = ArrayList<RankedHit>()
        for (dictionary in orderedForLookup(dictionaries(), activeDictionaryId())) {
            if (!dictionary.builtIn && !dictionary.enabled) continue
            val engine = engineFor(dictionary) ?: continue
            val hits = try {
                withContext(Dispatchers.IO) {
                    lockFor(dictionary.id).withLock { engine.lookupSmart(q, limit) }
                }
            } catch (e: CancellationException) {
                // 取消不是「查不到」：吞掉它会让已取消的取词把「未找到」写回 UI（S5 的
                // 卡片会在关闭后又自己弹出来）。其它异常仍然降级成空结果。
                throw e
            } catch (e: Exception) {
                emptyList()
            }
            for (hit in hits) {
                ranked += RankedHit(dictionary, hit, rankOf(hit.word, q))
            }
        }

        val strong = ranked.filter { it.rank != DictionaryHit.RANK_FUZZY }
        val selected = (if (strong.isNotEmpty()) strong else ranked)
            // sortedBy 是稳定排序：同一级内保持查询顺序（优先词典在前，其余按目录顺序）。
            .sortedBy { it.rank }
            .take(limit)

        val result = ArrayList<DictionaryHit>(selected.size)
        for (item in selected) {
            result += DictionaryHit(
                dictId = item.dictionary.id,
                dictName = item.dictionary.name,
                word = item.hit.word,
                offset = item.hit.offset,
                size = item.hit.size,
                preview = previewOf(item),
                rank = item.rank
            )
        }
        return result
    }

    /** 取 [hit] 的正文 HTML；词典已删除/禁用、[hit] 不属于该词典或读取失败时 null。 */
    suspend fun article(dictId: String, hit: DictionaryHit): String? {
        // 传错对（比如列表里另一部词典的命中）会按本词典的偏移去读，读出来是垃圾字节。
        if (hit.dictId != dictId) return null
        val dictionary = dictionaries().firstOrNull { it.id == dictId } ?: return null
        if (!dictionary.builtIn && !dictionary.enabled) return null
        val engine = engineFor(dictionary) ?: return null

        return try {
            withContext(Dispatchers.IO) {
                lockFor(dictId).withLock {
                    engine.article(DictHit(hit.word, hit.offset, hit.size)).toHtml()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun previewOf(item: RankedHit): String {
        val engine = engineFor(item.dictionary) ?: return ""
        return try {
            withContext(Dispatchers.IO) {
                lockFor(item.dictionary.id).withLock {
                    engine.article(DictHit(item.hit.word, item.hit.offset, item.hit.size))
                        .preview(PREVIEW_CHARS)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ""
        }
    }

    /**
     * 内置引擎每次向 [DictionaryRegistry] 现取：注册表可能因为重装而换掉实例，
     * 缓存旧引用会让后续查询打在一个已经 close 的引擎上。导入词典按 LRU 缓存。
     */
    private suspend fun engineFor(dictionary: InstalledDictionary): DictionaryEngine? {
        if (dictionary.builtIn) return DictionaryRegistry.openBuiltIn()

        return cacheLock.withLock {
            evictUnavailable()
            importedEngines[dictionary.id]?.let { return@withLock it }

            val engine = withContext(Dispatchers.IO) { opener(dictionary) } ?: return@withLock null
            importedEngines[dictionary.id] = engine
            while (importedEngines.size > MAX_IMPORTED_ENGINES) {
                val eldest = importedEngines.entries.iterator().next()
                importedEngines.remove(eldest.key)
                closeEngine(eldest.key, eldest.value)
            }
            engine
        }
    }

    /** 词典被删除或禁用后，下一次查询顺手关掉它的引擎（最多 2 个实例，不必订阅目录变化）。 */
    private suspend fun evictUnavailable() {
        val available = dictionaries().associateBy { it.id }
        val iterator = importedEngines.entries.iterator()
        while (iterator.hasNext()) {
            val (id, engine) = iterator.next()
            val current = available[id]
            if (current == null || !current.enabled) {
                iterator.remove()
                closeEngine(id, engine)
            }
        }
    }

    /**
     * 关闭前先拿该引擎自己的锁：并发的 `lookup`/`article` 正在用它时等它退出，
     * 不会让一次关闭把别人的读取变成异常（再被吞成空结果）。
     */
    private suspend fun closeEngine(id: String, engine: DictionaryEngine) {
        lockFor(id).withLock { runCatching { engine.close() } }
    }

    private data class RankedHit(
        val dictionary: InstalledDictionary,
        val hit: DictHit,
        val rank: Int
    )

    private companion object {
        const val PREVIEW_CHARS = 200
        const val MAX_IMPORTED_ENGINES = 2

        /**
         * 进程级的每引擎锁。键是词典 id（内置是 [DictionaryCatalog.BUILT_IN_ID]）。
         * `ConcurrentHashMap` 的 `computeIfAbsent` 保证同一个 id 只生成一把锁。
         */
        val engineLocks = ConcurrentHashMap<String, Mutex>()

        fun lockFor(dictionaryId: String): Mutex =
            engineLocks.computeIfAbsent(dictionaryId) { Mutex() }

        /**
         * 大小写不敏感：引擎的前缀搜索本身就是大小写不敏感的（`Apple` / `apple`），
         * 把 `apple` 查到的 `Apple` 判成「模糊」会让精确命中排到别的词典后面。
         */
        fun rankOf(word: String, query: String): Int = when {
            word.equals(query, ignoreCase = true) -> DictionaryHit.RANK_EXACT
            word.startsWith(query, ignoreCase = true) -> DictionaryHit.RANK_PREFIX
            else -> DictionaryHit.RANK_FUZZY
        }
    }
}

/**
 * 优先词典排在最前，其余保持目录顺序。同一 rank 内 `sortedBy` 是稳定排序，所以「优先词典的
 * 命中排在其它词典前面」不需要在排序里再加一层；rank 仍然优先于词典顺序。
 */
internal fun orderedForLookup(
    dictionaries: List<InstalledDictionary>,
    activeId: String?
): List<InstalledDictionary> {
    val active = dictionaries.firstOrNull { it.id == activeId } ?: return dictionaries
    return buildList(dictionaries.size) {
        add(active)
        dictionaries.forEach { if (it.id != active.id) add(it) }
    }
}

internal fun openDictionaryEngine(dictionary: InstalledDictionary): DictionaryEngine? =
    when (dictionary.type) {
        DictionaryType.STARDICT -> DictionaryEngines.openStarDict(
            id = dictionary.id,
            dir = dictionary.directory,
            baseName = dictionary.baseName
        )

        DictionaryType.MDX -> try {
            DictionaryEngines.openMdx(
                id = dictionary.id,
                mdxFile = File(dictionary.directory, "${dictionary.baseName}.mdx"),
                // 资源包（`.mdd`）不归查询引擎：句柄由 `DictionaryResourceStore` 独立持有
                // （设计 §2.2「资源句柄与查询引擎分离」），`MdxDictionary.resource()` 全仓无调用者。
                // 传进来只会让每次打开词典都多解析几个 mdd 的 key index、常驻几个 fd。
                mddFiles = emptyList(),
                title = dictionary.name,
                description = ""
            )
        } catch (e: Exception) {
            null
        }
    }
