package com.b2.ultraprocessed.network.llm

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProxyFoodLabelLlmWorkflowTest {

    private val extraction = IngredientExtraction(
        code = 0,
        productName = "Test cereal",
        rawText = "whole grain oats, sugar, corn syrup, salt, soy lecithin",
        ingredients = listOf("whole grain oats", "sugar", "corn syrup", "salt", "soy lecithin"),
        confidence = 0.6f,
        warnings = emptyList(),
    )

    @Test
    fun parsesResponse_andMakesExactlyOneHttpCallAcrossThreeStages() = runTest {
        val callCount = AtomicInteger(0)
        val workflow: FoodLabelLlmWorkflow = ProxyFoodLabelLlmWorkflow(
            baseUrl = "https://proxy.test",
            client = stubClient(callCount, code = 200, body = SUCCESS_BODY),
        )

        val nova = workflow.classifyNova(extraction, MODEL).getOrThrow()
        val ingredients = workflow.analyzeIngredientList(extraction, MODEL).getOrThrow()
        val allergens =
            workflow.detectAllergens(ingredients.value.correctedIngredients, MODEL).getOrThrow()

        // One scan = one network call; stages 2 and 3 are served from cache.
        assertEquals(1, callCount.get())

        assertEquals(4, nova.value.novaGroup)
        assertTrue(nova.value.containsConsumableFoodItem)
        assertEquals(0.85f, nova.value.confidence, 0.0001f)
        assertEquals(LlmUsage(inputTokens = 12, outputTokens = 34, totalTokens = 46), nova.usage)

        assertEquals(
            listOf("water", "sugar", "palm oil", "emulsifier"),
            ingredients.value.correctedIngredients,
        )
        assertEquals(listOf("emulsifier"), ingredients.value.ultraProcessedIngredients.map { it.name })
        // Usage is reported only once (on the nova stage) so aggregation is not tripled.
        assertNull(ingredients.usage)

        assertEquals(listOf("milk"), allergens.value.allergens)
        assertNull(allergens.usage)
    }

    @Test
    fun concurrentStagesForSameScan_shareOneNetworkCall() = runTest {
        val callCount = AtomicInteger(0)
        val workflow: FoodLabelLlmWorkflow = ProxyFoodLabelLlmWorkflow(
            baseUrl = "https://proxy.test",
            client = stubClient(callCount, code = 200, body = SUCCESS_BODY),
        )

        val nova = async { workflow.classifyNova(extraction, MODEL) }
        val ingredients = async { workflow.analyzeIngredientList(extraction, MODEL) }

        nova.await().getOrThrow()
        ingredients.await().getOrThrow()

        assertEquals(1, callCount.get())
    }

    @Test
    fun upstreamFailure_mapsToFriendlyFailure() = runTest {
        val callCount = AtomicInteger(0)
        val workflow: FoodLabelLlmWorkflow = ProxyFoodLabelLlmWorkflow(
            baseUrl = "https://proxy.test",
            client = stubClient(callCount, code = 502, body = ERROR_BODY),
        )

        val result = workflow.classifyNova(extraction, MODEL)

        assertTrue(result.isFailure)
        val message = result.exceptionOrNull()?.message.orEmpty()
        assertTrue(message, message.contains("temporarily unavailable", ignoreCase = true))
    }

    private fun stubClient(callCount: AtomicInteger, code: Int, body: String): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor(
                Interceptor { chain ->
                    callCount.incrementAndGet()
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(code)
                        .message(if (code in 200..299) "OK" else "ERR")
                        .body(body.toResponseBody("application/json".toMediaType()))
                        .build()
                },
            )
            .build()

    companion object {
        private const val MODEL = "gemini-2.5-flash"

        private val SUCCESS_BODY = """
            {
              "nova": {
                "containsConsumableFoodItem": true,
                "novaGroup": 4,
                "summary": "Ultra-processed snack with additives.",
                "rejectionReason": "",
                "confidence": 0.85,
                "warnings": []
              },
              "ingredients": {
                "correctedIngredients": ["water", "sugar", "palm oil", "emulsifier"],
                "ultraProcessedIngredients": [
                  {"name": "emulsifier", "reason": "Common ultra-processing marker."}
                ],
                "confidence": 0.8,
                "warnings": []
              },
              "allergens": {
                "allergens": ["milk"],
                "confidence": 0.6,
                "warnings": []
              },
              "model": "gemini-2.5-flash",
              "usage": {"inputTokens": 12, "outputTokens": 34, "totalTokens": 46}
            }
        """.trimIndent()

        private val ERROR_BODY = """
            {"detail": {"error": "model_call_failed", "message": "vertex unavailable"}}
        """.trimIndent()
    }
}
