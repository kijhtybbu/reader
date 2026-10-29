/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * fork 新增：Tink 的 S1 依赖 spike（第二阶段）。
 *
 * S6 要把 API Key 以 AES256-GCM 密文存进 DataStore。这里先在纯 JVM 上跑通「注册原语 →
 * 生成 keyset → 加解密」，不碰 AndroidKeysetManager（那是 Android-only，留给仪器测试）。
 *
 * 注意：tink-android 1.18.0 里 `Registry.register(AeadConfig)` 已不存在，`getPrimitive(Class)`
 * 也已废弃；只能用 `AeadConfig.register()` + `KeysetHandle.generateNew(Parameters)` +
 * `getPrimitive(RegistryConfiguration.get(), Aead::class.java)`。
 */

package ua.acclorite.book_story.spike

import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.aead.PredefinedAeadParameters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class TinkSpikeTest {

    @Test
    fun encryptsAndDecryptsWithAes256Gcm() {
        AeadConfig.register()
        val keysetHandle = KeysetHandle.generateNew(PredefinedAeadParameters.AES256_GCM)
        val aead = keysetHandle.getPrimitive(RegistryConfiguration.get(), Aead::class.java)

        val plaintext = "sk-test-abcdefghijklmnopqrstuvwxyz"
        val associatedData = "api-key".toByteArray()

        // 同一明文加密两次必须得到不同密文：AES-GCM 每次加密都会生成新 nonce。
        // 这比“密文 != 明文”强得多，后者连空实现都能过。
        val first = aead.encrypt(plaintext.toByteArray(), associatedData)
        val second = aead.encrypt(plaintext.toByteArray(), associatedData)
        assertFalse(
            "same plaintext must encrypt to different ciphertexts (fresh nonce per call)",
            first.contentEquals(second)
        )

        assertEquals(plaintext, aead.decrypt(first, associatedData).toString(Charsets.UTF_8))
        assertEquals(plaintext, aead.decrypt(second, associatedData).toString(Charsets.UTF_8))
    }
}
