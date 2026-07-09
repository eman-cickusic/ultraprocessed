package com.b2.ultraprocessed.network.llm

import com.b2.ultraprocessed.BuildConfig
import com.b2.ultraprocessed.analysis.AnalysisDebugLogger
import com.b2.ultraprocessed.analysis.AnalysisTelemetry
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Routes food-label analysis through the Cloud Run proxy (`POST /analyze`), which runs Gemini via
 * the runtime service account so no user API key is required.
 *
 * The proxy returns NOVA classification, ingredient analysis, allergens, and token usage in a
 * single response, but the [FoodLabelLlmWorkflow] interface (and the pipeline that drives it)
 * expects three sequential calls per scan. To guarantee **one scan = one network call** the
 * `/analyze` request is run once as a de-duplicated in-flight [Deferred] keyed by the scan's
 * ingredient text; all three interface methods join that same call.
 *
 * The de-dup also survives the pipeline's per-stage `withTimeout` + retry: the request runs in
 * this workflow's own [scope], so a caller timeout cancels only the *await* (not the underlying
 * call), and the retry joins the still-running request instead of issuing a second one. Token
 * usage is therefore reported exactly once (on [classifyNova]).
 */
class ProxyFoodLabelLlmWorkflow(
    private val baseUrl: String = BuildConfig.PROXY_BASE_URL,
    private val client: OkHttpClient = ProxyHttpClientFactory.create(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : FoodLabelLlmWorkflow {

    private val mutex = Mutex()
    private val inFlightByExtraction = LinkedHashMap<String, Deferred<CachedAnalysis>>()
    private val byCorrectedIngredients = LinkedHashMap<String, CachedAnalysis>()

    override suspend fun classifyNova(
        extraction: IngredientExtraction,
        modelId: String,
        onStatus: (String) -> Unit,
    ): Result<LlmStageResult<NovaClassification>> = runStage {
        val analysis = obtain(extraction)
        // Usage is reported here only, so aggregation across the three stages is not tripled.
        LlmStageResult(analysis.nova, analysis.usage)
    }

    override suspend fun analyzeIngredientList(
        extraction: IngredientExtraction,
        modelId: String,
        onStatus: (String) -> Unit,
    ): Result<LlmStageResult<IngredientListAnalysis>> = runStage {
        val analysis = obtain(extraction)
        LlmStageResult(analysis.ingredients, usage = null)
    }

    override suspend fun detectAllergens(
        correctedIngredientNames: List<String>,
        modelId: String,
        onStatus: (String) -> Unit,
    ): Result<LlmStageResult<AllergenDetection>> = runStage {
        val cached = mutex.withLock { byCorrectedIngredients[correctedIngredientNames.cacheKey()] }
            ?: run {
                // classifyNova/analyzeIngredientList run first in the same scan and populate this
                // cache, so a miss means the corrected list desynced. Fail loudly rather than
                // silently report "no allergens" — a false negative is unsafe for an allergen check.
                AnalysisDebugLogger.log("proxy_allergen_cache_miss", "names=$correctedIngredientNames")
                throw IOException("Allergen analysis was lost for this scan. Please scan again.")
            }
        LlmStageResult(cached.allergens, usage = null)
    }

    /**
     * Returns the single `/analyze` result for [extraction], starting the request at most once and
     * joining any in-flight or already-completed request for the same scan.
     */
    private suspend fun obtain(extraction: IngredientExtraction): CachedAnalysis {
        val key = extraction.cacheKey()
        val deferred = mutex.withLock {
            inFlightByExtraction[key]?.takeUnless { it.isCancelled }
                ?: scope.async { executeAnalyze(extraction) }
                    .also { inFlightByExtraction.putBounded(key, it) }
        }
        val analysis = try {
            deferred.await()
        } catch (t: Throwable) {
            // A non-cancellation throwable means the shared request itself failed (the workflow
            // scope never cancels), so evict it to let a later attempt re-fetch. A
            // CancellationException means only this awaiter was cancelled (e.g. the pipeline's
            // stage timeout) — leave the request running so the retry joins the same call.
            if (t !is CancellationException) {
                mutex.withLock { if (inFlightByExtraction[key] === deferred) inFlightByExtraction.remove(key) }
            }
            throw t
        }
        mutex.withLock {
            byCorrectedIngredients.putBounded(
                analysis.ingredients.correctedIngredients.cacheKey(),
                analysis,
            )
        }
        return analysis
    }

    private suspend fun executeAnalyze(extraction: IngredientExtraction): CachedAnalysis {
        val payload = JSONObject().put("ingredient_text", extraction.rawText)
        if (extraction.productName.isNotBlank()) {
            payload.put("product_name", extraction.productName)
        }
        val url = "${baseUrl.trimEnd('/')}/analyze"
        AnalysisTelemetry.event("proxy_request_start url=$url")
        val request = Request.Builder()
            .url(url)
            .header("Content-Type", "application/json")
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val body = suspendCancellableCoroutine { continuation ->
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
                            runCatching { readResponseBody(it) }
                                .onSuccess { parsed ->
                                    if (!continuation.isCancelled) continuation.resume(parsed)
                                }
                                .onFailure { error ->
                                    if (!continuation.isCancelled) continuation.resumeWithException(error)
                                }
                        }
                    }
                },
            )
        }
        return parseAnalyzeResponse(body)
    }

    private fun readResponseBody(response: Response): String {
        val raw = response.body?.string().orEmpty()
        AnalysisTelemetry.event("proxy_response http=${response.code}")
        AnalysisDebugLogger.log("proxy_http_body", "http=${response.code} body=${raw.take(8_000)}")
        if (!response.isSuccessful) {
            throw IOException(proxyErrorMessage(response.code, raw))
        }
        return raw
    }

    private fun proxyErrorMessage(statusCode: Int, body: String): String {
        val detailMessage = runCatching {
            JSONObject(body).optJSONObject("detail")?.optString("message").orEmpty()
        }.getOrDefault("")
        return when {
            statusCode == 429 ->
                "The AI service is temporarily busy (rate limit). Please wait a moment and try again."
            statusCode == 422 ->
                "The analysis service could not read this label. Please try again."
            statusCode in 500..599 ->
                "The AI service is temporarily unavailable. Please try again." +
                    if (detailMessage.isNotBlank()) " ($detailMessage)" else ""
            else -> "Analysis service request failed with HTTP $statusCode."
        }
    }

    private fun parseAnalyzeResponse(body: String): CachedAnalysis {
        val root = try {
            JSONObject(body)
        } catch (e: Exception) {
            throw IOException("Analysis service returned an unreadable response.", e)
        }

        val novaObj = root.optJSONObject("nova") ?: JSONObject()
        val containsFood = novaObj.optBoolean("containsConsumableFoodItem", true)
        val nova = NovaClassification(
            novaGroup = novaObj.optInt("novaGroup", 0).coerceIn(0, 4),
            summary = novaObj.optString("summary").trim(),
            confidence = novaObj.optConfidence("confidence"),
            warnings = novaObj.optStringList("warnings"),
            containsConsumableFoodItem = containsFood,
            rejectionReason = novaObj.optString("rejectionReason").trim(),
        )

        val ingredientsObj = root.optJSONObject("ingredients") ?: JSONObject()
        val ingredients = IngredientListAnalysis(
            correctedIngredients = ingredientsObj.optStringList("correctedIngredients"),
            ultraProcessedIngredients = ingredientsObj.optRiskMarkers("ultraProcessedIngredients"),
            warnings = ingredientsObj.optStringList("warnings"),
            confidence = ingredientsObj.optConfidence("confidence"),
        )

        val allergensObj = root.optJSONObject("allergens") ?: JSONObject()
        val allergens = AllergenDetection(
            allergens = allergensObj.optStringList("allergens"),
            warnings = allergensObj.optStringList("warnings"),
            confidence = allergensObj.optConfidence("confidence"),
        )

        return CachedAnalysis(
            nova = nova,
            ingredients = ingredients,
            allergens = allergens,
            usage = root.optJSONObject("usage")?.toLlmUsage(),
        )
    }

    /** Runs a stage body, mapping failures to [Result.failure] while letting cancellation propagate. */
    private suspend fun <T> runStage(block: suspend () -> T): Result<T> = withContext(Dispatchers.IO) {
        try {
            Result.success(block())
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    private data class CachedAnalysis(
        val nova: NovaClassification,
        val ingredients: IngredientListAnalysis,
        val allergens: AllergenDetection,
        val usage: LlmUsage?,
    )

    private fun <K, V> LinkedHashMap<K, V>.putBounded(key: K, value: V) {
        remove(key)
        put(key, value)
        while (size > MAX_CACHE_ENTRIES) {
            remove(keys.iterator().next())
        }
    }

    companion object {
        private const val MAX_CACHE_ENTRIES = 8
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

object ProxyHttpClientFactory {
    fun create(): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
}

private fun IngredientExtraction.cacheKey(): String = productName + " " + rawText

private fun List<String>.cacheKey(): String = joinToString(" ") { it.trim().lowercase() }

private fun JSONObject.optConfidence(name: String): Float =
    optDouble(name, 0.5).toFloat().coerceIn(0f, 1f)

private fun JSONObject.optStringList(name: String): List<String> {
    val array = optJSONArray(name) ?: return emptyList()
    return buildList {
        for (i in 0 until array.length()) {
            val value = array.optString(i).trim()
            if (value.isNotEmpty()) add(value)
        }
    }
}

private fun JSONObject.optRiskMarkers(name: String): List<IngredientRiskMarker> {
    val array = optJSONArray(name) ?: return emptyList()
    return buildList {
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val markerName = obj.optString("name").trim()
            if (markerName.isEmpty()) continue
            add(IngredientRiskMarker(name = markerName, reason = obj.optString("reason").trim()))
        }
    }
}

private fun JSONObject.toLlmUsage(): LlmUsage? {
    val input = optInt("inputTokens", 0).coerceAtLeast(0)
    val output = optInt("outputTokens", 0).coerceAtLeast(0)
    val total = optInt("totalTokens", input + output).coerceAtLeast(input + output)
    if (input == 0 && output == 0 && total == 0) return null
    return LlmUsage(inputTokens = input, outputTokens = output, totalTokens = total)
}
