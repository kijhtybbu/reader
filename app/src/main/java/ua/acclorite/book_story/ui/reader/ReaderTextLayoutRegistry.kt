/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * fork 新增：正文文本块的命中注册表（第二阶段 S5，设计 §5.1）。
 *
 * 为什么需要注册表：取词要把「屏幕上的一个点」变成「某个 TextLayoutResult 里的第几个字」。
 * 分页布局里每个页项都是独立测量出来的 `TextLayoutResult`（不是整段文本），连续布局里
 * 段落是 LazyColumn 的 item；两种布局都拿不到「点击点属于哪段文本」的公共入口。
 *
 * 约定（改动这里之前先读）：
 *  - [ReaderTextLayoutEntry.bounds] 是**窗口坐标**，而且是文本节点**未被裁剪**的完整矩形。
 *    不能改用 `boundsInWindow()`：它会按祖先裁剪（LazyColumn 视口）并夹到根节点尺寸内，
 *    段落有一半滚出屏幕时左上角会被钉在 0 上，`wordAt` 的「局部 → 窗口」换算和锚点
 *    就会整体偏移 —— 这正是「有的单词取词后高亮错位」的成因。
 *  - [ReaderTextLayoutEntry.wordAt] 的入参与 [WordHit.rect] 也都是窗口坐标。
 *  - 命中顺序是「后注册的优先」：图注、页脚这类叠在上层的文本先命中。
 *  - 注册表本身**不是** snapshot state：它只在点击时被读，不需要触发重组。
 *
 * 纯逻辑（[ReaderTextLayoutRegistry.hitTest]）与 Compose 无关，JVM 单测用假 entry 覆盖
 * （ReaderTextLayoutRegistryTest）；真正的 `TextLayoutResult` 只在真机/仪器测试里才存在。
 */

package ua.acclorite.book_story.ui.reader

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import android.graphics.Paint
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.resolveAsTypeface
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import ua.acclorite.book_story.domain.model.book.NodeId

/**
 * 文本块的稳定身份。
 *
 * 分页布局用「节点 id + 该页项在整段文本里的起点」：同一段文本被切成两页时，
 * 两个页项是两个不同的 `TextLayoutResult`，只按节点 id 会互相覆盖。
 */
internal data class ReaderTextLayoutKey(
    val nodeId: Int,
    val startOffset: Int
)

/**
 * 一次词命中。
 *
 * @param rect 词在窗口坐标系里的包围盒。竖向是**基线与字号**算出的字形带（不是行框）：
 *   行框会随用户的「行高」设置变胖，高亮跟着变高就会显得错位。
 */
internal data class WordHit(
    val nodeId: NodeId,
    val word: String,
    val rect: Rect
)

/**
 * 一个已注册的文本块。
 *
 * @param bounds 文本块在窗口坐标系里的**未被裁剪**的矩形（由 `positionInWindow()` + 节点尺寸拼出）。
 * @param wordAt 窗口坐标 → 命中的词；点不到词（行尾空白、纯标点）时返回 null。
 */
internal class ReaderTextLayoutEntry(
    val key: ReaderTextLayoutKey,
    val nodeId: NodeId,
    val bounds: Rect,
    val wordAt: (Offset) -> WordHit?
)

internal class ReaderTextLayoutRegistry {

    /** 插入顺序 = 注册顺序；[hitTest] 逆序查，后注册的（上层文本）优先。 */
    private val entries = LinkedHashMap<ReaderTextLayoutKey, ReaderTextLayoutEntry>()

    val size: Int
        get() = entries.size

    fun register(entry: ReaderTextLayoutEntry) {
        // 重新注册时先删再插：LinkedHashMap 对已存在的 key 只更新值、保留原插入位置，
        // 而 hitTest 逆序查，必须让最新布局排到最后（上层文本优先）。
        entries.remove(entry.key)
        entries[entry.key] = entry
    }

