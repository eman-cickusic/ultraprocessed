package com.b2.ultraprocessed.analysis

import com.b2.ultraprocessed.network.llm.LlmProviderResolver
import com.b2.ultraprocessed.network.llm.LlmUsage
import com.b2.ultraprocessed.ui.ModelUsageUi
import com.b2.ultraprocessed.ui.UsageEstimateUi
import kotlin.math.ceil

object UsageEstimateCalculator {
    fun fromProviderUsage(
        modelId: String,
        usage: LlmUsage,
    ): UsageEstimateUi = estimate(
        modelId = modelId,
        providerId = providerIdFor(modelId),
        inputTokens = usage.inputTokens,
        outputTokens = usage.outputTokens,
        totalTokensOverride = usage.totalTokens,
    )

    fun estimateTextWorkflow(
        modelId: String,
        ingredientText: String,
        problemIngredientCount: Int,
        allergenCount: Int,
    ): UsageEstimateUi = estimate(
        modelId = modelId,
        providerId = providerIdFor(modelId),
        inputTokens = TEXT_PROMPT_TOKENS + approximateTokens(ingredientText) * 2,
        outputTokens = TEXT_OUTPUT_TOKENS +
            problemIngredientCount.coerceAtLeast(0) * 18 +
            allergenCount.coerceAtLeast(0) * 6,
    )

    fun modelUsage(
        modelId: String,
        totalTokens: Int,
        scanCount: Int,
        estimatedCostUsd: Double,
    ): ModelUsageUi {
        val metadata = LlmProviderResolver.metadataFromModelId(modelId)
        return ModelUsageUi(
            modelName = metadata?.modelName ?: modelId,
            provider = metadata?.provider ?: "Unknown",
            scans = scanCount,
            estimatedTokens = totalTokens,
            estimatedCostUsd = estimatedCostUsd,
        )
    }

    private fun estimate(
        modelId: String,
        providerId: String,
        inputTokens: Int,
        outputTokens: Int,
        totalTokensOverride: Int? = null,
    ): UsageEstimateUi {
        val metadata = LlmProviderResolver.metadataFromModelId(modelId)
        val modelName = metadata?.modelName ?: modelId
        val provider = metadata?.provider ?: providerId.replaceFirstChar {
            if (it.isLowerCase()) it.titlecase() else it.toString()
        }
        val totalTokens = (totalTokensOverride ?: (inputTokens + outputTokens)).coerceAtLeast(0)
        val pricing = pricingFor(providerId)
        val estimatedCostUsd = ((inputTokens * pricing.inputPerMillion) +
            (outputTokens * pricing.outputPerMillion)) / 1_000_000.0
        return UsageEstimateUi(
            modelId = modelId,
            modelName = modelName,
            provider = provider,
            estimatedInputTokens = inputTokens.coerceAtLeast(0),
            estimatedOutputTokens = outputTokens.coerceAtLeast(0),
            estimatedTotalTokens = totalTokens,
            estimatedCostUsd = estimatedCostUsd,
        )
    }

    private fun approximateTokens(text: String): Int =
        ceil(text.length / 4.0).toInt().coerceAtLeast(0)

    private fun providerIdFor(modelId: String): String =
        when {
            modelId.startsWith("gemini-") -> "gemini"
            modelId.startsWith("gpt-") || modelId.startsWith("o1") || modelId.startsWith("o3") -> "openai"
            modelId.startsWith("grok-") -> "grok"
            modelId.startsWith("llama-") || modelId.startsWith("mixtral-") || modelId.startsWith("gemma-") -> "groq"
            else -> "unknown"
        }

    private fun pricingFor(providerId: String): Pricing = when (providerId.lowercase()) {
        "gemini" -> Pricing(inputPerMillion = 0.35, outputPerMillion = 1.05)
        "openai" -> Pricing(inputPerMillion = 0.15, outputPerMillion = 0.60)
        "grok" -> Pricing(inputPerMillion = 0.25, outputPerMillion = 1.00)
        "groq" -> Pricing(inputPerMillion = 0.05, outputPerMillion = 0.20)
        else -> Pricing(inputPerMillion = 0.20, outputPerMillion = 0.80)
    }

    private data class Pricing(
        val inputPerMillion: Double,
        val outputPerMillion: Double,
    )

    private const val TEXT_PROMPT_TOKENS = 120
    private const val TEXT_OUTPUT_TOKENS = 90
}
