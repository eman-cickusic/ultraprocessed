package com.b2.ultraprocessed.storage.room

import androidx.room.Entity
import androidx.room.ColumnInfo
import androidx.room.PrimaryKey

@Entity(tableName = "scan_results")
data class ScanResult(

    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(defaultValue = "Scanned label")
    val productName: String,

    @ColumnInfo(defaultValue = "0")
    val novaGroup: Int,

    // Raw text pulled from the camera scan
    val ocrText: String,

    // Cleaned up ingredient list after processing
    val cleanedIngredients: String,

    // Final verdict e.g. "Ultra Processed", "Minimally Processed"
    val verdict: String,

    // How confident the engine is in its verdict (0.0 to 1.0)
    val confidenceScore: Float,

    // Specific markers that triggered the verdict e.g. "contains emulsifiers"
    val detectedMarkers: String,

    @ColumnInfo(defaultValue = "")
    val allergens: String,

    // Human readable explanation of the verdict
    val explanation: String,

    // Which provider workflow produced this result, for example "Gemini staged LLM".
    val engineUsed: String,

    @ColumnInfo(defaultValue = "")
    val modelId: String = "",

    @ColumnInfo(defaultValue = "")
    val modelName: String = "",

    @ColumnInfo(defaultValue = "")
    val provider: String = "",

    @ColumnInfo(defaultValue = "0")
    val estimatedInputTokens: Int = 0,

    @ColumnInfo(defaultValue = "0")
    val estimatedOutputTokens: Int = 0,

    @ColumnInfo(defaultValue = "0")
    val estimatedTotalTokens: Int = 0,

    @ColumnInfo(defaultValue = "0.0")
    val estimatedCostUsd: Double = 0.0,

    val capturedImagePath: String?,

    @ColumnInfo(defaultValue = "0")
    val isBarcodeLookupOnly: Boolean,

    @ColumnInfo(defaultValue = "0")
    val isFailed: Boolean = false,

    @ColumnInfo(defaultValue = "")
    val failureMessage: String = "",

    // Timestamp of when the scan happened
    val scannedAt: Long = System.currentTimeMillis()
)
