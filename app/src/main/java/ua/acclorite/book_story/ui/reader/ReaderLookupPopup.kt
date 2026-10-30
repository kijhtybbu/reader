/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * fork 新增：贴词取词卡片（第二阶段 S5，设计 §5.2）。
 *
 * 位置由 [lookupPopupPosition] 算，不交给 `Popup(alignment, offset)`：
 * 后者在 RTL 下会把 offset.x 从右边量起，卡片直接跑到屏幕外。自己算还有一个好处 ——
 * [PopupPositionProvider.calculatePosition] 能拿到 `popupContentSize`，第一帧就摆在正确位置，
 * 不会先画在词下方再跳上去。
 *
 * 配色只用 `LocalReaderPalette`：夜间预设下卡片必须是阅读背景的色系，否则一开一关闪白。
 */

package ua.acclorite.book_story.ui.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import ua.acclorite.book_story.R
import ua.acclorite.book_story.presentation.reader.LookupState
import ua.acclorite.book_story.presentation.reader.anchor
import ua.acclorite.book_story.ui.common.components.common.StyledText
import ua.acclorite.book_story.ui.theme.LocalReaderPalette
import kotlin.math.roundToInt

/** 卡片与屏幕边缘的最小距离。 */
private val PopupMargin = 12.dp

/** 卡片与词之间的距离。 */
private val PopupGap = 8.dp

private val PopupMaxWidth = 360.dp

/** 卡片最高占安全区高度的比例（设计 §5.2）。 */
private const val PopupMaxHeightFraction = 0.45f

/**
 * 取词卡片。
 *
 * @param onSelect 多词典命中里选第 index 条。
 * @param onLookupLink 点了正文里的 `juice://lookup/…` 链接。
 * @param onRetry 错误态重试。
 * @param onDismiss 外部点击/返回/关闭。
 * @param onImportDictionary 未找到时去导入词典。
 * @param onExternalLookup 未找到时回退到外部词典应用（S4 之前的行为）。
 * @param onAiExplain S6 的 AI 解释；为 null 时按钮显示为禁用态。
 */
@Composable
fun ReaderLookupPopup(
    state: LookupState,
    onSelect: (Int) -> Unit,
    onLookupLink: (String) -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    onImportDictionary: () -> Unit,
    onExternalLookup: (String) -> Unit,
    onAiExplain: ((String) -> Unit)? = null
) {
    val palette = LocalReaderPalette.current
    val density = LocalDensity.current
    val windowInfo = LocalWindowInfo.current

    // 安全区：窗口减去系统栏/导航栏/刘海挖孔。卡片尺寸和夹紧都要用它，否则贴顶的词会把
    // 卡片翻到状态栏或挖孔下面（Popup 自己的窗口不避开 inset）。
    // 左右按物理方向取（传 Ltr）：`getLeft/getRight` 会随 layoutDirection 互换，
    // 而这里是像素级的夹紧范围，不跟着 RTL 翻转（RTL 只影响卡片内容的排版）。
    val safeInsets = WindowInsets.safeDrawing
    val safeLeft = safeInsets.getLeft(density, LayoutDirection.Ltr)
    val safeTop = safeInsets.getTop(density)
    val safeRight = safeInsets.getRight(density, LayoutDirection.Ltr)
    val safeBottom = safeInsets.getBottom(density)
    val containerSize = windowInfo.containerSize
    val safeBounds = remember(containerSize, safeLeft, safeTop, safeRight, safeBottom) {
        IntRect(
            left = safeLeft,
            top = safeTop,
            right = (containerSize.width - safeRight).coerceAtLeast(safeLeft),
            bottom = (containerSize.height - safeBottom).coerceAtLeast(safeTop)
        )
    }

    val margin = with(density) { PopupMargin.roundToPx() }
    val gap = with(density) { PopupGap.roundToPx() }
    val maxHeight = with(density) {
        (safeBounds.height * PopupMaxHeightFraction).toDp()
    }
    val anchor = state.anchor

    val positionProvider = remember(anchor, safeBounds, margin, gap) {
        LookupPopupPositionProvider(
            anchor = anchor,
            safeBounds = safeBounds,
            margin = margin,
            gap = gap
        )
    }

    Popup(
        popupPositionProvider = positionProvider,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true)
    ) {
        Surface(
            modifier = Modifier
                .widthIn(max = PopupMaxWidth)
                .heightIn(max = maxHeight),
            shape = RoundedCornerShape(16.dp),
            color = palette.bar,
            contentColor = palette.onBar,
            shadowElevation = 8.dp
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                when (state) {
                    is LookupState.Loading -> LoadingBody(state)
                    is LookupState.Success -> SuccessBody(
                        state = state,
                        onSelect = onSelect,
                        onLookupLink = onLookupLink
                    )

                    is LookupState.NotFound -> NotFoundBody(
                        state = state,
                        onImportDictionary = onImportDictionary,
                        onExternalLookup = onExternalLookup,
                        onAiExplain = onAiExplain
                    )

                    is LookupState.Error -> ErrorBody(state = state, onRetry = onRetry)

                    LookupState.Idle -> Unit
                }
            }
        }
    }
}

