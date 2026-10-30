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
import io.ktor.client.engine.okhttp.OkHttp
import org.commonmark.node.BlockQuote
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.Heading
import org.commonmark.node.HtmlBlock
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.ThematicBreak
import org.commonmark.parser.Parser
import ua.acclorite.book_story.data.ai.AiClientConfig
import ua.acclorite.book_story.data.ai.AiKeyCipher
import ua.acclorite.book_story.data.ai.AiKeyStore
import ua.acclorite.book_story.data.ai.AiTranslationRepository
import ua.acclorite.book_story.data.ai.TinkAiKeyCipher
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

    /**
     * fork: API Key 加密（第二阶段 S6）。
     *
     * Tink 只在 Android 上可用；JVM 单测自己构造 [AiKeyStore] 并注入假 [AiKeyCipher]。
     */
    @Provides
    @Singleton
    fun provideAiKeyCipher(application: Application): AiKeyCipher {
        return TinkAiKeyCipher(application)
    }

    /**
     * fork: AI 调用仓库（第二阶段 S6）。
     *
     * 配置用 lambda 现取现用：设置页改完 Base URL/模型后，下一次请求立即生效，
     * 不用重建仓库（Key 每次请求从 [AiKeyStore] 解密）。
     *
     * `engine` 显式传 OkHttp（S1 spike 的选择，`ktor-client-okhttp` 已在依赖里）：不传的话
     * Ktor 靠 ServiceLoader 发现引擎，R8 之后是编译期发现不了的运行时行为，单元测试也盖不到。
     */
    @Provides
    @Singleton
    fun provideAiTranslationRepository(
        settings: SettingsManager,
        keyStore: AiKeyStore
    ): AiTranslationRepository {
        return AiTranslationRepository(
            config = {
                AiClientConfig(
                    baseUrl = settings.aiBaseUrl.lastValue,
                    model = settings.aiModel.lastValue,
                    sessionId = settings.aiSessionId.lastValue,
                    reasoningEffort = settings.aiReasoningEffort.lastValue
                )
            },
            keyStore = keyStore,
            engine = OkHttp.create(),
            uiLocale = { settings.language.lastValue.locale }
        )
    }
}