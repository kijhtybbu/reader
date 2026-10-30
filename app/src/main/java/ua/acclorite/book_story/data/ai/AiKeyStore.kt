/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * fork 新增：加密保存的 API Key（第二阶段 S6，设计 §6.2）。
 *
 * DataStore 里只有 [AiKeyCipher] 产出的 base64 密文。解密失败（Keystore 被清、恢复备份
 * 到另一台设备、密文被手改）时清掉存量并返回 null，让设置页提示重填 —— 不能把密文
 * 当 Key 发出去，也不能在日志里打印明文/密文。
 *
 * 加解密失败要按 [Throwable] 捕：R8 把 Tink 的类全部删掉时抛的是 `NoClassDefFoundError` /
 * `ExceptionInInitializerError`，它们是 Error 不是 Exception；只捕 Exception 会让设置页直接崩。
 * 取消（CancellationException）仍然原样抛出，不当作「没有 Key」。
 *
 * [set]/[clear] 用同一把 [Mutex] 串行化：`set` 先加密（IO 线程）再写入，没有锁的话
 * 一个早开始的 `set` 可能在 `clear` 之后落地，把用户刚清掉的 Key 又写回去。
 */

package ua.acclorite.book_story.data.ai

import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import ua.acclorite.book_story.core.log.logW
import ua.acclorite.book_story.data.storage.data_store.DataStore
import javax.inject.Inject

private const val TAG = "AiKeyStore"
private val AI_API_KEY = stringPreferencesKey("ai_api_key_enc")

class AiKeyStore @Inject constructor(
    private val dataStore: DataStore,
    private val cipher: AiKeyCipher
) {

    private val mutex = Mutex()

    /** 没有 Key、密文为空或解密失败都返回 null；解密失败会顺手清掉存量。 */
    suspend fun get(): String? {
        val encrypted = dataStore.getNullableData(AI_API_KEY)
        if (encrypted.isNullOrBlank()) return null

        return try {
            withContext(Dispatchers.IO) { cipher.decrypt(encrypted) }.takeIf { it.isNotBlank() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            logW(TAG, "Could not decrypt the AI API key; clearing the stored value.")
            try {
                clear()
            } catch (clearError: CancellationException) {
                throw clearError
            } catch (clearError: Throwable) {
                logW(TAG, "Could not clear the stored AI API key.")
            }
            null
        }
    }

    /** 空白值等同于 [clear]，避免把空字符串加密后当成「已配置」。 */
    suspend fun set(value: String) {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) {
            clear()
            return
        }
        mutex.withLock {
            val encrypted = withContext(Dispatchers.IO) { cipher.encrypt(trimmed) }
            dataStore.putData(AI_API_KEY, encrypted)
        }
    }

    // DataStore 接口没有 remove；空字符串就是「没有 Key」，get() 会把它当成 null。
    suspend fun clear() {
        mutex.withLock {
            dataStore.putData(AI_API_KEY, "")
        }
    }
}
