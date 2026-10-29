package com.qiuminal.juicedict.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.zip.GZIPOutputStream

/**
 * 本地新增适配层 [DictionaryEngines] 的回归：上游把 `MdxDictionary` / `MdxProbe` 标为
 * `internal`，app 只能经这几个入口构造引擎，所以「用真实文件目录开一部 StarDict 并渲染
 * 正文」这条路径必须在这里被钉住。
 *
 * StarDict 侧覆盖 `.ifo` + `.idx` / `.idx.gz` + 可选 `.syn` + `.dict` 的装配分支；
 * `.dict.dz`（dictzip）分支不在这里造，留到 S3 用真实 ECDICT 资产覆盖（`DictZipReader`
 * 本身由上游 `DictZipLastChunkTest` 单独测过，适配层只是选文件）。
 */
class DictionaryEnginesTest {

    private fun writeIntBE(out: ByteArrayOutputStream, v: Int) {
        out.write((v ushr 24) and 0xff)
        out.write((v ushr 16) and 0xff)
        out.write((v ushr 8) and 0xff)
        out.write(v and 0xff)
    }

    /** `.idx` 与 `.dict` 两份字节：词头 NUL 结尾 + u32be 偏移 + u32be 长度。 */
    private fun starDictBytes(entries: List<Pair<String, String>>): Pair<ByteArray, ByteArray> {
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
        return idx.toByteArray() to dict.toByteArray()
    }

    private fun writeIfo(
        dir: File,
        base: String,
        wordCount: Int,
        idxFileSize: Int,
        synWordCount: Int? = null,
    ) {
        File(dir, "$base.ifo").writeText(
            buildString {
                appendLine("StarDict's dict ifo file")
                appendLine("version=3.0.0")
                appendLine("bookname=$base")
                appendLine("wordcount=$wordCount")
                appendLine("idxfilesize=$idxFileSize")
                if (synWordCount != null) appendLine("synwordcount=$synWordCount")
                appendLine("sametypesequence=h")
            },
        )
    }

    /** 在 [dir] 里写一部最小 StarDict：`<base>.ifo` / `.idx` / `.dict`。 */
    private fun writeStarDict(dir: File, base: String, entries: List<Pair<String, String>>) {
        val (idx, dict) = starDictBytes(entries)
        File(dir, "$base.dict").writeBytes(dict)
        File(dir, "$base.idx").writeBytes(idx)
        writeIfo(dir, base, entries.size, idx.size)
    }

    private fun tempDir(): File = Files.createTempDirectory("dict-engine").toFile().also {
        it.deleteOnExit()
    }

    @Test
    fun openStarDictReadsDirectoryAndRendersArticle() {
        val dir = tempDir()
        writeStarDict(
            dir,
            "fixture",
            listOf("hello" to "<b>hello</b> 你好。", "helium" to "<b>helium</b> 氦。"),
        )

        val engine = DictionaryEngines.openStarDict("fixture", dir, "fixture")
        assertNotNull("adapter must open a well-formed StarDict directory", engine)
        val opened = engine ?: error("openStarDict returned null")
        try {
            assertEquals("fixture", opened.id)
            assertEquals(2, opened.wordCount)
            val hits = opened.lookupSmart("hello", 5)
            assertEquals("hello", hits.first().word)
            assertTrue(opened.article(hits.first()).toHtml().contains("<b>hello</b>"))
        } finally {
            opened.close()
        }
        // 索引缓存写在词典目录旁，下次启动免去 .idx 重解析与排序
        assertTrue("expected a .jidx cache", File(dir, "fixture.jidx").isFile)
    }

