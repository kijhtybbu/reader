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
 *  - [ReaderTextLayoutEntry.bounds] 是**窗口坐标**。点击点必须先换算到窗口坐标再查，
 *    否则滚动/翻页动画中的锚点会错位（卡片贴到别的词上）。
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.TextLayoutResult
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

/** 一次词命中。[rect] 是词在窗口坐标系里的包围盒，用作取词卡片的锚点。 */
internal data class WordHit(
    val nodeId: NodeId,
    val word: String,
    val rect: Rect
)

/**
 * 一个已注册的文本块。
 *
 * @param bounds 文本块在窗口坐标系里的矩形（`LayoutCoordinates.boundsInWindow()`）。
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
    val startOffset: Int
) {
    val key = ReaderTextLayoutKey(nodeId.id, startOffset)

    private var layout: TextLayoutResult? = null
    private var bounds: Rect = Rect.Zero
    private var published: ReaderTextLayoutEntry? = null

    /**
     * 挂在被注册的那个文本 Composable 上。必须与 `StyledText` 是同一个节点：
     * `bounds` 就是该节点的窗口矩形，点击点的局部坐标加上 `bounds.topLeft` 才是窗口坐标。
     */
    val positionModifier: Modifier = Modifier.onGloballyPositioned { coordinates: LayoutCoordinates ->
        val next = coordinates.boundsInWindow()
        if (next != bounds) {
            bounds = next
            publish()
        }
    }

    fun onTextLayout(result: TextLayoutResult) {
        layout = result
        publish()
    }

    /** 局部坐标 → 窗口坐标。 */
    fun windowPosition(local: Offset): Offset = bounds.topLeft + local

    private fun publish() {
        val currentLayout = layout ?: return
        val currentBounds = bounds
        val entry = ReaderTextLayoutEntry(key, nodeId, currentBounds) { position ->
            readerWordAt(currentLayout, nodeId, currentBounds, position)
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
    val handle = remember(registry, nodeId, startOffset) {
        ReaderTextLayoutHandle(registry, nodeId, startOffset)
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
    bounds: Rect,
    windowPosition: Offset
): WordHit? {
    val local = windowPosition - bounds.topLeft
    if (local.x < 0f || local.y < 0f || local.x > bounds.width || local.y > bounds.height) {
        return null
    }
    val text = layout.layoutInput.text
    if (text.isEmpty()) return null

    val line = layout.getLineForVerticalPosition(local.y)
    val lineStart = minOf(layout.getLineLeft(line), layout.getLineRight(line))
    val lineEnd = maxOf(layout.getLineLeft(line), layout.getLineRight(line))
    if (local.x < lineStart || local.x > lineEnd) return null

    val offset = layout.getOffsetForPosition(local).coerceIn(0, text.length - 1)
    val boundary = layout.getWordBoundary(offset)
    val raw = text.text.substring(boundary.start, boundary.end)
    val word = lookupWordIn(raw) ?: return null

    val wordStart = boundary.start + word.start
    val wordEnd = boundary.start + word.end
    if (wordStart >= wordEnd) return null
    return WordHit(
        nodeId = nodeId,
        word = word.text,
        rect = layout.wordRect(wordStart, wordEnd).translate(bounds.topLeft)
    )
}

/**
 * [start, end) 的包围盒（文本局部坐标）。
 * RTL 下 `getBoundingBox` 的 left 可能大于 right，所以统一按 min/max 收拢。
 */
private fun TextLayoutResult.wordRect(start: Int, end: Int): Rect {
    val first = getBoundingBox(start)
    val last = getBoundingBox(end - 1)
    val left = minOf(first.left, first.right, last.left, last.right)
    val right = maxOf(first.left, first.right, last.left, last.right)
    return Rect(
        left = left,
        top = minOf(first.top, last.top),
        right = right,
        bottom = maxOf(first.bottom, last.bottom)
    )
}
