package com.b2.ultraprocessed.network.llm

import android.content.Context
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

interface ResultChatWorkflow {
    suspend fun askAboutResult(
        result: ResultChatContext,
        question: String,
        modelId: String,
        onStatus: (String) -> Unit = {},
    ): Result<ResultChatReply>
}

data class ResultChatReply(
    val allowed: Boolean,
    val answer: String,
    val reason: String,
)

data class ResultChatContext(
    val productName: String,
    val novaGroup: Int,
    val summary: String,
    val sourceLabel: String,
    val confidence: Float,
    val ingredients: List<String>,
    val ingredientAssessments: List<ResultChatIngredientSignal>,
    val allergens: List<String>,
    val warnings: List<String>,
)

data class ResultChatIngredientSignal(
    val name: String,
    val verdict: String,
    val reason: String,
)

object ResultChatWorkflowFactory {
    fun create(
        context: Context,
        apiKeyProvider: LlmApiKeyProvider,
    ): ResultChatWorkflow {
        val gemini = GeminiResultChatWorkflow(context, apiKeyProvider)
        val openAi = OpenAiCompatibleResultChatWorkflow(
            context = context,
            apiKeyProvider = apiKeyProvider,
            baseUrl = "https://api.openai.com/v1",
            providerTag = "openai_chat",
        )
        val grok = OpenAiCompatibleResultChatWorkflow(
            context = context,
            apiKeyProvider = apiKeyProvider,
            baseUrl = "https://api.x.ai/v1",
            providerTag = "grok_chat",
        )
        val groq = OpenAiCompatibleResultChatWorkflow(
            context = context,
            apiKeyProvider = apiKeyProvider,
            baseUrl = "https://api.groq.com/openai/v1",
            providerTag = "groq_chat",
        )
        return MultiProviderResultChatWorkflow(gemini, openAi, grok, groq)
    }
}

class MultiProviderResultChatWorkflow(
    private val geminiWorkflow: ResultChatWorkflow,
    private val openAiWorkflow: ResultChatWorkflow,
    private val grokWorkflow: ResultChatWorkflow,
    private val groqWorkflow: ResultChatWorkflow,
) : ResultChatWorkflow {
    override suspend fun askAboutResult(
        result: ResultChatContext,
        question: String,
        modelId: String,
        onStatus: (String) -> Unit,
    ): Result<ResultChatReply> =
        workflowFor(modelId).askAboutResult(result, question, modelId, onStatus)

    private fun workflowFor(modelId: String): ResultChatWorkflow {
        val normalized = modelId.trim().lowercase()
        return when {
            normalized.startsWith("gemini-") -> geminiWorkflow
            normalized.startsWith("gpt-") || normalized.startsWith("o1") || normalized.startsWith("o3") -> openAiWorkflow
            normalized.startsWith("grok-") -> grokWorkflow
            normalized.startsWith("llama-") || normalized.startsWith("mixtral-") ||
                normalized.startsWith("gemma-") -> groqWorkflow
            else -> throw IllegalArgumentException("Unsupported model/provider: $modelId")
        }
    }
}

