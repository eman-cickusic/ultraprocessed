package com.b2.ultraprocessed.analysis

import android.content.Context
import com.b2.ultraprocessed.barcode.BarcodeResult
import com.b2.ultraprocessed.barcode.BarcodeScanner
import com.b2.ultraprocessed.barcode.MlKitBarcodeScanner
import com.b2.ultraprocessed.classify.IngredientAssessment
import com.b2.ultraprocessed.ingredients.IngredientTextNormalizer
import com.b2.ultraprocessed.classify.ClassificationResult
import com.b2.ultraprocessed.network.llm.FoodLabelLlmWorkflow
import com.b2.ultraprocessed.network.llm.GeminiFoodLabelLlmWorkflow
import com.b2.ultraprocessed.network.llm.IngredientListAnalysis
import com.b2.ultraprocessed.network.llm.IngredientClassification
import com.b2.ultraprocessed.network.llm.IngredientExtraction
import com.b2.ultraprocessed.network.llm.IngredientRiskMarker
import com.b2.ultraprocessed.network.llm.LlmUsage
import com.b2.ultraprocessed.network.llm.MultiProviderFoodLabelLlmWorkflow
import com.b2.ultraprocessed.network.llm.NovaClassification
import com.b2.ultraprocessed.network.llm.OpenAiCompatibleFoodLabelLlmWorkflow
import com.b2.ultraprocessed.network.llm.ProxyFoodLabelLlmWorkflow
import com.b2.ultraprocessed.network.llm.SecretLlmApiKeyProvider
import com.b2.ultraprocessed.network.llm.SelectingFoodLabelLlmWorkflow
import com.b2.ultraprocessed.network.usda.SecretUsdaApiKeyProvider
import com.b2.ultraprocessed.network.usda.UsdaHttpClientFactory
import com.b2.ultraprocessed.network.usda.UsdaApiService
import com.b2.ultraprocessed.network.usda.UsdaRepository
import com.b2.ultraprocessed.ocr.MlKitOcrPipeline
import com.b2.ultraprocessed.ocr.OcrPipeline
import com.b2.ultraprocessed.ocr.OcrResult
import com.b2.ultraprocessed.storage.secrets.SecretKeyManager
import com.b2.ultraprocessed.ui.ClassificationUiMapper
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

