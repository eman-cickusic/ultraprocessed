package com.b2.ultraprocessed.analysis

import com.b2.ultraprocessed.barcode.BarcodeResult
import com.b2.ultraprocessed.barcode.BarcodeScanner
import com.b2.ultraprocessed.network.usda.UsdaApiDataSource
import com.b2.ultraprocessed.network.usda.UsdaFoodDetail
import com.b2.ultraprocessed.network.usda.UsdaRepository
import com.b2.ultraprocessed.network.usda.UsdaSearchFood
import com.b2.ultraprocessed.network.llm.AllergenDetection
import com.b2.ultraprocessed.network.llm.FoodLabelLlmWorkflow
import com.b2.ultraprocessed.network.llm.IngredientListAnalysis
import com.b2.ultraprocessed.network.llm.IngredientExtraction
import com.b2.ultraprocessed.network.llm.IngredientRiskMarker
import com.b2.ultraprocessed.network.llm.LlmStageResult
import com.b2.ultraprocessed.network.llm.NovaClassification
import com.b2.ultraprocessed.ocr.OcrPipeline
import com.b2.ultraprocessed.ocr.OcrResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FoodAnalysisPipelineTest {
    @Test
    fun analyzeFromImage_usesOnDeviceOcrThenTextOnlyApi() = runTest {
        val pipeline = buildPipeline(
            ocrPipeline = OcrPipeline {
                OcrResult.Success("Ingredients: sugar, wheat flour, milk, artificial flavor")
            },
            barcodeScanner = BarcodeScanner { BarcodeResult.Failure("unused") },
            usdaRepository = emptyUsdaRepository(),
            llmWorkflow = FakeFoodLabelLlmWorkflow(),
        )

        val result = pipeline.analyzeFromImage("/tmp/fake-image.jpg", "gemini-2.0-flash").getOrThrow()

        assertEquals(AnalysisSourceType.Ocr, result.sourceType)
        assertEquals("OCR", result.scanResult.sourceLabel)
        assertEquals(listOf("Milk", "Wheat"), result.scanResult.allergens)
        assertEquals(
            "Ingredients: sugar, wheat flour, milk, artificial flavor",
            result.scanResult.rawIngredientText,
        )
        assertTrue(result.warnings.any { it.contains("on device", ignoreCase = true) })
    }

    @Test
    fun analyzeFromImage_requiresTextOnlyApiWorkflow_afterOcrSucceeds() = runTest {
        val pipeline = buildPipeline(
            ocrPipeline = OcrPipeline { OcrResult.Success("Ingredients: corn, salt, sunflower oil") },
            barcodeScanner = BarcodeScanner { BarcodeResult.Failure("unused") },
            usdaRepository = emptyUsdaRepository(),
        )

        val result = pipeline.analyzeFromImage("/tmp/fake-image.jpg", "gemini-2.0-flash")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("API-only mode"))
    }

    @Test
    fun analyzeFromImage_returnsFailure_whenClassificationTimesOut() = runTest {
        val pipeline = buildPipeline(
            ocrPipeline = OcrPipeline {
                OcrResult.Success("Ingredients: corn syrup, natural flavor, salt")
            },
            barcodeScanner = BarcodeScanner { BarcodeResult.Failure("unused") },
            usdaRepository = emptyUsdaRepository(),
            llmWorkflow = FakeFoodLabelLlmWorkflow(classificationFailure = Exception("API text classification timed out.")),
        )

        val result = pipeline.analyzeFromImage("/tmp/fake-image.jpg", "gemini-2.0-flash")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("timed out", ignoreCase = true))
    }

    @Test
    fun analyzeFromBarcode_producesReportFromUsdaIngredients() = runTest {
        val pipeline = buildPipeline(
            ocrPipeline = OcrPipeline { OcrResult.Failure("unused") },
            barcodeScanner = BarcodeScanner { BarcodeResult.Success("078742195760") },
            usdaRepository = UsdaRepository(
                object : UsdaApiDataSource {
                    override suspend fun searchFoods(query: String, pageSize: Int): List<UsdaSearchFood> = listOf(
                        UsdaSearchFood(
                            fdcId = 100L,
                            description = "Frozen Cheeseburger",
                            dataType = "Branded",
                            brandOwner = "Great Value",
                            gtinUpc = "078742195760",
                            ingredients = "BEEF, BUN, ARTIFICIAL FLAVOR",
                        ),
                    )

                    override suspend fun fetchFoodDetail(fdcId: Long): UsdaFoodDetail? = UsdaFoodDetail(
                        fdcId = 100L,
                        description = "Frozen Cheeseburger",
                        brandOwner = "Great Value",
                        gtinUpc = "078742195760",
                        ingredients = "BEEF, BUN, ARTIFICIAL FLAVOR",
                    )
                },
            ),
            llmWorkflow = FakeFoodLabelLlmWorkflow(),
        )

        val result = pipeline.analyzeFromBarcodeImage("/tmp/fake-image.jpg").getOrThrow()
        assertEquals(AnalysisSourceType.Barcode, result.sourceType)
        assertEquals("Barcode → USDA", result.scanResult.sourceLabel)
    }

    @Test
    fun analyzeFromBarcode_withRawCode_samePathAsImageBarcodeFlow() = runTest {
        val pipeline = buildPipeline(
            ocrPipeline = OcrPipeline { OcrResult.Failure("unused") },
            barcodeScanner = BarcodeScanner { BarcodeResult.Failure("unused") },
            usdaRepository = UsdaRepository(
                object : UsdaApiDataSource {
                    override suspend fun searchFoods(query: String, pageSize: Int): List<UsdaSearchFood> = listOf(
                        UsdaSearchFood(
                            fdcId = 100L,
                            description = "Frozen Cheeseburger",
                            dataType = "Branded",
                            brandOwner = "Great Value",
                            gtinUpc = "078742195760",
                            ingredients = "BEEF, BUN, ARTIFICIAL FLAVOR",
                        ),
                    )

                    override suspend fun fetchFoodDetail(fdcId: Long): UsdaFoodDetail? = UsdaFoodDetail(
                        fdcId = 100L,
                        description = "Frozen Cheeseburger",
                        brandOwner = "Great Value",
                        gtinUpc = "078742195760",
                        ingredients = "BEEF, BUN, ARTIFICIAL FLAVOR",
                    )
                },
            ),
            llmWorkflow = FakeFoodLabelLlmWorkflow(),
        )

        val result = pipeline.analyzeFromBarcode("078742195760", sourceImagePath = null).getOrThrow()
        assertEquals(AnalysisSourceType.Barcode, result.sourceType)
        assertEquals("Barcode → USDA", result.scanResult.sourceLabel)
        assertEquals("078742195760", result.scanResult.scannedBarcode)
        assertEquals("Great Value", result.scanResult.brandOwner)
        assertFalse(result.scanResult.isBarcodeLookupOnly)
        assertTrue(result.scanResult.allIngredients.isNotEmpty())
    }

    @Test
    fun analyzeFromBarcode_fallsBackToOcr_whenUsdaMisses() = runTest {
        val pipeline = buildPipeline(
            ocrPipeline = OcrPipeline {
                OcrResult.Success("Ingredients: corn, salt, sunflower oil")
            },
            barcodeScanner = BarcodeScanner { BarcodeResult.Success("999999") },
            usdaRepository = UsdaRepository(
                object : UsdaApiDataSource {
                    override suspend fun searchFoods(query: String, pageSize: Int): List<UsdaSearchFood> = emptyList()
                    override suspend fun fetchFoodDetail(fdcId: Long): UsdaFoodDetail? = null
                },
            ),
            llmWorkflow = FakeFoodLabelLlmWorkflow(),
        )

        val result = pipeline.analyzeFromBarcodeImage("/tmp/fake-image.jpg").getOrThrow()
        assertEquals(AnalysisSourceType.UsdaPlusOcr, result.sourceType)
        assertTrue(result.scanResult.warnings.isNotEmpty())
    }

    @Test
    fun analyzeFromImage_returnsFailure_whenNoOcrText() = runTest {
        val pipeline = buildPipeline(
            ocrPipeline = OcrPipeline { OcrResult.Failure("No text detected in image.") },
            barcodeScanner = BarcodeScanner { BarcodeResult.Failure("unused") },
            usdaRepository = UsdaRepository(
                object : UsdaApiDataSource {
                    override suspend fun searchFoods(query: String, pageSize: Int): List<UsdaSearchFood> = emptyList()
                    override suspend fun fetchFoodDetail(fdcId: Long): UsdaFoodDetail? = null
                },
            ),
        )

        val result = pipeline.analyzeFromImage("/tmp/fake-image.jpg")
        assertTrue(result.isFailure)
    }

}

