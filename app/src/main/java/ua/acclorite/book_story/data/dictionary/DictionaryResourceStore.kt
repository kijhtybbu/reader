/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * fork 新增：词典资源仓库（第二阶段 S6，设计 `docs/dictionary-resources-design.md` §3.3）。
 *
 * MDX 词条引用的 CSS / 图片 / 字体 / 发音都在 `.mdd` 里，按路径取出即可。这里只做四件事：
 *
 *  1. **按词典串行读取**。`MdxResourcePack` 的 seek+read 不是原子的（一个包只有一份
 *     `MdxFileReader`），所以每部词典一把锁，同一部词典的并发请求排队，不同词典互不影响。
 *     [read] 因此是**阻塞**的：WebView 的 `shouldInterceptRequest` 在后台线程调用它，阻塞
 *     比把它包成 suspend 再在别处 runBlocking 更诚实。
 *  2. **内存缓存**。词条通常只要一份 CSS + 几个小图，单条 ≤256 KB、总量 ≤8 MB；大图与音频
 *     不进缓存（大对象命中率低，还会把 CSS 挤出去）。音频按后缀判定（见 [isAudioResource]），
 *     与大小无关。
 *  3. **句柄 LRU**。`.mdd` 可能是上百 MB 的分卷包，同时只留 [MAX_HANDLES] 组句柄；禁用、
 *     删除、重装（目录/主干/格式/导入时间变了）时 [evictUnavailable] 关闭它们。关闭也要拿该
 *     词典的锁，否则会把一次正在进行的读取变成异常（那种异常在 WebView 里会静默变成空白图片）。
 *     打开失败同样按来源记下来（负缓存），否则每个资源请求都会重开一次上 GB 的包并再漏一组 FD。
 *  4. **发音落盘**。`MediaPlayer` 读文件最稳（`.spx` 之类只是格式不支持，与文件无关），
 *     所以 `sound://` 指向的资源先写到 `cacheDir/dict-audio/`，文件名是「词典 id + 路径」的
 *     安全 hash + 原扩展名；同一路径第二次直接复用，总量超过 32 MB 按 mtime 从旧到新清，
 *     过期的 `.part` 残骸顺手删掉。
 *
 * 路径规范化见 [normalizeResourcePath]：HTML 实体已由 jsoup 解好，这里只处理 URLDecoder、
 * `file:///` 前缀、`#`/`?` 尾巴。反斜杠**原样保留** —— `MdxResourcePack` 自己容错 `/` 与 `\`，
 * 在这里改写会破坏 `sizeOf`/`candidatesFor` 依赖的原始拼写。
 *
 * 构造参数全部可注入（目录快照、opener、缓存目录、时钟），JVM 单测直接 new 一份就能跑，
 * 不需要 Context、真机或真实的 `.mdd`。生产端用 [shared]。
 */

package ua.acclorite.book_story.data.dictionary

import android.content.Context
import com.qiuminal.juicedict.engine.DictionaryEngines
import com.qiuminal.juicedict.engine.MdxResources
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ua.acclorite.book_story.core.log.logD
import ua.acclorite.book_story.core.log.logW
import java.io.File
import java.io.IOException
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

private const val TAG = "DictionaryResourceStore"

class DictionaryResourceStore(
    /** 已安装词典的快照（默认 [DictionaryCatalog] 的当前值）。 */
    private val dictionaries: () -> List<InstalledDictionary> = {
        DictionaryCatalog.dictionaries.value
    },
    /** 打开一组 `.mdd`；测试注入假实现，生产端走引擎。 */
    private val opener: (List<File>) -> MdxResources? = DictionaryEngines::openMdxResources,
    /**
     * 发音缓存目录的覆盖值（单测指向临时目录）。返回 null 表示按 [Context] 推导
     * `context.cacheDir/dict-audio/` —— 生产的调用方只有 Context，不该自己拼目录。
     */
    private val cacheDir: () -> File? = { null },
    /** 写盘后给文件打 mtime 用的时钟；淘汰顺序按 mtime，测试因此可控。 */
    private val clock: () -> Long = System::currentTimeMillis
) {

    /** 保护 [memory] 与 [handles] 两张表。只做表操作，绝不在持锁时做 IO 或等别的锁。 */
    private val cacheLock = ReentrantLock()

    /** access-order = true：迭代顺序即 LRU 顺序，[cacheResource] 从头部淘汰。 */
    private val memory = LinkedHashMap<String, ByteArray>(16, 0.75f, true)
    private var memoryBytes = 0L

    /** 打开的 `.mdd` 句柄，同样是 access-order LRU，容量 [MAX_HANDLES]。 */
    private val handles = LinkedHashMap<String, OpenHandle>(MAX_HANDLES, 0.75f, true)

    /**
     * 每部词典一把锁，**每个仓库实例各一份**：实例内同一部词典的 seek+read 串行，不同词典互不
     * 影响。生产端只经单例 [shared] 使用，所以实际只有一份；但跨实例并发不在本类的保证范围内
     * （[dictionaryLocks] 是实例字段，不是进程级表）。
     * 用 [ReentrantLock] 而不是 `Mutex`，因为 [read] 是阻塞函数、[audioFile] 是 suspend 函数，
     * 两者必须能共用同一把锁。
     */
    private val dictionaryLocks = ConcurrentHashMap<String, ReentrantLock>()

    /**
     * 打开失败过的来源：`dictId` → [sourceKey]。`.mdd` 可能是 1.85 GB 的三包，坏包每个资源
     * 请求都重开一遍既慢又重复泄漏 FD，所以失败也缓存；来源变了才重试（见 [evictUnavailable]
     * 与 [handleFor]）。与 [handles] 一样只受 [cacheLock] 保护。
     */
    private val unavailable = HashMap<String, String>()

    /** 发音目录的写/清理锁：读资源不经过它，所以不会有别的加锁顺序与它交叉。 */
    private val audioLock = ReentrantLock()

    private val watchScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 读 [dictId] 在 [path] 处的资源字节；词典未启用、已删除、没有资源包、或包里没有这个
     * 路径时返回 null（“缺失”是常态：CSS 里引用的图经常不在包里）。
     *
     * **阻塞**，按词典串行。调用方线程：WebView 的 `shouldInterceptRequest`。
     */
    fun read(dictId: String, path: String): ByteArray? {
        val resource = normalizeResourcePath(path) ?: return null
        return readNormalized(dictId, resource)
    }

    /**
     * [read] 的已规范化入口：[resource] 必须已经过 [normalizeResourcePath]。[audioFile] 走这里，
     * 避免把 `%2520` 这类双重编码再解一层（第二次解码会把 `%20` 变成空格，读错键）。
     */
    private fun readNormalized(dictId: String, resource: String): ByteArray? {
        // 先看目录再看缓存：禁用/删除后不能继续吐还留在缓存里的字节。
        val dictionary = availableDictionary(dictId) ?: return null
        cachedResource(dictionary, resource)?.let { return it }

        val evicted = ArrayList<OpenHandle>(1)
        val bytes = dictionaryLock(dictId).withLock {
            val handle = handleFor(dictionary, evicted)
            if (handle == null) {
                null
            } else {
                try {
                    handle.pack.read(resource)
                } catch (e: Exception) {
                    logW(TAG, "Could not read $resource from ${dictionary.name}: ${e.message}")
                    null
                }
            }
        }
        // 关闭放在锁外：持着 A 的锁去关 B，同时持着 B 的锁去关 A，就是死锁。
        closeAll(evicted)
        if (bytes != null) cacheResource(dictionary, resource, bytes)
        return bytes
    }

    /**
     * 把 [path] 处的资源（通常是 `sound://` 指向的音频）落成 `cacheDir/dict-audio/` 下的文件，
     * 返回文件的绝对路径；资源缺失或写盘失败时 null。
     *
     * 同一部词典的同一个路径只写一次：文件名是「词典 id + 规范化路径」的 SHA-256 前缀 + 原扩展名
     * （见 [audioFileNameFor]），存在且非空就直接复用。写完顺手把目录清到
     * [MAX_AUDIO_CACHE_BYTES] 以内（按 mtime 从旧到新删）。
     */
    suspend fun audioFile(context: Context, dictId: String, path: String): File? =
        withContext(Dispatchers.IO) {
            val resource = normalizeResourcePath(path) ?: return@withContext null
            val directory = audioDirectory(context) ?: return@withContext null
            // 先看目录再看磁盘缓存（与 [read] 一致）：禁用/删除后不能继续吐还留在盘上的旧音频。
            availableDictionary(dictId) ?: return@withContext null

            audioLock.withLock {
                val target = File(directory, audioFileNameFor(dictId, resource))
                if (target.isFile && target.length() > 0L) return@withLock target

                val bytes = readNormalized(dictId, resource)?.takeIf { it.isNotEmpty() }
                    ?: return@withLock null
                if (!writeAtomically(target, bytes)) return@withLock null
                pruneAudio(directory, keep = target)
                target
            }
        }

    /**
     * 关闭已经不可用的句柄：词典被删除、被禁用，或目录/主干/格式变了（重装、同名新版本）。
     * 同时清掉这些词典的打开失败标记 —— 换了一份资源包就该允许重试。
     * 关闭前拿该词典的锁，等正在进行的读取退出。
     *
     * 生产端由 [shared] 订阅 `DictionaryCatalog.dictionaries` 自动调用；单测直接调。
     */
    internal suspend fun evictUnavailable() = withContext(Dispatchers.IO) {
        val available = dictionaries().associateBy { it.id }
        val evicted = cacheLock.withLock {
            val victims = ArrayList<OpenHandle>(1)
            val iterator = handles.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (!isAvailable(available[entry.key], entry.value.dictionary)) {
                    victims += entry.value
                    iterator.remove()
                }
            }
            // 负缓存按来源失效：禁用 / 删除 / 重装之后必须允许重试。
            unavailable.entries.removeAll { (id, source) ->
                val current = available[id]
                current == null || !current.isEnabled() || sourceKey(current) != source
            }
            victims
        }
        closeAll(evicted)
    }

    /** 目录变化时关掉失效句柄；[shared] 建好实例后调用一次。 */
    internal fun startWatching(changes: Flow<List<InstalledDictionary>>) {
        watchScope.launch {
            changes.collect { evictUnavailable() }
        }
    }

    /** 单测用：内存缓存当前条数与字节数（生产代码不读它们）。 */
    internal val cachedResourceCount: Int get() = cacheLock.withLock { memory.size }

    internal val cachedResourceBytes: Long get() = cacheLock.withLock { memoryBytes }

    // ---- 句柄 ------------------------------------------------------------------

    /**
     * 取（必要时打开）[dictionary] 的资源包句柄。调用方持有该词典的锁。
     *
     * **不能持着 [cacheLock] 做磁盘 IO**：`mddFilesFor` 要列目录，`opener` 要为每个分卷建一个
     * `RandomAccessFile`（上 GB 的包、网络盘/慢 IO 上可能是几十毫秒）。持锁打开会把**别的**词典
     * 的资源请求、缓存读写一起堵住。所以拆成三步：锁内查表 → 锁外打开 → 锁内回填。
     *
     * @param evicted 收集需要关闭的旧句柄；调用方必须在锁外关它们（见 [closeAll]）。
     */
    private fun handleFor(
        dictionary: InstalledDictionary,
        evicted: MutableList<OpenHandle>
    ): OpenHandle? {
        val source = sourceKey(dictionary)

        // 1) 只做表操作：命中 / 来源变了 / 负缓存，全部在锁内判定。
        cacheLock.withLock {
            val open = handles[dictionary.id]
            if (open != null && sameSource(open.dictionary, dictionary)) return open
            if (open != null) {
                handles.remove(dictionary.id)
                evicted += open
            }
            // 负缓存：这个来源已经确认打不开，别再为每个资源请求重开一次（也避免重复泄漏 FD）。
            if (unavailable[dictionary.id] == source) return null
        }

        // 2) 锁外打开：列 `.mdd` 与建句柄都是磁盘 IO。
        //    只看 `.mdd` 文件本身：`hasResources` 是同一份推导的缓存值，两处并列反而可能互相矛盾。
        val files = DictionaryEngines.mddFilesFor(dictionary.directory, dictionary.baseName)
        if (files.isEmpty()) return null

        val pack = try {
            opener(files)
        } catch (e: Exception) {
            logW(TAG, "Could not open the resource packs of ${dictionary.name}: ${e.message}")
            null
        }

        // 3) 回填。打开期间别的线程可能已经存了同一份句柄，或来源又变了。
        return cacheLock.withLock {
            val current = handles[dictionary.id]
            if (current != null && sameSource(current.dictionary, dictionary)) {
                // 别人先到了：以表里那份为准，刚打开的这份交给调用方在锁外关掉。
                if (pack != null) evicted += OpenHandle(dictionary, pack)
                return current
            }
            if (current != null) {
                handles.remove(dictionary.id)
                evicted += current
            }
            if (pack == null) {
                unavailable[dictionary.id] = source
                return null
            }

            val handle = OpenHandle(dictionary, pack)
            handles[dictionary.id] = handle
            evictExcessHandles(evicted)
            logD(TAG, "Opened ${pack.names.size} resource pack(s) for ${dictionary.id}")
            handle
        }
    }

    private fun evictExcessHandles(evicted: MutableList<OpenHandle>) {
        while (handles.size > MAX_HANDLES) {
            val eldest = handles.entries.iterator().next()
            handles.remove(eldest.key)
            evicted += eldest.value
        }
    }

    /** 调用方必须**不持有**任何词典锁：这里会去等被关句柄自己的锁。 */
    private fun closeAll(closed: List<OpenHandle>) {
        for (handle in closed) {
            val id = handle.dictionary.id
            dictionaryLock(id).withLock {
                runCatching { handle.pack.close() }
                    .onSuccess { logD(TAG, "Closed the resource packs of $id") }
                    .onFailure { logW(TAG, "Could not close the resource packs of $id: ${it.message}") }
            }
        }
    }

    private fun dictionaryLock(dictId: String): ReentrantLock =
        dictionaryLocks.computeIfAbsent(dictId) { ReentrantLock() }

    private fun availableDictionary(dictId: String): InstalledDictionary? {
        val dictionary = dictionaries().firstOrNull { it.id == dictId } ?: return null
        if (!dictionary.isEnabled()) return null
        return dictionary
    }

    private fun isAvailable(
        current: InstalledDictionary?,
        opened: InstalledDictionary
    ): Boolean {
        if (current == null) return false
        if (!current.isEnabled()) return false
        return sameSource(current, opened)
    }

    /** 内置词典没有开关，其余按用户的启用状态。 */
    private fun InstalledDictionary.isEnabled(): Boolean = builtIn || enabled

    /**
     * 资源来源：格式 + 目录 + 主干 + 导入时间。任一变化都算换了一份资源包，所以负缓存以它为键
     * —— 删除、重装（包括装回同一目录，[InstalledDictionary.importedAt] 会变）都会让旧标记失效。
     */
    private fun sourceKey(dictionary: InstalledDictionary): String =
        "${dictionary.type}\u0000${dictionary.directory.path}\u0000${dictionary.baseName}\u0000" +
            dictionary.importedAt

    /** 目录 / 主干 / 格式任一变化都算「换了词典」：删除、重装、同名新版本都落在这三条里。 */
    private fun sameSource(a: InstalledDictionary, b: InstalledDictionary): Boolean =
        a.type == b.type && a.baseName == b.baseName && a.directory == b.directory

    // ---- 内存缓存 --------------------------------------------------------------

    /**
     * 缓存键带上了资源来源（目录 + 主干）：同名同 id 的词典重装后目录会变，旧字节不会再被命中。
     */
    private fun cacheKey(dictionary: InstalledDictionary, resource: String): String =
        "${dictionary.id}\u0000${dictionary.directory.path}\u0000${dictionary.baseName}\u0000$resource"

    private fun cachedResource(dictionary: InstalledDictionary, resource: String): ByteArray? =
        cacheLock.withLock { memory[cacheKey(dictionary, resource)] }

    private fun cacheResource(
        dictionary: InstalledDictionary,
        resource: String,
        bytes: ByteArray
    ) {
        // 大图与音频不进内存缓存：命中率低，还会把每次都需要的 CSS 挤出去。音频与大小无关，
        // 按后缀判定（`sound://` 走 [audioFile] 落盘，回包里重读一次也不贵）。
        if (bytes.size > MAX_CACHED_RESOURCE_BYTES) return
        if (isAudioResource(resource)) return
        cacheLock.withLock {
            val key = cacheKey(dictionary, resource)
            memory[key]?.let { memoryBytes -= it.size }
            memory[key] = bytes
            memoryBytes += bytes.size
            while (memoryBytes > MAX_CACHED_BYTES && memory.isNotEmpty()) {
                val eldest = memory.entries.iterator().next()
                memory.remove(eldest.key)
                memoryBytes -= eldest.value.size
            }
        }
    }

    // ---- 发音文件 --------------------------------------------------------------

    private fun audioDirectory(context: Context): File? {
        val directory = cacheDir() ?: File(context.cacheDir, AUDIO_DIRECTORY)
        if (directory.isDirectory) return directory
        return if (directory.mkdirs() || directory.isDirectory) {
            directory
        } else {
            logW(TAG, "Could not create the audio cache directory: ${directory.absolutePath}")
            null
        }
    }

    private fun writeAtomically(target: File, bytes: ByteArray): Boolean = try {
        val temp = File(target.parentFile, target.name + TEMP_SUFFIX)
        temp.writeBytes(bytes)
        if (target.exists() && !target.delete()) {
            logW(TAG, "Could not replace the audio cache file ${target.absolutePath}")
        }
        if (temp.renameTo(target)) {
            target.setLastModified(clock())
            true
        } else {
            logW(TAG, "Could not publish the audio cache file ${target.absolutePath}")
            temp.delete()
            false
        }
    } catch (e: IOException) {
        logW(TAG, "Could not write the audio cache file ${target.absolutePath}: ${e.message}")
        false
    }

    private fun pruneAudio(directory: File, keep: File) {
        val entries = directory.listFiles()
            ?.filter { it.isFile && it.name != keep.name }
            ?: return
        val files = ArrayList<File>(entries.size)
        val now = clock()
        for (entry in entries) {
            if (entry.name.endsWith(TEMP_SUFFIX)) {
                // 写盘中途被杀留下的 `.part`：过期就删。它们不计入下面的 32 MiB，也没有别处会
                // 清理，留着只会一直占空间。新鲜的不动：可能是并发的另一次写盘（尽管有锁）。
                if (now - entry.lastModified() > STALE_TEMP_MILLIS) entry.delete()
            } else {
                files += entry
            }
        }
        var total = files.sumOf { it.length() } + keep.length()
        if (total <= MAX_AUDIO_CACHE_BYTES) return

        // 名字作为次级键：同一毫秒写出的文件也要有稳定的删除顺序。
        for (file in files.sortedWith(compareBy({ it.lastModified() }, { it.name }))) {
            if (total <= MAX_AUDIO_CACHE_BYTES) break
            val length = file.length()
            if (file.delete()) total -= length
        }
    }

    private data class OpenHandle(
        val dictionary: InstalledDictionary,
        val pack: MdxResources
    )

    companion object {
        /** 单条资源进内存缓存的上限（CSS / 图标 / 小图）。 */
        internal const val MAX_CACHED_RESOURCE_BYTES = 256 * 1024

        /** 内存缓存总量上限。 */
        internal const val MAX_CACHED_BYTES = 8L * 1024 * 1024

        /** 同时打开的 `.mdd` 句柄组数。 */
        internal const val MAX_HANDLES = 2

        /** 发音缓存目录（相对 `context.cacheDir`）。 */
        internal const val AUDIO_DIRECTORY = "dict-audio"

        /** 发音缓存总量上限；超出按 mtime 从旧到新删。 */
        internal const val MAX_AUDIO_CACHE_BYTES = 32L * 1024 * 1024

        private const val TEMP_SUFFIX = ".part"

        /** 超过这个年龄的 `.part` 视为写盘残骸，[pruneAudio] 直接删。 */
        private const val STALE_TEMP_MILLIS = 60 * 60 * 1000L

        /**
         * 进程内唯一实例：WebView 的 `shouldInterceptRequest` 与音频播放共用同一个句柄池，
         * 否则每处各开一组 `.mdd` 句柄，LRU 的上限就形同虚设。
         */
        val shared: DictionaryResourceStore by lazy {
            DictionaryResourceStore().also { it.startWatching(DictionaryCatalog.dictionaries) }
        }
    }
}

