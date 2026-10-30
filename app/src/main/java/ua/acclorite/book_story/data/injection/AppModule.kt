/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 */

package ua.acclorite.book_story.data.injection

import android.app.Application
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import org.commonmark.node.BlockQuote
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.Heading
import org.commonmark.node.HtmlBlock
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.ThematicBreak
import org.commonmark.parser.Parser
import ua.acclorite.book_story.data.dictionary.DictionaryRepository
import ua.acclorite.book_story.data.settings.SettingsManager
import ua.acclorite.book_story.data.storage.room.AppDatabase
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideCommonmarkParser(): Parser {
        return Parser
            .builder()
            .enabledBlockTypes(
                setOf(
                    Heading::class.java,
                    HtmlBlock::class.java,
                    ThematicBreak::class.java,
                    BlockQuote::class.java,
                    FencedCodeBlock::class.java,
                    IndentedCodeBlock::class.java,
                    ThematicBreak::class.java
                )
            )
            .build()
    }

    @Provides
    @Singleton
    fun provideBookDatabase(application: Application): AppDatabase {
        return AppDatabase.buildDatabase(application)
    }

    /**
     * fork: 取词仓库（第二阶段 S5）。
     *
     * 进程内只允许一个实例：仓库自带导入词典的引擎 LRU（最多 2 个实例），多份仓库会各自缓存
     * 一份，同一个词典被打开两次、内存翻倍。优先词典用 lambda 传进去（而不是把当前值抄一份），
     * 这样设置页改完马上生效，不用重建仓库。
     */
    @Provides
    @Singleton
    fun provideDictionaryRepository(settings: SettingsManager): DictionaryRepository {
        return DictionaryRepository(
            activeDictionaryId = { settings.activeDictionaryId.lastValue }
        )
    }
}