package com.qiuminal.juicedict.engine

import com.qiuminal.juicedict.engine.mdict.MdxBlockReader
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.nio.file.Files
import java.util.zip.Deflater

/**
 * [DictionaryEngines.openMdxResources] 的合成 MDD fixture：测试运行时现造一个合法的
 * MDD 2.0 容器，把「打开资源包 → 按路径读字节」这条 app 层唯一的资源入口真正跑通。
 *
 * 为什么不能用真实语料：本机 MDD（`E:\dictionary\MDict` 那批）在 CI 上不存在，上游
 * 依赖它们的回归直接 `return` 报成 passed。所以 MDD 与 MDX 的三处差异必须在这里钉住：
 *
 * - 根标签是 `Library_Data`（`MdxKind.RESOURCE`），不是 `Dictionary`；
 * - key 恒为 UTF-16LE、`StripKey="No"`：key block 里的 key 是
 *   `UTF-16LE 文本 + 一个 NUL 编码单元`，key index 的长度字段按字符数（不是字节数）计；
 * - record 是裸字节、**没有尾 NUL**，所以每条的大小靠下一条的 offset 推导——fixture 里
 *   的图片特意带内嵌 `0x00` 字节，尾 NUL 一旦被写进去就会读多一字节。
 *
 * 容器拼装（单 key block + 单 record block，zlib 压缩）与 `MdxFixtureTest` 相同，helper
 * 按最小集复制到本文件——用一个 MDX 专用的构造器去造 MDD 反而要绕着改它。
 */
class MdxResourcesFixtureTest {

    @Test
    fun readFindsBackslashPrefixedPath() {
        openSinglePack().use {
            val bytes = it.read("\\a.css")
            assertEquals("stored key is \\A.CSS, queried \\a.css", CSS, bytes?.toString(Charsets.UTF_8))
        }
    }

    @Test
    fun readFindsPathWithoutLeadingBackslash() {
        openSinglePack().use {
            assertEquals(CSS, it.read("a.css")?.toString(Charsets.UTF_8))
        }
    }

    @Test
    fun readFindsPathWithForwardSlashes() {
        openSinglePack().use {
            assertEquals(CSS, it.read("/a.css")?.toString(Charsets.UTF_8))
            assertEquals(CSS, it.read("/A.CSS")?.toString(Charsets.UTF_8))
        }
    }

    /** key 存的是小写 `\b.png`，查询放成大写也要命中（MDD 路径大小写不敏感）。 */
    @Test
    fun readIgnoresKeyCase() {
        openSinglePack().use {
            assertArrayEquals(PNG, it.read("\\B.PNG"))
        }
    }

    @Test
    fun readReturnsNullForUnknownPath() {
        openSinglePack().use {
            // 排序位置在所有 key 之后（key block 直接跳过）与落在块内（扫完不命中）两条路径
            assertNull(it.read("\\missing.png"))
            assertNull(it.read("\\a2.css"))
        }
    }

    @Test
    fun readReturnsNullForEmptyPath() {
        openSinglePack().use {
            assertNull(it.read(""))
        }
    }

    @Test
    fun namesAreTheOpenedPacksInSearchOrder() {
        val dir = tempDir()
        val first = buildMdd(dir, "part.1.mdd", listOf("\\shared.txt" to bytesOf("part-1")))
        val second = buildMdd(
            dir,
            "part.2.mdd",
            listOf(
                "\\only-in-second.txt" to bytesOf("second"),
                "\\shared.txt" to bytesOf("part-2"),
            ),
        )

        val resources = DictionaryEngines.openMdxResources(listOf(first, second))
        assertNotNull("both packs are well formed", resources)
        resources!!.use {
            assertEquals(listOf("part.1.mdd", "part.2.mdd"), it.names)
            // 同键取前一个包，后面的包不再读
            assertEquals("part-1", it.read("\\shared.txt")?.toString(Charsets.UTF_8))
            // 前一个包没有的键回落到后一个包
            assertEquals("second", it.read("\\only-in-second.txt")?.toString(Charsets.UTF_8))
        }
    }

