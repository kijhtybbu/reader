/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * fork 新增：取词仓库的回归（第二阶段 S4）。
 *
 * 设计 §4.3 的四条规则在这里被钉住：
 *  - 优先词典先查，其余按目录顺序；
 *  - 精确 > 前缀 > 模糊，且只要出现精确/前缀结果就整批丢掉模糊结果；
 *  - 同级之间按词典目录顺序（`sortedBy` 稳定排序）；
 *  - 每部引擎一把锁：并发查询不会同时进入同一个 `DictionaryEngine`。
 * 最后一条用真实 StarDict fixture 走一遍默认 opener，证明「目录 → 引擎 → 命中 → 正文」
 * 这条链在 JVM 上完整可用（不依赖 Android 上下文）。
 */

package ua.acclorite.book_story.data.dictionary

import com.qiuminal.juicedict.engine.Article
import com.qiuminal.juicedict.engine.ArticleSection
import com.qiuminal.juicedict.engine.DictHit
import com.qiuminal.juicedict.engine.DictionaryEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger

class DictionaryRepositoryTest {

    private class FakeEngine(
        override val id: String,
        private val answer: (String) -> List<String>
    ) : DictionaryEngine {

        override val wordCount: Int = 10

        private val inFlight = AtomicInteger()
        val maxInFlight = AtomicInteger()

        @Volatile
        var closed = false

        @Volatile
        var delayMillis = 0L

        override fun lookupSmart(query: String, limit: Int): List<DictHit> {
            val current = inFlight.incrementAndGet()
            maxInFlight.updateAndGet { maxOf(it, current) }
            try {
                if (delayMillis > 0) Thread.sleep(delayMillis)
                return answer(query).take(limit).mapIndexed { index, word ->
                    DictHit(word, index.toLong(), word.length)
                }
            } finally {
                inFlight.decrementAndGet()
            }
        }

        override fun article(hit: DictHit): Article {
            val html = "<b>${hit.word}</b>"
            return Article(
                hit.word,
                "",
                listOf(ArticleSection('h', html.toByteArray(StandardCharsets.UTF_8), html))
            )
        }

        override fun close() {
            closed = true
        }
    }

    private fun dictionary(
        id: String,
        enabled: Boolean = true,
        type: DictionaryType = DictionaryType.MDX
    ) = InstalledDictionary(
        id = id,
        type = type,
        name = "Dictionary $id",
        baseName = id,
        directory = File("/nonexistent/$id"),
        wordCount = 10,
        sizeBytes = 100,
        enabled = enabled,
        importedAt = 1L,
        builtIn = false
    )

    private fun repositoryWith(
        vararg engines: FakeEngine,
        enabled: (String) -> Boolean = { true }
    ): Pair<DictionaryRepository, MutableList<String>> {
        val byId = engines.associateBy { it.id }
        val opened = mutableListOf<String>()
        val repository = DictionaryRepository(
            dictionaries = { engines.map { dictionary(it.id, enabled(it.id)) } },
            opener = { dictionary ->
                opened += dictionary.id
                byId[dictionary.id]
            }
        )
        return repository to opened
    }

    @Test
    fun `exact hits drop fuzzy hits from other dictionaries`() = runTest {
        val fuzzy = FakeEngine("a") { listOf("helo") }
        val exact = FakeEngine("b") { listOf("hello") }
        val (repository, _) = repositoryWith(fuzzy, exact)

        val hits = repository.lookup("hello")

        assertEquals(listOf("hello"), hits.map { it.word })
        assertEquals(DictionaryHit.RANK_EXACT, hits.single().rank)
        assertEquals("b", hits.single().dictId)
    }

    @Test
    fun `prefix hits drop fuzzy hits from other dictionaries`() = runTest {
        val fuzzy = FakeEngine("a") { listOf("helo") }
        val prefix = FakeEngine("b") { listOf("hellos") }
        val (repository, _) = repositoryWith(fuzzy, prefix)

        val hits = repository.lookup("hello")

        assertEquals(listOf("hellos"), hits.map { it.word })
        assertEquals(DictionaryHit.RANK_PREFIX, hits.single().rank)
    }

