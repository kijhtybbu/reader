/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * fork 新增：Tink AndroidKeysetManager 的 AES256-GCM 实现（第二阶段 S6，设计 §6.2）。
 *
 * Keyset 存在应用私有的 SharedPreferences 里，主密钥在 Android Keystore
 * （`android-keystore://...`）；DataStore 里只放 base64 密文。
 * 本类只在 Android 上可用，JVM 单测走 [AiKeyCipher] 的假实现。
 */

package ua.acclorite.book_story.data.ai

import android.app.Application
import android.util.Base64
import com.google.crypto.tink.Aead
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.aead.AeadKeyTemplates
import com.google.crypto.tink.integration.android.AndroidKeysetManager

private const val KEYSET_NAME = "ai_api_key_keyset"
private const val KEYSET_PREF_FILE = "ai_api_key_keyset_prefs"
private const val MASTER_KEY_URI = "android-keystore://ai_api_key_master_key"
private val ASSOCIATED_DATA = "ai_api_key".toByteArray(Charsets.UTF_8)

class TinkAiKeyCipher(application: Application) : AiKeyCipher {

    // 懒初始化：只在第一次真正读写 Key 时碰 Keystore，不拖慢应用启动。
    private val aead: Aead by lazy {
        AeadConfig.register()
        val keysetHandle = AndroidKeysetManager.Builder()
            .withSharedPref(application, KEYSET_NAME, KEYSET_PREF_FILE)
            .withKeyTemplate(AeadKeyTemplates.AES256_GCM)
            .withMasterKeyUri(MASTER_KEY_URI)
            .build()
            .keysetHandle
        keysetHandle.getPrimitive(RegistryConfiguration.get(), Aead::class.java)
    }

    override fun encrypt(plaintext: String): String {
        val encrypted = aead.encrypt(plaintext.toByteArray(Charsets.UTF_8), ASSOCIATED_DATA)
        return Base64.encodeToString(encrypted, Base64.NO_WRAP)
    }

    override fun decrypt(ciphertext: String): String {
        val decrypted = aead.decrypt(Base64.decode(ciphertext, Base64.NO_WRAP), ASSOCIATED_DATA)
        return String(decrypted, Charsets.UTF_8)
    }
}
