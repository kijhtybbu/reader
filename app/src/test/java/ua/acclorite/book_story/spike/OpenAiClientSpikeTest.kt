/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * fork 新增：openai-kotlin + Ktor 的 S1 依赖 spike（第二阶段）。
 *
 * 只验证四件 S6 会依赖的事：自定义 host 拼出正确的 URL、Bearer 头与自定义 header 上了线、
 * `chatCompletions()` 的流能被逐块读出、`ktor-client-okhttp` 在 JVM 上可实例化。
 * 全程不碰网络。
 */

package ua.acclorite.book_story.spike

import com.aallam.openai.api.chat.ChatCompletionRequest
import com.aallam.openai.api.chat.ChatMessage
import com.aallam.openai.api.chat.ChatRole
import com.aallam.openai.api.logging.LogLevel
import com.aallam.openai.api.model.ModelId
import com.aallam.openai.client.LoggingConfig
import com.aallam.openai.client.OpenAI
import com.aallam.openai.client.OpenAIConfig
import com.aallam.openai.client.OpenAIHost
import com.aallam.openai.client.RetryStrategy
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiClientSpikeTest {

    private val sseBody = listOf(
        """data: {"id":"chunk-1","object":"chat.completion.chunk","created":1728000000,""" +
            """"model":"test-model","choices":[{"index":0,"delta":{"role":"assistant","content":""}}]}""",
        """data: {"id":"chunk-1","object":"chat.completion.chunk","created":1728000000,""" +
            """"model":"test-model","choices":[{"index":0,"delta":{"content":"Hello"}}]}""",
        """data: {"id":"chunk-1","object":"chat.completion.chunk","created":1728000000,""" +
            """"model":"test-model","choices":[{"index":0,"delta":{"content":" world"}}]}""",
        "data: [DONE]"
    ).joinToString(separator = "\n\n", postfix = "\n\n")

    @Test
    fun streamingChatCompletionsCollectsAllDeltas() = runTest {
        var requestedPath: String? = null
        var requestedAuthorization: String? = null
        var requestedTitle: String? = null
        var requestedBody: String? = null

        val engine = MockEngine { request ->
            requestedPath = request.url.encodedPath
            requestedAuthorization = request.headers[HttpHeaders.Authorization]
            requestedTitle = request.headers["X-Title"]
            requestedBody = (request.body as? TextContent)?.text
            respond(
                content = ByteReadChannel(sseBody),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Text.EventStream.toString())
            )
        }

        val client = OpenAI(
            OpenAIConfig(
                token = "test",
                host = OpenAIHost("https://example.test/v1/"),
                headers = mapOf("X-Title" to "reader"),
                engine = engine,
                logging = LoggingConfig(logLevel = LogLevel.None),
                retry = RetryStrategy(maxRetries = 0)
            )
        )

        // 4.1.0 的 ChatCompletionRequest 没有 `stream` 字段：流式由 chatCompletions() 自己注入。
        val request = ChatCompletionRequest(
            model = ModelId("test-model"),
            messages = listOf(ChatMessage(role = ChatRole.User, content = "translate: hello"))
        )

        val deltas = client.chatCompletions(request).toList()
            .mapNotNull { chunk -> chunk.choices.firstOrNull()?.delta?.content }
        val text = deltas.joinToString(separator = "")

        assertEquals("/v1/chat/completions", requestedPath)
        assertEquals("Bearer test", requestedAuthorization)
        // S6 靠 headers 传 OpenRouter 的 X-Title / 自定义服务商的鉴权头，必须真的发出去
        assertEquals("reader", requestedTitle)
        assertTrue(
            "stream flag must reach the wire, body was: $requestedBody",
            requestedBody?.contains("\"stream\":true") == true
        )
        assertEquals(3, deltas.size)
        assertEquals("Hello world", text)

        client.close()
    }

    @Test
    fun okHttpEngineIsInstantiableOnJvm() {
        // S6 在 Android 上用 OkHttp 引擎；这里只证明 JVM 单测类路径上有它且能建/关，不发请求。
        val client = HttpClient(OkHttp)
        client.close()
    }
}
