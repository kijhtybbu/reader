/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * fork 新增：Room 20 → 21 自动迁移的仪器测试（第二阶段 S7，设计 §7.1/§11）。
 *
 * `AutoMigration(20, 21)` 只是建一张新表，但「自动迁移」是大版本升级时最容易静默出错的环节
 * （schema 改错、identity hash 对不上、丢列），因此用 `MigrationTestHelper` 对着导出的
 * `schemas/….AppDatabase/20.json` 与 `21.json` 实际跑一次并校验表结构。
 *
 * 运行：`./gradlew :app:connectedDebugAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.class=ua.acclorite.book_story.data.ai.cache.TranslationCacheMigrationTest`
 */

package ua.acclorite.book_story.data.ai.cache

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ua.acclorite.book_story.data.storage.room.AppDatabase

@RunWith(AndroidJUnit4::class)
class TranslationCacheMigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test
    fun migration20To21AddsTheCacheTableAndKeepsExistingRows() {
        helper.createDatabase(TEST_DATABASE, 20).use { db ->
            db.execSQL(
                """
                INSERT INTO CategoryEntity (id, title, `order`, sortOrder, sortOrderDescending)
                VALUES (42, 'kept', 3, 'LAST_READ', 1)
                """
            )
        }

        helper.runMigrationsAndValidate(TEST_DATABASE, 21, true).use { db ->
            db.query("SELECT COUNT(*) FROM CategoryEntity").use { cursor ->
                cursor.moveToFirst()
                assertEquals("existing rows must survive the migration", 1, cursor.getInt(0))
            }

            db.query(
                "SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'translationcacheentity'"
            ).use { cursor ->
                assertTrue("translationcacheentity table must exist", cursor.moveToFirst())
            }

            // 新表的列写一遍：以后有人改实体却忘了迁移，这里会先炸。
            db.execSQL(
                """
                INSERT INTO translationcacheentity (
                    cacheKey, bookId, nodeId, kind, model, baseUrlHost,
                    targetLanguage, promptVersion, payload, complete, createdAt, lastAccessedAt
                ) VALUES ('key', 1, 2, 'TRANSLATION', 'gpt-4o-mini', 'api.openai.com',
                          'English (en)', 1, 'hi', 1, 10, 11)
                """
            )
            db.query("SELECT COUNT(*) FROM translationcacheentity").use { cursor ->
                cursor.moveToFirst()
                assertEquals(1, cursor.getInt(0))
            }
        }
    }

    private companion object {
        const val TEST_DATABASE = "translation-cache-migration-test.db"
    }
}
