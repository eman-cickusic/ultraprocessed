package com.b2.ultraprocessed.network.llm

import com.b2.ultraprocessed.BuildConfig
import com.b2.ultraprocessed.analysis.AnalysisTelemetry
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

class ProxyResultChatWorkflow(
    private val baseUrl: String = BuildConfig.PROXY_BASE_URL,
    private val client: OkHttpClient = ProxyChatHttpClientFactory.create(),
) : ResultChatWorkflow {

    override suspend fun askAboutResult(
        result: ResultChatContext,
        question: String,
        modelId: String,
        history: List<ResultChatHistoryMessage>,
        onStatus: (String) -> Unit,
    ): Result<ResultChatReply> = withContext(Dispatchers.IO) {
        try {
            onStatus("Checking the result context...")
            val payload = JSONObject()
                .put("question", question.trim())
                .put("result", result.toProxyJson())
                .put("history", JSONArray(trimHistory(history).map { it.toJson() }))
            val url = "${baseUrl.trimEnd('/')}/chat"
            AnalysisTelemetry.event("proxy_chat_request_start url=$url")
            val requestBuilder = Request.Builder()
                .url(url)
                .header("Content-Type", "application/json")
                .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            AppCheckTokenProvider.currentToken()?.let {
                requestBuilder.header(AppCheckTokenProvider.X_FIREBASE_APPCHECK_HEADER, it)
            }
            val request = requestBuilder.build()
            client.newCall(request).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                AnalysisTelemetry.event("proxy_chat_response http=${response.code}")
                if (!response.isSuccessful) {
                    throw IOException(proxyChatErrorMessage(response.code))
                }
                Result.success(parseProxyChatReply(raw))
            }
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    private fun parseProxyChatReply(raw: String): ResultChatReply {
        val root = JSONObject(raw)
        val reply = root.optJSONObject("reply") ?: root
        return ResultChatReply(
            allowed = reply.optBoolean("allowed", false),
            answer = reply.optString("answer").trim().ifBlank { EMPTY_RESULT_CHAT_TEMPLATE },
            reason = reply.optString("reason").trim(),
        )
    }

    private fun proxyChatErrorMessage(statusCode: Int): String {
        return when {
            statusCode == 429 ->
                "The AI service is temporarily busy. Please wait a moment and try again."
            statusCode == 404 ->
                "Result chat is not available on the current backend deployment. Please update the backend and try again."
            statusCode == 422 ->
                "The result assistant could not read this question. Please try again."
            statusCode in 500..599 ->
                "The result assistant is temporarily unavailable."
            else -> "Result assistant request failed with HTTP $statusCode."
        }
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

object ProxyChatHttpClientFactory {
    fun create(): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .callTimeout(50, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
}

private fun ResultChatContext.toProxyJson(): JSONObject =
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
                        .put("verdict", assessment.verdict)
                        .put("reason", assessment.reason)
                },
            ),
        )
        .put("allergens", JSONArray(allergens))
        .put("warnings", JSONArray(warnings))

private fun ResultChatHistoryMessage.toJson(): JSONObject =
    JSONObject()
        .put("role", role.trim().lowercase())
        .put("text", text.trim())

private fun trimHistory(history: List<ResultChatHistoryMessage>): List<ResultChatHistoryMessage> {
    val normalized = history
        .mapNotNull { message ->
            val role = message.role.trim().lowercase()
            val text = message.text.trim()
            if (role in setOf("user", "assistant") && text.isNotBlank()) {
                ResultChatHistoryMessage(role = role, text = text.take(1_000))
            } else {
                null
            }
        }
        .takeLast(MAX_HISTORY_MESSAGES)

    val reversed = mutableListOf<ResultChatHistoryMessage>()
    var totalChars = 0
    for (message in normalized.asReversed()) {
        val remaining = MAX_HISTORY_CHARS - totalChars
        if (remaining <= 0) break
        val text = if (message.text.length > remaining) message.text.takeLast(remaining) else message.text
        reversed += message.copy(text = text)
        totalChars += text.length
    }
    return reversed.asReversed()
}

private const val EMPTY_RESULT_CHAT_TEMPLATE =
    "I received the scan context, but the assistant did not return a usable sentence. Ask about the NOVA group, flagged ingredients, or allergens and I will keep the answer tied to this scan."
private const val MAX_HISTORY_MESSAGES = 12
private const val MAX_HISTORY_CHARS = 4_000
