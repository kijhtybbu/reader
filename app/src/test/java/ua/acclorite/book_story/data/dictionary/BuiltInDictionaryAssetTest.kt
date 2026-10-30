/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * fork 新增：随包 ECDICT 资产的字节级回归（第二阶段 S3）。
 *
 * 钉住 `NOTICE.txt` 公布的 SHA-256/大小：换资产却没更新许可、或者脚本重建出了不同的字节，
 * 都会在这里报错，而不是等到用户装上以后才发现「内置词典打不开」。
 * 重建方法见 `tools/dict/prepare_ecdict.py`（固定 mtime + ustar 中转，同一份 CSV 恒定同一 SHA）。
 */

package ua.acclorite.book_story.data.dictionary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

class BuiltInDictionaryAssetTest {

    private val assetDir = File("src/main/assets/dictionaries/ecdict")
    private val asset = File(assetDir, "stardict.7z")
    private val notice = File(assetDir, "NOTICE.txt")

    /** 与 `NOTICE.txt` 里 `stardict.7z SHA-256` 那一行一致；换资产时两处一起改。 */
    private val expectedSha256 = "91c1367ce0b65562bd3a1a98ba1d84f351b067287bb40513eb6cd35bd4c46aec"
    private val expectedSize = 21_112_502L

    @Test
    fun shippedAssetMatchesNotice() {
        assertTrue("asset is missing: ${asset.absolutePath}", asset.isFile)
        assertEquals("shipped asset size changed", expectedSize, asset.length())
        assertEquals("shipped asset SHA-256 changed", expectedSha256, sha256(asset))

        val published = notice.readText()
        assertTrue(
            "NOTICE.txt must publish the asset SHA-256, got: $published",
            published.contains(expectedSha256)
        )
        assertTrue(
            "NOTICE.txt must publish the asset size, got: $published",
            published.contains(expectedSize.toString())
        )
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(256 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