@Composable
private fun LoadingBody(state: LookupState.Loading) {
    WordHeader(word = state.word, source = null)
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(
            modifier = Modifier.size(16.dp),
            color = LocalReaderPalette.current.accent,
            strokeWidth = 2.dp
        )
        Spacer(Modifier.width(8.dp))
        BodyText(stringResource(id = R.string.lookup_popup_loading))
    }
}

@Composable
private fun SuccessBody(
    state: LookupState.Success,
    onSelect: (Int) -> Unit,
    onLookupLink: (String) -> Unit
) {
    val palette = LocalReaderPalette.current
    val selected = state.entries.getOrNull(state.selectedIndex)
    WordHeader(word = state.word, source = selected?.dictName)

    if (state.entries.size > 1) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            state.entries.forEachIndexed { index, entry ->
                FilterChip(
                    selected = index == state.selectedIndex,
                    label = { StyledText(text = entry.dictName, maxLines = 1) },
                    onClick = { onSelect(index) }
                )
            }
        }
    }

    val currentOnLookupLink = rememberUpdatedState(onLookupLink)
    val linkStyles = remember(palette.accent) {
        TextLinkStyles(SpanStyle(color = palette.accent, textDecoration = TextDecoration.Underline))
    }
    val html = remember(state.html, linkStyles) {
        parseArticleHtml(
            html = state.html,
            linkStyles = linkStyles,
            onLink = { link -> currentOnLookupLink.value(link) }
        )
    }

    if (html != null) {
        BasicText(
            text = html,
            style = MaterialTheme.typography.bodyMedium.copy(color = palette.onBar)
        )
    } else {
        // fromHtml 只保证基础标签；解析失败时退化成引擎给的纯文本预览，不留白卡片。
        BodyText(selected?.preview.orEmpty())
    }
}

@Composable
private fun NotFoundBody(
    state: LookupState.NotFound,
    onImportDictionary: () -> Unit,
    onExternalLookup: (String) -> Unit,
    onAiExplain: ((String) -> Unit)?
) {
    WordHeader(word = state.word, source = null)
    BodyText(stringResource(id = R.string.lookup_popup_not_found))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = onImportDictionary) {
            StyledText(text = stringResource(id = R.string.lookup_popup_import_dictionary))
        }
        // S6 才接 AI：现在永远是禁用态，但按钮先占位，用户知道后面有这条路。
        TextButton(onClick = { onAiExplain?.invoke(state.word) }, enabled = onAiExplain != null) {
            StyledText(text = stringResource(id = R.string.lookup_popup_ai_explain))
        }
        TextButton(onClick = { onExternalLookup(state.word) }) {
            StyledText(text = stringResource(id = R.string.lookup_popup_external))
        }
    }
}

