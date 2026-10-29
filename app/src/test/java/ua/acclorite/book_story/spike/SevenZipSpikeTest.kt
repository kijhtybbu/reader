/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * fork 新增：commons-compress + xz 的 S1 依赖 spike（第二阶段）。
 *
 * 内置 ECDICT 以官方 `stardict.7z` 分发，首次启动要解压。7z 的 LZMA2 解码器在
 * commons-compress 里是**反射探测** `org.tukaani.xz` 的，缺依赖时不是编译错误而是
 * 运行时 "No XZ support in the classpath"。所以这里必须真的写一份 7z 再读回来。
 */

package ua.acclorite.book_story.spike

import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.sevenz.SevenZMethod
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class SevenZipSpikeTest {

    @Test
    fun writesAndReadsBackLzma2Archive() {
        val dir = Files.createTempDirectory("sevenzip-spike").toFile()
        try {
            val archive = dir.resolve("spike.7z")
            val payload = "apple\tn. 苹果\n".repeat(64).toByteArray()
            val source = dir.resolve("stardict.ifo").apply { writeBytes(payload) }

            SevenZOutputFile(archive).use { out ->
                // 显式钉住 LZMA2：默认方法随版本变过，spike 要验证的正是 LZMA2 + xz。
                out.setContentCompression(SevenZMethod.LZMA2)
                // createArchiveEntry 会把 size/hasStream 一起填好；手写 SevenZArchiveEntry 时
                // hasStream 默认 false，写出去的内容会被当成目录条目静默丢掉。
                out.putArchiveEntry(out.createArchiveEntry(source, "stardict.ifo"))
                out.write(payload)
                out.closeArchiveEntry()
            }

            // 压缩过才有意义：内容高度重复，LZMA2 必须让它明显小于原文。
            assertTrue(
                "archive should be compressed, size=${archive.length()}",
                archive.length() < payload.size
            )

            val readBack = SevenZFile.builder().setFile(archive).get().use { sevenZFile ->
                val entry = sevenZFile.nextEntry
                assertEquals("stardict.ifo", entry.name)
                val buffer = ByteArray(entry.size.toInt())
                var offset = 0
                while (offset < buffer.size) {
                    val read = sevenZFile.read(buffer, offset, buffer.size - offset)
                    if (read < 0) break
                    offset += read
                }
                buffer.copyOf(offset)
            }

            assertEquals(payload.toString(Charsets.UTF_8), readBack.toString(Charsets.UTF_8))
        } finally {
            dir.deleteRecursively()
        }
    }
}
