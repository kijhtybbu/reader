package com.qiuminal.juicedict.engine

import com.qiuminal.juicedict.engine.mdict.MdxBlockReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.nio.file.Files
import java.util.zip.Deflater

/**
 * 合成 MDX 2.0 fixture：测试运行时用 `java.util.zip.Deflater` 现造一个合法容器，
 * 把 `probeMdx` → `openMdx` → `lookupSmart` → `article().toHtml()` 整条链路真正跑通。
 *
 * 为什么必须自己造：上游唯一打开真实 `.mdx` 的测试（`MdxLinkRewriteTest` 的语料回归）
 * 以本机 `E:\dictionary\MDict` 为语料，没有该目录的机器上直接 `return`——报成 passed
 * 而非 skipped。所以容器布局必须在这里被钉住，否则 S2 的「MDX fixture 查询/渲染通过」
 * 只是空话。
 *
 * 造出来的文件严格按 [MdxBlockReader] / `MdxHeader` / `MdxKeyIndex` / `MdxWordIndex` /
 * `MdxRecordStore` 的 on-disk 说明拼接（单 key block + 单 record block，zlib 压缩）：
 *
 * ```
 * u32be header_len | UTF-16LE <Dictionary …>\0 | u32le adler32(header_len 之后的那段)
 * -- key section --
 * u64be 1                     key block 数
 * u64be n                     key 数
 * u64be index_decomp_size
 * u64be index_comp_size
 * u64be key_block_comp_size
 * u32be adler32(前 40 字节)
 * <压缩的 key index block>
 * <压缩的 key block>
 * -- record section --
 * u64be 1 | u64be n | u64be 16 (== 16 * block 数) | u64be blocks_decomp_size
 * u64be record_block_comp_size | u64be record_block_decomp_size
 * <压缩的 record block>
 * ```
 *
 * 每个 block 的前缀都是 `u32le 压缩类型(2 = zlib) | u32be adler32(解压后字节)`；
 * key block 的解压内容是逐条 `u64be record_offset | key(UTF-8) | \0`，key index 的解压
 * 内容是一条 `u64be 条目数 | u16be 首词长 | 首词 \0 | u16be 末词长 | 末词 \0 | u64be
 * 压缩长度 | u64be 解压长度`（长度按编码单位计、不含 NUL 终结符）；record block 的解压
 * 内容是所有正文 `body(UTF-8) | \0` 顺序拼接，`record_offset` 就是正文在这段拼接空间里
 * 的偏移。
 */
class MdxFixtureTest {

    @Test
    fun probesAndRendersSyntheticMdx() {
        val dir = tempDir()
        val file = buildMdx(
            dir = dir,
            name = "fixture.mdx",
            entries = listOf(
                "greeting" to "@@@LINK=hello",
                "hello" to "<b>hello</b> world",
            ),
            title = "fixture",
            description = "synthetic single-block MDX",
        )

        val info = DictionaryEngines.probeMdx(file)
        assertNotNull("probeMdx must read a well-formed MDX", info)
        assertEquals("fixture", info!!.title)
        assertEquals("synthetic single-block MDX", info.description)
        assertEquals(2L, info.entryCount)

        val engine = DictionaryEngines.openMdx("fixture", file, emptyList(), "fixture", "d")
        try {
            assertEquals(2, engine.wordCount)

            val hits = engine.lookupSmart("hello", 5)
            assertEquals("hello", hits.first().word)
            val html = engine.article(hits.first()).toHtml()
            assertTrue("<b>hello</b> world expected, got $html", html.contains("<b>hello</b>"))
            assertTrue("body text must survive rendering, got $html", html.contains("world"))

            // `@@@LINK=` 重定向：正文来自 hello，词头仍是用户查的 greeting
            val redirect = engine.lookupSmart("greeting", 5)
            assertEquals("greeting", redirect.first().word)
            val redirected = engine.article(redirect.first()).toHtml()
            assertTrue("redirect must follow to hello's body, got $redirected", redirected.contains("<b>hello</b>"))
        } finally {
            engine.close()
        }
    }

    // ---- fixture builder ----------------------------------------------------

    private fun tempDir(): File = Files.createTempDirectory("mdx-fixture").toFile().also {
        it.deleteOnExit()
    }

    /** 拼一个可被引擎打开的 MDX；[entries] 必须已按 `MdxKeyOrder` 排好序。 */
    private fun buildMdx(
        dir: File,
        name: String,
        entries: List<Pair<String, String>>,
        title: String,
        description: String,
    ): File {
        // 正文拼接空间：record_offset 是「所有解压后 record block 顺序拼接」里的偏移。
        val records = ByteArrayOutputStream()
        val recordOffsets = ArrayList<Long>(entries.size)
        for ((_, body) in entries) {
            recordOffsets.add(records.size().toLong())
            records.write(body.toByteArray(Charsets.UTF_8))
            records.write(0) // MDX 正文以 NUL 结尾
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

        val xml = "<Dictionary GeneratedByEngineVersion=\"2.0\" Encoding=\"UTF-8\" " +
            "Title=\"$title\" Description=\"$description\" " +
            "KeyCaseSensitive=\"No\" StripKey=\"Yes\" NumEntries=\"${keys.size}\"/>"

        val file = File(dir, name)
        file.outputStream().use { out ->
            out.write(headerBytes(xml))
            out.write(preambleBytes)
            writeIntBE(out, MdxBlockReader.adler32(preambleBytes))
            out.write(keyIndexComp)
            out.write(keyBlockComp)
            out.write(recordSectionBytes(keys.size, recordBlock, recordSpace.size))
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

    /** 解压后的 key block：逐条 `u64be record_offset | key | \0`。 */
    private fun keyBlockBytes(keys: List<String>, recordOffsets: List<Long>): ByteArray {
        val out = ByteArrayOutputStream()
        for (i in keys.indices) {
            writeLongBE(out, recordOffsets[i])
            out.write(keys[i].toByteArray(Charsets.UTF_8))
            out.write(0)
        }
        return out.toByteArray()
    }

    /** 解压后的 key index：单 block 的 `条目数 / 首词 / 末词 / 压缩与解压长度`。 */
    private fun keyIndexBytes(keys: List<String>, blockCompSize: Int, blockDecompSize: Int): ByteArray {
        val first = keys.first().toByteArray(Charsets.UTF_8)
        val last = keys.last().toByteArray(Charsets.UTF_8)
        val out = ByteArrayOutputStream()
        writeLongBE(out, keys.size.toLong())
        writeU16BE(out, first.size) // 长度按编码单位（UTF-8 下即字节）计，不含 NUL
        out.write(first)
        out.write(0)
        writeU16BE(out, last.size)
        out.write(last)
        out.write(0)
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
}