class FoodAnalysisPipeline(
    private val ocrPipeline: OcrPipeline,
    private val barcodeScanner: BarcodeScanner,
    private val usdaRepository: UsdaRepository,
    private val llmWorkflow: FoodLabelLlmWorkflow? = null,
) {
    suspend fun analyzeFromImage(
        imagePath: String,
        modelId: String = DEFAULT_MODEL_ID,
        onStage: (AnalysisStage) -> Unit = {},
        onStatus: (String) -> Unit = {},
    ): Result<AnalysisReport> {
        onStage(AnalysisStage.AnalysingImage)
        onStage(AnalysisStage.ExtractingIngredients)
        onStatus("Reading label text on device. Images are never sent to the API.")
        val ocr = ocrPipeline.recognizeText(imagePath)
        return when (ocr) {
            is OcrResult.Failure -> {
                AnalysisDebugLogger.log("ocr_failure", ocr.message)
                Result.failure(
                    Exception(ocr.message.toFriendlyAnalysisMessage("Could not read enough ingredient text. Please try again.")),
                )
            }
            is OcrResult.Success -> classifyFromIngredientsTextApiOnly(
                rawText = ocr.rawText,
                sourceImagePath = imagePath,
                modelId = modelId,
                sourceLabel = "OCR",
                sourceType = AnalysisSourceType.Ocr,
                productNameOverride = null,
                warnings = listOf("Image text was extracted on device with ML Kit OCR."),
                onStage = onStage,
                onStatus = onStatus,
            )
        }
    }

    suspend fun analyzeFromBarcode(
        barcodeCode: String,
        sourceImagePath: String?,
        modelId: String = DEFAULT_MODEL_ID,
        onStage: (AnalysisStage) -> Unit = {},
        onStatus: (String) -> Unit = {},
    ): Result<AnalysisReport> {
        onStage(AnalysisStage.AnalysingImage)
        val usda = usdaRepository.lookupByBarcode(barcodeCode)
            ?: return fallbackToImageOrError(
                sourceImagePath = sourceImagePath,
                error = "No USDA match found for barcode $barcodeCode.",
                modelId = modelId,
                onStage = onStage,
                onStatus = onStatus,
            )

        onStage(AnalysisStage.ExtractingIngredients)
        val ingredients = usda.ingredientsText
        if (ingredients.isNullOrBlank()) {
            return fallbackToImageOrError(
                sourceImagePath = sourceImagePath,
                error = "USDA record found but no ingredient text was available.",
                modelId = modelId,
                onStage = onStage,
            )
        }

        return classifyFromIngredientsTextApiOnly(
            rawText = ingredients,
            sourceImagePath = sourceImagePath,
            modelId = modelId,
            sourceLabel = "Barcode → USDA",
            sourceType = AnalysisSourceType.Barcode,
            productNameOverride = usda.productName,
                warnings = emptyList(),
                scannedBarcode = barcodeCode.trim().takeIf { it.isNotEmpty() },
                brandOwner = usda.brandOwner,
                onStage = onStage,
                onStatus = onStatus,
            )
    }

    suspend fun analyzeFromBarcodeImage(
        imagePath: String,
        modelId: String = DEFAULT_MODEL_ID,
        onStage: (AnalysisStage) -> Unit = {},
        onStatus: (String) -> Unit = {},
    ): Result<AnalysisReport> {
        onStage(AnalysisStage.AnalysingImage)
        return when (val barcode = barcodeScanner.scanFromImagePath(imagePath)) {
            is BarcodeResult.Failure -> fallbackToImageOrError(
                sourceImagePath = imagePath,
                error = barcode.message,
                modelId = modelId,
                onStage = onStage,
                onStatus = onStatus,
            )
            is BarcodeResult.Success -> analyzeFromBarcode(
                barcodeCode = barcode.code,
                sourceImagePath = imagePath,
                modelId = modelId,
                onStage = onStage,
                onStatus = onStatus,
            )
        }
    }

    private suspend fun fallbackToImageOrError(
        sourceImagePath: String?,
        error: String,
        modelId: String = DEFAULT_MODEL_ID,
        onStage: (AnalysisStage) -> Unit = {},
        onStatus: (String) -> Unit = {},
    ): Result<AnalysisReport> {
        if (sourceImagePath != null) {
            val fallback = analyzeFromImage(sourceImagePath, modelId, onStage, onStatus)
            if (fallback.isSuccess) {
                val report = fallback.getOrNull() ?: return fallback
                return Result.success(
                    report.copy(
                        sourceType = AnalysisSourceType.UsdaPlusOcr,
                        warnings = report.warnings + error + " Falling back to on-device OCR.",
                        scanResult = report.scanResult.copy(
                            sourceLabel = "USDA+OCR",
                            warnings = report.scanResult.warnings + error +
                                " Falling back to on-device OCR.",
                        ),
                    ),
                )
            }
        }
        return Result.failure(Exception(error.toFriendlyAnalysisMessage("Analysis unavailable.")))
    }

    private suspend fun classifyFromIngredientsTextApiOnly(
        rawText: String,
        sourceImagePath: String?,
        modelId: String,
        sourceLabel: String,
        sourceType: AnalysisSourceType,
        productNameOverride: String?,
        warnings: List<String>,
        scannedBarcode: String? = null,
        brandOwner: String? = null,
        rawIngredientText: String = rawText,
        onStage: (AnalysisStage) -> Unit = {},
        onStatus: (String) -> Unit = {},
    ): Result<AnalysisReport> {
        val normalized = IngredientTextNormalizer.normalize(rawText)
        AnalysisDebugLogger.log("text_raw", rawText)
        AnalysisDebugLogger.log("text_normalized", normalized)
        if (normalized.length < MIN_NORMALIZED_LENGTH) {
            return Result.failure(
                Exception("Could not read enough ingredient text. Please try again."),
            )
        }

        val workflow = llmWorkflow ?: return Result.failure(
            Exception("API-only mode requires a configured LLM workflow."),
        )

        val extraction = IngredientExtraction(
            code = 0,
            productName = productNameOverride ?: "Scanned food label",
            rawText = rawText,
            ingredients = normalized
                .split(',', ';')
                .map { it.trim() }
                .filter { it.isNotBlank() },
            confidence = 0.6f,
            warnings = emptyList(),
        )
        AnalysisDebugLogger.log("classification_input", extraction.toDebugString())

        onStage(AnalysisStage.AnalysingIngredients)
        val novaStage = runLlmStage(
            stageName = "llm_classify_nova",
            timeoutMillis = CLASSIFICATION_TIMEOUT_MILLIS,
            timeoutMessage = "API NOVA classification timed out.",
        ) {
            onStatus("Classifying NOVA group")
            workflow.classifyNova(extraction, modelId, onStatus)
        }.getOrElse { error ->
            val message = error.toFriendlyAnalysisMessage("API NOVA classification unavailable.")
            AnalysisDebugLogger.log("classification_failure", message)
            return Result.failure(Exception(message))
        }
        val nova = novaStage.value.also {
            AnalysisDebugLogger.log(
                "nova_output",
                "containsFood=${it.containsConsumableFoodItem} nova=${it.novaGroup} " +
                    "confidence=${it.confidence} summary=${it.summary} " +
                    "rejectionReason=${it.rejectionReason} warnings=${it.warnings}",
            )
        }
        if (!nova.containsConsumableFoodItem) {
            val message = nova.rejectionReason.ifBlank {
                nova.summary.ifBlank { "Text doesn't contain any consumable food item." }
            }
            AnalysisDebugLogger.log("classification_non_food_text", message)
            return Result.failure(NonConsumableFoodTextException(message))
        }

        val ingredientListStage = runLlmStage(
            stageName = "llm_analyze_ingredient_list",
            timeoutMillis = INGREDIENT_LIST_TIMEOUT_MILLIS,
            timeoutMessage = "API ingredient list cleanup timed out.",
        ) {
            onStatus("Correcting ingredient names")
            workflow.analyzeIngredientList(extraction, modelId, onStatus)
        }.getOrElse { error ->
            val message = error.toFriendlyAnalysisMessage("API ingredient list cleanup unavailable.")
            AnalysisDebugLogger.log("ingredient_list_failure", message)
            return Result.failure(Exception(message))
        }
        val ingredientList = ingredientListStage.value.also {
            AnalysisDebugLogger.log(
                "ingredient_list_output",
                "corrected=${it.correctedIngredients} ultraProcessed=${it.ultraProcessedIngredients} warnings=${it.warnings}",
            )
        }

        val allergensStage = runLlmStage(
            stageName = "llm_detect_allergens_from_corrected_ingredients",
            timeoutMillis = ALLERGEN_TIMEOUT_MILLIS,
            timeoutMessage = "API allergen detection timed out.",
        ) {
            onStatus("Checking common allergens")
            workflow.detectAllergens(ingredientList.correctedIngredients, modelId, onStatus)
        }.getOrElse {
            val message = it.toFriendlyAnalysisMessage("Allergen detection unavailable.")
            AnalysisDebugLogger.log("allergen_failure", message)
            return Result.failure(Exception(message))
        }
        val allergens = allergensStage.value.also {
            AnalysisDebugLogger.log(
                "allergen_output",
                "allergens=${it.allergens} confidence=${it.confidence} warnings=${it.warnings}",
            )
        }
        val exactUsage = LlmUsage.aggregate(
            listOf(novaStage.usage, ingredientListStage.usage, allergensStage.usage),
        )
        val usageEstimate = exactUsage?.let {
            UsageEstimateCalculator.fromProviderUsage(modelId = modelId, usage = it)
        } ?: UsageEstimateCalculator.estimateTextWorkflow(
            modelId = modelId,
            ingredientText = rawIngredientText,
            problemIngredientCount = ingredientList.ultraProcessedIngredients.size,
            allergenCount = allergens.allergens.size,
        )
        val classification = buildClassificationFromApiStages(nova, ingredientList)
        val correctedIngredientLine = ingredientList.correctedIngredients.joinToString(", ")
        val scanResult = ClassificationUiMapper.toScanResultUi(
            classification = classification.toClassificationResult(),
            normalizedIngredientLine = correctedIngredientLine,
            productNameOverride = productNameOverride,
            sourceLabel = sourceLabel,
            warnings = warnings + classification.warnings + ingredientList.warnings + allergens.warnings,
            labelImagePath = sourceImagePath,
            scannedBarcode = scannedBarcode,
            brandOwner = brandOwner,
            allergens = allergens.allergens,
            rawIngredientText = rawIngredientText,
            usageEstimate = usageEstimate,
        )
        onStage(AnalysisStage.Completed)
        return Result.success(
            AnalysisReport(
                sourceType = sourceType,
                productName = scanResult.productName,
                ingredientsTextUsed = correctedIngredientLine,
                warnings = warnings + classification.warnings + ingredientList.warnings + allergens.warnings,
                scanResult = scanResult,
            ),
        )
    }

    companion object {
        const val MIN_NORMALIZED_LENGTH: Int = 12
        const val DEFAULT_MODEL_ID: String = "gemini-2.0-flash"
        private const val CLASSIFICATION_TIMEOUT_MILLIS = 35_000L
        private const val INGREDIENT_LIST_TIMEOUT_MILLIS = 35_000L
        private const val ALLERGEN_TIMEOUT_MILLIS = 12_000L

        fun create(context: Context): FoodAnalysisPipeline {
            val appContext = context.applicationContext
            AnalysisDebugLogger.initialize(appContext)
            return FoodAnalysisPipeline(
                ocrPipeline = MlKitOcrPipeline(appContext),
                barcodeScanner = MlKitBarcodeScanner(appContext),
                usdaRepository = UsdaRepository(
                    dataSource = UsdaApiService(
                        apiKeyProvider = SecretUsdaApiKeyProvider(
                            SecretKeyManager(appContext),
                        ),
                        client = UsdaHttpClientFactory.create(),
                    ),
                ),
                llmWorkflow = SelectingFoodLabelLlmWorkflow(
                    proxyWorkflow = ProxyFoodLabelLlmWorkflow(),
                    byokWorkflow = MultiProviderFoodLabelLlmWorkflow(
                    geminiWorkflow = GeminiFoodLabelLlmWorkflow(
                        context = appContext,
                        apiKeyProvider = SecretLlmApiKeyProvider(
                            SecretKeyManager(appContext),
                        ),
                    ),
                    openAiWorkflow = OpenAiCompatibleFoodLabelLlmWorkflow(
                        context = appContext,
                        apiKeyProvider = SecretLlmApiKeyProvider(
                            SecretKeyManager(appContext),
                        ),
                        baseUrl = "https://api.openai.com/v1",
                        providerTag = "openai",
                    ),
                    grokWorkflow = OpenAiCompatibleFoodLabelLlmWorkflow(
                        context = appContext,
                        apiKeyProvider = SecretLlmApiKeyProvider(
                            SecretKeyManager(appContext),
                        ),
                        baseUrl = "https://api.x.ai/v1",
                        providerTag = "grok",
                    ),
                    groqWorkflow = OpenAiCompatibleFoodLabelLlmWorkflow(
                        context = appContext,
                        apiKeyProvider = SecretLlmApiKeyProvider(
                            SecretKeyManager(appContext),
                        ),
                        baseUrl = "https://api.groq.com/openai/v1",
                        providerTag = "groq",
                    ),
                    ),
                    apiKeyProvider = SecretLlmApiKeyProvider(SecretKeyManager(appContext)),
                ),
            )
        }
    }
}

