package com.b2.ultraprocessed.network.llm

import android.content.Context
import com.b2.ultraprocessed.analysis.AnalysisDebugLogger
import com.b2.ultraprocessed.analysis.AnalysisTelemetry
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

class OpenAiCompatibleFoodLabelLlmWorkflow(
    context: Context,
    private val apiKeyProvider: LlmApiKeyProvider,
    private val baseUrl: String,
    private val providerTag: String,
    private val client: OkHttpClient = OpenAiCompatibleHttpClientFactory.create(),
) : FoodLabelLlmWorkflow {
    private val appContext = context.applicationContext

    override suspend fun classifyNova(
        extraction: IngredientExtraction,
        modelId: String,
        onStatus: (String) -> Unit,
    ): Result<LlmStageResult<NovaClassification>> = withContext(Dispatchers.IO) {
        try {
            val apiKey = requireApiKey()
            val prompt = readPrompt(CLASSIFICATION_PROMPT)
            AnalysisDebugLogger.log("${providerTag}_nova_request", extraction.toPromptJson().toString(2))
            val response = executeJsonRequest(
                requestBody = buildTextRequestBody(prompt, extraction.toPromptJson(), modelId),
                operation = "classify_nova",
                modelId = modelId,
                apiKey = apiKey,
            )
            val candidate = response.json
            AnalysisDebugLogger.log("${providerTag}_nova_candidate", candidate.toString(2))
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
            val apiKey = requireApiKey()
            val prompt = readPrompt(INGREDIENT_ANALYSIS_PROMPT)
            AnalysisDebugLogger.log("${providerTag}_ingredient_list_request", extraction.toPromptJson().toString(2))
            val response = executeJsonRequest(
                requestBody = buildTextRequestBody(prompt, extraction.toPromptJson(), modelId),
                operation = "analyze_ingredient_list",
                modelId = modelId,
                apiKey = apiKey,
            )
            val candidate = response.json
            AnalysisDebugLogger.log("${providerTag}_ingredient_list_candidate", candidate.toString(2))
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
            val apiKey = requireApiKey()
            val prompt = readPrompt(ALLERGEN_PROMPT)
            val input = JSONObject().put("correctedIngredients", JSONArray(correctedIngredientNames))
            AnalysisDebugLogger.log("${providerTag}_allergen_request", input.toString(2))
            val response = executeJsonRequest(
                requestBody = buildTextRequestBody(prompt, input, modelId),
                operation = "detect_allergens",
                modelId = modelId,
                apiKey = apiKey,
            )
            val allergensCandidate = response.json
            AnalysisDebugLogger.log("${providerTag}_allergen_candidate", allergensCandidate.toString(2))
            val allergens = parseAllergenDetection(allergensCandidate)
            Result.success(LlmStageResult(allergens, response.usage))
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    private fun requireApiKey(): String {
        val apiKey = apiKeyProvider.getApiKey()
        require(apiKey.isNotBlank()) {
            "Add an API key in Settings for the selected provider."
        }
        return apiKey
    }

    private fun readPrompt(assetPath: String): String =
        appContext.assets.open(assetPath).bufferedReader().use { it.readText() }

    private fun buildTextRequestBody(
        prompt: String,
        inputJson: JSONObject,
        modelId: String,
    ): okhttp3.RequestBody {
        val text = prompt.trim() + "\n\n## Input JSON\n" + inputJson.toString(2)
        val root = JSONObject()
            .put("model", modelId)
            .put(
                "messages",
                JSONArray().put(
                    JSONObject()
                        .put("role", "user")
                        .put("content", text),
                ),
            )
            .put("temperature", 0.0)
            .put("top_p", 1.0)
            .put("frequency_penalty", 0.0)
            .put("presence_penalty", 0.0)
            .put("response_format", JSONObject().put("type", "json_object"))
        return root.toString().toRequestBody(JSON_MEDIA_TYPE)
    }

    private suspend fun executeJsonRequest(
        requestBody: okhttp3.RequestBody,
        operation: String,
        modelId: String,
        apiKey: String,
    ): OpenAiProviderJsonResponse = withContext(Dispatchers.IO) {
        val url = "$baseUrl/chat/completions".toHttpUrl().newBuilder().build()
        AnalysisTelemetry.event("${providerTag}_request_start op=$operation model=$modelId")
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $apiKey")
            .post(requestBody)
            .build()
        client.newCall(request).execute().use { response ->
            AnalysisTelemetry.event("${providerTag}_response op=$operation http=${response.code}")
            val body = response.body?.string().orEmpty()
            AnalysisDebugLogger.log("${providerTag}_http_body", "operation=$operation body=${body.take(8_000)}")
            if (!response.isSuccessful) {
                val trimmed = body.replace('\n', ' ').take(220)
                AnalysisTelemetry.event("${providerTag}_http_error code=${response.code} body=$trimmed")
                throw IOException(buildHttpFailureMessage(response.code, trimmed))
            }
            parseChatCompletionResponse(body, operation)
        }
    }

    private fun parseChatCompletionResponse(rawBody: String, operation: String): OpenAiProviderJsonResponse {
        val root = JSONObject(rawBody)
        val content = root.optJSONArray("choices")
            ?.optJSONObject(0)
            ?.optJSONObject("message")
            ?.optString("content")
            .orEmpty()
        AnalysisDebugLogger.log("${providerTag}_model_text", "operation=$operation text=${content.take(8_000)}")
        if (content.isBlank()) {
            throw IOException("LLM food-label workflow returned no text.")
        }
        return OpenAiProviderJsonResponse(
            json = parseResponseJson(content, operation),
            usage = root.parseOpenAiUsage(),
        )
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

    private fun parseAllergenDetection(json: JSONObject): AllergenDetection =
        AllergenDetection(
            allergens = json.requiredArray("allergens").toStringList(),
            warnings = json.requiredArray("warnings").toStringList(),
            confidence = json.requiredConfidence("confidence"),
        )

    companion object {
        private const val CLASSIFICATION_PROMPT = "prompts/food_label_classification_prompt.md"
        private const val INGREDIENT_ANALYSIS_PROMPT = "prompts/food_label_ingredient_analysis_prompt.md"
        private const val ALLERGEN_PROMPT = "prompts/food_label_allergen_prompt.md"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

private data class OpenAiProviderJsonResponse(
    val json: JSONObject,
    val usage: LlmUsage?,
)

private fun JSONObject.parseOpenAiUsage(): LlmUsage? {
    val usage = optJSONObject("usage") ?: return null
    val input = usage.optInt("prompt_tokens", usage.optInt("input_tokens", 0)).coerceAtLeast(0)
    val output = usage.optInt("completion_tokens", usage.optInt("output_tokens", 0)).coerceAtLeast(0)
    val total = usage.optInt("total_tokens", input + output).coerceAtLeast(input + output)
    if (input == 0 && output == 0 && total == 0) return null
    return LlmUsage(inputTokens = input, outputTokens = output, totalTokens = total)
}

object OpenAiCompatibleHttpClientFactory {
    fun create(): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
}

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

private fun IngredientExtraction.toPromptJson(): JSONObject =
    JSONObject()
        .put("code", code)
        .put("productName", productName)
        .put("rawIngredientText", rawText)
        .put("ingredients", JSONArray(ingredients))
        .put("extractionConfidence", confidence)
        .put("extractionWarnings", JSONArray(warnings))