@Composable
private fun ErrorBody(state: LookupState.Error, onRetry: () -> Unit) {
    WordHeader(word = state.word, source = null)
    BodyText(stringResource(id = R.string.lookup_popup_error))
    TextButton(onClick = onRetry) {
        StyledText(text = stringResource(id = R.string.retry))
    }
}

@Composable
private fun WordHeader(word: String, source: String?) {
    val palette = LocalReaderPalette.current
    Column(Modifier.fillMaxWidth()) {
        StyledText(
            text = word,
            style = MaterialTheme.typography.titleMedium.copy(
                color = palette.onBar,
                fontWeight = FontWeight.SemiBold
            )
        )
        if (source != null) {
            StyledText(
                text = stringResource(id = R.string.lookup_popup_source, source),
                style = MaterialTheme.typography.labelSmall.copy(color = palette.onBarMuted)
            )
        }
    }
}

@Composable
private fun BodyText(text: String) {
    StyledText(
        modifier = Modifier.fillMaxWidth(),
        text = text,
        style = MaterialTheme.typography.bodyMedium.copy(color = LocalReaderPalette.current.onBar)
    )
}

/**
 * 词条 HTML → AnnotatedString。`juice://lookup/` 交给 [onLink] 继续查，其余链接
 * （外部 URL）在解析阶段就被吞掉：卡片里点外链会把用户带出阅读页，本期不需要。
 *
 * @return null 表示解析失败，调用方退化成纯文本预览。
 */
internal fun parseArticleHtml(
    html: String,
    linkStyles: TextLinkStyles,
    onLink: (String) -> Unit
): AnnotatedString? {
    if (html.isBlank()) return null
    return try {
        AnnotatedString.fromHtml(
            htmlString = html,
            linkStyles = linkStyles,
            linkInteractionListener = { link ->
                val url = (link as? LinkAnnotation.Url)?.url
                if (url != null) lookupWordFromLink(url)?.let(onLink)
            }
        )
    } catch (e: Exception) {
        null
    }
}

/**
 * 卡片定位：优先贴在词下方，放不下就翻到词上方；水平居中于词，并夹在**安全区**内。
 * 锚点未知（长按选区拿不到矩形）时在安全区内居中。
 *
 * [safeBounds] 是窗口坐标系里的可用区域（已扣除系统栏/刘海）；卡片不会摆到它外面。
 * 纯函数（无 Compose 运行时依赖），JVM 单测覆盖（ReaderLookupPopupPositionTest）。
 */
internal fun lookupPopupPosition(
    anchor: Rect?,
    safeBounds: IntRect,
    popupSize: IntSize,
    margin: Int,
    gap: Int
): IntOffset {
    val minX = safeBounds.left + margin
    val maxX = (safeBounds.right - popupSize.width - margin).coerceAtLeast(minX)
    val minY = safeBounds.top + margin
    val maxY = (safeBounds.bottom - popupSize.height - margin).coerceAtLeast(minY)

    if (anchor == null) {
        return IntOffset(
            x = ((safeBounds.left + safeBounds.right - popupSize.width) / 2).coerceIn(minX, maxX),
            y = ((safeBounds.top + safeBounds.bottom - popupSize.height) / 2).coerceIn(minY, maxY)
        )
    }

    val x = (anchor.center.x - popupSize.width / 2f).roundToInt().coerceIn(minX, maxX)
    val below = anchor.bottom.roundToInt() + gap
    val y = if (below + popupSize.height <= safeBounds.bottom - margin) {
        below.coerceIn(minY, maxY)
    } else {
        // 上方也放不下时贴安全区顶，宁可盖住词本身也不要一半在屏幕外/状态栏下面。
        (anchor.top.roundToInt() - gap - popupSize.height).coerceIn(minY, maxY)
    }
    return IntOffset(x, y)
}

private class LookupPopupPositionProvider(
    private val anchor: Rect?,
    private val safeBounds: IntRect,
    private val margin: Int,
    private val gap: Int
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize
    ): IntOffset = lookupPopupPosition(anchor, safeBounds, popupContentSize, margin, gap)
}
