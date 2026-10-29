/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * fork 新增：章节切换提示。
 *
 * 为什么要它：分页模式跨章时正文会先淡出到 alpha 0 再重新分页（ReaderLayoutPaged 的
 * contentAlpha 闸门），用户看到的是「页面消失一下又出现」，很难判断到底换没换章。
 * 这里在顶部短暂显示章名，给一个明确反馈。
 *
 * 用 LocalReaderPalette 取色 —— 它浮在正文之上，必须跟阅读背景一致，
 * 不能跟 App 主题（否则深色预设上会出现一块亮色浮层）。
 */

package ua.acclorite.book_story.ui.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import ua.acclorite.book_story.ui.common.components.common.StyledText
import ua.acclorite.book_story.ui.theme.LocalReaderPalette
import ua.acclorite.book_story.ui.theme.Motion
import ua.acclorite.book_story.ui.theme.Radius
import ua.acclorite.book_story.ui.theme.Spacing

/** 提示停留时长；跨章本身还有一次重新分页，太短会看不见 */
private const val HintDurationMillis = 1500L

/**
 * 是否应该弹提示。
 *
 * 抽成纯函数是因为这里有一个容易写错的边界：**首次进入阅读器不该弹**。
 * 那时 lastShown 为 null，若直接用 `lastShown != newTitle` 判断，一进书就会闪一下章名。
 *
 * @param lastShown 上一次显示（或初始化）的章名；null 表示还没初始化过
 */
internal fun shouldShowChapterHint(lastShown: String?, newTitle: String?): Boolean =
    newTitle != null && lastShown != null && lastShown != newTitle

/**
 * @param chapterTitle 当前章名。变化时短暂显示；首次进入不显示（那时用户已经知道自己在哪一章）
 */
@Composable
fun ReaderChapterHint(
    chapterTitle: String?,
    modifier: Modifier = Modifier
) {
    var visible by remember { mutableStateOf(false) }
    var lastShown by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(chapterTitle) {
        val title = chapterTitle ?: return@LaunchedEffect
        if (!shouldShowChapterHint(lastShown, title)) {
            // 首次进入只记录不弹；章名没变也不弹
            lastShown = title
            return@LaunchedEffect
        }
        lastShown = title
        visible = true
        delay(HintDurationMillis)
        visible = false
    }

    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn(Motion.short()),
        exit = fadeOut(Motion.medium())
    ) {
        Surface(
            shape = RoundedCornerShape(Radius.pill),
            color = LocalReaderPalette.current.bar,
            contentColor = LocalReaderPalette.current.onBar
        ) {
            Row(
                modifier = Modifier.padding(
                    horizontal = Spacing.l,
                    vertical = Spacing.s
                ),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                StyledText(
                    text = chapterTitle.orEmpty(),
                    style = MaterialTheme.typography.labelLarge.copy(
                        color = LocalReaderPalette.current.onBar,
                        textAlign = TextAlign.Center
                    ),
                    modifier = Modifier.widthIn(max = 280.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