private fun IngredientExtraction.toDebugString(): String =
    "productName=$productName confidence=$confidence rawText=$rawText ingredients=$ingredients warnings=$warnings"

private fun buildClassificationFromApiStages(
    nova: NovaClassification,
    ingredientList: IngredientListAnalysis,
): IngredientClassification {
    val ultraProcessedKeys = ingredientList.ultraProcessedIngredients
        .map { it.name.normalizedIngredientKey() }
        .toSet()
    return IngredientClassification(
        novaGroup = nova.novaGroup,
        summary = nova.summary,
        confidence = minOf(nova.confidence, ingredientList.confidence),
        problemIngredients = ingredientList.ultraProcessedIngredients,
        warnings = nova.warnings + ingredientList.warnings,
        ingredientAssessments = ingredientList.correctedIngredients.map { ingredient ->
            val isUltraProcessed = ingredient.normalizedIngredientKey() in ultraProcessedKeys
            IngredientAssessment(
                name = ingredient,
                novaGroup = if (isUltraProcessed) 4 else 1,
                reason = if (isUltraProcessed) {
                    ingredientList.ultraProcessedIngredients
                        .firstOrNull { it.name.normalizedIngredientKey() == ingredient.normalizedIngredientKey() }
                        ?.reason.orEmpty()
                } else {
                    ""
                },
            )
        },
    )
}

