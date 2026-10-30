/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * fork 新增：OpenAI 兼容的翻译/语法调用（第二阶段 S6，设计 §6.3/§6.4）。
 *
 * 只用 openai-kotlin 的公开 API：`OpenAIHost(baseUrl)`、`chatCompletions()` 流、
 * `ChatResponseFormat.JsonObject`。不手写 SSE/JSON。
 *
 * 客户端按请求创建、`finally` 关闭：Key 或 Base URL 改完后下一次请求必然用新配置，
 * 也不会出现「旧客户端还挂着旧 Key」的窗口。翻译的多个块共用一个客户端，块间顺序输出。
 *
 * 错误映射只保留类别 + HTTP 状态码；异常消息不含响应体/Key。取消原样抛出
 * CancellationException —— 对话框关闭时不能把它吞成一条「未知错误」。
 *
 * 本机服务（设计 §6.3 的 localhost/127.0.0.1/10.0.2.2）允许不配 Key：这类地址上的
 * Ollama 等本地服务不校验鉴权。公网地址没有 Key 仍然直接抛 NO_KEY，不建立连接。
 */

package ua.acclorite.book_story.data.ai

import com.aallam.openai.api.chat.ChatCompletionRequest
import com.aallam.openai.api.chat.ChatMessage
import com.aallam.openai.api.chat.ChatResponseFormat
import com.aallam.openai.api.chat.ChatRole
import com.aallam.openai.api.core.RequestOptions
import com.aallam.openai.api.exception.AuthenticationException
import com.aallam.openai.api.exception.InvalidRequestException
import com.aallam.openai.api.exception.OpenAIAPIException
import com.aallam.openai.api.exception.OpenAIHttpException
import com.aallam.openai.api.exception.OpenAIIOException
import com.aallam.openai.api.exception.OpenAIServerException
import com.aallam.openai.api.exception.OpenAITimeoutException
import com.aallam.openai.api.exception.PermissionException
import com.aallam.openai.api.exception.RateLimitException
import com.aallam.openai.api.exception.UnknownAPIException
import com.aallam.openai.api.http.Timeout
import com.aallam.openai.api.logging.LogLevel
import com.aallam.openai.api.model.ModelId
import com.aallam.openai.client.LoggingConfig
import com.aallam.openai.client.OpenAI
import com.aallam.openai.client.OpenAIConfig
import com.aallam.openai.client.OpenAIHost
import com.aallam.openai.client.RetryStrategy
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.ResponseException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapConcat
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.mapNotNull
import java.util.Locale
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private const val AI_TITLE_HEADER = "Book's Story"

/**
 * 流式请求自己覆盖 request 超时：`chatCompletions` 的 SSE 可能持续几分钟，
 * 整次调用的 60s 上限会把正常的长流从中间掐断（socket 超时仍然在无数据 60s 后生效）。
 */
private val STREAMING_REQUEST_OPTIONS = RequestOptions(
    timeout = Timeout(request = Duration.INFINITE)
)