    @Test
    fun `results are ordered by rank then catalog order`() = runTest {
        val first = FakeEngine("a") { listOf("hellos", "hello") }
        val second = FakeEngine("b") { listOf("hellos") }
        val (repository, _) = repositoryWith(first, second)

        val hits = repository.lookup("hello")

        assertEquals(listOf("hello", "hellos", "hellos"), hits.map { it.word })
        assertEquals(listOf("a", "a", "b"), hits.map { it.dictId })
        assertEquals(
            listOf(
                DictionaryHit.RANK_EXACT,
                DictionaryHit.RANK_PREFIX,
                DictionaryHit.RANK_PREFIX
            ),
            hits.map { it.rank }
        )
    }

    @Test
    fun `fuzzy results are kept when nothing better exists`() = runTest {
        val fuzzy = FakeEngine("a") { listOf("helo") }
        val empty = FakeEngine("b") { emptyList() }
        val (repository, _) = repositoryWith(fuzzy, empty)

        val hits = repository.lookup("hello")

        assertEquals(listOf("helo"), hits.map { it.word })
        assertEquals(DictionaryHit.RANK_FUZZY, hits.single().rank)
    }

    @Test
    fun `limit is applied across dictionaries`() = runTest {
        val first = FakeEngine("a") { listOf("hello1", "hello2") }
        val second = FakeEngine("b") { listOf("hello3") }
        val (repository, _) = repositoryWith(first, second)

        val hits = repository.lookup("hello", limit = 2)

        assertEquals(listOf("hello1", "hello2"), hits.map { it.word })
    }

    @Test
    fun `disabled dictionaries are neither opened nor queried`() = runTest {
        val engine = FakeEngine("a") { listOf("hello") }
        val (repository, opened) = repositoryWith(engine, enabled = { false })

        assertTrue(repository.lookup("hello").isEmpty())
        assertTrue(opened.isEmpty())
    }

    @Test
    fun `enabled engines are reused across lookups`() = runTest {
        val first = FakeEngine("a") { listOf("hello") }
        val second = FakeEngine("b") { listOf("hello") }
        val (repository, opened) = repositoryWith(first, second)

        repository.lookup("hello")
        repository.lookup("hello")

        assertEquals(listOf("a", "b"), opened)
    }

    @Test
    fun `at most two imported engines are cached and the oldest is closed`() = runTest {
        val created = mutableMapOf<String, MutableList<FakeEngine>>()
        val repository = DictionaryRepository(
            dictionaries = { listOf(dictionary("a"), dictionary("b"), dictionary("c")) },
            opener = { dictionary ->
                FakeEngine(dictionary.id) { emptyList() }.also {
                    created.getOrPut(dictionary.id) { mutableListOf() } += it
                }
            }
        )

        repository.lookup("hello")

        assertEquals(1, created["a"]?.size)
        assertTrue("oldest engine must be evicted and closed", created["a"]!!.single().closed)
        assertFalse(created["b"]!!.single().closed)
        assertFalse(created["c"]!!.single().closed)
    }

    @Test
    fun `article renders the html of the hit`() = runTest {
        val engine = FakeEngine("a") { listOf("hello") }
        val (repository, _) = repositoryWith(engine)

        val hit = repository.lookup("hello").single()
        val html = repository.article(hit.dictId, hit)

        assertTrue(html != null && html.contains("<b>hello</b>"))
    }

    @Test
    fun `article returns null for an unknown dictionary`() = runTest {
        val engine = FakeEngine("a") { listOf("hello") }
        val (repository, _) = repositoryWith(engine)
        val hit = repository.lookup("hello").single()

        assertTrue(repository.article("missing", hit) == null)
    }

    @Test
    fun `concurrent lookups never enter one engine at the same time`() = runTest {
        val engine = FakeEngine("a") { listOf("hello") }.apply { delayMillis = 2 }
        val (repository, _) = repositoryWith(engine)

        coroutineScope {
            (1..8).map { async(Dispatchers.Default) { repository.lookup("hello") } }.awaitAll()
        }

        assertEquals(1, engine.maxInFlight.get())
    }

    @Test
    fun `default opener queries a real StarDict fixture`() = runTest {
        withTempDirectory { directory ->
            writeStarDict(
                directory,
                "fixture",
                listOf(
                    "hello" to "<b>hello</b> 你好。",
                    "helium" to "<b>helium</b> 氦。"
                )
            )
            val dictionary = InstalledDictionary(
                id = "fixture",
                type = DictionaryType.STARDICT,
                name = "Fixture",
                baseName = "fixture",
                directory = directory,
                wordCount = 2,
                sizeBytes = 64,
                enabled = true,
                importedAt = 1L,
                builtIn = false
            )
            val repository = DictionaryRepository(dictionaries = { listOf(dictionary) })

            val hits = repository.lookup("hello")

            assertEquals("hello", hits.first().word)
            assertEquals(DictionaryHit.RANK_EXACT, hits.first().rank)
            assertTrue(hits.first().preview.contains("hello"))
            val html = repository.article("fixture", hits.first())
            assertTrue(html != null && html.contains("<b>hello</b>"))
        }
    }