class GeminiResultChatWorkflow(
    context: Context,
    private val apiKeyProvider: LlmApiKeyProvider,
    private val client: OkHttpClient = GeminiHttpClientFactory.create(),
) : ResultChatWorkflow {
    private val appContext = context.applicationContext

    override suspend fun askAboutResult(
        result: ResultChatContext,
        question: String,
        modelId: String,
        onStatus: (String) -> Unit,
    ): Result<ResultChatReply> = withContext(Dispatchers.IO) {
        try {
            require(modelId.startsWith("gemini-")) {
                "Selected model is not supported for result chat."
            }
            val sanitizedQuestion = sanitizeQuestion(question)
            if (looksLikeInjectionAttempt(sanitizedQuestion)) {
                return@withContext Result.success(
                    ResultChatReply(
                        allowed = false,
                        answer = "I can only answer questions about this scan result.",
                        reason = "Prompt injection attempt detected.",
                    ),
                )
            }
            val prompt = readPrompt(RESULT_CHAT_PROMPT)
            val contextJson = result.toChatContextJson().toString(2)
            val reply = retryContractParse(
                operationLabel = "result chat",
                onStatus = onStatus,
                buildPrompt = { attempt, previousError ->
                    prompt + buildContractRepairSuffix("result chat", attempt, previousError)
                },
                request = { repairedPrompt ->
                    executeJsonRequest(
                        requestBody = buildTextRequestBody(
                            prompt = repairedPrompt,
                            contextJson = contextJson,
                            question = sanitizedQuestion,
                        ),
                        modelId = modelId,
                        apiKey = requireApiKey(),
                        operation = "result_chat",
                    )
                },
                parse = ::parseReply,
            )
            Result.success(reply)
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    private fun requireApiKey(): String {
        val apiKey = apiKeyProvider.getApiKey()
        require(apiKey.isNotBlank()) {
            "Add an LLM API key in Settings to use result chat."
        }
        return apiKey
    }

    private fun readPrompt(assetPath: String): String =
        appContext.assets.open(assetPath).bufferedReader().use { it.readText() }

    private fun buildTextRequestBody(
        prompt: String,
        contextJson: String,
        question: String,
    ): okhttp3.RequestBody {
        val text = buildString {
            append(prompt.trim())
            append("\n\n## Current scan result JSON\n")
            append(contextJson)
            append("\n\n## User question\n")
            append(question.trim())
        }
        val root = JSONObject()
            .put(
                "contents",
                JSONArray().put(
                    JSONObject().put(
                        "parts",
                        JSONArray().put(JSONObject().put("text", text)),
                    ),
                ),
            )
            .put(
                "generationConfig",
                JSONObject()
                    .put("temperature", 0.0)
                    .put("topP", 1.0)
                    .put("maxOutputTokens", 900)
                    .put("responseMimeType", "application/json"),
            )
        return root.toString().toRequestBody(JSON_MEDIA_TYPE)
    }

    private suspend fun executeJsonRequest(
        requestBody: okhttp3.RequestBody,
        modelId: String,
        apiKey: String,
        operation: String,
    ): JSONObject {
        val url = "$BASE_URL/models/$modelId:generateContent".toHttpUrl().newBuilder()
            .build()
        AnalysisTelemetry.event("gemini_chat_request_start op=$operation model=$modelId")
        val request = Request.Builder()
            .url(url)
            .header("x-goog-api-key", apiKey)
            .post(requestBody)
            .build()

        return kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(
                object : okhttp3.Callback {
                    override fun onFailure(call: okhttp3.Call, e: IOException) {
                        if (!continuation.isCancelled) {
                            continuation.resumeWith(Result.failure(e))
                        }
                    }

                    override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                        response.use {
                            AnalysisTelemetry.event("gemini_chat_response op=$operation http=${it.code}")
                            runCatching {
                                parseGenerateContentResponse(it)
                            }.onSuccess { json ->
                                if (!continuation.isCancelled) {
                                    continuation.resumeWith(Result.success(json))
                                }
                            }.onFailure { error ->
                                if (!continuation.isCancelled) {
                                    continuation.resumeWith(Result.failure(error))
                                }
                            }
                        }
                    }
                },
            )
        }
    }

    private fun parseGenerateContentResponse(response: okhttp3.Response): JSONObject {
        val body = response.body?.string().orEmpty()
        if (!response.isSuccessful) {
            val trimmed = body.replace('\n', ' ').take(220)
            throw IOException("Result chat failed with HTTP ${response.code}.")
                .also {
                    AnalysisTelemetry.event(
                        "gemini_chat_http_error code=${response.code} body=$trimmed",
                    )
                }
        }
        val root = JSONObject(body)
        val text = root.optJSONArray("candidates")
            ?.optJSONObject(0)
            ?.optJSONObject("content")
            ?.optJSONArray("parts")
            ?.optJSONObject(0)
            ?.optString("text")
            .orEmpty()
        if (text.isBlank()) {
            throw IOException("Result chat returned no text.")
        }
        return parseResponseJson(text)
    }
}

