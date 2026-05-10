package com.b2.ultraprocessed.network.llm

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class FoodLabelPromptContractTest {
    @Test
    fun classificationPrompt_usesOnlyExtractedIngredients() {
        val prompt = promptText("food_label_classification_prompt.md")

        assertTrue(prompt.contains("Use only `rawIngredientText` and `ingredients`", ignoreCase = true))
        assertTrue(prompt.contains("Make exactly one overall NOVA classification", ignoreCase = true))
        assertTrue(prompt.contains("Do not correct ingredient names", ignoreCase = true))
        assertTrue(prompt.contains("Do not detect allergens", ignoreCase = true))
        assertTrue(prompt.contains("novaGroup", ignoreCase = true))
        assertTrue(prompt.contains("Do not use a generic default NOVA group", ignoreCase = true))
        assertTrue(prompt.contains("human-friendly shopper takeaway", ignoreCase = true))
        assertTrue(prompt.contains("surrounding package text", ignoreCase = true))
    }

    @Test
    fun ingredientAnalysisPrompt_correctsNamesAndReturnsUltraProcessedSubset() {
        val prompt = promptText("food_label_ingredient_analysis_prompt.md")

        assertTrue(prompt.contains("Correct ingredient list names", ignoreCase = true))
        assertTrue(prompt.contains("ultraProcessedIngredients", ignoreCase = true))
        assertTrue(prompt.contains("must exactly match one item from `correctedIngredients`", ignoreCase = true))
        assertTrue(prompt.contains("Do not make the overall NOVA classification", ignoreCase = true))
        assertTrue(prompt.contains("directly controls capsule coloration", ignoreCase = true))
        assertTrue(prompt.contains("Filter all non-ingredient content out", ignoreCase = true))
    }

    @Test
    fun allergenPrompt_usesOnlyExtractedIngredients() {
        val prompt = promptText("food_label_allergen_prompt.md")

        assertTrue(prompt.contains("Use only `correctedIngredients`", ignoreCase = true))
        assertTrue(prompt.contains("Common US / Western Allergen Signals", ignoreCase = true))
        assertTrue(prompt.contains("standalone allergen name", ignoreCase = true))
    }

    @Test
    fun validationPrompt_repairsSentenceLikeLabels() {
        val prompt = promptText("food_label_response_validation_prompt.md")

        assertTrue(prompt.contains("classification", ignoreCase = true))
        assertTrue(prompt.contains("allergen detection", ignoreCase = true))
        assertTrue(prompt.contains("sentence-like ingredient text or allergen text", ignoreCase = true))
        assertTrue(prompt.contains("Contains: Wheat, May Contain Milk", ignoreCase = true))
    }

    @Test
    fun resultChatPrompt_refusesOffTopicAndInjection() {
        val prompt = promptText("food_label_result_chat_prompt.md")

        assertTrue(prompt.contains("answer only questions about this one scan result", ignoreCase = true))
        assertTrue(prompt.contains("prompt injection", ignoreCase = true))
        assertTrue(prompt.contains("Do not answer questions about other products", ignoreCase = true))
        assertTrue(prompt.contains("\"allowed\": true", ignoreCase = true))
    }

    private fun promptText(fileName: String): String =
        File("src/main/assets/prompts/$fileName").readText()
}
