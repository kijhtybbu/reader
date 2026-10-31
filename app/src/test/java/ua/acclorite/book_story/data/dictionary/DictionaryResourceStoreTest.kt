/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * fork 新增：词典资源仓库的回归（第二阶段 S6，设计 §3.3）。
 *
 * 全部用假的 `MdxResources`：真 `.mdd` 有几百 MB，不可能进仓库，而且这里要钉的是仓库自己的
 * 四条规则，不是引擎的解析：
 *  - 同一条资源只回包里读一次（内存缓存命中）；
 *  - 单条 >256 KB 不进缓存，总量超 8 MiB 从最久未用处淘汰；
 *  - 句柄 LRU ≤2，禁用 / 删除 / 重装后关闭（关闭也要拿到该词典的锁，见并发用例）；
 *  - 打开失败有负缓存：同一来源不再为每个资源请求重开，换了一份资源包才重试；
 *  - 路径规范化：`file:///`、URL 编码、`#`/`?` 截断，反斜杠保留。
 * 外加发音落盘：文件名带词典 id（两部词典的同名路径不能互相复用）、写一次第二次复用、
 * 禁用后不再吐旧文件、路径只解码一层、超过 32 MiB 按 mtime 从旧到新清、过期 `.part` 清掉。
 */

package ua.acclorite.book_story.data.dictionary

import android.content.Context
import com.qiuminal.juicedict.engine.MdxResources
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

class DictionaryResourceStoreTest {

    /**
     * 假资源包：记录每次 `read` 的路径、并发进入次数与是否被关闭。
     * `delayMillis` 用来把并发用例的窗口撑开（否则两个线程可能刚好不重叠）。
     */
    private class FakeResources(private val records: Map<String, ByteArray>) : MdxResources {

        val reads: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val maxInFlight = AtomicInteger()
        private val inFlight = AtomicInteger()

        @Volatile
        var closed = false

        @Volatile
        var delayMillis = 0L

        override val names: List<String> = listOf("fake.mdd")

        override fun read(path: String): ByteArray? {
            val current = inFlight.incrementAndGet()
            maxInFlight.updateAndGet { maxOf(it, current) }
            try {
                if (delayMillis > 0) Thread.sleep(delayMillis)
                reads += path
                return records[path]
            } finally {
                inFlight.decrementAndGet()
            }
        }

        override fun close() {
            closed = true
        }
    }

    /** 临时目录必须在 `finally` 里递归删掉：`deleteOnExit()` 对非空目录不生效。 */
    private suspend fun withTempDirectory(block: suspend (File) -> Unit) {
        val dir = Files.createTempDirectory("dictionary-resources").toFile()
        try {
            block(dir)
        } finally {
            dir.deleteRecursively()
        }
    }

    /**
     * 一部 MDX 词典。[hasResources] 为 true 时在 [dir] 里放一个占位 `.mdd` —— 仓库用
     * `DictionaryEngines.mddFilesFor` 找包（真实规则），真正打开的那一步被换成了假实现。
     */
    private fun dictionary(
        id: String,
        dir: File,
        baseName: String = id,
        enabled: Boolean = true,
        hasResources: Boolean = true,
        importedAt: Long = 1L
    ): InstalledDictionary {
        if (hasResources) {
            File(dir, "$baseName.mdd").let { file ->
                file.parentFile?.mkdirs()
                if (!file.exists()) file.writeBytes(ByteArray(0))
            }
        }
        return InstalledDictionary(
            id = id,
            type = DictionaryType.MDX,
            name = "Dictionary $id",
            baseName = baseName,
            directory = dir,
            wordCount = 1,
            sizeBytes = 1,
            enabled = enabled,
            importedAt = importedAt,
            builtIn = false,
            hasResources = hasResources
        )
    }

    // ---- 内存缓存 --------------------------------------------------------------

