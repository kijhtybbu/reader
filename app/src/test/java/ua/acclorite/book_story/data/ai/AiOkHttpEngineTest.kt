/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * fork 新增：生产引擎的离线测试（第二阶段 S6）。
 *
 * MockEngine 只能证明「配置对」，证明不了「生产真的在用 OkHttp 引擎」：`AppModule` 里显式传
 * `OkHttp.create()`，否则 Ktor 靠 ServiceLoader 在运行时发现引擎 —— R8 之后这是编译期看不见、
 * 单测也盖不到的行为。这里起一个 loopback 的最小 HTTP 服务（`ServerSocket`，不用 Android 单元
 * 测试里拿不到的 `com.sun.net.httpserver`），用和 `AppModule` 一样的 engine + 空 Key 依次走
 * `/models` 与流式 `/chat/completions`，把「生产引擎可用」和「本机免 Key」一起钉住。不访问外网。
 *
 * 两次请求共用一个注入引擎：`AppModule` 给单例仓库一个 `OkHttp.create()`，而仓库每次请求都
 * 新建并关闭 `OpenAI`。如果关闭 `OpenAI`（Ktor `HttpClient`）顺手关掉注入的引擎，第二次请求
 * 就会失败——这个测试就是那个回归位。
 */

package ua.acclorite.book_story.data.ai

import io.ktor.client.engine.okhttp.OkHttp
import java.io.BufferedReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiOkHttpEngineTest {

    @Test
    fun sharedOkHttpEngineServesSequentialRequests() {
        val server = LoopbackAiServer()

        try {
            val repository = AiTranslationRepository(
                config = {
                    AiClientConfig(
                        baseUrl = "http://127.0.0.1:${server.port}/v1/",
                        model = "test-model"
                    )
                },
                keyStore = AiKeyStore(FakeDataStore(), FakeAiKeyCipher()),
                engine = OkHttp.create()
            )

            val connection = runBlocking { repository.testConnection() }
            assertTrue("test connection failed: ${connection.exceptionOrNull()}", connection.isSuccess)

            val deltas = runBlocking {
                repository.translate("Hello world.", AiTargetLanguage.ENGLISH).toList()
            }

            assertEquals(listOf("Hello", " world"), deltas)
            assertEquals(
                listOf("GET /v1/models HTTP/1.1", "POST /v1/chat/completions HTTP/1.1"),
                server.requestLines.toList()
            )
            // 服务端自己 trim 掉 header 值，所以这里不依赖引擎对尾部空格的处理；
            // 要钉住的性质是「没有真凭据」。
            assertTrue(
                "unexpected credentials: ${server.authorizations.toList()}",
                server.authorizations.all { it.isNullOrBlank() || it.trim() == "Bearer" }
            )
        } finally {
            server.close()
        }
    }

    /**
     * 最小 HTTP 服务：逐连接读请求头、排掉请求体，然后按路径回 JSON 或 SSE。
     * 同时服务两次顺序请求（第二个连接由共享引擎重新建立），记录每一行的 request line 与
     * `Authorization`。
     */
    private class LoopbackAiServer {
        private val socket = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
        private val payload = sse("Hello", " world").toByteArray()

        val port: Int = socket.localPort
        val requestLines = CopyOnWriteArrayList<String>()
        val authorizations = CopyOnWriteArrayList<String?>()

        private val worker = thread(isDaemon = true, name = "loopback-ai-server") {
            try {
                while (!socket.isClosed) {
                    socket.accept().use { client -> handle(client) }
                }
            } catch (_: Exception) {
                // 测试自身失败会从断言里体现；服务端异常不需要再抛一次。
            }
        }

        private fun handle(client: Socket) {
            val reader: BufferedReader = client.getInputStream().bufferedReader()
            val requestLine = reader.readLine() ?: return
            requestLines += requestLine

            val headers = mutableListOf<String>()
            while (true) {
                val header = reader.readLine() ?: return
                if (header.isEmpty()) break
                headers += header
                if (header.startsWith("Authorization:", ignoreCase = true)) {
                    authorizations += header.substringAfter(':').trim()
                }
            }
            skipRequestBody(reader, headers)

            val streaming = requestLine.contains("/chat/completions ")
            val contentType = if (streaming) "text/event-stream" else "application/json"
            val body = if (streaming) payload else MODEL_LIST.toByteArray()
            val head = buildString {
                append("HTTP/1.1 200 OK\r\n")
                append("Content-Type: ").append(contentType).append("\r\n")
                append("Content-Length: ").append(body.size).append("\r\n")
                append("Connection: close\r\n\r\n")
            }
            client.getOutputStream().apply {
                write(head.toByteArray())
                write(body)
                flush()
            }
        }

        /** 不解析请求体，但必须先排掉，否则提前关连接会让客户端写一半报错。 */
        private fun skipRequestBody(reader: BufferedReader, headers: List<String>) {
            val contentLength = headers
                .firstOrNull { it.startsWith("Content-Length:", ignoreCase = true) }
                ?.substringAfter(':')?.trim()?.toIntOrNull()
            if (contentLength != null) {
                var remaining = contentLength
                while (remaining > 0 && reader.read() >= 0) remaining--
                return
            }

            val chunked = headers.any {
                it.startsWith("Transfer-Encoding:", ignoreCase = true) &&
                    it.contains("chunked", ignoreCase = true)
            }
            if (!chunked) return

            while (true) {
                val size = reader.readLine()?.trim()?.toIntOrNull(16) ?: return
                if (size == 0) {
                    reader.readLine()
                    return
                }
                repeat(size) { reader.read() }
                reader.readLine()
            }
        }

        fun close() {
            socket.close()
            worker.join(5_000)
        }

        private companion object {
            const val MODEL_LIST = """{"object":"list","data":[]}"""

            fun sse(vararg deltas: String): String = buildString {
                deltas.forEach { delta ->
                    append("data: ")
                    append(
                        """{"id":"chunk","object":"chat.completion.chunk","created":1,""" +
                            """"model":"test-model","choices":[{"index":0,"delta":{"content":"$delta"}}]}"""
                    )
                    append("\n\n")
                }
                append("data: [DONE]\n\n")
            }
        }
    }
}
