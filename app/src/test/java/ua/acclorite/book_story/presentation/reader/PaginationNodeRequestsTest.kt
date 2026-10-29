/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * fork 新增：节点窗口重新加载的触发信号。
 *
 * 前两条断言在 MutableStateFlow 版本上会超时失败（实测：两次 `value = NodeId(0)` 只产出
 * 一个值，`take(2)` 永远等不到第二个）—— 那正是「从远处跳回第 0 节点后阅读页永久空白」的根因：
 * StateFlow 合并相等值。所以这里刻意用 `withTimeout` 包住：一旦有人把它改回 StateFlow，
 * 失败的是一条带超时的断言，而不是静默通过。
 */

package ua.acclorite.book_story.presentation.reader

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import ua.acclorite.book_story.domain.model.book.NodeId

private const val TIMEOUT_MS = 5_000L

class PaginationNodeRequestsTest {

    @Test
    fun `repeated identical targets are each delivered`() = runBlocking {
        withTimeout(TIMEOUT_MS) {
            val requests = newPaginationNodeRequests()

            // UNDISPATCHED：先建立订阅，免得缓冲影响「两次都收到」的判断
            val received = async(start = CoroutineStart.UNDISPATCHED) {
                requests.take(2).toList()
            }

            requests.emit(NodeId(0))
            requests.emit(NodeId(0))

            assertEquals(listOf(NodeId(0), NodeId(0)), received.await())
        }
    }

    @Test
    fun `consumers restart once per target even when the target is unchanged`() = runBlocking {
        withTimeout(TIMEOUT_MS) {
            val requests = newPaginationNodeRequests()

            // 与 GetBookContentUseCase 相同的消费方式：collectLatest + 一次读库
            val started = Channel<Unit>(Channel.UNLIMITED)
            val job = launch(start = CoroutineStart.UNDISPATCHED) {
                requests.collectLatest {
                    started.send(Unit)
                    awaitCancellation()
                }
            }

            requests.emit(NodeId(0))
            started.receive()
            requests.emit(NodeId(0))
            started.receive()

            job.cancel()
        }
    }

    @Test
    fun `a late collector still receives the current target`() = runBlocking {
        withTimeout(TIMEOUT_MS) {
            val requests = newPaginationNodeRequests()

            // 打开阅读页是先 emit 再 collect（OnLoadContent），重试也是重新 collect：
            // replay = 1 保证这两种情况都还能拉到节点窗口
            requests.emit(NodeId(7))

            assertEquals(NodeId(7), requests.first())
        }
    }
}
