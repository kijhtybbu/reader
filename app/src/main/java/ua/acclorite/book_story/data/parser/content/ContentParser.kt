/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 */

package ua.acclorite.book_story.data.parser.content

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.flow.Flow
import ua.acclorite.book_story.core.ui.UIText
import ua.acclorite.book_story.data.storage.file.CachedFile
import ua.acclorite.book_story.domain.model.book.CharsetConfidence
import ua.acclorite.book_story.domain.model.book.NodeContent
import ua.acclorite.book_story.domain.model.file.FilePath
import ua.acclorite.book_story.domain.model.file.ImageMetadata
import java.io.InputStream

@Immutable
sealed class ContentParserState {
    @Immutable
    data class Progress(val progress: Float) : ContentParserState()

    /**
     * fork 新增：TXT 编码的探测结果，在 [Success] 之前发出。
     *
     * 走的是同一条 Flow，而不是另起一个回调 —— 调用方（[ua.acclorite.book_story.domain.use_case.book.GetBookContentUseCase]）
     * 本来就在收集这条流来更新进度，把判定结果连同进度一起交回去，才能保证「解析用的编码」
     * 与「持久化下来的编码」是同一份数据。
     */
    @Immutable
    data class CharsetDetected(
        val charsetName: String,
        val confidence: CharsetConfidence
    ) : ContentParserState()

    @Immutable
    data class Success(val nodePaths: List<FilePath>) : ContentParserState()

    @Immutable
    data class Error(val message: UIText) : ContentParserState()
}

interface ContentParser {
    /**
     * @param charsetName 只有 TXT 会用到：书的编码（用户手动覆盖或上次探测的结果）。
     * null 表示「自动」，由 [TxtContentParser] 探测后通过 [ContentParserState.CharsetDetected] 报告。
     */
    fun parse(
        cachedFile: CachedFile,
        cacheImage: suspend (filename: String, inputStream: InputStream) -> ImageMetadata?,
        cacheNodes: suspend (List<NodeContent>) -> FilePath?,
        charsetName: String? = null
    ): Flow<ContentParserState>
}