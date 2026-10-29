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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import ua.acclorite.book_story.ui.theme.Dimens
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
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

/** 提示的完整状态。抽出来是为了能测「arm/disarm」与「标题变 null 时收起」两条真实路径 */
internal data class ChapterHintState(val lastShown: String?, val visible: Boolean) {
    companion object {
        val Initial = ChapterHintState(lastShown = null, visible = false)
    }
}

/**
 * 状态转移。
 *
 * 三条规则：
 *  - 标题变 null（章节表尚未加载完 / 章名被清洗成空串）→ 收起。否则胶囊会永远挂在屏幕上，
 *    因为那次 LaunchedEffect 的 delay 已被取消，没有别人会把它关掉。
 *  - 章名与上次相同 → 原样返回（不要打断正在进行的 1.5s 显示）
 *  - 章名变化 → 记录并显示；首次（lastShown 为 null）只记录不显示
 */
internal fun chapterHintTransition(state: ChapterHintState, title: String?): ChapterHintState {
    if (title == null) return state.copy(visible = false)
    if (title == state.lastShown) return state
    return ChapterHintState(
        lastShown = title,
        visible = shouldShowChapterHint(state.lastShown, title)
    )
}

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
    var state by remember { mutableStateOf(ChapterHintState.Initial) }

    LaunchedEffect(chapterTitle) {
        val next = chapterHintTransition(state, chapterTitle)
        state = next
        // 只要这次要显示就（重新）计时。
        // 不能只在「false → true」时挂计时器：连续跨两章时，第二次变化会让
        // LaunchedEffect 重启并取消第一次的 delay，而那时 previous.visible 已经是 true，
        // 于是不会再挂新计时器 —— 胶囊就永久停在屏幕上了。
        if (next.visible) {
            delay(HintDurationMillis)
            state = state.copy(visible = false)
        }
    }
    val visible = state.visible

    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn(Motion.short()),
        exit = fadeOut(Motion.medium())
    ) {
        // 注意：不能用 M3 Surface —— 它会安装一个空的 pointerInput 节点，
        // 胶囊显示的 1.5s 内会吞掉落在这一条的点击（顶部中央正好是「呼出菜单」区）。
        // Box + clip + background 没有输入节点，点击会穿透到 Pager。
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(Radius.pill))
                .background(LocalReaderPalette.current.bar)
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
                    text = state.lastShown.orEmpty(),
                    style = MaterialTheme.typography.labelLarge.copy(
                        color = LocalReaderPalette.current.onBar,
                        textAlign = TextAlign.Center
                    ),
                    modifier = Modifier.widthIn(max = Dimens.hintMaxWidth),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