class OpenAiCompatibleResultChatWorkflow(
    context: Context,
    private val apiKeyProvider: LlmApiKeyProvider,
    private val baseUrl: String,
    private val providerTag: String,
    private val client: OkHttpClient = OpenAiCompatibleHttpClientFactory.create(),
) : ResultChatWorkflow {
    private val appContext = context.applicationContext

    override suspend fun askAboutResult(
        result: ResultChatContext,
        question: String,
        modelId: String,
        onStatus: (String) -> Unit,
    ): Result<ResultChatReply> = withContext(Dispatchers.IO) {
        try {
            val sanitizedQuestion = sanitizeQuestion(question)
            if (looksLikeInjectionAttempt(sanitizedQuestion)) {
                return@withContext Result.success(
                    ResultChatReply(
                        allowed = false,
                        answer = "I can only answer questions about this scan result.",
                        reason = "Prompt injection attempt detected.",
                    ),
                )
            }
            val prompt = readPrompt(RESULT_CHAT_PROMPT)
            val contextJson = result.toChatContextJson().toString(2)
            val reply = retryContractParse(
                operationLabel = "result chat",
                onStatus = onStatus,
                buildPrompt = { attempt, previousError ->
                    prompt + buildContractRepairSuffix("result chat", attempt, previousError)
                },
                request = { repairedPrompt ->
                    executeJsonRequest(
                        requestBody = buildTextRequestBody(
                            prompt = repairedPrompt,
                            contextJson = contextJson,
                            question = sanitizedQuestion,
                            modelId = modelId,
                        ),
                        operation = "result_chat",
                        modelId = modelId,
                        apiKey = requireApiKey(),
                    )
                },
                parse = ::parseReply,
            )
            Result.success(reply)
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
        contextJson: String,
        question: String,
        modelId: String,
    ): okhttp3.RequestBody {
        val text = buildString {
            append("\n\n## Current scan result JSON\n")
            append(contextJson)
            append("\n\n## User question\n")
            append(question.trim())
        }
        val root = JSONObject()
            .put("model", modelId)
            .put(
                "messages",
                JSONArray()
                    .put(JSONObject().put("role", "system").put("content", prompt.trim()))
                    .put(JSONObject().put("role", "user").put("content", text.trim())),
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
    ): JSONObject = withContext(Dispatchers.IO) {
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
            if (!response.isSuccessful) {
                val trimmed = body.replace('\n', ' ').take(220)
                AnalysisTelemetry.event("${providerTag}_http_error code=${response.code} body=$trimmed")
                throw IOException("Result chat failed with HTTP ${response.code}.")
            }
            parseChatCompletionResponse(body)
        }
    }

    private fun parseChatCompletionResponse(rawBody: String): JSONObject {
        val root = JSONObject(rawBody)
        val content = root.optJSONArray("choices")
            ?.optJSONObject(0)
            ?.optJSONObject("message")
            ?.optString("content")
            .orEmpty()
        if (content.isBlank()) {
            throw IOException("Result chat returned no text.")
        }
        return parseResponseJson(content)
    }
}

private fun ResultChatContext.toChatContextJson(): JSONObject =
    JSONObject()
        .put("productName", productName)
        .put("novaGroup", novaGroup)
        .put("summary", summary)
        .put("sourceLabel", sourceLabel)
        .put("confidence", confidence)
        .put("ingredients", JSONArray(ingredients))
        .put(
            "ingredientAssessments",
            JSONArray(
                ingredientAssessments.map { assessment ->
                    JSONObject()
                        .put("name", assessment.name)
                        .put(
                            "verdict",
                            when (assessment.verdict.lowercase()) {
                                "safe" -> "safe"
                                "watch" -> "watch"
                                "avoid" -> "avoid"
                                else -> "watch"
                            },
                        )
                        .put("reason", assessment.reason)
                },
            ),
        )
        .put("allergens", JSONArray(allergens))
        .put("warnings", JSONArray(warnings))

private fun parseReply(json: JSONObject): ResultChatReply {
    val allowed = json.requiredBoolean("allowed")
    val answer = json.requiredString("answer")
    val reason = json.optString("reason").trim()
    return ResultChatReply(
        allowed = allowed,
        answer = answer.ifBlank { EMPTY_RESULT_CHAT_TEMPLATE },
        reason = reason,
    )
}

private const val EMPTY_RESULT_CHAT_TEMPLATE =
    "I received the scan context, but the assistant did not return a usable sentence. Ask about the NOVA group, flagged ingredients, or allergens and I will keep the answer tied to this scan."

private fun JSONObject.requiredBoolean(name: String): Boolean {
    if (!has(name)) throw IOException("LLM response missing required field '$name'.")
    return try {
        getBoolean(name)
    } catch (e: Exception) {
        throw IOException("LLM response field '$name' must be a boolean.", e)
    }
}

private fun JSONObject.requiredString(name: String): String {
    if (!has(name)) throw IOException("LLM response missing required field '$name'.")
    return try {
        getString(name).trim()
    } catch (e: Exception) {
        throw IOException("LLM response field '$name' must be a string.", e)
    }
}

private fun sanitizeQuestion(question: String): String =
    question.replace(Regex("\\s+"), " ").trim().take(500)

private fun looksLikeInjectionAttempt(question: String): Boolean {
    val lower = question.lowercase()
    return injectionMarkers.any { lower.contains(it) }
}

private fun parseResponseJson(text: String): JSONObject {
    val trimmed = text.trim()
    return try {
        JSONObject(trimmed)
    } catch (e: JSONException) {
        throw IOException("Result chat returned invalid JSON.", e)
    }
}

private val injectionMarkers = listOf(
    "ignore previous",
    "ignore the above",
    "system prompt",
    "developer message",
    "jailbreak",
    "act as",
    "pretend to be",
    "reveal",
    "bypass",
    "prompt injection",
)

private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta"
private const val RESULT_CHAT_PROMPT = "prompts/food_label_result_chat_prompt.md"
private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
