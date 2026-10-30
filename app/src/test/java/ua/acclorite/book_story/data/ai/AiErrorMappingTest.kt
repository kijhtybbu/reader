/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 */

package ua.acclorite.book_story.data.ai

import com.aallam.openai.api.exception.AuthenticationException
import com.aallam.openai.api.exception.InvalidRequestException
import com.aallam.openai.api.exception.OpenAIError
import com.aallam.openai.api.exception.OpenAIHttpException
import com.aallam.openai.api.exception.OpenAIServerException
import com.aallam.openai.api.exception.OpenAITimeoutException
import com.aallam.openai.api.exception.PermissionException
import com.aallam.openai.api.exception.RateLimitException
import com.aallam.openai.api.exception.UnknownAPIException
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.ServerResponseException
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import java.io.IOException
import kotlin.test.assertFailsWith
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class AiErrorMappingTest {

    @Test
    fun authenticationAndPermissionMapToInvalidKey() {
        val auth = mapAiException(AuthenticationException(401, OpenAIError()))
        assertEquals(AiError.INVALID_KEY, auth.error)
        assertEquals(401, auth.statusCode)

        val forbidden = mapAiException(PermissionException(403, OpenAIError()))
        assertEquals(AiError.INVALID_KEY, forbidden.error)
        assertEquals(403, forbidden.statusCode)
    }

    @Test
    fun rateLimitMapsToRateLimit() {
        val exception = mapAiException(RateLimitException(429, OpenAIError()))

        assertEquals(AiError.RATE_LIMIT, exception.error)
        assertEquals(429, exception.statusCode)
    }

    @Test
    fun notFoundMapsToNotFoundAndOtherBadRequestsAreUnknown() {
        val notFound = mapAiException(InvalidRequestException(404, OpenAIError()))
        assertEquals(AiError.NOT_FOUND, notFound.error)
        assertEquals(404, notFound.statusCode)

        val badRequest = mapAiException(InvalidRequestException(400, OpenAIError()))
        assertEquals(AiError.UNKNOWN, badRequest.error)
        assertEquals(400, badRequest.statusCode)
    }

    @Test
    fun serverAndTimeoutAndNetworkAreDistinct() {
        // 库自己没给 5xx 状态时不能凭空编一个出来（真实 5xx 的状态码见下一个用例）。
        val server = mapAiException(OpenAIServerException())
        assertEquals(AiError.SERVER, server.error)
        assertNull(server.statusCode)

        val timeout = mapAiException(OpenAITimeoutException(IOException("timeout")))
        assertEquals(AiError.TIMEOUT, timeout.error)

        val network = mapAiException(OpenAIHttpException(IOException("offline")))
        assertEquals(AiError.NETWORK, network.error)
    }

    @Test
    fun serverErrorKeepsTheStatusFromTheWrappedResponse() = runTest {
        // openai-kotlin 把 5xx 包成不带状态码的 OpenAIServerException，原始 Ktor 响应在 cause 里。
        val server = mapAiException(OpenAIServerException(serverResponseException()))

        assertEquals(AiError.SERVER, server.error)
        assertEquals(503, server.statusCode)
        assertEquals("AI request failed: SERVER (HTTP 503)", server.message)
    }

    @Test
    fun unknownApiStatusCodesAreMapped() {
        assertEquals(
            AiError.SERVER,
            mapAiException(UnknownAPIException(503, OpenAIError())).error
        )
        assertEquals(
            AiError.UNKNOWN,
            mapAiException(UnknownAPIException(418, OpenAIError())).error
        )
    }

    @Test
    fun existingAiExceptionIsPassedThrough() {
        val original = AiException(AiError.NO_KEY)
        assertEquals(original, mapAiException(original))
    }

    @Test
    fun cancellationIsNotMappedToAnError() {
        assertThrows(CancellationException::class.java) {
            mapAiException(CancellationException("cancelled"))
        }
    }

    @Test
    fun unknownThrowableMapsToUnknownWithoutLeakingMessage() {
        val exception = mapAiException(IllegalStateException("body text with sk-secret"))

        assertEquals(AiError.UNKNOWN, exception.error)
        assertNull(exception.statusCode)
        assertEquals("AI request failed: UNKNOWN", exception.message)
    }

    /** 用真实 Ktor 客户端造一个 [ServerResponseException]（它要一个 HttpResponse，不手搓）。 */
    private suspend fun serverResponseException(): ServerResponseException {
        val client = HttpClient(MockEngine { respond(content = "", status = HttpStatusCode.ServiceUnavailable) }) {
            expectSuccess = true
        }
        return try {
            assertFailsWith<ServerResponseException> { client.get("https://example.test/v1/models") }
        } finally {
            client.close()
        }
    }
}