    /**
     * `.idx.gz` + `.syn` 分支：上游仓库对这两个文件都有装配逻辑，但没有任何测试走
     * `DictionaryEngines` 这条路径（`StarDictSynTest` 直接构造索引）。
     */
    @Test
    fun openStarDictReadsGzippedIndexAndSynAlias() {
        val dir = tempDir()
        val entries = listOf(
            "hello" to "<b>hello</b> 你好。",
            "helium" to "<b>helium</b> 氦。",
        )
        val (idx, dict) = starDictBytes(entries)
        File(dir, "gz.dict").writeBytes(dict)
        GZIPOutputStream(File(dir, "gz.idx.gz").outputStream()).use { it.write(idx) }
        // .syn：别名 NUL 结尾 + u32be 指向 .idx 文件序的条目号
        val syn = ByteArrayOutputStream()
        syn.write("hi".toByteArray(StandardCharsets.UTF_8))
        syn.write(0)
        writeIntBE(syn, 0) // -> hello
        File(dir, "gz.syn").writeBytes(syn.toByteArray())
        writeIfo(dir, "gz", entries.size, idx.size, synWordCount = 1)

        val engine = DictionaryEngines.openStarDict("gz", dir, "gz")
        assertNotNull("adapter must open a .idx.gz + .syn dictionary", engine)
        val opened = engine ?: error("openStarDict returned null")
        // openStarDict 总是返回 StarDict 门面；lookupExact / lookupPrefix 只在那里有
        val starDict = opened as StarDict
        try {
            assertEquals(2, opened.wordCount)
            val exact = starDict.lookupExact("helium", 5)
            assertEquals("helium", exact.first().word)
            assertTrue(opened.article(exact.first()).toHtml().contains("<b>helium</b>"))

            // 别名命中：词头是用户输入的别名，正文来自目标词条
            val alias = opened.lookupSmart("hi", 5)
            assertEquals("hi", alias.first().word)
            assertTrue(opened.article(alias.first()).toHtml().contains("<b>hello</b>"))
        } finally {
            opened.close()
        }

        // 第二次打开走 .jidx 缓存（含 .syn 别名），结果必须一致
        val cached = DictionaryEngines.openStarDict("gz", dir, "gz")
        assertNotNull("adapter must reopen from the .jidx cache", cached)
        val reopened = cached ?: error("openStarDict returned null")
        try {
            assertEquals("hi", reopened.lookupSmart("hi", 5).first().word)
        } finally {
            reopened.close()
        }
    }

    @Test
    fun openStarDictReturnsNullInsteadOfThrowing() {
        val empty = tempDir()
        assertNull(DictionaryEngines.openStarDict("none", empty, "none"))

        // 只有 .ifo、没有 .idx/.dict：同样返回 null，不把异常抛给调用方
        File(empty, "half.ifo").writeText("bookname=half\nwordcount=1\nsametypesequence=h\n")
        assertNull(DictionaryEngines.openStarDict("half", empty, "half"))
    }

    /**
     * 被吞掉的异常必须能从 `logSink` 看到：S4 的「损坏/加密/空间不足错误明确」全靠它。
     */
    @Test
    fun openStarDictReportsSwallowedFailureThroughLogSink() {
        val dir = tempDir()
        File(dir, "broken.ifo").writeText("bookname=broken\nwordcount=1\nsametypesequence=h\n")
        // .idx.gz 不是 gzip：索引解压直接抛异常
        File(dir, "broken.idx.gz").writeText("not a gzip stream")
        File(dir, "broken.dict").writeBytes(byteArrayOf(0))

        val messages = ArrayList<String>()
        DictionaryEngines.logSink = { tag, message -> messages.add("$tag: $message") }
        try {
            assertNull(DictionaryEngines.openStarDict("broken", dir, "broken"))
        } finally {
            DictionaryEngines.logSink = null
        }
        assertTrue("expected a diagnostic, got $messages", messages.isNotEmpty())
        assertTrue(
            "diagnostic must name the dictionary, got $messages",
            messages.any { it.contains("broken") },
        )
    }

    @Test
    fun mddFilesForPairsBaseAndNumberedPacks() {
        val dir = tempDir()
        for (name in listOf("dict.mdd", "dict.2.mdd", "dict.1.mdd", "dict.10.mdd", "other.mdd")) {
            File(dir, name).writeBytes(ByteArray(0))
        }
        assertEquals(
            listOf("dict.mdd", "dict.1.mdd", "dict.2.mdd", "dict.10.mdd"),
            DictionaryEngines.mddFilesFor(dir, "dict").map { it.name },
        )
        assertTrue(DictionaryEngines.mddFilesFor(dir, "missing").isEmpty())
    }

    @Test
    fun probeMdxRejectsNonMdictFiles() {
        val file = File.createTempFile("not-an-mdx", ".mdx")
        file.deleteOnExit()
        file.writeText("plain text, not an MDict container")
        assertNull(DictionaryEngines.probeMdx(file))
        assertNull(DictionaryEngines.probeMdxHead(file.readBytes()))
    }

    @Test
    fun openMdxRejectsNonMdictFiles() {
        val file = File.createTempFile("not-an-mdx", ".mdx")
        file.deleteOnExit()
        file.writeText("plain text, not an MDict container")
        val thrown = runCatching {
            DictionaryEngines.openMdx("bad", file, emptyList(), "t", "d").close()
        }.exceptionOrNull()
        assertTrue("expected a rejection, got ${thrown?.message}", thrown is IllegalStateException)
    }
}