class AiTranslationRepository(
    private val config: () -> AiClientConfig,
    private val keyStore: AiKeyStore,
    /** 生产显式传 OkHttp（`AppModule`；不依赖 Ktor 的 ServiceLoader 在 R8 后仍能发现引擎）；单测注入 MockEngine。 */
    private val engine: HttpClientEngine? = null,
    private val uiLocale: () -> Locale = { Locale.getDefault() },
    /** 非流式请求的整次调用上限；流式用 [STREAMING_REQUEST_OPTIONS] 覆盖成不限。 */
    private val timeout: Timeout = Timeout(
        request = 60.seconds,
        connect = 15.seconds,
        socket = 60.seconds
    )
) {

    /** `/models` 是最便宜的鉴权/地址探测；失败只返回类型化错误。 */
    suspend fun testConnection(): Result<Unit> {
        return try {
            val (clientConfig, key) = resolve()
            open(clientConfig, key).use { it.models() }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(mapAiException(e))
        }
    }

    /**
     * 流式翻译。长文本按句切块后顺序请求，块内 delta 原样发出。
     * 空文本返回空流（不建请求）；没有 Key 且不是本机地址时抛 [AiException] `NO_KEY`。
     *
     * 错误只在请求侧映射：[Flow.catch] 只包住上游，下游收集者自己抛的异常原样冒泡，
     * 不会被包成一条「AI 请求失败」。
     */
    fun translate(text: String, targetLanguage: AiTargetLanguage): Flow<String> = flow {
        if (text.isBlank()) return@flow

        var client: OpenAI? = null
        try {
            val (clientConfig, key) = resolve()
            val systemPrompt = translationSystemPrompt(targetLanguage.promptName(uiLocale()))
            val openAi = open(clientConfig, key)
            client = openAi

            chunkAiText(text)
                .asFlow()
                .flatMapConcat { chunk ->
                    openAi.chatCompletions(
                        translationRequest(clientConfig, systemPrompt, chunk),
                        STREAMING_REQUEST_OPTIONS
                    ).mapNotNull { completion ->
                        completion.choices.firstOrNull()?.delta?.content
                            ?.takeIf { it.isNotEmpty() }
                    }
                }
                .catch { throw mapAiException(it) }
                .collect { emit(it) }
        } finally {
            client?.close()
        }
    }

    /**
     * 语法分析。要求 `response_format=json_object`，返回原始 JSON 字符串（S8 负责解析），
     * 不在这里把 JSON 拆成模型 —— 解析失败时 S8 要能拿到原文展示。
     */
    suspend fun analyzeGrammar(
        text: String,
        targetLanguage: AiTargetLanguage
    ): Result<String> {
        return try {
            if (text.isBlank()) throw AiException(AiError.UNKNOWN)

            val (clientConfig, key) = resolve()
            val request = ChatCompletionRequest(
                model = ModelId(clientConfig.model),
                messages = listOf(
                    ChatMessage(
                        role = ChatRole.System,
                        content = grammarSystemPrompt(targetLanguage.promptName(uiLocale()))
                    ),
                    ChatMessage(role = ChatRole.User, content = text)
                ),
                temperature = 0.0,
                responseFormat = ChatResponseFormat.JsonObject
            )
            val content = open(clientConfig, key).use { client ->
                client.chatCompletion(request).choices.firstOrNull()?.message?.content
            }
            if (content.isNullOrBlank()) throw AiException(AiError.EMPTY_RESPONSE)
            Result.success(content)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(mapAiException(e))
        }
    }

    /**
     * 解析本次请求的配置与 Key。本机地址允许空 Key（返回空串），公网地址没有 Key 就
     * 在建立连接前失败 —— 不能把一个空 Key 发到一个会因此计费/鉴权的公网服务上。
     */
    private suspend fun resolve(): Pair<AiClientConfig, String> {
        val configured = config()
        val model = configured.model.trim().ifBlank { DEFAULT_AI_MODEL }
        val clientConfig = AiClientConfig(
            baseUrl = normalizeAiBaseUrl(configured.baseUrl),
            model = model
        )

        val key = keyStore.get().orEmpty()
        if (key.isBlank() && !isLoopbackAiBaseUrl(clientConfig.baseUrl)) {
            throw AiException(AiError.NO_KEY)
        }
        return clientConfig to key
    }

    private fun translationRequest(
        config: AiClientConfig,
        systemPrompt: String,
        chunk: String
    ) = ChatCompletionRequest(
        model = ModelId(config.model),
        messages = listOf(
            ChatMessage(role = ChatRole.System, content = systemPrompt),
            ChatMessage(role = ChatRole.User, content = chunk)
        ),
        temperature = 0.2
    )

    /**
     * 空 Key 时 `token` 传空串。openai-kotlin 4.1.0 无条件安装 Bearer 鉴权（没有「跳过鉴权」
     * 的开关），所以请求上会带一个空的 `Authorization: Bearer`；本机服务不校验它。
     * 从没配置 Key 的地址不可能拿到真 Key，公网地址在 [resolve] 里就被拦下了。
     */
    private fun open(config: AiClientConfig, key: String): OpenAI {
        return OpenAI(
            OpenAIConfig(
                token = key,
                host = OpenAIHost(config.baseUrl),
                timeout = timeout,
                headers = mapOf("X-Title" to AI_TITLE_HEADER),
                logging = LoggingConfig(logLevel = LogLevel.None),
                retry = RetryStrategy(maxRetries = 0),
                engine = engine
            )
        )
    }
}

/** 把 openai-kotlin/Ktor 的异常收敛成 [AiException]；取消不映射。 */
internal fun mapAiException(throwable: Throwable): AiException {
    if (throwable is CancellationException) throw throwable
    if (throwable is AiException) return throwable

    return when (throwable) {
        is AuthenticationException -> AiException(AiError.INVALID_KEY, throwable.statusCode, throwable)
        is PermissionException -> AiException(AiError.INVALID_KEY, throwable.statusCode, throwable)
        is RateLimitException -> AiException(AiError.RATE_LIMIT, throwable.statusCode, throwable)
        is InvalidRequestException -> AiException(
            if (throwable.statusCode == 404) AiError.NOT_FOUND else AiError.UNKNOWN,
            throwable.statusCode,
            throwable
        )

        is OpenAIServerException -> AiException(
            AiError.SERVER,
            statusCode = throwable.wrappedStatusCode(),
            cause = throwable
        )
        is OpenAITimeoutException -> AiException(AiError.TIMEOUT, cause = throwable)
        is OpenAIIOException -> AiException(AiError.NETWORK, cause = throwable)
        is OpenAIHttpException -> AiException(AiError.NETWORK, cause = throwable)
        is UnknownAPIException -> AiException(
            error = errorForStatus(throwable.statusCode),
            statusCode = throwable.statusCode,
            cause = throwable
        )

        is OpenAIAPIException -> AiException(
            error = errorForStatus(throwable.statusCode),
            statusCode = throwable.statusCode,
            cause = throwable
        )

        else -> AiException(AiError.UNKNOWN, cause = throwable)
    }
}

/**
 * openai-kotlin 把 5xx 包成不带状态码的 [OpenAIServerException]，原始 Ktor 响应在 cause 里。
 * 这里把状态码捞回来，UI 才能显示「(HTTP 503)」而不是光秃秃的 SERVER。
 */
private fun Throwable.wrappedStatusCode(): Int? = when (val cause = cause) {
    null -> null
    is ResponseException -> cause.response.status.value
    else -> cause.wrappedStatusCode()
}

private fun errorForStatus(statusCode: Int): AiError = when (statusCode) {
    401, 403 -> AiError.INVALID_KEY
    404 -> AiError.NOT_FOUND
    429 -> AiError.RATE_LIMIT
    in 500..599 -> AiError.SERVER
    else -> AiError.UNKNOWN
}
