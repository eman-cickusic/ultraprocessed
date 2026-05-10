package com.b2.ultraprocessed.network.llm

class MultiProviderFoodLabelLlmWorkflow(
    private val geminiWorkflow: FoodLabelLlmWorkflow,
    private val openAiWorkflow: FoodLabelLlmWorkflow,
    private val grokWorkflow: FoodLabelLlmWorkflow,
    private val groqWorkflow: FoodLabelLlmWorkflow,
) : FoodLabelLlmWorkflow {
    override suspend fun classifyNova(
        extraction: IngredientExtraction,
        modelId: String,
        onStatus: (String) -> Unit,
    ): Result<LlmStageResult<NovaClassification>> =
        workflowFor(modelId).classifyNova(extraction, modelId, onStatus)

    override suspend fun analyzeIngredientList(
        extraction: IngredientExtraction,
        modelId: String,
        onStatus: (String) -> Unit,
    ): Result<LlmStageResult<IngredientListAnalysis>> =
        workflowFor(modelId).analyzeIngredientList(extraction, modelId, onStatus)

    override suspend fun detectAllergens(
        correctedIngredientNames: List<String>,
        modelId: String,
        onStatus: (String) -> Unit,
    ): Result<LlmStageResult<AllergenDetection>> =
        workflowFor(modelId).detectAllergens(correctedIngredientNames, modelId, onStatus)

    private fun workflowFor(modelId: String): FoodLabelLlmWorkflow {
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
