/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * fork 新增：取词注册表的命中映射（第二阶段 S5）。
 *
 * 真正的 `TextLayoutResult` 在 JVM 单测里造不出来（需要 Android 的文本测量），
 * 所以这里用假 entry 钉住注册表自己的那部分契约：
 *  - 只命中矩形内的点；
 *  - 后注册的（图注、页脚这类叠在上层的文本）优先；
 *  - 注销后不再命中（LazyColumn 回收段落时必须清掉）。
 */

package ua.acclorite.book_story.ui.reader

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ua.acclorite.book_story.domain.model.book.NodeId

class ReaderTextLayoutRegistryTest {

    private val firstNode = NodeId(1)
    private val secondNode = NodeId(2)

    private fun entry(
        nodeId: NodeId,
        startOffset: Int = 0,
        bounds: Rect,
        word: String = "apple"
    ) = ReaderTextLayoutEntry(
        key = ReaderTextLayoutKey(nodeId.id, startOffset),
        nodeId = nodeId,
        bounds = bounds
    ) { position ->
        // 假词命中：矩形内的点都算命中同一个词，词框就是文本块左上角的一小块。
        WordHit(
            nodeId = nodeId,
            word = word,
            rect = Rect(
                left = position.x,
                top = bounds.top,
                right = position.x + 10f,
                bottom = bounds.top + 20f
            )
        )
    }

    @Test
    fun `a point inside the text block maps to its word`() {
        val registry = ReaderTextLayoutRegistry()
        registry.register(
            entry(firstNode, bounds = Rect(0f, 100f, 400f, 140f), word = "hello")
        )

        val hit = registry.hitTest(Offset(120f, 110f))

        assertEquals("hello", hit?.word)
        assertEquals(firstNode, hit?.nodeId)
    }

    @Test
    fun `a point outside every text block hits nothing`() {
        val registry = ReaderTextLayoutRegistry()
        registry.register(entry(firstNode, bounds = Rect(0f, 100f, 400f, 140f)))

        assertNull(registry.hitTest(Offset(120f, 40f)))
        assertNull(registry.hitTest(Offset(-5f, 110f)))
        assertNull(registry.hitTest(Offset(500f, 110f)))
    }

    @Test
    fun `the entry registered last wins when blocks overlap`() {
        val registry = ReaderTextLayoutRegistry()
        registry.register(entry(firstNode, bounds = Rect(0f, 100f, 400f, 140f), word = "below"))
        registry.register(entry(secondNode, bounds = Rect(0f, 100f, 400f, 140f), word = "above"))

        assertEquals("above", registry.hitTest(Offset(10f, 110f))?.word)
    }

    @Test
    fun `entries are keyed by node and item start offset`() {
        val registry = ReaderTextLayoutRegistry()
        val first = entry(firstNode, startOffset = 0, bounds = Rect(0f, 0f, 100f, 20f), word = "one")
        val second = entry(firstNode, startOffset = 40, bounds = Rect(0f, 20f, 100f, 40f), word = "two")
        registry.register(first)
        registry.register(second)

        assertEquals(2, registry.size)
        assertEquals(
            Rect(0f, 20f, 100f, 40f),
            registry.entry(ReaderTextLayoutKey(firstNode.id, 40))?.bounds
        )
        assertEquals(
            Rect(0f, 0f, 100f, 20f),
            registry.entry(ReaderTextLayoutKey(firstNode.id, 0))?.bounds
        )
    }

    @Test
    fun `registering the same key twice replaces the previous layout`() {
        val registry = ReaderTextLayoutRegistry()
        val key = ReaderTextLayoutKey(firstNode.id, 0)
        registry.register(entry(firstNode, bounds = Rect(0f, 0f, 100f, 20f), word = "old"))
        registry.register(entry(firstNode, bounds = Rect(0f, 100f, 100f, 120f), word = "new"))

        assertEquals(1, registry.size)
        assertNull(registry.hitTest(Offset(10f, 10f)))
        assertEquals("new", registry.hitTest(Offset(10f, 110f))?.word)
        assertEquals(key, registry.entry(key)?.key)
    }

    @Test
    fun `unregistering removes the block from hit testing`() {
        val registry = ReaderTextLayoutRegistry()
        val block = entry(firstNode, bounds = Rect(0f, 0f, 100f, 20f))
        registry.register(block)

        registry.unregister(block)

        assertEquals(0, registry.size)
        assertNull(registry.hitTest(Offset(10f, 10f)))
    }

    @Test
    fun `unregistering a stale block does not drop the fresh one`() {
        val registry = ReaderTextLayoutRegistry()
        val stale = entry(firstNode, bounds = Rect(0f, 0f, 100f, 20f), word = "old")
        val fresh = entry(firstNode, bounds = Rect(0f, 0f, 100f, 20f), word = "new")
        registry.register(stale)
        // 重新排版：同一个 key 先注册了新布局，旧组合的 onDispose 之后才跑
        registry.register(fresh)

        registry.unregister(stale)

        assertEquals(1, registry.size)
        assertEquals("new", registry.hitTest(Offset(10f, 10f))?.word)
    }

    @Test
    fun `clear drops every block`() {
        val registry = ReaderTextLayoutRegistry()
        registry.register(entry(firstNode, bounds = Rect(0f, 0f, 100f, 20f)))
        registry.register(entry(secondNode, bounds = Rect(0f, 20f, 100f, 40f)))

        registry.clear()

        assertEquals(0, registry.size)
        assertNull(registry.entry(ReaderTextLayoutKey(firstNode.id, 0)))
    }
}