    @Test
    fun `the preferred dictionary is queried first at the same rank`() = runTest {
        val first = FakeEngine("a") { listOf("hellos") }
        val second = FakeEngine("b") { listOf("hellos") }
        val engines = mapOf("a" to first, "b" to second)
        val repository = DictionaryRepository(
            dictionaries = { engines.keys.map { dictionary(it) } },
            activeDictionaryId = { "b" },
            opener = { engines[it.id] }
        )

        val hits = repository.lookup("hello")

        assertEquals(listOf("b", "a"), hits.map { it.dictId })
        assertEquals(DictionaryHit.RANK_PREFIX, hits.first().rank)
    }

    @Test
    fun `rank still wins over the preferred dictionary`() = runTest {
        val preferred = FakeEngine("a") { listOf("helo") }
        val other = FakeEngine("b") { listOf("hello") }
        val engines = mapOf("a" to preferred, "b" to other)
        val repository = DictionaryRepository(
            dictionaries = { engines.keys.map { dictionary(it) } },
            activeDictionaryId = { "a" },
            opener = { engines[it.id] }
        )

        val hits = repository.lookup("hello")

        assertEquals(listOf("hello"), hits.map { it.word })
        assertEquals("b", hits.single().dictId)
    }

    @Test
    fun `article returns null when the hit belongs to another dictionary`() = runTest {
        val engine = FakeEngine("a") { listOf("hello") }
        val (repository, _) = repositoryWith(engine, FakeEngine("b") { emptyList() })
        val hit = repository.lookup("hello").single()

        assertTrue(repository.article("b", hit) == null)
        assertTrue(repository.article(hit.dictId, hit) != null)
    }

    @Test
    fun `engines of disabled dictionaries are closed on the next lookup`() = runTest {
        val enabled = mutableSetOf("a", "b")
        val engines = mapOf(
            "a" to FakeEngine("a") { emptyList() },
            "b" to FakeEngine("b") { emptyList() }
        )
        val repository = DictionaryRepository(
            dictionaries = { engines.keys.map { dictionary(it, enabled = it in enabled) } },
            opener = { engines[it.id] }
        )

        repository.lookup("hello")
        enabled.remove("a")
        repository.lookup("hello")

        assertTrue("a disabled dictionary must not keep its engine open", engines.getValue("a").closed)
        assertFalse(engines.getValue("b").closed)
    }

    // ---- StarDict fixture -----------------------------------------------------

    /** 临时目录必须在 `finally` 里递归删掉：`deleteOnExit()` 对非空目录不生效。 */
    private suspend fun withTempDirectory(block: suspend (File) -> Unit) {
        val dir = Files.createTempDirectory("dictionary-repository").toFile()
        try {
            block(dir)
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun writeIntBE(out: ByteArrayOutputStream, value: Int) {
        out.write((value ushr 24) and 0xff)
        out.write((value ushr 16) and 0xff)
        out.write((value ushr 8) and 0xff)
        out.write(value and 0xff)
    }

    private fun writeStarDict(dir: File, base: String, entries: List<Pair<String, String>>) {
        val dict = ByteArrayOutputStream()
        val idx = ByteArrayOutputStream()
        for ((word, body) in entries) {
            val bytes = body.toByteArray(StandardCharsets.UTF_8)
            idx.write(word.toByteArray(StandardCharsets.UTF_8))
            idx.write(0)
            writeIntBE(idx, dict.size())
            writeIntBE(idx, bytes.size)
            dict.write(bytes)
        }
        File(dir, "$base.dict").writeBytes(dict.toByteArray())
        File(dir, "$base.idx").writeBytes(idx.toByteArray())
        File(dir, "$base.ifo").writeText(
            buildString {
                appendLine("StarDict's dict ifo file")
                appendLine("version=3.0.0")
                appendLine("bookname=$base")
                appendLine("wordcount=${entries.size}")
                appendLine("idxfilesize=${idx.size()}")
                appendLine("sametypesequence=h")
            }
        )
    }
}
