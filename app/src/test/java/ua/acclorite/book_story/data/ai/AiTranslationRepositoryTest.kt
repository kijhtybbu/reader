/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * fork 新增：AI 仓库的离线测试（第二阶段 S6，设计 §11）。
 *
 * 全部请求都打到 Ktor MockEngine：钉住 URL/鉴权/header、SSE 流式拼接、JSON 请求、
 * 401/404/429/5xx/超时映射、本机地址免 Key、长文本切块与取消传播。
 * 没有真实网络，也没有 Key。
 */

package ua.acclorite.book_story.data.ai

import com.aallam.openai.api.http.Timeout
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import java.net.SocketTimeoutException
import java.util.Locale
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiTranslationRepositoryTest {

    private val dataStore = FakeDataStore()
    private val keyStore = AiKeyStore(dataStore, FakeAiKeyCipher())

    private fun repository(
        engine: MockEngine,
        baseUrl: String = "https://example.test/v1/",
        model: String = "test-model",
        locale: Locale = Locale.US,
        sessionId: String = "",
        reasoningEffort: AiReasoningEffort = AiReasoningEffort.LOW
    ) = AiTranslationRepository(
        config = {
            AiClientConfig(
                baseUrl = baseUrl,
                model = model,
                sessionId = sessionId,
                reasoningEffort = reasoningEffort
            )
        },
        keyStore = keyStore,
        engine = engine,
        uiLocale = { locale }
    )

    @Test
    fun translateStreamsDeltasWithBaseUrlAuthAndHeaders() = runTest {
        keyStore.set("sk-test-key")

        var path: String? = null
        var host: String? = null
        var authorization: String? = null
        var title: String? = null
        var session: String? = null
        var requestBody: String? = null

        val engine = MockEngine { request ->
            path = request.url.encodedPath
            host = request.url.host
            authorization = request.headers[HttpHeaders.Authorization]
            title = request.headers["X-Title"]
            session = request.headers["x-opencode-session"]
            requestBody = (request.body as? TextContent)?.text
            respond(
                content = ByteReadChannel(sse("Hello", " world")),
                status = HttpStatusCode.OK,
                headers = eventStreamHeaders()
            )
        }

        val deltas = repository(
            engine,
            baseUrl = " https://example.test/v1 ",
            sessionId = "session-1"
        ).translate(
            text = "Hello world.",
            targetLanguage = AiTargetLanguage.ENGLISH
        ).toList()

        assertEquals(listOf("Hello", " world"), deltas)
        assertEquals("/v1/chat/completions", path)
        assertEquals("example.test", host)
        assertEquals("Bearer sk-test-key", authorization)
        assertEquals("Book's Story", title)
        assertEquals("session-1", session)
        assertTrue("stream flag missing: $requestBody", requestBody?.contains("\"stream\":true") == true)
        assertTrue("target language missing: $requestBody", requestBody?.contains("English (en)") == true)
        assertTrue("user text missing: $requestBody", requestBody?.contains("Hello world.") == true)
    }

    /**
     * 推理模型先思考再写正文（实测语法分析要 35 秒才出第一段正文），所以默认发 low。
     * `DEFAULT` 必须真的**不发**这个字段：有些兼容端点不认 `reasoning_effort` 会直接 400。
     */
    @Test
    fun reasoningEffortIsSentByDefaultAndCanBeOmitted() = runTest {
        keyStore.set("sk-test-key")

        val requestBodies = mutableListOf<String>()
        val engine = MockEngine { request ->
            requestBodies += (request.body as TextContent).text
            respond(
                content = ByteReadChannel(sse("x")),
                status = HttpStatusCode.OK,
                headers = eventStreamHeaders()
            )
        }

        repository(engine).translate("Hello.", AiTargetLanguage.ENGLISH).toList()
        repository(engine, reasoningEffort = AiReasoningEffort.DEFAULT)
            .translate("Hello.", AiTargetLanguage.ENGLISH)
            .toList()

        assertTrue(
            "reasoning_effort=low missing: ${requestBodies[0]}",
            requestBodies[0].contains("\"reasoning_effort\":\"low\"")
        )
        assertFalse(
            "reasoning_effort must not be sent for DEFAULT: ${requestBodies[1]}",
            requestBodies[1].contains("reasoning_effort")
        )
    }

    @Test
    fun longTextIsSplitIntoSequentialRequests() = runTest {
        keyStore.set("sk-test-key")

        val requestBodies = mutableListOf<String>()
        val engine = MockEngine { request ->
            requestBodies += (request.body as TextContent).text
            respond(
                content = ByteReadChannel(sse("x")),
                status = HttpStatusCode.OK,
                headers = eventStreamHeaders()
            )
        }

        val text = buildString {
            repeat(600) { append("Sentence number ").append(it).append(". ") }
        }
        val chunks = chunkAiText(text)

        val output = repository(engine).translate(text, AiTargetLanguage.ENGLISH)
            .toList()
            .joinToString(separator = "")

        assertTrue(chunks.size > 1)
        assertEquals(chunks.size, requestBodies.size)
        assertEquals("x".repeat(chunks.size), output)
    }

    @Test
    fun testConnectionUsesModelsEndpoint() = runTest {
        keyStore.set("sk-test-key")

        var path: String? = null
        val engine = MockEngine { request ->
            path = request.url.encodedPath
            respond(
                content = ByteReadChannel(
                    """{"object":"list","data":[{"id":"gpt-4o-mini","object":"model","created":1,"owned_by":"openai"}]}"""
                ),
                status = HttpStatusCode.OK,
                headers = jsonHeaders()
            )
        }

        val result = repository(engine).testConnection()

        assertTrue(result.isSuccess)
        assertEquals("/v1/models", path)
    }

    @Test
    fun testConnectionMaps401ToInvalidKey() = runTest {
        keyStore.set("sk-test-key")

        val engine = MockEngine {
            respond(
                content = ByteReadChannel(errorBody("Incorrect API key provided")),
                status = HttpStatusCode.Unauthorized,
                headers = jsonHeaders()
            )
        }

        val error = repository(engine).testConnection().exceptionOrNull() as AiException

        assertEquals(AiError.INVALID_KEY, error.error)
        assertEquals(401, error.statusCode)
        assertFalse(error.message.orEmpty().contains("Incorrect API key provided"))
    }

    @Test
    fun testConnectionFallsBackToChatWhenModelsReturns400() = runTest {
        keyStore.set("sk-test-key")
        val paths = mutableListOf<String>()
        val engine = MockEngine { request ->
            paths += request.url.encodedPath
            if (request.url.encodedPath.endsWith("/models")) {
                respond(
                    content = ByteReadChannel("""{"error":{"message":"models disabled"}}"""),
                    status = HttpStatusCode.BadRequest,
                    headers = jsonHeaders()
                )
            } else {
                respond(
                    content = ByteReadChannel(
                        """{"id":"chat-1","object":"chat.completion","created":1,""" +
                            """"model":"test-model","choices":[{"index":0,"message":""" +
                            """{"role":"assistant","content":"pong"},"finish_reason":"stop"}]}"""
                    ),
                    status = HttpStatusCode.OK,
                    headers = jsonHeaders()
                )
            }
        }

        val result = repository(engine).testConnection()

        assertTrue(result.isSuccess)
        assertEquals(listOf("/v1/models", "/v1/chat/completions"), paths)
    }

    @Test
    fun testConnectionMaps429ToRateLimit() = runTest {
        keyStore.set("sk-test-key")

        val engine = MockEngine {
            respond(
                content = ByteReadChannel(errorBody("Rate limit reached")),
                status = HttpStatusCode.TooManyRequests,
                headers = jsonHeaders()
            )
        }

        val error = repository(engine).testConnection().exceptionOrNull() as AiException

        assertEquals(AiError.RATE_LIMIT, error.error)
        assertEquals(429, error.statusCode)
    }

    /** 公网地址（默认 Base URL）没有 Key 时必须在建连之前失败。 */
    @Test
    fun missingKeyOnARemoteEndpointReturnsNoKeyWithoutSendingARequest() = runTest {
        val engine = MockEngine {
            error("network must not be touched without a key")
        }
        val repository = repository(engine)

        val testError = repository.testConnection().exceptionOrNull() as AiException
        assertEquals(AiError.NO_KEY, testError.error)

        val flowError = assertFailsWith<AiException> {
            repository.translate("Hello.", AiTargetLanguage.ENGLISH).toList()
        }
        assertEquals(AiError.NO_KEY, flowError.error)
    }

    /** 本机 OpenAI 兼容服务（Ollama 等）不校验 Key：允许无 Key 直连，且不发任何真凭据。 */
    @Test
    fun keylessLoopbackEndpointIsAllowed() = runTest {
        var path: String? = null
        var authorization: String? = null
        val engine = MockEngine { request ->
            path = request.url.encodedPath
            authorization = request.headers[HttpHeaders.Authorization]
            respond(
                content = ByteReadChannel(sse("Hello")),
                status = HttpStatusCode.OK,
                headers = eventStreamHeaders()
            )
        }

        val deltas = repository(engine, baseUrl = "http://127.0.0.1:11434/v1")
            .translate("Hello.", AiTargetLanguage.ENGLISH)
            .toList()

        val sentAuthorization = authorization

        assertEquals(listOf("Hello"), deltas)
        assertEquals("/v1/chat/completions", path)
        // 没配置 Key 就不能把任何凭据发出去。openai-kotlin 4.1.0 无条件安装 Bearer 鉴权，
        // 所以 MockEngine 看到的是空的 "Bearer "（这里只断言「没有真凭据」，不依赖尾部空格的处理）。
        assertTrue(
            "unexpected credentials: $sentAuthorization",
            sentAuthorization.isNullOrBlank() || sentAuthorization.trim() == "Bearer"
        )
    }

    @Test
    fun keylessLoopbackTestConnectionUsesModelsEndpoint() = runTest {
        var path: String? = null
        val engine = MockEngine { request ->
            path = request.url.encodedPath
            respond(
                content = ByteReadChannel(
                    """{"object":"list","data":[{"id":"llama3.1","object":"model","created":1,"owned_by":"ollama"}]}"""
                ),
                status = HttpStatusCode.OK,
                headers = jsonHeaders()
            )
        }

        val result = repository(engine, baseUrl = "http://localhost:11434/v1").testConnection()

        assertTrue(result.isSuccess)
        assertEquals("/v1/models", path)
    }

    @Test
    fun testConnectionMaps404ToNotFound() = runTest {
        keyStore.set("sk-test-key")

        val engine = MockEngine {
            respond(
                content = ByteReadChannel(errorBody("no such model")),
                status = HttpStatusCode.NotFound,
                headers = jsonHeaders()
            )
        }

        val error = repository(engine).testConnection().exceptionOrNull() as AiException

        assertEquals(AiError.NOT_FOUND, error.error)
        assertEquals(404, error.statusCode)
    }

    @Test
    fun testConnectionMaps500ToServerWithStatus() = runTest {
        keyStore.set("sk-test-key")

        val engine = MockEngine {
            respond(
                content = ByteReadChannel(errorBody("upstream exploded")),
                status = HttpStatusCode.InternalServerError,
                headers = jsonHeaders()
            )
        }

        val error = repository(engine).testConnection().exceptionOrNull() as AiException

        assertEquals(AiError.SERVER, error.error)
        assertEquals(500, error.statusCode)
        assertFalse(error.message.orEmpty().contains("upstream exploded"))
    }

    @Test
    fun socketTimeoutMapsToTimeout() = runTest {
        keyStore.set("sk-test-key")

        val engine = MockEngine { throw SocketTimeoutException("read timed out") }

        val error = repository(engine).testConnection().exceptionOrNull() as AiException

        assertEquals(AiError.TIMEOUT, error.error)
    }

    /**
     * 流式请求不能被「整次调用」的 request 超时掐断：同一个仓库、同一条超时配置下，
     * `/models` 会 TIMEOUT，而 SSE 流仍然正常读完（它用 per-request 覆盖成不限）。
     */
    @Test
    fun streamingIsNotKilledByTheWholeCallRequestTimeout() = runBlocking {
        keyStore.set("sk-test-key")

        val engine = MockEngine { request ->
            delay(400)
            if (request.url.encodedPath.endsWith("/models")) {
                respond(
                    content = ByteReadChannel("""{"object":"list","data":[]}"""),
                    status = HttpStatusCode.OK,
                    headers = jsonHeaders()
                )
            } else {
                respond(
                    content = ByteReadChannel(sse("Hello", " world")),
                    status = HttpStatusCode.OK,
                    headers = eventStreamHeaders()
                )
            }
        }

        val repository = AiTranslationRepository(
            config = { AiClientConfig(baseUrl = "https://example.test/v1/", model = "test-model") },
            keyStore = keyStore,
            engine = engine,
            timeout = Timeout(
                request = 50.milliseconds,
                connect = 5.seconds,
                socket = 5.seconds
            )
        )

        // 超时确实生效（否则下一条断言就是空的）。
        val timeoutError = repository.testConnection().exceptionOrNull() as AiException
        assertEquals(AiError.TIMEOUT, timeoutError.error)

        val deltas = repository.translate("Hello.", AiTargetLanguage.ENGLISH).toList()
        assertEquals(listOf("Hello", " world"), deltas)
    }

    /**
     * 语法请求：流式 SSE、不要 `response_format=json_object`，模型返回什么 Markdown 就原样输出什么。
     */
    @Test
    fun grammarStreamsMarkdownWithoutResponseFormat() = runTest {
        keyStore.set("sk-test-key")

        var requestBody: String? = null
        var accept: String? = null
        val engine = MockEngine { request ->
            requestBody = (request.body as TextContent).text
            accept = request.headers[HttpHeaders.Accept]
            respond(
                content = ByteReadChannel(sse("## Main clause\n", "- **Subject**: *It*")),
                status = HttpStatusCode.OK,
                headers = eventStreamHeaders()
            )
        }

        val deltas = repository(engine).analyzeGrammar(
            text = "It was the best of times.",
            targetLanguage = AiTargetLanguage.SIMPLIFIED_CHINESE
        ).toList()

        assertEquals(listOf("## Main clause\n", "- **Subject**: *It*"), deltas)
        assertEquals("text/event-stream", accept)
        assertFalse(
            "response_format must not be sent: $requestBody",
            requestBody?.contains("response_format") == true
        )
        assertTrue(
            "target language missing: $requestBody",
            requestBody?.contains("Simplified Chinese (zh-CN)") == true
        )
        assertTrue(
            "grammar prompt missing: $requestBody",
            requestBody?.contains("Markdown") == true
        )
        assertTrue(
            "the grammar request must be streamed: $requestBody",
            requestBody?.contains("\"stream\":true") == true
        )
    }

    /**
     * 曾经为了兼容不认 `response_format` 的端点，400 时会去掉该字段重试一次。
     * 现在根本不发那个字段，400 就应该直接失败，不再发第二个请求。
     */
    @Test
    fun grammarBadRequestIsNotRetried() = runTest {
        keyStore.set("sk-test-key")

        var requests = 0
        val engine = MockEngine {
            requests += 1
            respond(
                content = ByteReadChannel(errorBody("unsupported")),
                status = HttpStatusCode.BadRequest,
                headers = jsonHeaders()
            )
        }

        val error = assertFailsWith<AiException> {
            repository(engine)
                .analyzeGrammar("Hello.", AiTargetLanguage.ENGLISH)
                .toList()
        }

        assertEquals(AiError.UNKNOWN, error.error)
        assertEquals(400, error.statusCode)
        assertEquals(1, requests)
    }

    /**
     * 空内容不再在这里变成 `EMPTY_RESPONSE`：流式仓库只管把 delta 原样发出，
     * 聚合与判空在状态机里（`TranslationModelTest` 里有那条用例）。这里钉住「一个 delta 都没有」。
     */
    @Test
    fun emptyGrammarStreamEmitsNothing() = runTest {
        keyStore.set("sk-test-key")

        val engine = MockEngine {
            respond(
                content = ByteReadChannel(sse()),
                status = HttpStatusCode.OK,
                headers = eventStreamHeaders()
            )
        }

        val deltas = repository(engine)
            .analyzeGrammar("Hello.", AiTargetLanguage.ENGLISH)
            .toList()

        assertTrue(deltas.isEmpty())
    }

    @Test
    fun cancellationPropagatesAndEmitsNothing() = runTest {
        keyStore.set("sk-test-key")

        val started = CompletableDeferred<Unit>()
        val engine = MockEngine {
            started.complete(Unit)
            awaitCancellation()
        }

        val received = mutableListOf<String>()
        val job = launch {
            repository(engine).translate("Hello.", AiTargetLanguage.ENGLISH)
                .collect { received += it }
        }

        started.await()
        job.cancel()
        job.join()

        assertTrue(job.isCancelled)
        assertTrue(received.isEmpty())
    }

    @Test
    fun blankTextDoesNotSendARequest() = runTest {
        var requests = 0
        val engine = MockEngine {
            requests += 1
            error("blank text must not reach the network")
        }

        val deltas = repository(engine).translate("   ", AiTargetLanguage.ENGLISH).toList()

        assertTrue(deltas.isEmpty())
        assertEquals(0, requests)
    }

    private fun sse(vararg deltas: String): String = buildString {
        deltas.forEach { delta ->
            append("data: ")
            append(
                """{"id":"chunk","object":"chat.completion.chunk","created":1,"model":"test-model",""" +
                    """"choices":[{"index":0,"delta":{"content":"${jsonEscape(delta)}"}}]}"""
            )
            append("\n\n")
        }
        append("data: [DONE]\n\n")
    }

    private fun errorBody(message: String): String {
        return """{"error":{"message":"${jsonEscape(message)}","type":"invalid_request_error"}}"""
    }

    private fun jsonEscape(value: String): String {
        // 换行也必须转义：语法分析的 Markdown 增量是带换行的，裸换行会让 mock 的响应体变成非法 JSON。
        return value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
    }

    private fun eventStreamHeaders() = headersOf(
        HttpHeaders.ContentType,
        ContentType.Text.EventStream.toString()
    )

    private fun jsonHeaders() = headersOf(
        HttpHeaders.ContentType,
        ContentType.Application.Json.toString()
    )
}
