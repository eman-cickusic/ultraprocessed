package com.b2.ultraprocessed.network.llm

/**
 * Picks the analysis backend per call: if the user has saved their own LLM key, route to the
 * bring-your-own-key [byokWorkflow]; otherwise use the keyless Cloud Run [proxyWorkflow].
 *
 * The key is re-read on every call (not cached at construction) so saving or clearing a key in
 * Settings takes effect on the next scan without rebuilding the pipeline. A scan is not split
 * across backends in practice: the key can only change from the Settings screen, which the user
 * navigates to between scans, never mid-scan.
 */
class SelectingFoodLabelLlmWorkflow(
    private val proxyWorkflow: FoodLabelLlmWorkflow,
    private val byokWorkflow: FoodLabelLlmWorkflow,
    private val apiKeyProvider: LlmApiKeyProvider,
) : FoodLabelLlmWorkflow {

    override suspend fun classifyNova(
        extraction: IngredientExtraction,
        modelId: String,
        onStatus: (String) -> Unit,
    ): Result<LlmStageResult<NovaClassification>> =
        active().classifyNova(extraction, modelId, onStatus)

    override suspend fun analyzeIngredientList(
        extraction: IngredientExtraction,
        modelId: String,
        onStatus: (String) -> Unit,
    ): Result<LlmStageResult<IngredientListAnalysis>> =
        active().analyzeIngredientList(extraction, modelId, onStatus)

    override suspend fun detectAllergens(
        correctedIngredientNames: List<String>,
        modelId: String,
        onStatus: (String) -> Unit,
    ): Result<LlmStageResult<AllergenDetection>> =
        active().detectAllergens(correctedIngredientNames, modelId, onStatus)

    private fun active(): FoodLabelLlmWorkflow =
        if (apiKeyProvider.getApiKey().isNotBlank()) byokWorkflow else proxyWorkflow
}
