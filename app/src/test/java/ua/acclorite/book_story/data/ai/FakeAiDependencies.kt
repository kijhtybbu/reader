/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * fork 新增：AI 单测的假依赖（第二阶段 S6）。
 *
 * JVM 单测没有 Android Keystore，也没有 DataStore 的 Android 实现：
 * 用内存 map + 前缀式「密文」替代，只验证 AiKeyStore 的存取/清理逻辑。
 */

package ua.acclorite.book_story.data.ai

import androidx.datastore.preferences.core.Preferences
import ua.acclorite.book_story.data.storage.data_store.DataStore

@Suppress("UNCHECKED_CAST")
internal class FakeDataStore : DataStore {
    val values = mutableMapOf<Preferences.Key<*>, Any?>()

    override suspend fun <T> getNullableData(key: Preferences.Key<T>): T? {
        return values[key] as T?
    }

    override suspend fun <T> putData(key: Preferences.Key<T>, value: T) {
        values[key] = value
    }
}

internal class FakeAiKeyCipher : AiKeyCipher {
    override fun encrypt(plaintext: String): String = PREFIX + plaintext

    override fun decrypt(ciphertext: String): String {
        check(ciphertext.startsWith(PREFIX)) { "ciphertext was not produced by this cipher" }
        return ciphertext.removePrefix(PREFIX)
    }

    companion object {
        const val PREFIX = "enc:"
    }
}
