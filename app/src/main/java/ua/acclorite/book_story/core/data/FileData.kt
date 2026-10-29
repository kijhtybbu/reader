/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 */

package ua.acclorite.book_story.core.data

import android.content.Context
import java.io.File

object FileData {
    fun getCacheDirectory(context: Context) = context.filesDir.resolve("app_cache")

    /**
     * fork: 用户导入字体的落盘目录。字体永远从这里读，不从 SAF URI 读 ——
     * URI 的授权可能过期，而 Typeface 重建会发生在任意一次主题/字重变化时。
     */
    fun getFontsDirectory(context: Context) = context.filesDir.resolve("fonts")
    fun getTempDirectory() = "temp_${System.currentTimeMillis()}"
    fun getBookContentDirectory(
        bookUUID: String,
        context: Context,
        subDirectory: String = ""
    ): String {
        return context.filesDir
            .resolve("content${File.separator}book_$bookUUID")
            .resolve(subDirectory)
            .absolutePath
    }

    fun getBookContentImagesDirectory(
        bookUUID: String,
        context: Context
    ): String {
        return context.filesDir
            .resolve("content${File.separator}book_$bookUUID")
            .resolve("images")
            .absolutePath
    }

    fun getBookContentDatabaseFile(bookUUID: String, context: Context): String {
        return getBookContentDirectory(
            bookUUID,
            context
        ) + File.separator + "book_content_$bookUUID.db"
    }

    fun getBookContentImageFile(
        bookUUID: String,
        context: Context,
        id: Int,
        extension: String
    ): String {
        return getBookContentImagesDirectory(
            bookUUID,
            context
        ) + File.separator + "book_content_image_${bookUUID}_$id.$extension"
    }
}