    /** 一个包坏了（这里是文本文件）不能让别的包不可读——分卷资源正是这个场景。 */
    @Test
    fun skipsUnreadablePackAndKeepsTheRest() {
        val dir = tempDir()
        val good = buildMdd(dir, "good.mdd", listOf("\\a.css" to bytesOf(CSS)))
        val broken = File(dir, "broken.mdd").also { it.writeText("plain text, not an MDict container") }

        val messages = ArrayList<String>()
        DictionaryEngines.logSink = { tag, message -> messages.add("$tag: $message") }
        val resources = try {
            DictionaryEngines.openMdxResources(listOf(good, broken))
        } finally {
            DictionaryEngines.logSink = null
        }

        assertNotNull("one unreadable pack must not take the set down", resources)
        resources!!.use {
            assertEquals(listOf("good.mdd"), it.names)
            assertEquals(CSS, it.read("\\a.css")?.toString(Charsets.UTF_8))
        }
        assertTrue("expected a diagnostic, got $messages", messages.isNotEmpty())
        assertTrue(
            "diagnostic must name the skipped pack, got $messages",
            messages.any { it.contains("broken.mdd") },
        )
    }

    @Test
    fun openMdxResourcesReturnsNullForEmptyList() {
        assertNull(DictionaryEngines.openMdxResources(emptyList()))
    }

    @Test
    fun openMdxResourcesReturnsNullWhenEveryPackFails() {
        val dir = tempDir()
        val one = File(dir, "one.mdd").also { it.writeText("not an MDict file") }
        val two = File(dir, "two.mdd").also { it.writeBytes(ByteArray(0)) }
        assertNull(DictionaryEngines.openMdxResources(listOf(one, two)))
    }

    // ---- checkMdxResourcePack (import-time classification) -------------------

    @Test
    fun checkMdxResourcePackAcceptsWellFormedPack() {
        val dir = tempDir()
        val file = buildMdd(dir, "dict.mdd", listOf("\\a.css" to bytesOf(CSS), "\\b.png" to PNG))
        assertEquals(MdxPackCheck.Ok(2), DictionaryEngines.checkMdxResourcePack(file))
    }

    /** 用户把 `.mdx`（或它的兄弟文件）当成 `.mdd` 选中：容器是 MDict，但是另一种。 */
    @Test
    fun checkMdxResourcePackRejectsAnArticleDictionary() {
        val dir = tempDir()
        val file = buildMdd(
            dir,
            "article.mdd",
            listOf("\\a.css" to bytesOf(CSS)),
            rootTag = "Dictionary",
        )
        assertEquals(MdxPackCheck.NotResourcePack, DictionaryEngines.checkMdxResourcePack(file))
    }

    /** 没下完的 `.mdd`：头部与 key section 完整，record section 缺失（构造时读 record 前导即 EOF）。 */
    @Test
    fun checkMdxResourcePackReportsTruncatedPackAsCorrupt() {
        val dir = tempDir()
        val file = buildMdd(
            dir,
            "truncated.mdd",
            listOf("\\a.css" to bytesOf(CSS)),
            withRecords = false,
        )
        val check = DictionaryEngines.checkMdxResourcePack(file)
        assertTrue("a truncated pack must not be Ok/NotResourcePack: $check", check is MdxPackCheck.Corrupt)
    }

    /** 文件根本不是 MDict：同样按「资源包不合法」处理，而不是抛给调用方。 */
    @Test
    fun checkMdxResourcePackReportsGarbageAsNotResourcePack() {
        val dir = tempDir()
        val file = File(dir, "garbage.mdd").also { it.writeText("plain text, not an MDict container") }
        assertEquals(MdxPackCheck.NotResourcePack, DictionaryEngines.checkMdxResourcePack(file))
    }

    // ---- fixture builder ----------------------------------------------------