private fun String.normalizedIngredientKey(): String =
    lowercase().filter { it.isLetterOrDigit() }

private class LlmStageTimeoutException(message: String) : Exception(message)

private suspend fun <T> runLlmStage(
    stageName: String,
    timeoutMillis: Long,
    timeoutMessage: String,
    block: suspend () -> Result<T>,
): Result<T> {
    val startedAt = AnalysisTelemetry.markStart()
    AnalysisDebugLogger.log(stageName, "start timeoutMs=$timeoutMillis")
    repeat(LLM_TIMEOUT_RETRY_ATTEMPTS) { index ->
        val attempt = index + 1
        val result = try {
            withTimeout(timeoutMillis) { block() }
        } catch (e: TimeoutCancellationException) {
            AnalysisTelemetry.stageFailed(stageName, startedAt, "timeout")
            AnalysisDebugLogger.log(stageName, "timeout attempt=$attempt")
            if (attempt < LLM_TIMEOUT_RETRY_ATTEMPTS) {
                return@repeat
            }
            return Result.failure(LlmStageTimeoutException(timeoutMessage))
        }
        result
            .onSuccess {
                AnalysisTelemetry.stageSucceeded(stageName, startedAt)
                AnalysisDebugLogger.log(stageName, "success")
            }
            .onFailure {
                AnalysisTelemetry.stageFailed(
                    stageName,
                    startedAt,
                    it::class.simpleName ?: "unknown",
                )
                AnalysisDebugLogger.log(stageName, "failure type=${it::class.simpleName} message=${it.message}")
            }
        return result
    }
    return Result.failure(LlmStageTimeoutException(timeoutMessage))
}