private fun buildPipeline(
    ocrPipeline: OcrPipeline,
    barcodeScanner: BarcodeScanner,
    usdaRepository: UsdaRepository,
    llmWorkflow: FoodLabelLlmWorkflow? = null,
): FoodAnalysisPipeline = FoodAnalysisPipeline(
    ocrPipeline = ocrPipeline,
    barcodeScanner = barcodeScanner,
    usdaRepository = usdaRepository,
    llmWorkflow = llmWorkflow,
)

private fun emptyUsdaRepository(): UsdaRepository =
    UsdaRepository(
        object : UsdaApiDataSource {
            override suspend fun searchFoods(query: String, pageSize: Int): List<UsdaSearchFood> = emptyList()
            override suspend fun fetchFoodDetail(fdcId: Long): UsdaFoodDetail? = null
        },
    )

private class FakeFoodLabelLlmWorkflow(
    private val classificationFailure: Exception? = null,
) : FoodLabelLlmWorkflow {
    override suspend fun classifyNova(
        extraction: IngredientExtraction,
        modelId: String,
        onStatus: (String) -> Unit,
    ): Result<LlmStageResult<NovaClassification>> {
        classificationFailure?.let { return Result.failure(it) }
        return Result.success(
            LlmStageResult(
                NovaClassification(
                    novaGroup = 4,
                    summary = "The staged classifier found ultra-processed ingredient markers.",
                    confidence = 0.82f,
                    warnings = emptyList(),
                ),
            ),
        )
    }

    override suspend fun analyzeIngredientList(
        extraction: IngredientExtraction,
        modelId: String,
        onStatus: (String) -> Unit,
    ): Result<LlmStageResult<IngredientListAnalysis>> =
        Result.success(
            LlmStageResult(
                IngredientListAnalysis(
                    correctedIngredients = extraction.ingredients.map { it.replaceFirstChar { c -> c.titlecaseChar() } },
                    ultraProcessedIngredients = extraction.ingredients
                        .filter { it.contains("flavor", ignoreCase = true) }
                        .map {
                            IngredientRiskMarker(
                                name = it.replaceFirstChar { c -> c.titlecaseChar() },
                                reason = "Flavor systems are a NOVA 4 processing marker.",
                            )
                        },
                    warnings = emptyList(),
                    confidence = 0.82f,
                ),
            ),
        )

    override suspend fun detectAllergens(
        correctedIngredientNames: List<String>,
        modelId: String,
        onStatus: (String) -> Unit,
    ): Result<LlmStageResult<AllergenDetection>> =
        Result.success(
            LlmStageResult(
                AllergenDetection(
                    allergens = listOf("Milk", "Wheat"),
                    warnings = emptyList(),
                    confidence = 0.88f,
                ),
            ),
        )
}
