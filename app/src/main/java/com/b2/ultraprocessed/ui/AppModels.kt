package com.b2.ultraprocessed.ui

data class ModelOption(
    val id: String,
    val name: String,
    val provider: String,
    val description: String,
    val supportsImages: Boolean = false,
    val recommended: Boolean = false,
)

data class ProblemIngredient(
    val name: String,
    val reason: String,
)

data class IngredientBubbleUi(
    val name: String,
    val novaGroup: Int,
    val reason: String,
)

data class UsageEstimateUi(
    val modelId: String,
    val modelName: String,
    val provider: String,
    val estimatedInputTokens: Int,
    val estimatedOutputTokens: Int,
    val estimatedTotalTokens: Int,
    val estimatedCostUsd: Double,
)

data class ScanResultUi(
    val productName: String,
    val novaGroup: Int,
    val summary: String,
    val problemIngredients: List<ProblemIngredient>,
    val allIngredients: List<String>,
    val engineLabel: String,
    val confidence: Float = 0f,
    val sourceLabel: String = "OCR",
    val warnings: List<String> = emptyList(),
    val allergens: List<String> = emptyList(),
    val ingredientAssessments: List<IngredientBubbleUi> = emptyList(),
    val rawIngredientText: String = "",
    /** Local file path to the image that was analyzed from camera or gallery import. */
    val labelImagePath: String? = null,
    /** Barcode → USDA path: show product identity only until OCR/classification is wired. */
    val isBarcodeLookupOnly: Boolean = false,
    val scannedBarcode: String? = null,
    val brandOwner: String? = null,
    val usageEstimate: UsageEstimateUi? = null,
    val analyzedAtMillis: Long = System.currentTimeMillis(),
)

enum class ResultChatRole {
    User,
    Assistant,
    System,
}

data class ResultChatMessageUi(
    val id: String,
    val role: ResultChatRole,
    val text: String,
    val allowed: Boolean = true,
)

data class ResultChatStateUi(
    val messages: List<ResultChatMessageUi> = emptyList(),
    val input: String = "",
    val isSending: Boolean = false,
    val statusMessage: String? = null,
)

data class ModelUsageUi(
    val modelName: String,
    val provider: String,
    val scans: Int,
    val estimatedTokens: Int,
    val estimatedCostUsd: Double,
)

enum class AppDestination {
    Splash,
    Disclaimer,
    Scanner,
    Analyzing,
    Results,
    AnalysisError,
    Settings,
}

object AppCatalog {
    val modelOptions = listOf(
        ModelOption(
            id = "gemini-3.5-flash",
            name = "Gemini 3.5 Flash",
            provider = "Gemini (Google)",
            description = "Backend proxy analysis model for on-device OCR output",
            supportsImages = false,
            recommended = true,
        ),
        ModelOption(
            id = "gpt-4.1-mini",
            name = "GPT-4.1 mini",
            provider = "OpenAI",
            description = "Fast text classification for OCR output",
            supportsImages = false,
        ),
        ModelOption(
            id = "grok-2-vision-latest",
            name = "Grok 2",
            provider = "Grok (xAI)",
            description = "Text-only analysis in Zest; images stay on device",
            supportsImages = false,
        ),
        ModelOption(
            id = "llama-3.1-8b-instant",
            name = "Llama 3.1 8B Instant",
            provider = "Groq",
            description = "Fast text inference via Groq",
            supportsImages = false,
        ),
    )
}
