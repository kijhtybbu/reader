/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * fork 新增：许可/Credits 清单的守门测试（第二阶段 S9）。
 *
 * AboutLibraries 的资源是**随仓库提交**的 `res/raw/aboutlibraries.json`（插件只生成一次，
 * 不是每次构建重新生成）。S9 手工补的 ECDICT / juicedict 引擎 / openai-kotlin / Ktor /
 * Tink / commons-compress / xz 条目一旦被下一次重新生成冲掉，这个测试就会红。
 */

package ua.acclorite.book_story.presentation.licenses

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertTrue
import org.junit.Test

class LicensesCreditsTest {

    private val creditsFile = File("src/main/res/raw/aboutlibraries.json")

    private val root: JsonObject by lazy {
        Json.parseToJsonElement(creditsFile.readText()).jsonObject
    }

    @Test
    fun everyManualCreditIsPresent() {
        val libraries = root["libraries"]!!.jsonArray
            .map { it.jsonObject }
            .associateBy { it["uniqueId"]!!.jsonPrimitive.content }

        val expected = mapOf(
            "ecdict" to "MIT",
            "com.qiuminal:juicedict-engine" to "GPL-3.0-only",
            "com.aallam.openai:openai-client" to "MIT",
            "io.ktor:ktor-client-okhttp" to "Apache-2.0",
            "com.google.crypto.tink:tink-android" to "Apache-2.0",
            "org.apache.commons:commons-compress" to "Apache-2.0",
            "org.tukaani:xz" to "Public-Domain"
        )

        expected.forEach { (uniqueId, licenseId) ->
            val entry = libraries[uniqueId]
                ?: error("missing credits entry: $uniqueId")
            val licenses = entry["licenses"]!!.jsonArray.map { it.jsonPrimitive.content }
            assertTrue(
                "$uniqueId must list $licenseId, got $licenses",
                licenses.contains(licenseId)
            )
            assertTrue(
                "$uniqueId needs an SCM url, otherwise AboutLibraries renders it as closed source",
                !entry["scm"]?.jsonObject?.get("url")?.jsonPrimitive?.content.isNullOrBlank()
            )
        }
    }

    @Test
    fun everyReferencedLicenseHasADefinition() {
        val defined = root["licenses"]!!.jsonObject.keys

        root["libraries"]!!.jsonArray
            .map { it.jsonObject }
            .forEach { library ->
                library["licenses"]?.jsonArray?.forEach { license ->
                    val id = license.jsonPrimitive.content
                    assertTrue(
                        "license $id referenced by ${library["uniqueId"]} has no definition",
                        defined.contains(id)
                    )
                }
            }
    }
}
