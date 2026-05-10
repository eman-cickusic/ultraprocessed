package com.b2.ultraprocessed.network.llm

import android.content.Context
import com.b2.ultraprocessed.analysis.AnalysisDebugLogger
import com.b2.ultraprocessed.analysis.AnalysisTelemetry
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class GeminiFoodLabelLlmWorkflow(
    context: Context,
    private val apiKeyProvider: LlmApiKeyProvider,
    private val client: OkHttpClient = GeminiHttpClientFactory.create(),
) : FoodLabelLlmWorkflow {
    private val appContext = context.applicationContext

    override suspend fun classifyNova(
        extraction: IngredientExtraction,
        modelId: String,
        onStatus: (String) -> Unit,
    ): Result<LlmStageResult<NovaClassification>> = withContext(Dispatchers.IO) {
        try {
            requireSupportedModel(modelId)
            val apiKey = requireApiKey()

            val prompt = readPrompt(CLASSIFICATION_PROMPT)
            AnalysisDebugLogger.log("gemini_nova_request", extraction.toPromptJson().toString(2))
            val response = executeJsonRequest(
                requestBody = buildTextRequestBody(prompt, extraction.toPromptJson()),
                modelId = modelId,
                apiKey = apiKey,
                operation = "classify_nova",
            )
            val candidate = response.json
            AnalysisDebugLogger.log("gemini_nova_candidate", candidate.toString(2))
            val classification = LlmClassificationParser.parseNova(candidate)
            Result.success(LlmStageResult(classification, response.usage))
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    override suspend fun analyzeIngredientList(
        extraction: IngredientExtraction,
        modelId: String,
        onStatus: (String) -> Unit,
    ): Result<LlmStageResult<IngredientListAnalysis>> = withContext(Dispatchers.IO) {
        try {
            requireSupportedModel(modelId)
            val apiKey = requireApiKey()
            val prompt = readPrompt(INGREDIENT_ANALYSIS_PROMPT)
            AnalysisDebugLogger.log("gemini_ingredient_list_request", extraction.toPromptJson().toString(2))
            val response = executeJsonRequest(
                requestBody = buildTextRequestBody(prompt, extraction.toPromptJson()),
                modelId = modelId,
                apiKey = apiKey,
                operation = "analyze_ingredient_list",
            )
            val candidate = response.json
            AnalysisDebugLogger.log("gemini_ingredient_list_candidate", candidate.toString(2))
            Result.success(LlmStageResult(LlmClassificationParser.parseIngredientList(candidate), response.usage))
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    override suspend fun detectAllergens(
        correctedIngredientNames: List<String>,
        modelId: String,
        onStatus: (String) -> Unit,
    ): Result<LlmStageResult<AllergenDetection>> = withContext(Dispatchers.IO) {
        try {
            requireSupportedModel(modelId)
            val apiKey = requireApiKey()

            val prompt = readPrompt(ALLERGEN_PROMPT)
            val input = JSONObject().put("correctedIngredients", JSONArray(correctedIngredientNames))
            AnalysisDebugLogger.log("gemini_allergen_request", input.toString(2))
            val response = executeJsonRequest(
                requestBody = buildTextRequestBody(prompt, input),
                modelId = modelId,
                apiKey = apiKey,
                operation = "detect_allergens",
            )
            val allergensCandidate = response.json
            AnalysisDebugLogger.log("gemini_allergen_candidate", allergensCandidate.toString(2))
            val allergens = parseAllergenDetection(allergensCandidate)
            Result.success(LlmStageResult(allergens, response.usage))
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    private fun requireSupportedModel(modelId: String) {
        require(modelId.startsWith("gemini-")) {
            "Selected model is not supported for direct food-label analysis yet."
        }
    }

    private fun requireApiKey(): String {
        val apiKey = apiKeyProvider.getApiKey()
        require(apiKey.isNotBlank()) {
            "Add an LLM API key in Settings to analyze OCR text."
        }
        return apiKey
    }

    private fun readPrompt(assetPath: String): String =
        appContext.assets.open(assetPath).bufferedReader().use { it.readText() }

    private fun buildTextRequestBody(
        prompt: String,
        inputJson: JSONObject,
    ): RequestBody {
        val text = prompt.trim() + "\n\n## Input JSON\n" + inputJson.toString(2)
        return buildGenerateContentRequest(JSONArray().put(JSONObject().put("text", text)))
    }

    private fun buildGenerateContentRequest(parts: JSONArray): RequestBody {
        val root = JSONObject()
            .put(
                "contents",
                JSONArray().put(JSONObject().put("parts", parts)),
            )
            .put(
                "generationConfig",
                JSONObject()
                    .put("temperature", 0.0)
                    .put("topP", 1.0)
                    .put("maxOutputTokens", 1800)
                    .put("responseMimeType", "application/json"),
            )
        return root.toString().toRequestBody(JSON_MEDIA_TYPE)
    }

    private suspend fun executeJsonRequest(
        requestBody: RequestBody,
        modelId: String,
        apiKey: String,
        operation: String,
    ): GeminiProviderJsonResponse {
        val url = "$BASE_URL/models/$modelId:generateContent".toHttpUrl().newBuilder()
            .build()
        AnalysisTelemetry.event("gemini_request_start op=$operation model=$modelId")
        val request = Request.Builder()
            .url(url)
            .header("x-goog-api-key", apiKey)
            .post(requestBody)
            .build()

        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(
                object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        if (!continuation.isCancelled) {
                            continuation.resumeWithException(e)
                        }
                    }

                    override fun onResponse(call: Call, response: Response) {
                        response.use {
                            AnalysisTelemetry.event("gemini_response op=$operation http=${it.code}")
                            runCatching {
                                parseGenerateContentResponse(it, operation)
                            }.onSuccess { json ->
                                if (!continuation.isCancelled) {
                                    continuation.resume(json)
                                }
                            }.onFailure { error ->
                                if (!continuation.isCancelled) {
                                    continuation.resumeWithException(error)
                                }
                            }
                        }
                    }
                },
            )
        }
    }

    private fun parseGenerateContentResponse(response: Response, operation: String): GeminiProviderJsonResponse {
        val body = response.body?.string().orEmpty()
        AnalysisDebugLogger.log("gemini_http_body", "operation=$operation body=${body.take(8_000)}")
        if (!response.isSuccessful) {
            val trimmed = body.replace('\n', ' ').take(220)
            throw IOException(buildHttpFailureMessage(response.code, trimmed))
                .also {
                    AnalysisTelemetry.event(
                        "gemini_http_error code=${response.code} body=$trimmed",
                    )
                }
        }
        val root = JSONObject(body)
        val candidates = root.optJSONArray("candidates")
        val content = candidates
            ?.optJSONObject(0)
            ?.optJSONObject("content")
        val parts = content?.optJSONArray("parts")
        val text = parts?.optJSONObject(0)?.optString("text").orEmpty()
        AnalysisDebugLogger.log("gemini_model_text", "operation=$operation text=${text.take(8_000)}")
        if (text.isBlank()) {
            throw IOException("LLM food-label workflow returned no text.")
        }
        return GeminiProviderJsonResponse(
            json = parseResponseJson(text, operation),
            usage = root.parseGeminiUsage(),
        )
    }

    private fun buildHttpFailureMessage(statusCode: Int, apiBody: String): String {
        return when (statusCode) {
            429 -> buildString {
                append("LLM food-label workflow failed with HTTP 429 (rate limit or quota exceeded).")
                if (apiBody.isNotBlank()) {
                    append(" Provider says: ")
                    append(apiBody)
                }
            }
            else -> "LLM food-label workflow failed with HTTP $statusCode."
        }
    }

    private fun parseResponseJson(text: String, operation: String): JSONObject {
        val trimmed = text.trim()
        return try {
            JSONObject(trimmed)
        } catch (e: JSONException) {
            val start = trimmed.indexOf('{')
            val end = trimmed.lastIndexOf('}')
            if (start >= 0 && end > start) {
                return try {
                    JSONObject(trimmed.substring(start, end + 1))
                } catch (_: JSONException) {
                    throw IOException("LLM food-label workflow returned invalid JSON.", e)
                }
            }
            throw IOException("LLM food-label workflow returned invalid JSON.", e)
        }
    }

    private fun parseAllergenDetection(json: JSONObject): AllergenDetection =
        AllergenDetection(
            allergens = json.requiredArray("allergens").toStringList(),
            warnings = json.requiredArray("warnings").toStringList(),
            confidence = json.requiredConfidence("confidence"),
        )

    companion object {
        private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta"
        private const val CLASSIFICATION_PROMPT = "prompts/food_label_classification_prompt.md"
        private const val INGREDIENT_ANALYSIS_PROMPT = "prompts/food_label_ingredient_analysis_prompt.md"
        private const val ALLERGEN_PROMPT = "prompts/food_label_allergen_prompt.md"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

private data class GeminiProviderJsonResponse(
    val json: JSONObject,
    val usage: LlmUsage?,
)

private fun JSONObject.parseGeminiUsage(): LlmUsage? {
    val usage = optJSONObject("usageMetadata") ?: return null
    val input = usage.optInt("promptTokenCount", 0).coerceAtLeast(0)
    val output = usage.optInt("candidatesTokenCount", 0).coerceAtLeast(0)
    val total = usage.optInt("totalTokenCount", input + output).coerceAtLeast(input + output)
    if (input == 0 && output == 0 && total == 0) return null
    return LlmUsage(inputTokens = input, outputTokens = output, totalTokens = total)
}

object GeminiHttpClientFactory {
    fun create(): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
}

private fun IngredientExtraction.toPromptJson(): JSONObject =
    JSONObject()
        .put("code", code)
        .put("productName", productName)
        .put("rawIngredientText", rawText)
        .put("ingredients", JSONArray(ingredients))
        .put("extractionConfidence", confidence)
        .put("extractionWarnings", JSONArray(warnings))

private fun JSONObject.requiredString(name: String): String {
    if (!has(name)) throw IOException("LLM response missing required field '$name'.")
    return try {
        getString(name).trim()
    } catch (e: Exception) {
        throw IOException("LLM response field '$name' must be a string.", e)
    }
}

private fun JSONObject.requiredInt(name: String): Int {
    if (!has(name)) throw IOException("LLM response missing required field '$name'.")
    return try {
        getInt(name)
    } catch (e: Exception) {
        throw IOException("LLM response field '$name' must be an integer.", e)
    }
}

private fun JSONObject.requiredArray(name: String): JSONArray {
    if (!has(name)) throw IOException("LLM response missing required field '$name'.")
    return try {
        getJSONArray(name)
    } catch (e: Exception) {
        throw IOException("LLM response field '$name' must be an array.", e)
    }
}

private fun JSONObject.requiredConfidence(name: String): Float {
    if (!has(name)) throw IOException("LLM response missing required field '$name'.")
    val confidence = try {
        getDouble(name).toFloat()
    } catch (e: Exception) {
        throw IOException("LLM response field '$name' must be a number.", e)
    }
    if (confidence !in 0f..1f) {
        throw IOException("LLM response field '$name' must be between 0.0 and 1.0.")
    }
    return confidence
}