    @Test
    fun `a resource is read from the pack once and then served from memory`() = runTest {
        withTempDirectory { dir ->
            val css = "<style>body{margin:0}</style>".toByteArray()
            val pack = FakeResources(mapOf("\\style.css" to css))
            val store = DictionaryResourceStore(
                dictionaries = { listOf(dictionary("d", dir)) },
                opener = { pack }
            )

            assertArrayEquals(css, store.read("d", "\\style.css"))
            assertArrayEquals(css, store.read("d", "\\style.css"))

            assertEquals(listOf("\\style.css"), pack.reads)
            assertEquals(1, store.cachedResourceCount)
        }
    }

    @Test
    fun `the path is normalized before it reaches the pack`() = runTest {
        withTempDirectory { dir ->
            val png = ByteArray(4) { it.toByte() }
            val pack = FakeResources(mapOf("\\pic\\a b.png" to png))
            val store = DictionaryResourceStore(
                dictionaries = { listOf(dictionary("d", dir)) },
                opener = { pack }
            )

            assertArrayEquals(png, store.read("d", "file:///%5Cpic%5Ca%20b.png?v=2#frag"))

            assertEquals(listOf("\\pic\\a b.png"), pack.reads)
        }
    }

    @Test
    fun `resources above the per-entry limit are never cached`() = runTest {
        withTempDirectory { dir ->
            val big = ByteArray(DictionaryResourceStore.MAX_CACHED_RESOURCE_BYTES + 1)
            val pack = FakeResources(mapOf("\\big.png" to big))
            val store = DictionaryResourceStore(
                dictionaries = { listOf(dictionary("d", dir)) },
                opener = { pack }
            )

            store.read("d", "\\big.png")
            store.read("d", "\\big.png")

            assertEquals(2, pack.reads.size)
            assertEquals(0L, store.cachedResourceBytes)
        }
    }

    @Test
    fun `the memory cache stays within 8 MiB and evicts the least recently used`() = runTest {
        withTempDirectory { dir ->
            val size = 200 * 1024
            val paths = (0 until 45).map { "\\r%02d.png".format(it) }
            val pack = FakeResources(paths.associateWith { ByteArray(size) { b -> b.toByte() } })
            val store = DictionaryResourceStore(
                dictionaries = { listOf(dictionary("d", dir)) },
                opener = { pack }
            )

            paths.forEach { store.read("d", it) }

            // 40 × 200 KB = 8.0 MB 还在 8 MiB 以内，第 41 条才触发淘汰。
            assertEquals(40, store.cachedResourceCount)
            assertTrue(store.cachedResourceBytes <= DictionaryResourceStore.MAX_CACHED_BYTES)

            val before = pack.reads.size
            // 最久未用的第一条已经被挤掉：再读要回包里读。
            store.read("d", paths.first())
            assertEquals(before + 1, pack.reads.size)
            // 最后一条还在缓存里：不再回包里读。
            store.read("d", paths.last())
            assertEquals(before + 1, pack.reads.size)
        }
    }

    @Test
    fun `audio bytes never enter the memory cache`() = runTest {
        withTempDirectory { dir ->
            val mp3 = ByteArray(64)
            val pack = FakeResources(mapOf("\\sound\\a.mp3" to mp3))
            val store = DictionaryResourceStore(
                dictionaries = { listOf(dictionary("d", dir)) },
                opener = { pack }
            )

            assertArrayEquals(mp3, store.read("d", "\\sound\\a.mp3"))
            assertArrayEquals(mp3, store.read("d", "\\sound\\a.mp3"))

            // 音频按后缀排除，与大小无关：64 字节也不进内存缓存。
            assertEquals(2, pack.reads.size)
            assertEquals(0L, store.cachedResourceBytes)
        }
    }

    // ---- 句柄 ------------------------------------------------------------------

