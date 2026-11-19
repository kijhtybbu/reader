/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 */

package ua.acclorite.book_story.core.data

object LinkData {
    // fork: 「检查更新」指向本分支的 release 页 —— 上游的最新版是另一个应用。
    const val RELEASES = "https://github.com/kijhtybbu/reader/releases"
    // fork: 「报告错误」指向本分支的 issue 列表 —— 用户装的是 2.0 的修改版，
    // 提给上游只会变成「这不是我们的版本」。上游仅保留贡献者/翻译入口作署名。
    const val ISSUES = "https://github.com/kijhtybbu/reader/issues"
    const val CONTRIBUTORS = "https://github.com/Acclorite/book-story/graphs/contributors"
    const val TRANSLATION = "https://hosted.weblate.org/projects/book-story"
    const val SUPPORT = "https://patreon.com/Acclorite"

    // fork: GPL-3.0-only 要求分发时标明上游来源、授权协议，并提供对应源码。
    // UPSTREAM 指向本分支所基于的上游项目，SOURCE 指向本分支的仓库（含全部修改）。
    const val UPSTREAM = "https://github.com/Acclorite/book-story"
    const val SOURCE = "https://github.com/kijhtybbu/reader"
    const val LICENSE = "https://www.gnu.org/licenses/gpl-3.0.html"
}