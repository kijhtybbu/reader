/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * fork 新增：Room 21 → 22 自动迁移的仪器测试（收藏夹）。
 *
 * 这次迁移同样只是建一张新表，但它有两个索引（唯一索引 + createdAt），
 * 而且收藏数据一旦在升级时丢了，用户是感知不到的（列表直接变空）。
 * 因此对着导出的 21/22 schema 实际跑一次，确认旧数据还在、新表可写。
 *
 * 运行：`./gradlew :app:connectedDebugAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.class=ua.acclorite.book_story.data.repository.FavoriteMigrationTest`
 */

package ua.acclorite.book_story.data.repository

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
class FavoriteMigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test
    fun migration21To22AddsTheFavoriteTableAndKeepsExistingRows() {
        helper.createDatabase(TEST_DATABASE, 21).use { db ->
            db.execSQL(
                """
                INSERT INTO CategoryEntity (id, title, `order`, sortOrder, sortOrderDescending)
                VALUES (7, 'kept', 1, 'LAST_READ', 1)
                """
            )
        }

        helper.runMigrationsAndValidate(TEST_DATABASE, 22, true).use { db ->
            db.query("SELECT COUNT(*) FROM CategoryEntity").use { cursor ->
                cursor.moveToFirst()
                assertEquals("existing rows must survive the migration", 1, cursor.getInt(0))
            }

            db.query(
                "SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'favoriteentity'"
            ).use { cursor ->
                assertTrue("favoriteentity table must exist", cursor.moveToFirst())
            }

            // 列写一遍：以后有人改实体却忘了迁移，这里会先炸。
            db.execSQL(
                """
                INSERT INTO favoriteentity (
                    type, text, phonetic, definition, translation, grammar, createdAt
                ) VALUES ('WORD', 'apple', '/ˈæpl/', 'n. 苹果', NULL, NULL, 10)
                """
            )
            // 唯一索引 (type, text) 必须生效：同一词再插一次应当被拒。
            val duplicateRejected = try {
                db.execSQL(
                    """
                    INSERT INTO favoriteentity (
                        type, text, phonetic, definition, translation, grammar, createdAt
                    ) VALUES ('WORD', 'apple', NULL, NULL, NULL, NULL, 11)
                    """
                )
                false
            } catch (e: Exception) {
                true
            }
            assertTrue("the unique (type, text) index must reject duplicates", duplicateRejected)

            db.query("SELECT COUNT(*) FROM favoriteentity").use { cursor ->
                cursor.moveToFirst()
                assertEquals(1, cursor.getInt(0))
            }
        }
    }

    private companion object {
        const val TEST_DATABASE = "favorite-migration-test.db"
    }
}