    @Test
    fun `a resource pack that fails to open is not reopened until the source changes`() = runTest {
        withTempDirectory { dir ->
            var importedAt = 1L
            var broken = true
            var opened = 0
            val pack = FakeResources(mapOf("\\a.png" to ByteArray(2)))
            val store = DictionaryResourceStore(
                dictionaries = { listOf(dictionary("d", dir, importedAt = importedAt)) },
                opener = {
                    opened++
                    if (broken) throw IllegalStateException("corrupt mdd") else pack
                }
            )

            assertNull(store.read("d", "\\a.png"))
            assertNull(store.read("d", "\\a.png"))
            assertEquals("the broken pack must not be reopened for every request", 1, opened)

            // 重装（导入时间变了 = 换了一份资源包）：负缓存失效，允许重试。
            broken = false
            importedAt = 2L

            assertArrayEquals(ByteArray(2), store.read("d", "\\a.png"))
            assertEquals(2, opened)
        }
    }

    @Test
    fun `at most two resource handles stay open`() = runTest {
        withTempDirectory { dir ->
            val ids = listOf("a", "b", "c")
            val packs = ids.associateWith { FakeResources(mapOf("\\a.png" to ByteArray(1))) }
            val dictionaries = ids.map { dictionary(it, File(dir, it)) }
            val store = DictionaryResourceStore(
                dictionaries = { dictionaries },
                opener = { files -> packs.getValue(files.first().name.removeSuffix(".mdd")) }
            )

            ids.forEach { store.read(it, "\\a.png") }

            assertTrue("the oldest handle must be closed", packs.getValue("a").closed)
            assertFalse(packs.getValue("b").closed)
            assertFalse(packs.getValue("c").closed)
        }
    }

    @Test
    fun `disabling a dictionary closes its handle and stops serving resources`() = runTest {
        withTempDirectory { dir ->
            var enabled = true
            val pack = FakeResources(mapOf("\\a.png" to ByteArray(2)))
            val store = DictionaryResourceStore(
                dictionaries = { listOf(dictionary("d", dir, enabled = enabled)) },
                opener = { pack }
            )

            assertNotNull(store.read("d", "\\a.png"))
            assertFalse(pack.closed)

            enabled = false
            store.evictUnavailable()

            assertTrue(pack.closed)
            // 缓存里还有这条字节，但词典已禁用：不能再吐出去。
            assertNull(store.read("d", "\\a.png"))
        }
    }

    @Test
    fun `deleting a dictionary closes its handle`() = runTest {
        withTempDirectory { dir ->
            var present = true
            val pack = FakeResources(mapOf("\\a.png" to ByteArray(2)))
            val store = DictionaryResourceStore(
                dictionaries = { if (present) listOf(dictionary("d", dir)) else emptyList() },
                opener = { pack }
            )

            assertNotNull(store.read("d", "\\a.png"))
            present = false
            store.evictUnavailable()

            assertTrue(pack.closed)
            assertNull(store.read("d", "\\a.png"))
        }
    }

    @Test
    fun `a reinstalled dictionary closes the old packs and serves the new ones`() = runTest {
        withTempDirectory { root ->
            val oldPack = FakeResources(mapOf("\\a.png" to ByteArray(3)))
            val newPack = FakeResources(mapOf("\\a.png" to ByteArray(5)))
            val oldDir = File(root, "v1")
            val newDir = File(root, "v2")
            var directory = oldDir
            val store = DictionaryResourceStore(
                dictionaries = { listOf(dictionary("d", directory)) },
                opener = { files ->
                    if (files.first().parentFile == newDir) newPack else oldPack
                }
            )

            assertEquals(3, store.read("d", "\\a.png")!!.size)
            assertFalse(oldPack.closed)

            directory = newDir
            assertEquals(5, store.read("d", "\\a.png")!!.size)

            assertTrue("the packs of the replaced install must be closed", oldPack.closed)
            assertFalse(newPack.closed)
        }
    }