    /** 单包 fixture：`\A.CSS`（正文是 [CSS]）与 `\b.png`（二进制、含内嵌 NUL）。 */
    private fun openSinglePack(): MdxResources {
        val dir = tempDir()
        val file = buildMdd(
            dir,
            "dict.mdd",
            listOf(
                "\\A.CSS" to bytesOf(CSS),
                "\\b.png" to PNG,
            ),
        )
        val resources = DictionaryEngines.openMdxResources(listOf(file))
        assertNotNull("a well-formed MDD must open", resources)
        return resources!!
    }

    private fun tempDir(): File = Files.createTempDirectory("mdd-fixture").toFile().also {
        it.deleteOnExit()
    }

    private fun bytesOf(text: String): ByteArray = text.toByteArray(Charsets.UTF_8)

    /**
     * 拼一个可被 [DictionaryEngines.openMdxResources] 打开的 MDD 2.0。
     *
     * [entries] 必须已按 `MdxKeyOrder`（`StripKey="No"`、忽略大小写）排好序，否则
     * key block 内的二分查找会漏词。
     *
     * [rootTag] 换成 `Dictionary` 就得到一个 `MdxKind.ARTICLE` 的容器（「把 .mdx 当 .mdd」的
     * fixture）；[withRecords] 为 false 则省略整个 record section，得到一个头部与 key section
     * 完整、但构造时读不到 record 前导的截断包。
     */
    private fun buildMdd(
        dir: File,
        name: String,
        entries: List<Pair<String, ByteArray>>,
        rootTag: String = "Library_Data",
        withRecords: Boolean = true,
    ): File {
        // record 拼接空间：record_offset 是「所有解压后 record block 顺序拼接」里的偏移。
        val records = ByteArrayOutputStream()
        val recordOffsets = ArrayList<Long>(entries.size)
        for ((_, body) in entries) {
            recordOffsets.add(records.size().toLong())
            records.write(body) // 裸字节：MDD 正文没有尾 NUL
        }
        val recordSpace = records.toByteArray()
        val recordBlock = zlibBlock(recordSpace)

        val keys = entries.map { it.first }
        val keyBlock = keyBlockBytes(keys, recordOffsets)
        val keyBlockComp = zlibBlock(keyBlock)

        val keyIndex = keyIndexBytes(keys, keyBlockComp.size, keyBlock.size)
        val keyIndexComp = zlibBlock(keyIndex)

        val preamble = ByteArrayOutputStream()
        writeLongBE(preamble, 1L) // key block 数
        writeLongBE(preamble, keys.size.toLong())
        writeLongBE(preamble, keyIndex.size.toLong())
        writeLongBE(preamble, keyIndexComp.size.toLong())
        writeLongBE(preamble, keyBlockComp.size.toLong())
        val preambleBytes = preamble.toByteArray()

        // MDD 与 MDX 的差别全在这行 header 里：根标签 Library_Data，key 恒为 UTF-16，StripKey="No"
        val xml = "<$rootTag GeneratedByEngineVersion=\"2.0\" Encoding=\"UTF-16\" " +
            "Title=\"$name\" KeyCaseSensitive=\"No\" StripKey=\"No\" NumEntries=\"${keys.size}\"/>"

        val file = File(dir, name)
        file.outputStream().use { out ->
            out.write(headerBytes(xml))
            out.write(preambleBytes)
            writeIntBE(out, MdxBlockReader.adler32(preambleBytes))
            out.write(keyIndexComp)
            out.write(keyBlockComp)
            if (withRecords) {
                out.write(recordSectionBytes(keys.size, recordBlock, recordSpace.size))
            }
        }
        return file
    }

    /** `u32be 长度 | UTF-16LE XML + \0 | u32le adler32(整段 XML，含 NUL)`。 */
    private fun headerBytes(xml: String): ByteArray {
        val body = (xml + "\u0000").toByteArray(Charsets.UTF_16LE)
        val out = ByteArrayOutputStream()
        writeIntBE(out, body.size)
        out.write(body)
        writeIntLE(out, MdxBlockReader.adler32(body))
        return out.toByteArray()
    }

