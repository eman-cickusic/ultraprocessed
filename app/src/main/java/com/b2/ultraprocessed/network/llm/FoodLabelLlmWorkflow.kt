package com.b2.ultraprocessed.network.llm

import com.b2.ultraprocessed.classify.IngredientAssessment

interface FoodLabelLlmWorkflow {
    suspend fun classifyNova(
        extraction: IngredientExtraction,
        modelId: String,
        onStatus: (String) -> Unit = {},
    ): Result<LlmStageResult<NovaClassification>>

    suspend fun analyzeIngredientList(
        extraction: IngredientExtraction,
        modelId: String,
        onStatus: (String) -> Unit = {},
    ): Result<LlmStageResult<IngredientListAnalysis>>

    suspend fun detectAllergens(
        correctedIngredientNames: List<String>,
        modelId: String,
        onStatus: (String) -> Unit = {},
    ): Result<LlmStageResult<AllergenDetection>>
}

data class LlmStageResult<T>(
    val value: T,
    val usage: LlmUsage? = null,
)

data class LlmUsage(
    val inputTokens: Int,
    val outputTokens: Int,
    val totalTokens: Int,
) {
    companion object {
        fun aggregate(usages: Iterable<LlmUsage?>): LlmUsage? {
            val present = usages.filterNotNull()
            if (present.isEmpty()) return null
            return LlmUsage(
                inputTokens = present.sumOf { it.inputTokens.coerceAtLeast(0) },
                outputTokens = present.sumOf { it.outputTokens.coerceAtLeast(0) },
                totalTokens = present.sumOf { it.totalTokens.coerceAtLeast(0) },
            )
        }
    }
}

data class IngredientExtraction(
    val code: Int,
    val productName: String,
    val rawText: String,
    val ingredients: List<String>,
    val confidence: Float,
    val warnings: List<String>,
)

data class IngredientClassification(
    val novaGroup: Int,
    val summary: String,
    val confidence: Float,
    val problemIngredients: List<IngredientRiskMarker>,
    val warnings: List<String>,
    val ingredientAssessments: List<IngredientAssessment> = emptyList(),
)

data class NovaClassification(
    val novaGroup: Int,
    val summary: String,
    val confidence: Float,
    val warnings: List<String>,
    val containsConsumableFoodItem: Boolean = true,
    val rejectionReason: String = "",
)

data class IngredientListAnalysis(
    val correctedIngredients: List<String>,
    val ultraProcessedIngredients: List<IngredientRiskMarker>,
    val warnings: List<String>,
    val confidence: Float,
)

data class IngredientRiskMarker(
    val name: String,
    val reason: String,
)

data class AllergenDetection(
    val allergens: List<String>,
    val warnings: List<String>,
    val confidence: Float,
)