    @Test
    fun `concurrent reads of one dictionary never enter the pack at the same time`() = runTest {
        withTempDirectory { dir ->
            val paths = (0 until 8).map { "\\r$it.png" }
            val pack = FakeResources(paths.associateWith { ByteArray(8) }).apply { delayMillis = 2 }
            val store = DictionaryResourceStore(
                dictionaries = { listOf(dictionary("d", dir)) },
                opener = { pack }
            )

            coroutineScope {
                paths.map { path ->
                    async(Dispatchers.IO) { store.read("d", path) }
                }.awaitAll()
            }

            assertEquals(1, pack.maxInFlight.get())
        }
    }

    @Test
    fun `nothing is opened for a dictionary without resource packs`() = runTest {
        withTempDirectory { dir ->
            var opened = 0
            val store = DictionaryResourceStore(
                dictionaries = { listOf(dictionary("d", dir, hasResources = false)) },
                opener = {
                    opened++
                    FakeResources(emptyMap())
                }
            )

            assertNull(store.read("d", "\\a.png"))
            assertEquals(0, opened)
        }
    }

    @Test
    fun `read returns null for an unknown or blank resource`() = runTest {
        withTempDirectory { dir ->
            val pack = FakeResources(mapOf("\\a.png" to ByteArray(1)))
            val store = DictionaryResourceStore(
                dictionaries = { listOf(dictionary("d", dir)) },
                opener = { pack }
            )

            assertNull(store.read("missing", "\\a.png"))
            assertNull(store.read("d", "   "))
            assertNull(store.read("d", "#frag"))
            assertTrue(pack.reads.isEmpty())
        }
    }

    // ---- 路径规范化 ------------------------------------------------------------

    @Test
    fun `file urls and percent encoding are normalized`() {
        assertEquals("\\pic\\a.png", normalizeResourcePath("file:///\\pic\\a.png"))
        assertEquals("\\pic\\a b.png", normalizeResourcePath("file:///%5Cpic%5Ca%20b.png"))
        // URLDecoder 会把 `+` 当空格，文件名里的 `+` 是字面量。
        assertEquals("\\pic\\a+b.png", normalizeResourcePath("\\pic\\a+b.png"))
    }

    @Test
    fun `fragments and queries are stripped`() {
        assertEquals("\\style.css", normalizeResourcePath("\\style.css?v=2"))
        assertEquals("\\style.css", normalizeResourcePath("\\style.css#anchor"))
        assertEquals("\\style.css", normalizeResourcePath("file:///\\style.css?v=2#anchor"))
    }

    @Test
    fun `backslashes survive normalization for the pack to match`() {
        // 引擎自己容错 `/` 与 `\`（`MdxResourcePack.candidatesFor`），这里不能抢先改写。
        assertEquals("\\pic\\01.png", normalizeResourcePath("  \\pic\\01.png\t"))
        assertEquals("pic/01.png", normalizeResourcePath("pic/01.png"))
    }

    @Test
    fun `unusual paths fall back instead of throwing`() {
        assertNull(normalizeResourcePath(""))
        assertNull(normalizeResourcePath("   "))
        assertNull(normalizeResourcePath("#anchor"))
        assertNull(normalizeResourcePath("?query"))
        // 非法转义：整体原样返回，不让一次坏链接把整篇文章的请求都抛出去。
        assertEquals("100%discount.png", normalizeResourcePath("100%discount.png"))
    }

    // ---- 发音落盘 --------------------------------------------------------------

