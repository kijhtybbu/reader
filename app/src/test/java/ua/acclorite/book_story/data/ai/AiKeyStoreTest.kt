/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 */

package ua.acclorite.book_story.data.ai

import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AiKeyStoreTest {

    private val dataStore = FakeDataStore()
    private val store = AiKeyStore(dataStore, FakeAiKeyCipher())
    private val key = stringPreferencesKey("ai_api_key_enc")

    @Test
    fun setEncryptsAndGetDecrypts() = runTest {
        store.set("  sk-test-key  ")

        assertEquals("enc:sk-test-key", dataStore.getNullableData(key))
        assertEquals("sk-test-key", store.get())
    }

    @Test
    fun missingKeyReturnsNull() = runTest {
        assertNull(store.get())
    }

    @Test
    fun blankValueClearsTheStoredKey() = runTest {
        store.set("sk-test-key")
        store.set("   ")

        assertEquals("", dataStore.getNullableData(key))
        assertNull(store.get())
    }

    @Test
    fun clearRemovesTheStoredKey() = runTest {
        store.set("sk-test-key")
        store.clear()

        assertEquals("", dataStore.getNullableData(key))
        assertNull(store.get())
    }

    @Test
    fun decryptFailureClearsTheStoredValueAndReturnsNull() = runTest {
        dataStore.putData(key, "not-a-valid-ciphertext")

        assertNull(store.get())
        assertEquals("", dataStore.getNullableData(key))
    }

    @Test
    fun classLoadingFailureDegradesToNoKeyInsteadOfCrashing() = runTest {
        // R8 把 Tink 的类全删了的时候抛的是 Error 不是 Exception；设置页不能因此崩掉。
        val crashing = AiKeyStore(dataStore, object : AiKeyCipher {
            override fun encrypt(plaintext: String): String = throw NoClassDefFoundError("tink")
            override fun decrypt(ciphertext: String): String = throw ExceptionInInitializerError("tink")
        })
        dataStore.putData(key, "ciphertext-from-another-install")

        assertNull(crashing.get())
        assertEquals("", dataStore.getNullableData(key))
    }
}
