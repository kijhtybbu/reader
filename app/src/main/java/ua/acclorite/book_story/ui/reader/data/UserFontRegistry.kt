/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * fork 新增：用户导入字体的运行时索引。
 *
 * 同步可读是硬要求：SettingsManager.fontFamily 的 deserialize 在 DataStore 一 emit 时就查字体，
 * 如果这里异步加载，重启后用户选的字体查不到，会静默回落默认字体。因此：
 *  - index.json 在 Application.onCreate 里同步读进内存（几 KB）；
 *  - 之后所有写操作同时更新内存与文件，读永远是内存；
 *  - 索引损坏（不是合法 JSON）时当作「没有用户字体」，不删字体文件、不删索引 ——
 *    文件还在，只是暂时不可见，比静默删用户数据安全。
 */

package ua.acclorite.book_story.ui.reader.data

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ua.acclorite.book_story.core.data.FileData
import ua.acclorite.book_story.core.log.logW
import ua.acclorite.book_story.data.storage.file.CachedFile
import ua.acclorite.book_story.data.storage.font.FontFileCheck
import ua.acclorite.book_story.data.storage.font.FontFileInspector
import ua.acclorite.book_story.data.storage.font.FontRejection
import ua.acclorite.book_story.data.storage.font.UserFontIndexCodec
import ua.acclorite.book_story.ui.reader.model.UserFontEntry
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID

private const val TAG = "UserFontRegistry"
private const val INDEX_FILE = "index.json"
private const val INDEX_TEMP_FILE = "index.json.tmp"
private const val USER_FONT_ID_PREFIX = "user:"

sealed interface FontImportResult {
    data class Imported(val entry: UserFontEntry) : FontImportResult
    data class Rejected(val reason: FontRejection) : FontImportResult
}

object UserFontRegistry {

    /** 10 个上限：每个字体是一份 mmap 的字库，再多设置页的一次组合就会把内存吃穿。 */
    const val MAX_FONTS = 10

    private var directory: File? = null

    private val _fonts = mutableStateOf<List<UserFontEntry>>(emptyList())

    /**
     * snapshot state：设置页读它会在导入/删除后自动重组；SettingsManager 在非组合线程读它
     * 也能拿到当前值。
     */
    val fonts: List<UserFontEntry>
        get() = _fonts.value

    /** 同步加载。只允许在 Application.onCreate 里调用（早于任何设置反序列化）。 */
    fun load(context: Context) {
        val dir = FileData.getFontsDirectory(context)
        if (!dir.exists() && !dir.mkdirs()) {
            logW(TAG, "Could not create font directory: ${dir.absolutePath}")
            _fonts.value = emptyList()
            return
        }
        directory = dir

        val indexFile = File(dir, INDEX_FILE)
        if (!indexFile.isFile) {
            _fonts.value = emptyList()
            return
        }

        val raw = try {
            indexFile.readText()
        } catch (e: IOException) {
            logW(TAG, "Could not read font index: ${e.message}")
            _fonts.value = emptyList()
            return
        }

        val decoded = UserFontIndexCodec.decode(raw)
        if (decoded == null) {
            logW(TAG, "Font index is corrupt; starting with no user fonts (font files are kept).")
            _fonts.value = emptyList()
            return
        }

        val existing = decoded.filter { File(dir, it.fileName).isFile }
        if (existing.size != decoded.size) {
            logW(TAG, "Ignoring ${decoded.size - existing.size} font entries with missing files.")
        }
        _fonts.value = existing
    }