/**
 * 把词条 HTML 里引用的路径规范成 `.mdd` 的键。
 *
 * 处理顺序：HTML 实体（已由 jsoup 解好）→ `URLDecoder`（`%5C` / `%20`）→ 去 `file:///`
 * 前缀 → 截掉 `#` 片段与 `?` 查询。反斜杠原样保留：`MdxResourcePack.candidatesFor` / `find`
 * 自己容错 `/` 与 `\` 以及缺前导反斜杠，这里改写只会破坏它依赖的原始拼写。
 *
 * `+` 先转成 `%2B`：`URLDecoder` 会把 `+` 当成空格，而文件名里的 `+` 是字面量。
 * 非法转义（`100%discount.png`）整体回落成原字符串，不抛异常。
 */
internal fun normalizeResourcePath(raw: String): String? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return null

    val decoded = try {
        URLDecoder.decode(trimmed.replace("+", "%2B"), Charsets.UTF_8)
    } catch (e: IllegalArgumentException) {
        trimmed
    }

    val withoutScheme = when {
        decoded.startsWith("file:///", ignoreCase = true) -> decoded.substring("file:///".length)
        decoded.startsWith("file://", ignoreCase = true) -> decoded.substring("file://".length)
        else -> decoded
    }

    val end = withoutScheme.indexOfFirst { it == '#' || it == '?' }
    val path = if (end >= 0) withoutScheme.substring(0, end) else withoutScheme
    return path.trim().takeIf { it.isNotEmpty() }
}