    /**
     * 只注销**自己注册的那一份**。字体/字号变化后重新排版时，旧组合的 `onDispose`
     * 可能晚于新组合的 [register] 执行；按 key 无条件删会把刚注册好的新布局删掉。
     */
    fun unregister(entry: ReaderTextLayoutEntry) {
        if (entries[entry.key] === entry) entries.remove(entry.key)
    }

    fun clear() {
        entries.clear()
    }

    fun entry(key: ReaderTextLayoutKey): ReaderTextLayoutEntry? = entries[key]

    /** 窗口坐标 → 词命中。点在任何文本块之外时返回 null（空白区照旧翻页/切菜单）。 */
    fun hitTest(windowPosition: Offset): WordHit? {
        val snapshot = entries.values.toList()
        for (index in snapshot.indices.reversed()) {
            val entry = snapshot[index]
            if (!entry.bounds.contains(windowPosition)) continue
            entry.wordAt(windowPosition)?.let { return it }
        }
        return null
    }
}

/**
 * 一个文本块的注册句柄：把 `onTextLayout` 与 `onGloballyPositioned` 两个回调合到一处，
 * 两者都到齐（且位置变了）才更新注册表。离开组合时自动注销。
 */
internal class ReaderTextLayoutHandle(
    private val registry: ReaderTextLayoutRegistry,
    val nodeId: NodeId,
    val startOffset: Int,
    private val density: Density,
    private val fontFamilyResolver: FontFamily.Resolver
) {
    val key = ReaderTextLayoutKey(nodeId.id, startOffset)

    private var layout: TextLayoutResult? = null
    private var origin: Offset = Offset.Unspecified
    private var size: IntSize = IntSize.Zero
    private var published: ReaderTextLayoutEntry? = null

    /**
     * 挂在被注册的那个文本 Composable 上。必须与 `StyledText` 是同一个节点：
     * [origin] 是该节点左上角在窗口里的位置，局部坐标加上它才是窗口坐标。
     *
     * 用 `positionInWindow()` 而不是 `boundsInWindow()`：后者会被祖先裁剪并把角点夹到
     * 根节点范围内（见文件头说明），段落部分滚出视口时坐标就不再是节点原点。
     */
    val positionModifier: Modifier = Modifier.onGloballyPositioned { coordinates: LayoutCoordinates ->
        val nextOrigin = coordinates.positionInWindow()
        val nextSize = coordinates.size
        if (nextOrigin != origin || nextSize != size) {
            origin = nextOrigin
            size = nextSize
            publish()
        }
    }

    fun onTextLayout(result: TextLayoutResult) {
        layout = result
        publish()
    }

    /** 局部坐标 → 窗口坐标。 */
    fun windowPosition(local: Offset): Offset = origin + local

    private fun publish() {
        val currentLayout = layout ?: return
        if (origin == Offset.Unspecified || size == IntSize.Zero) return
        val currentOrigin = origin
        val bounds = Rect(
            left = currentOrigin.x,
            top = currentOrigin.y,
            right = currentOrigin.x + size.width,
            bottom = currentOrigin.y + size.height
        )
        // 字号（sp→px）与同款字体的 Paint 用来量**词的墨迹范围**（高亮用）。
        // 行框高度 = 字号 × 用户的行高设置，拿行框当字形带会在行距调大/无下伸字母的词上偏移。
        val style = currentLayout.layoutInput.style
        val fontSize = style.fontSize
        val emPx = if (fontSize.isSp) with(density) { fontSize.toPx() } else null
        val inkPaint = if (emPx != null && emPx > 0f) {
            runCatching {
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    typeface = fontFamilyResolver.resolveAsTypeface(
                        fontFamily = style.fontFamily,
                        fontWeight = style.fontWeight ?: FontWeight.Normal,
                        fontStyle = style.fontStyle ?: FontStyle.Normal,
                        fontSynthesis = style.fontSynthesis ?: FontSynthesis.All
                    ).value
                    textSize = emPx
                }
            }.getOrNull()
        } else null
        val entry = ReaderTextLayoutEntry(key, nodeId, bounds) { position ->
            readerWordAt(currentLayout, nodeId, currentOrigin, bounds, position, emPx, inkPaint)
        }
        published = entry
        registry.register(entry)
    }

    /** 离开组合时由 [rememberReaderTextLayoutHandle] 调用。 */
    fun release() {
        published?.let { registry.unregister(it) }
        published = null
    }
}