    suspend fun importFont(
        context: Context,
        uri: Uri
    ): FontImportResult = withContext(Dispatchers.IO) {
        val dir = directory ?: FileData.getFontsDirectory(context).also { directory = it }
        if (!dir.exists() && !dir.mkdirs()) {
            return@withContext FontImportResult.Rejected(FontRejection.UNREADABLE)
        }
        if (_fonts.value.size >= MAX_FONTS) {
            return@withContext FontImportResult.Rejected(FontRejection.LIMIT_REACHED)
        }

        val cachedFile = CachedFile(context, uri)
        val displayName = cachedFile.name
        val extension = displayName.substringAfterLast('.', "")
        val tempFile = File(dir, "import-${UUID.randomUUID()}.part")
        var moved = false

        try {
            val header = ByteArray(FontFileInspector.HEADER_BYTES)
            var headerLength = 0
            var copied = 0L

            val input = cachedFile.openInputStream()
                ?: return@withContext FontImportResult.Rejected(FontRejection.UNREADABLE)
            input.use { source ->
                BufferedOutputStream(FileOutputStream(tempFile)).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = source.read(buffer)
                        if (read == -1) break

                        copied += read
                        if (copied > FontFileInspector.MAX_BYTES) {
                            return@withContext FontImportResult.Rejected(FontRejection.TOO_LARGE)
                        }
                        if (headerLength < header.size) {
                            val take = minOf(header.size - headerLength, read)
                            System.arraycopy(buffer, 0, header, headerLength, take)
                            headerLength += take
                        }
                        output.write(buffer, 0, read)
                    }
                }
            }

            val accepted = when (
                val check = FontFileInspector.inspect(
                    extension = extension,
                    sizeBytes = copied,
                    header = header.copyOf(headerLength)
                )
            ) {
                is FontFileCheck.Rejected -> return@withContext FontImportResult.Rejected(check.reason)
                is FontFileCheck.Accepted -> check
            }

            val hasCjk = try {
                UserFontTypefaces.hasCjkGlyphs(tempFile)
            } catch (e: Exception) {
                logW(TAG, "Imported file is not a readable font: ${e.message}")
                return@withContext FontImportResult.Rejected(FontRejection.NOT_A_FONT)
            }

            val uuid = UUID.randomUUID().toString()
            val fileName = "$uuid.${extension.lowercase().removePrefix(".")}"
            val targetFile = File(dir, fileName)
            if (!tempFile.renameTo(targetFile)) {
                tempFile.copyTo(targetFile, overwrite = true)
                tempFile.delete()
            }
            moved = true

            val entry = UserFontEntry(
                id = "$USER_FONT_ID_PREFIX$uuid",
                name = displayName.substringBeforeLast('.', displayName)
                    .ifBlank { fileName },
                fileName = fileName,
                size = copied,
                variable = accepted.variable,
                hasCjk = hasCjk,
                importedAt = System.currentTimeMillis()
            )
            _fonts.value = _fonts.value + entry
            persist(dir)
            FontImportResult.Imported(entry)
        } catch (e: IOException) {
            logW(TAG, "Could not import font: ${e.message}")
            FontImportResult.Rejected(FontRejection.UNREADABLE)
        } finally {
            if (!moved && tempFile.exists()) tempFile.delete()
        }
    }

    /** 删文件 + 删索引。调用方必须先把当前字体切回默认（见 UserFontOption）。 */
    suspend fun deleteFont(context: Context, entry: UserFontEntry) = withContext(Dispatchers.IO) {
        val dir = directory ?: FileData.getFontsDirectory(context).also { directory = it }

        UserFontTypefaces.invalidate(entry)
        File(dir, entry.fileName).delete()
        _fonts.value = _fonts.value.filterNot { it.id == entry.id }
        persist(dir)
    }

    private fun persist(dir: File) {
        val target = File(dir, INDEX_FILE)
        val temp = File(dir, INDEX_TEMP_FILE)
        try {
            temp.writeText(UserFontIndexCodec.encode(_fonts.value))
            if (!temp.renameTo(target)) {
                target.delete()
                if (!temp.renameTo(target)) {
                    logW(TAG, "Could not persist font index.")
                }
            }
        } catch (e: IOException) {
            logW(TAG, "Could not persist font index: ${e.message}")
        }
    }
}
