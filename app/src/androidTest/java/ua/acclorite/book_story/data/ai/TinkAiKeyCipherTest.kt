/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * fork 新增：真实 Tink/Android Keystore 路径的仪器测试（第二阶段 S6，设计 §11）。
 *
 * 运行：`./gradlew :app:connectedDebugAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.class=ua.acclorite.book_story.data.ai.TinkAiKeyCipherTest`
 * 本环境没有设备，此测试只编译、未执行；PROGRESS.md 里按「未验证」记录。
 */

package ua.acclorite.book_story.data.ai

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TinkAiKeyCipherTest {

    private val application: Application =
        InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application

    @Test
    fun encryptsAndDecryptsWithAesGcm() {
        val cipher = TinkAiKeyCipher(application)
        val plaintext = "sk-test-abcdefghijklmnopqrstuvwxyz"

        val first = cipher.encrypt(plaintext)
        val second = cipher.encrypt(plaintext)

        assertNotEquals(plaintext, first)
        assertNotEquals("same plaintext must produce a fresh nonce", first, second)
        assertEquals(plaintext, cipher.decrypt(first))
        assertEquals(plaintext, cipher.decrypt(second))
    }

    @Test
    fun keysetSurvivesCipherInstances() {
        val first = TinkAiKeyCipher(application)
        val ciphertext = first.encrypt("sk-persisted")

        val second = TinkAiKeyCipher(application)
        assertEquals("sk-persisted", second.decrypt(ciphertext))
    }

    @Test
    fun garbageCiphertextIsRejected() {
        val cipher = TinkAiKeyCipher(application)

        assertThrows(Exception::class.java) {
            cipher.decrypt("not-a-valid-base64-ciphertext")
        }
    }
}