/**
 * 发音文件的缓存名：`dictId` + `\u0000` + 规范化路径的 SHA-256 前 64 位 + 原扩展名
 * （`MediaPlayer` 靠扩展名猜格式，丢了 `mp3`/`ogg` 会静默不播）。扩展名只保留字母数字，
 * 避免路径里的怪字符进文件名。
 *
 * **[dictId] 必须参与 hash**：`\sound\1.mp3` 这种命名到处都是，只 hash 路径会让第二部词典
 * 直接播到第一部写下的文件（重装后也会复用旧字节）。
 */
internal fun audioFileNameFor(dictId: String, path: String): String {
    val key = "$dictId\u0000$path"
    val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
    val hash = buildString(16) {
        for (byte in digest.take(8)) append("%02x".format(byte))
    }
    // 先取文件名再取扩展名：目录名里的点（`\v1.0\…`）不能当成扩展名。
    val name = path.substringAfterLast('\\').substringAfterLast('/')
    val extension = name.substringAfterLast('.', "")
        .filter { it.isLetterOrDigit() }
        .take(8)
        .lowercase()
    return if (extension.isEmpty()) hash else "$hash.$extension"
}

/**
 * 音频后缀。发音字节走 [DictionaryResourceStore.audioFile] 落盘，不进内存缓存 —— 与大小无关，
 * 几十 KB 的 `.mp3` 也不会命中第二次（每次播放本来就读文件）。
 */
private val AUDIO_EXTENSIONS = setOf(
    "mp3", "mp4", "m4a", "ogg", "oga", "opus", "wav", "spx", "aac", "flac", "wma", "amr"
)

private fun isAudioResource(resource: String): Boolean {
    val name = resource.substringAfterLast('\\').substringAfterLast('/')
    return name.substringAfterLast('.', "").lowercase() in AUDIO_EXTENSIONS
}
