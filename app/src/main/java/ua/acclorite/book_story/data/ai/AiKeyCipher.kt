/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * fork 新增：API Key 的加解密接缝（第二阶段 S6，设计 §6.2）。
 *
 * 单测跑在 JVM 上，没有 Android Keystore，所以把 Tink 藏在接口后面：
 * 生产用 [TinkAiKeyCipher]，测试用假实现。返回值是可直接存 DataStore 的字符串。
 */

package ua.acclorite.book_story.data.ai

interface AiKeyCipher {
    fun encrypt(plaintext: String): String

    /** 密文损坏/来自另一台设备时抛异常；调用方负责清理存量值。 */
    fun decrypt(ciphertext: String): String
}
