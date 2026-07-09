package com.b2.ultraprocessed.network.llm

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class SelectingFoodLabelLlmWorkflowTest {

    private val extraction = IngredientExtraction(
        code = 0,
        productName = "Test",
        rawText = "sugar, salt",
        ingredients = listOf("sugar", "salt"),
        confidence = 0.6f,
        warnings = emptyList(),
    )

    @Test
    fun routesToProxy_whenNoKey() = runTest {
        val proxy = RecordingWorkflow()
        val byok = RecordingWorkflow()
        val selecting = SelectingFoodLabelLlmWorkflow(proxy, byok, FakeKeyProvider(""))

        selecting.classifyNova(extraction, MODEL)
        selecting.analyzeIngredientList(extraction, MODEL)
        selecting.detectAllergens(listOf("sugar"), MODEL)

        assertEquals(3, proxy.totalCalls)
        assertEquals(0, byok.totalCalls)
    }

    @Test
    fun routesToByok_whenKeyPresent() = runTest {
        val proxy = RecordingWorkflow()
        val byok = RecordingWorkflow()
        val selecting = SelectingFoodLabelLlmWorkflow(proxy, byok, FakeKeyProvider("aiza-some-key"))

        selecting.classifyNova(extraction, MODEL)
        selecting.detectAllergens(listOf("sugar"), MODEL)

        assertEquals(0, proxy.totalCalls)
        assertEquals(2, byok.totalCalls)
    }

    @Test
    fun reReadsKeyPerCall() = runTest {
        val proxy = RecordingWorkflow()
        val byok = RecordingWorkflow()
        val keyProvider = FakeKeyProvider("")
        val selecting = SelectingFoodLabelLlmWorkflow(proxy, byok, keyProvider)

        selecting.classifyNova(extraction, MODEL) // no key -> proxy
        keyProvider.key = "aiza-now-present"
        selecting.classifyNova(extraction, MODEL) // key added -> byok

        assertEquals(1, proxy.totalCalls)
        assertEquals(1, byok.totalCalls)
    }

    private class FakeKeyProvider(var key: String) : LlmApiKeyProvider {
        override fun getApiKey(): String = key
    }

    private class RecordingWorkflow : FoodLabelLlmWorkflow {
        var totalCalls = 0
            private set

        override suspend fun classifyNova(
            extraction: IngredientExtraction,
            modelId: String,
            onStatus: (String) -> Unit,
        ): Result<LlmStageResult<NovaClassification>> {
            totalCalls++
            return Result.success(
                LlmStageResult(
                    NovaClassification(
                        novaGroup = 1,
                        summary = "",
                        confidence = 0.5f,
                        warnings = emptyList(),
                    ),
                ),
            )
        }

        override suspend fun analyzeIngredientList(
            extraction: IngredientExtraction,
            modelId: String,
            onStatus: (String) -> Unit,
        ): Result<LlmStageResult<IngredientListAnalysis>> {
            totalCalls++
            return Result.success(
                LlmStageResult(
                    IngredientListAnalysis(
                        correctedIngredients = emptyList(),
                        ultraProcessedIngredients = emptyList(),
                        warnings = emptyList(),
                        confidence = 0.5f,
                    ),
                ),
            )
        }

        override suspend fun detectAllergens(
            correctedIngredientNames: List<String>,
            modelId: String,
            onStatus: (String) -> Unit,
        ): Result<LlmStageResult<AllergenDetection>> {
            totalCalls++
            return Result.success(
                LlmStageResult(
                    AllergenDetection(
                        allergens = emptyList(),
                        warnings = emptyList(),
                        confidence = 0.5f,
                    ),
                ),
            )
        }
    }

    companion object {
        private const val MODEL = "gemini-2.5-flash"
    }
}