private const val LLM_TIMEOUT_RETRY_ATTEMPTS = 2

private class NonConsumableFoodTextException(message: String) : Exception(message)

private fun IngredientClassification.toClassificationResult(): ClassificationResult =
    ClassificationResult(
        novaGroup = novaGroup,
        confidence = confidence,
        markers = problemIngredients.map { it.name },
        explanation = summary,
        highlightTerms = problemIngredients.map { it.name },
        engine = "Gemini staged LLM",
        ingredientAssessments = ingredientAssessments,
    )

private fun Throwable.toFriendlyAnalysisMessage(defaultMessage: String): String {
    val message = message.orEmpty().trim()
    val lower = message.lowercase()
    return when {
        this is LlmStageTimeoutException -> message.ifBlank { defaultMessage }
        lower.contains("could not be validated after") ||
            lower.contains("failed after contract retries") ||
            lower.contains("llm response") ||
            lower.contains("invalid json") ||
            lower.contains("missing required field") ||
            lower.contains("incomplete") ||
            lower.contains("unsupported code") ||
            lower.contains("no usable ingredient list") ->
            "The AI returned an unreadable response after several retries. Please try again."
        lower.contains("429") ||
            lower.contains("rate limit") ||
            lower.contains("quota exceeded") ->
            "The AI service is temporarily busy. Please wait a moment and try again."
        message.isNotBlank() -> message
        else -> defaultMessage
    }
}

private fun String?.toFriendlyAnalysisMessage(defaultMessage: String): String =
    this.orEmpty().ifBlank { defaultMessage }