    @Test
    fun `the audio resource is written to the cache directory once and reused`() = runTest {
        withTempDirectory { dir ->
            val audio = ByteArray(64 * 1024) { 7 }
            val pack = FakeResources(mapOf("\\sound\\hello.mp3" to audio))
            val cache = File(dir, "audio")
            val store = DictionaryResourceStore(
                dictionaries = { listOf(dictionary("d", dir)) },
                opener = { pack },
                cacheDir = { cache }
            )
            val context = mockk<Context>(relaxed = true)

            val first = store.audioFile(context, "d", "\\sound\\hello.mp3")

            assertNotNull(first)
            assertEquals(audio.size.toLong(), first!!.length())
            assertArrayEquals(audio, first.readBytes())
            assertTrue("MediaPlayer 靠扩展名猜格式", first.name.endsWith(".mp3"))

            val second = store.audioFile(context, "d", "\\sound\\hello.mp3")

            assertEquals(first, second)
            assertEquals(1, pack.reads.size)
        }
    }

    @Test
    fun `audio cache names are per dictionary so two dictionaries never share a file`() = runTest {
        withTempDirectory { dir ->
            val first = ByteArray(16) { 1 }
            val second = ByteArray(16) { 2 }
            // 两部词典都有 `\sound\1.mp3`（这种命名到处都是）。
            val packs = mapOf(
                "d1" to FakeResources(mapOf("\\sound\\1.mp3" to first)),
                "d2" to FakeResources(mapOf("\\sound\\1.mp3" to second))
            )
            val dictionaries = listOf(dictionary("d1", File(dir, "d1")), dictionary("d2", File(dir, "d2")))
            val store = DictionaryResourceStore(
                dictionaries = { dictionaries },
                opener = { files -> packs.getValue(files.first().parentFile!!.name) },
                cacheDir = { File(dir, "audio") }
            )
            val context = mockk<Context>(relaxed = true)

            val one = store.audioFile(context, "d1", "\\sound\\1.mp3")
            val two = store.audioFile(context, "d2", "\\sound\\1.mp3")

            assertNotNull(one)
            assertNotNull(two)
            assertNotEquals("the second dictionary must not reuse the first one's file", one, two)
            assertArrayEquals(first, one!!.readBytes())
            assertArrayEquals(second, two!!.readBytes())

            // 同一部词典的同一路径仍然复用，不再回包里读。
            assertEquals(one, store.audioFile(context, "d1", "\\sound\\1.mp3"))
            assertEquals(1, packs.getValue("d1").reads.size)
        }
    }

    @Test
    fun `audio for a disabled dictionary is null even when the file is already on disk`() = runTest {
        withTempDirectory { dir ->
            var enabled = true
            val pack = FakeResources(mapOf("\\sound\\hello.mp3" to ByteArray(32)))
            val store = DictionaryResourceStore(
                dictionaries = { listOf(dictionary("d", dir, enabled = enabled)) },
                opener = { pack },
                cacheDir = { File(dir, "audio") }
            )
            val context = mockk<Context>(relaxed = true)

            val file = store.audioFile(context, "d", "\\sound\\hello.mp3")
            assertNotNull(file)

            enabled = false
            store.evictUnavailable()

            // 盘上的文件还在，但词典已禁用：不能再把它交给播放器。
            assertTrue(file!!.isFile)
            assertNull(store.audioFile(context, "d", "\\sound\\hello.mp3"))
        }
    }

    @Test
    fun `an audio path is decoded exactly once`() = runTest {
        withTempDirectory { dir ->
            val audio = ByteArray(8) { 3 }
            // `%2520` 只解一层 → `%20`；再解一次会变成空格，读错键。
            val pack = FakeResources(mapOf("\\sound\\a%20b.mp3" to audio))
            val store = DictionaryResourceStore(
                dictionaries = { listOf(dictionary("d", dir)) },
                opener = { pack },
                cacheDir = { File(dir, "audio") }
            )
            val context = mockk<Context>(relaxed = true)

            val file = store.audioFile(context, "d", "file:///%5Csound%5Ca%2520b.mp3")

            assertNotNull(file)
            assertArrayEquals(audio, file!!.readBytes())
            assertEquals(listOf("\\sound\\a%20b.mp3"), pack.reads)
        }
    }

