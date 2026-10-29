/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 */

package ua.acclorite.book_story.data.parser.content

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import ua.acclorite.book_story.R
import ua.acclorite.book_story.core.helpers.containsVisibleText
import ua.acclorite.book_story.core.log.logE
import ua.acclorite.book_story.core.log.logI
import ua.acclorite.book_story.core.ui.UIText
import ua.acclorite.book_story.data.storage.file.CachedFile
import ua.acclorite.book_story.domain.model.book.NodeContent
import ua.acclorite.book_story.domain.model.file.FilePath
import ua.acclorite.book_story.domain.model.file.ImageMetadata
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.SequenceInputStream
import java.nio.charset.Charset
import javax.inject.Inject

private const val TAG = "TxtContentParser"
private const val NODES_CHUNK_SIZE = 30000 // Each file is ~50 KB
private const val SAMPLE_CHUNK_SIZE = 8192

class TxtContentParser @Inject constructor() : ContentParser {

    override fun parse(
        cachedFile: CachedFile,
        cacheImage: suspend (filename: String, inputStream: InputStream) -> ImageMetadata?,
        cacheNodes: suspend (List<NodeContent>) -> FilePath?,
        charsetName: String?
    ): Flow<ContentParserState> = flow {
        try {
            logI(TAG, "Started TXT parsing of [${cachedFile.name}].")
            emit(ContentParserState.Progress(0.0f))

            val nodePaths = mutableListOf<FilePath>()
            var cacheFailures = 0

            currentCoroutineContext().ensureActive()

            cachedFile.openInputStream()?.use { input ->
                // fork: 编码由「书」决定。书的 charsetName 是用户手动覆盖或上次探测的结果，
                // 为 null 时才重新探测（只读前 100 KB，不读整个文件）。
                val sample = input.readSample(TXT_CHARSET_SAMPLE_SIZE)
                val guess = if (charsetName == null) detectTxtCharset(sample) else null
                guess?.let {
                    emit(ContentParserState.CharsetDetected(it.charset.name(), it.confidence))
                    logI(
                        TAG,
                        "Detected charset [${it.charset.name()}] with confidence " +
                                "[${it.confidence}], BOM=[${it.hadBom}]."
                    )
                }

                val charset = guess?.charset
                    ?: runCatching { Charset.forName(charsetName) }.getOrElse {
                        // fork: 只可能来自损坏的数据库行；退回 UTF-8 并记日志，
                        // 不要在解析途中抛异常把书变成「无法解析」。
                        logE(TAG, "Unknown charset [$charsetName], falling back to UTF-8.")
                        Charsets.UTF_8
                    }

                // fork: BOM 不是正文。按最终生效的编码丢掉它（手动覆盖时没有探测结果，
                // 所以不能只看 guess.hadBom），否则正文开头会多出一个 U+FEFF。
                val bomLength = txtBomPrefixLength(sample, charset)
                val payload = SequenceInputStream(
                    ByteArrayInputStream(sample, bomLength, sample.size - bomLength),
                    input
                )

                payload.bufferedReader(charset).use { reader ->
                    emit(ContentParserState.Progress(0.5f))

                    reader.lineSequence().chunked(NODES_CHUNK_SIZE).forEach { chunk ->
                        cacheNodes(
                            chunk.mapNotNull { line ->
                                if (!line.containsVisibleText()) return@mapNotNull null
                                NodeContent.Paragraph(text = line, styles = emptyList())
                            }
                        )?.let { path -> nodePaths.add(path) } ?: run { cacheFailures++ }
                    }
                }
            } ?: throw IllegalStateException("Could not open Input Stream in TXT.")

            currentCoroutineContext().ensureActive()

            if (nodePaths.isEmpty()) {
                // fork: 区分两种完全不同的失败。缓存写失败被报成「文件似乎为空」会把用户
                // 引向错误的排查方向（release 构建里 R8 破坏 protobuf 序列化时正是如此）。
                if (cacheFailures > 0) {
                    emit(
                        ContentParserState.Error(
                            UIText.StringResource(R.string.error_could_not_save_file, "")
                        )
                    )
                    logE(TAG, "Could not cache nodes for TXT (cache write failed $cacheFailures time(s)).")
                } else {
                    emit(ContentParserState.Error(UIText.StringResource(R.string.error_empty_file)))
                    logE(TAG, "Could not extract nodes from TXT (file is empty).")
                }
                return@flow
            }

            emit(ContentParserState.Progress(1.0f))
            emit(ContentParserState.Success(nodePaths))

            logI(TAG, "Successfully finished TXT parsing.")
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            emit(
                ContentParserState.Error(
                    UIText.StringResource(
                        R.string.error_could_not_parse_file,
                        e.message ?: ""
                    )
                )
            )
            logE(TAG, "Could not extract nodes from TXT with error: ${e.message}.")
            return@flow
        }
    }.flowOn(Dispatchers.IO)
}

/**
 * fork 新增：从流里预读最多 [max] 字节做编码探测。
 *
 * 流读完这一段后仍可继续使用（剩余部分原样留给正文解码）。按块读而不是 `readNBytes`：
 * 后者在 Android 上要 API 33，而本 fork 的 minSdk 是 29。
 */
private fun InputStream.readSample(max: Int): ByteArray {
    val sample = ByteArrayOutputStream(minOf(max, SAMPLE_CHUNK_SIZE))
    val chunk = ByteArray(SAMPLE_CHUNK_SIZE)
    var remaining = max

    while (remaining > 0) {
        val read = read(chunk, 0, minOf(chunk.size, remaining))
        if (read <= 0) break
        sample.write(chunk, 0, read)
        remaining -= read
    }

    return sample.toByteArray()
}