@Composable
internal fun rememberReaderTextLayoutHandle(
    registry: ReaderTextLayoutRegistry,
    nodeId: NodeId,
    startOffset: Int = 0
): ReaderTextLayoutHandle {
    val density = LocalDensity.current
    val fontFamilyResolver = LocalFontFamilyResolver.current
    val handle = remember(registry, nodeId, startOffset, density, fontFamilyResolver) {
        ReaderTextLayoutHandle(registry, nodeId, startOffset, density, fontFamilyResolver)
    }
    DisposableEffect(handle) {
        onDispose { handle.release() }
    }
    return handle
}

/**
 * 窗口坐标 → 词。逐层收窄：
 *  1. 点必须在文本块的矩形内（含 RTL 时 `bounds` 仍是左上角原点）；
 *  2. 点必须落在该行的左右边界之间 —— 否则行尾的空白会把「最后一个词」当成命中；
 *  3. 词边界交给 `getWordBoundary`，归一化失败（纯标点）算没命中。
 *
 * 锚点用**归一化之后**那一段的包围盒，不是整个 ICU 词：`“apple,”` 的引号与逗号不该把
 * 卡片推离词本身。
 */
internal fun readerWordAt(
    layout: TextLayoutResult,
    nodeId: NodeId,
    origin: Offset,
    bounds: Rect,
    windowPosition: Offset,
    fontSizePx: Float?,
    inkPaint: Paint?
): WordHit? {
    val local = windowPosition - origin
    if (local.x < 0f || local.y < 0f || local.x > bounds.width || local.y > bounds.height) {
        return null
    }
    val text = layout.layoutInput.text
    if (text.isEmpty()) return null

    val line = layout.getLineForVerticalPosition(local.y)
    val lineStart = minOf(layout.getLineLeft(line), layout.getLineRight(line))
    val lineEnd = maxOf(layout.getLineLeft(line), layout.getLineRight(line))
    if (local.x < lineStart || local.x > lineEnd) return null

    val rawOffset = layout.getOffsetForPosition(local).coerceIn(0, text.length - 1)
    // 两端对齐的行：布局坐标比实际绘制位置偏左，先把点击点往左挪一点再取词，
    // 否则点在行尾的词上会命中到下一个词/空白。
    val tapShift = layout.justificationShift(line, rawOffset)
    val offset = if (tapShift > 0f) {
        layout.getOffsetForPosition(local - Offset(tapShift, 0f)).coerceIn(0, text.length - 1)
    } else {
        rawOffset
    }
    val boundary = layout.getWordBoundary(offset)
    val raw = text.text.substring(boundary.start, boundary.end)
    val word = lookupWordIn(raw) ?: return null

    val wordStart = boundary.start + word.start
    val wordEnd = boundary.start + word.end
    if (wordStart >= wordEnd) return null
    // 词矩形也要按同一个拉伸量右移，高亮/卡片才会贴在绘制出来的词上。
    val wordLine = layout.getLineForOffset(wordStart)
    val wordShift = layout.justificationShift(wordLine, wordStart)
    return WordHit(
        nodeId = nodeId,
        word = word.text,
        rect = layout.wordRect(wordStart, wordEnd, fontSizePx, inkPaint)
            .translate(origin)
            .translate(wordShift, 0f)
    )
}

/**
 * 两端对齐时的横向校正量（文本局部坐标）。
 *
 * Android 的 `getPrimaryHorizontal`/`getBoundingBox` 返回的是**未拉伸**的位置，而绘制时
 * 词间空格会被拉宽到行宽。真机实测（20pt、行宽 1804px）：「through」的布局坐标比实际绘制
 * 位置偏左约 35px，越靠行尾偏得越多 —— 高亮就会「选到上一个词的末尾、漏掉最后一个字母」。
 *
 * 校正量 = 词前的空格数 × 每个间隔的拉伸量；拉伸总量 = 行右边界 − 最后一个非空白字符的
 * 布局右边界。非两端对齐的行、段落最后一行这个差值本来就是 0，天然不校正。
 */