    /** 解压后的 key block：逐条 `u64be record_offset | key(UTF-16LE) | 一个 NUL 编码单元`。 */
    private fun keyBlockBytes(keys: List<String>, recordOffsets: List<Long>): ByteArray {
        val out = ByteArrayOutputStream()
        for (i in keys.indices) {
            writeLongBE(out, recordOffsets[i])
            out.write(keys[i].toByteArray(Charsets.UTF_16LE))
            writeU16BE(out, 0)
        }
        return out.toByteArray()
    }

    /** 解压后的 key index：单 block 的 `条目数 / 首词 / 末词 / 压缩与解压长度`。 */
    private fun keyIndexBytes(keys: List<String>, blockCompSize: Int, blockDecompSize: Int): ByteArray {
        val out = ByteArrayOutputStream()
        writeLongBE(out, keys.size.toLong())
        // 长度按编码单位计：UTF-16 下是字符数，不含后面的 NUL 单元
        writeU16BE(out, keys.first().length)
        out.write(keys.first().toByteArray(Charsets.UTF_16LE))
        writeU16BE(out, 0)
        writeU16BE(out, keys.last().length)
        out.write(keys.last().toByteArray(Charsets.UTF_16LE))
        writeU16BE(out, 0)
        writeLongBE(out, blockCompSize.toLong())
        writeLongBE(out, blockDecompSize.toLong())
        return out.toByteArray()
    }

    /** record section 头 + 索引 + 单块；第三字段必须是 `16 * block 数`。 */
    private fun recordSectionBytes(entryCount: Int, recordBlock: ByteArray, decompSize: Int): ByteArray {
        val out = ByteArrayOutputStream()
        writeLongBE(out, 1L)
        writeLongBE(out, entryCount.toLong())
        writeLongBE(out, 16L)
        writeLongBE(out, decompSize.toLong())
        writeLongBE(out, recordBlock.size.toLong())
        writeLongBE(out, decompSize.toLong())
        out.write(recordBlock)
        return out.toByteArray()
    }

    /** `u32le 压缩类型 | u32be adler32(解压后字节) | zlib 流`。 */
    private fun zlibBlock(uncompressed: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION) // zlib 包装，非 raw deflate
        deflater.setInput(uncompressed)
        deflater.finish()
        val payload = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        while (!deflater.finished()) {
            val n = deflater.deflate(buf)
            payload.write(buf, 0, n)
        }
        deflater.end()
        val out = ByteArrayOutputStream()
        writeIntLE(out, 2) // MdxCompression.ZLIB
        writeIntBE(out, MdxBlockReader.adler32(uncompressed))
        out.write(payload.toByteArray())
        return out.toByteArray()
    }

    private fun writeIntBE(out: OutputStream, v: Int) {
        out.write((v ushr 24) and 0xff)
        out.write((v ushr 16) and 0xff)
        out.write((v ushr 8) and 0xff)
        out.write(v and 0xff)
    }

    private fun writeIntLE(out: OutputStream, v: Int) {
        out.write(v and 0xff)
        out.write((v ushr 8) and 0xff)
        out.write((v ushr 16) and 0xff)
        out.write((v ushr 24) and 0xff)
    }

    private fun writeLongBE(out: OutputStream, v: Long) {
        for (i in 7 downTo 0) out.write(((v ushr (i * 8)) and 0xff).toInt())
    }

    private fun writeU16BE(out: OutputStream, v: Int) {
        out.write((v ushr 8) and 0xff)
        out.write(v and 0xff)
    }

    private companion object {
        const val CSS = "body{font-family:'XHZD'}"

        /** PNG 签名 + 故意夹几个 `0x00`：MDD 正文不靠 NUL 结尾，读到 0 也不能提前截断。 */
        val PNG = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
        )
    }
}
