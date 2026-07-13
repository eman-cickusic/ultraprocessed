package com.b2.ultraprocessed.network.llm

import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProxyResultChatWorkflowTest {

    @Test
    fun askAboutResult_postsQuestionResultAndHistoryToChatEndpoint() = runTest {
        var capturedUrl = ""
        var capturedBody = ""
        val workflow = ProxyResultChatWorkflow(
            baseUrl = "https://proxy.test",
            client = OkHttpClient.Builder()
                .addInterceptor(
                    Interceptor { chain ->
                        capturedUrl = chain.request().url.toString()
                        capturedBody = chain.request().body.bodyToString()
                        Response.Builder()
                            .request(chain.request())
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .body(SUCCESS_BODY.toResponseBody("application/json".toMediaType()))
                            .build()
                    },
                )
                .build(),
        )

        val reply = workflow.askAboutResult(
            result = chatContext,
            question = "What is concerning?",
            modelId = "gemini-3.5-flash",
            history = listOf(
                ResultChatHistoryMessage(role = "user", text = "Why NOVA 4?"),
                ResultChatHistoryMessage(role = "assistant", text = "Because additives were detected."),
            ),
        ).getOrThrow()

        val body = JSONObject(capturedBody)
        assertEquals("https://proxy.test/chat", capturedUrl)
        assertFalse(body.has("type"))
        assertEquals("What is concerning?", body.getString("question"))
        assertEquals("Test cereal", body.getJSONObject("result").getString("productName"))
        assertEquals("Why NOVA 4?", body.getJSONArray("history").getJSONObject(0).getString("text"))
        assertTrue(reply.allowed)
        assertEquals("Soy lecithin is the main marker.", reply.answer)
    }

    @Test
    fun askAboutResult_doesNotSendAuthorizationHeaders() = runTest {
        var hasAuthorization = false
        var hasGoogleApiKey = false
        val workflow = ProxyResultChatWorkflow(
            baseUrl = "https://proxy.test",
            client = OkHttpClient.Builder()
                .addInterceptor(
                    Interceptor { chain ->
                        hasAuthorization = chain.request().header("Authorization") != null
                        hasGoogleApiKey = chain.request().header("x-goog-api-key") != null
                        Response.Builder()
                            .request(chain.request())
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .body(SUCCESS_BODY.toResponseBody("application/json".toMediaType()))
                            .build()
                    },
                )
                .build(),
        )

        workflow.askAboutResult(
            result = chatContext,
            question = "What is concerning?",
            modelId = "gemini-3.5-flash",
        ).getOrThrow()

        assertFalse(hasAuthorization)
        assertFalse(hasGoogleApiKey)
    }

    @Test
    fun askAboutResult_mapsMissingChatEndpointToDeploymentMessage() = runTest {
        val workflow = ProxyResultChatWorkflow(
            baseUrl = "https://proxy.test",
            client = OkHttpClient.Builder()
                .addInterceptor(
                    Interceptor { chain ->
                        Response.Builder()
                            .request(chain.request())
                            .protocol(Protocol.HTTP_1_1)
                            .code(404)
                            .message("Not Found")
                            .body("Not Found".toResponseBody("text/plain".toMediaType()))
                            .build()
                    },
                )
                .build(),
        )

        val result = workflow.askAboutResult(
            result = chatContext,
            question = "What is concerning?",
            modelId = "gemini-3.5-flash",
        )

        assertTrue(result.isFailure)
        assertTrue(
            result.exceptionOrNull()?.message.orEmpty()
                .contains("current backend deployment", ignoreCase = true),
        )
    }

    @Test
    fun askAboutResult_hidesBackendDetailsOnServerErrors() = runTest {
        val workflow = ProxyResultChatWorkflow(
            baseUrl = "https://proxy.test",
            client = OkHttpClient.Builder()
                .addInterceptor(
                    Interceptor { chain ->
                        Response.Builder()
                            .request(chain.request())
                            .protocol(Protocol.HTTP_1_1)
                            .code(502)
                            .message("Bad Gateway")
                            .body(ERROR_BODY.toResponseBody("application/json".toMediaType()))
                            .build()
                    },
                )
                .build(),
        )

        val result = workflow.askAboutResult(
            result = chatContext,
            question = "What is concerning?",
            modelId = "gemini-3.5-flash",
        )

        val message = result.exceptionOrNull()?.message.orEmpty()
        assertTrue(result.isFailure)
        assertTrue(message.contains("temporarily unavailable", ignoreCase = true))
        assertFalse(message.contains("vertex unavailable", ignoreCase = true))
    }

    private fun okhttp3.RequestBody?.bodyToString(): String {
        val buffer = okio.Buffer()
        this?.writeTo(buffer)
        return buffer.readUtf8()
    }

    private companion object {
        private val chatContext = ResultChatContext(
            productName = "Test cereal",
            novaGroup = 4,
            summary = "Contains additive markers.",
            sourceLabel = "OCR",
            confidence = 0.85f,
            ingredients = listOf("sugar", "soy lecithin"),
            ingredientAssessments = listOf(
                ResultChatIngredientSignal(
                    name = "soy lecithin",
                    verdict = "nova-4",
                    reason = "Emulsifier marker.",
                ),
            ),
            allergens = listOf("Soy"),
            warnings = emptyList(),
        )

        private val SUCCESS_BODY = """
            {
              "type": "chat",
              "reply": {
                "allowed": true,
                "answer": "Soy lecithin is the main marker.",
                "reason": ""
              },
              "model": "gemini-3.5-flash",
              "usage": {"inputTokens": 1, "outputTokens": 2, "totalTokens": 3}
            }
        """.trimIndent()

        private val ERROR_BODY = """
            {"detail": {"error": "model_call_failed", "message": "vertex unavailable with provider internals"}}
        """.trimIndent()
    }
}