private fun TextLayoutResult.justificationShift(line: Int, offset: Int): Float {
    if (layoutInput.style.textAlign != TextAlign.Justify) return 0f
    val text = layoutInput.text.text
    val lineStart = getLineStart(line)
    val lineEnd = getLineEnd(line, visibleEnd = false).coerceAtMost(text.length)
    if (lineEnd <= lineStart) return 0f
    val lineText = text.substring(lineStart, lineEnd)
    val trailingSpaces = lineText.length - lineText.trimEnd(' ').length
    val gaps = lineText.count { it == ' ' } - trailingSpaces
    if (gaps <= 0) return 0f
    val lastNonSpace = lineText.indexOfLast { !it.isWhitespace() }
    if (lastNonSpace < 0) return 0f
    val unjustifiedRight = getBoundingBox(lineStart + lastNonSpace).right
    val extra = getLineRight(line) - unjustifiedRight
    if (extra <= 0f) return 0f
    val spacesBefore = text.substring(lineStart, offset.coerceIn(lineStart, lineEnd))
        .count { it == ' ' }
    return spacesBefore * extra / gaps
}

/** 高亮带相对基线的比例：上侧覆盖大写/上伸部，下侧覆盖下伸部。 */
private const val HIGHLIGHT_ASCENT_RATIO = 0.72f
private const val HIGHLIGHT_DESCENT_RATIO = 0.22f

/**
 * [start, end) 的包围盒（文本局部坐标）。
 *
 * 横向：取首/末字符的左右边界（RTL 下 `getBoundingBox` 的 left 可能大于 right，统一按
 * min/max 收拢）。竖向：**优先用量词的墨迹范围**（同字号、同字体的 `Paint.getTextBounds`）——
 * 这样 `crack`（无下伸字母）不会在基线下方留一条空白，`k` 的上伸也不会被切掉；
 * 拿不到 Paint 时退回「基线 ± 字号比例」，再不行退回行框 + 对称内缩。
 */
private fun TextLayoutResult.wordRect(
    start: Int,
    end: Int,
    fontSizePx: Float?,
    inkPaint: Paint?
): Rect {
    val first = getBoundingBox(start)
    val last = getBoundingBox(end - 1)
    val left = minOf(first.left, first.right, last.left, last.right)
    val right = maxOf(first.left, first.right, last.left, last.right)
    val line = getLineForOffset(start)
    val baseline = getLineBaseline(line)
    val top: Float
    val bottom: Float
    val ink = wordInkBounds(start, end, inkPaint)
    if (ink != null) {
        top = baseline + ink.top - 1f
        bottom = baseline + ink.bottom + 1f
    } else if (fontSizePx != null && fontSizePx > 0f) {
        top = baseline - fontSizePx * HIGHLIGHT_ASCENT_RATIO
        bottom = baseline + fontSizePx * HIGHLIGHT_DESCENT_RATIO
    } else {
        val lineTop = getLineTop(line)
        val lineBottom = getLineBottom(line)
        val inset = (lineBottom - lineTop) * 0.16f
        top = lineTop + inset
        bottom = lineBottom - inset
    }
    return Rect(left = left, top = top, right = right, bottom = bottom)
}

/** 词在当前字体下的墨迹范围（相对基线）。量不到时返回 null。 */
private fun TextLayoutResult.wordInkBounds(start: Int, end: Int, paint: Paint?): android.graphics.Rect? {
    if (paint == null || start < 0 || end <= start) return null
    val text = layoutInput.text.text
    if (end > text.length) return null
    val rect = android.graphics.Rect()
    paint.getTextBounds(text, start, end, rect)
    return rect.takeIf { it.width() > 0 && it.height() > 0 }
}