    @Test
    fun `stale part files are removed from the audio cache`() = runTest {
        withTempDirectory { dir ->
            val cache = File(dir, "audio").apply { mkdirs() }
            val now = System.currentTimeMillis()
            val stale = File(cache, "deadbeef.mp3.part").apply {
                writeBytes(ByteArray(4))
                setLastModified(now - 2 * 60 * 60 * 1000L)
            }
            val fresh = File(cache, "cafebabe.mp3.part").apply {
                writeBytes(ByteArray(4))
                setLastModified(now)
            }
            val store = DictionaryResourceStore(
                dictionaries = { listOf(dictionary("d", dir)) },
                opener = { FakeResources(mapOf("\\sound\\hello.mp3" to ByteArray(16))) },
                cacheDir = { cache }
            )
            val context = mockk<Context>(relaxed = true)

            assertNotNull(store.audioFile(context, "d", "\\sound\\hello.mp3"))

            assertFalse("写盘残骸必须被清掉", stale.exists())
            assertTrue("新鲜的 .part 可能是并发的另一次写盘，不能删", fresh.exists())
        }
    }

    @Test
    fun `audio for a missing resource is null`() = runTest {
        withTempDirectory { dir ->
            val store = DictionaryResourceStore(
                dictionaries = { listOf(dictionary("d", dir)) },
                opener = { FakeResources(emptyMap()) },
                cacheDir = { File(dir, "audio") }
            )
            val context = mockk<Context>(relaxed = true)

            assertNull(store.audioFile(context, "d", "\\sound\\missing.mp3"))
            assertNull(store.audioFile(context, "missing", "\\sound\\missing.mp3"))
        }
    }

    @Test
    fun `the audio cache is pruned to 32 MiB by modification time`() = runTest {
        withTempDirectory { dir ->
            val perFile = 512 * 1024
            val count = 70
            val paths = (0 until count).map { "\\sound\\a$it.mp3" }
            val pack = FakeResources(paths.associateWith { ByteArray(perFile) })
            val cache = File(dir, "audio")
            var now = 1_000L
            val store = DictionaryResourceStore(
                dictionaries = { listOf(dictionary("d", dir)) },
                opener = { pack },
                cacheDir = { cache },
                clock = { now }
            )
            val context = mockk<Context>(relaxed = true)

            for (path in paths) {
                now += 1_000
                assertNotNull(store.audioFile(context, "d", path))
            }

            // 32 MiB / 512 KiB = 64 个文件；更早写的按 mtime 删掉，刚写的那个永远留下。
            val remaining = cache.listFiles().orEmpty().map { it.name }.toSet()
            val limit = DictionaryResourceStore.MAX_AUDIO_CACHE_BYTES / perFile
            assertEquals(limit, remaining.size.toLong())
            assertFalse(remaining.contains(audioFileNameFor("d", paths.first())))
            assertTrue(remaining.contains(audioFileNameFor("d", paths.last())))
        }
    }

    // ---- 目录推导 --------------------------------------------------------------

    @Test
    fun `hasResources is derived from the mdd files on disk`() = runTest {
        withTempDirectory { dir ->
            assertFalse(hasResourcePacks(DictionaryType.MDX, dir, "d"))

            File(dir, "d.mdd").writeBytes(ByteArray(0))
            assertTrue(hasResourcePacks(DictionaryType.MDX, dir, "d"))

            // `.1.mdd` 这类分卷也算资源（《字源》的 43 MB 扫描件就在分卷里）。
            assertFalse(hasResourcePacks(DictionaryType.MDX, dir, "e"))
            File(dir, "e.1.mdd").writeBytes(ByteArray(0))
            assertTrue(hasResourcePacks(DictionaryType.MDX, dir, "e"))

            // StarDict 的资源页不在本期范围。
            assertFalse(hasResourcePacks(DictionaryType.STARDICT, dir, "d"))
        }
    }
}
