package com.b2.ultraprocessed.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.b2.ultraprocessed.BuildConfig
import com.b2.ultraprocessed.storage.preferences.AppPreferences
import com.b2.ultraprocessed.network.llm.LlmApiKeyVerifier
import com.b2.ultraprocessed.network.llm.LlmProviderResolver
import com.b2.ultraprocessed.network.llm.ResultChatContext
import com.b2.ultraprocessed.network.llm.ResultChatIngredientSignal
import com.b2.ultraprocessed.network.llm.ResultChatWorkflowFactory
import com.b2.ultraprocessed.network.llm.SecretLlmApiKeyProvider
import com.b2.ultraprocessed.storage.room.NovaDatabase
import com.b2.ultraprocessed.storage.room.ScanResult as ScanResultEntity
import com.b2.ultraprocessed.storage.secrets.SecretKeyManager
import com.b2.ultraprocessed.ui.audio.AppSoundEvent
import com.b2.ultraprocessed.ui.audio.AppSoundManager
import com.b2.ultraprocessed.ui.theme.DarkBg
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Base64
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

data class AppTimingConfig(
    /** Production default: show the animated brand loading screen on cold start. */
    val splashDurationMillis: Long = 4_200L,
    /** Production default: show results as soon as analysis completes. */
    val analysisMinimumDisplayMillis: Long = 0L,
)

@Composable
fun UltraProcessedApp(
    timingConfig: AppTimingConfig = AppTimingConfig(),
    enableLiveCamera: Boolean = true,
) {
    val appContext = LocalContext.current.applicationContext
    val secretKeyManager = remember(appContext) { SecretKeyManager(appContext) }
    val appPreferences = remember(appContext) { AppPreferences(appContext) }
    val soundManager = remember(appContext) { AppSoundManager(appContext) }
    val resultChatWorkflow = remember(appContext) {
        ResultChatWorkflowFactory.create(
            context = appContext,
            apiKeyProvider = SecretLlmApiKeyProvider(secretKeyManager),
        )
    }
    val llmApiKeyVerifier = remember { LlmApiKeyVerifier() }
    val scanResultDao = remember(appContext) {
        NovaDatabase.getDatabase(appContext).scanResultDao()
    }
    val coroutineScope = rememberCoroutineScope()
    val storedHistory by scanResultDao.getAllScanResults().collectAsState(initial = emptyList())
    val historyItems = remember(storedHistory) {
        storedHistory.map { it.toHistoryItemUi() }
    }
    val historySummary = remember(storedHistory) {
        storedHistory.toHistoryUsageSummaryUi()
    }
    var hasLlmApiKey by rememberSaveable { mutableStateOf(false) }
    var hasUsdaApiKey by rememberSaveable { mutableStateOf(false) }
    var soundEffectsEnabled by rememberSaveable { mutableStateOf(appPreferences.soundEffectsEnabled) }
    var llmKeyMetadata by remember { mutableStateOf<KeyMetadata?>(null) }
    var selectedModelId by rememberSaveable {
        mutableStateOf(AppCatalog.modelOptions.first().id)
    }
    var lastCapturedPhotoPath by rememberSaveable { mutableStateOf<String?>(null) }
    var barcodeValue by rememberSaveable { mutableStateOf<String?>(null) }
    var scanSessionId by remember { mutableIntStateOf(0) }
    var analysisMode by remember { mutableStateOf(AnalysisMode.LabelImage) }
    var analysisErrorMessage by remember { mutableStateOf("") }
    var currentScanResult by remember { mutableStateOf<ScanResultUi?>(null) }
    var destination by rememberSaveable { mutableStateOf(AppDestination.Splash) }
    var previousDestination by rememberSaveable { mutableStateOf<AppDestination?>(null) }
    var hasAcceptedDisclaimer by rememberSaveable {
        mutableStateOf(appPreferences.disclaimerAccepted)
    }
    val splashDurationMillis = if (soundEffectsEnabled) {
        soundManager.startupCueDurationMillis.takeIf { it > 0L } ?: timingConfig.splashDurationMillis
    } else {
        timingConfig.splashDurationMillis
    }

    DisposableEffect(soundManager) {
        onDispose {
            soundManager.release()
        }
    }

    fun playSound(event: AppSoundEvent) {
        if (soundEffectsEnabled) {
            soundManager.play(event)
        }
    }

    fun navigateTo(nextDestination: AppDestination, rememberCurrentForBack: Boolean = false) {
        previousDestination = if (rememberCurrentForBack) destination else null
        destination = nextDestination
    }

    fun navigateBackWithinApp() {
        when (destination) {
            AppDestination.Splash -> Unit
            AppDestination.Disclaimer -> {
                if (hasAcceptedDisclaimer) {
                    val target = previousDestination
                        ?.takeUnless { it == AppDestination.Splash || it == AppDestination.Analyzing }
                        ?: AppDestination.Scanner
                    previousDestination = null
                    destination = target
                }
            }
            AppDestination.Scanner -> Unit
            AppDestination.Analyzing -> navigateTo(AppDestination.Scanner)
            AppDestination.Results -> navigateTo(AppDestination.Scanner)
            AppDestination.AnalysisError -> {
                analysisErrorMessage = ""
                navigateTo(AppDestination.Scanner)
            }
            AppDestination.Settings,
            AppDestination.History -> {
                val target = previousDestination
                    ?.takeUnless { it == AppDestination.Splash || it == AppDestination.Analyzing }
                    ?: AppDestination.Scanner
                previousDestination = null
                destination = target
            }
        }
    }

    BackHandler(enabled = destination != AppDestination.Splash) {
        navigateBackWithinApp()
    }

    LaunchedEffect(secretKeyManager) {
        val bootstrapUsdaKey = decodeBootstrapSecret(BuildConfig.USDA_BOOTSTRAP_API_KEY_B64)
        if (bootstrapUsdaKey.isNotBlank() &&
            !secretKeyManager.hasApiKey(SecretKeyManager.USDA_API_KEY)
        ) {
            runCatching {
                secretKeyManager.saveApiKey(SecretKeyManager.USDA_API_KEY, bootstrapUsdaKey)
            }
        }
        val savedLlmKey = runCatching {
            secretKeyManager.getApiKey(SecretKeyManager.LLM_API_KEY)
        }.getOrNull().orEmpty()
        val provider = LlmProviderResolver.detectProvider(savedLlmKey)
        hasLlmApiKey = savedLlmKey.isNotBlank()
        llmKeyMetadata = provider?.let { provider ->
            LlmProviderResolver.defaultModelForProvider(provider)?.let {
                KeyMetadata(
                    modelName = it.modelName,
                    provider = it.provider,
                    acceptsImages = it.acceptsImages,
                )
            }
        }
        provider?.let {
            LlmProviderResolver.defaultModelForProvider(it)?.let { model ->
                selectedModelId = model.modelId
            }
        }
        hasUsdaApiKey = runCatching {
            secretKeyManager.hasApiKey(SecretKeyManager.USDA_API_KEY)
        }.getOrDefault(false)
    }

    LaunchedEffect(selectedModelId) {
        val valid = AppCatalog.modelOptions.any { it.id == selectedModelId }
        if (!valid) {
            selectedModelId = AppCatalog.modelOptions.first().id
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = DarkBg,
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            AnimatedContent(
                targetState = destination,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "destination-animation",
            ) { screen ->
                when (screen) {
                    AppDestination.Splash -> SplashScreen(
                        displayDurationMillis = splashDurationMillis,
                        onSoundEffect = { event -> playSound(event) },
                        onComplete = {
                            navigateTo(
                                if (hasAcceptedDisclaimer) {
                                    AppDestination.Scanner
                                } else {
                                    AppDestination.Disclaimer
                                },
                            )
                        },
                    )

                    AppDestination.Disclaimer -> DisclaimerScreen(
                        navigationAction = if (hasAcceptedDisclaimer) {
                            backHeaderAction { navigateBackWithinApp() }
                        } else {
                            null
                        },
                        onAccepted = {
                            appPreferences.disclaimerAccepted = true
                            hasAcceptedDisclaimer = true
                            val target = previousDestination
                                ?.takeUnless { it == AppDestination.Splash || it == AppDestination.Analyzing }
                                ?: AppDestination.Scanner
                            previousDestination = null
                            navigateTo(target)
                        },
                    )

                    AppDestination.Scanner -> ScannerScreen(
                        hasApiKey = hasLlmApiKey,
                        hasUsdaApiKey = hasUsdaApiKey,
                        enableLiveCamera = enableLiveCamera,
                        onScan = { path ->
                            lastCapturedPhotoPath = path
                            barcodeValue = null
                            analysisMode = AnalysisMode.LabelImage
                            scanSessionId++
                            navigateTo(AppDestination.Analyzing)
                        },
                        onBarcodeScanned = { code ->
                            lastCapturedPhotoPath = null
                            barcodeValue = code
                            analysisMode = AnalysisMode.BarcodeValue
                            scanSessionId++
                            navigateTo(AppDestination.Analyzing)
                        },
                        onSettings = {
                            navigateTo(AppDestination.Settings, rememberCurrentForBack = true)
                        },
                        onHistory = {
                            navigateTo(AppDestination.History, rememberCurrentForBack = true)
                        },
                        onSoundEffect = { event -> playSound(event) },
                    )

                    AppDestination.Analyzing -> AnalyzingScreen(
                        scanSessionId = scanSessionId,
                        imagePath = lastCapturedPhotoPath,
                        barcodeValue = barcodeValue,
                        mode = analysisMode,
                        minimumDisplayMillis = timingConfig.analysisMinimumDisplayMillis,
                        modelId = selectedModelId,
                        modelName = AppCatalog.modelOptions
                            .firstOrNull { it.id == selectedModelId }
                            ?.name
                            ?: selectedModelId,
                        onSuccess = { result ->
                            barcodeValue = null
                            coroutineScope.launch {
                                runCatching {
                                    scanResultDao.insertScanResult(result.toScanResultEntity())
                                }.onSuccess {
                                    playSound(AppSoundEvent.Success)
                                    currentScanResult = result
                                    navigateTo(AppDestination.Results)
                                }.onFailure {
                                    playSound(AppSoundEvent.Error)
                                    deleteLocalScanImage(appContext, result.labelImagePath)
                                    analysisErrorMessage = "Could not save scan history. Please try again."
                                    navigateTo(AppDestination.AnalysisError)
                                }
                            }
                        },
                        onFailure = { message ->
                            playSound(AppSoundEvent.Error)
                            val failedImagePath = lastCapturedPhotoPath
                            if (!failedImagePath.isNullOrBlank()) {
                                coroutineScope.launch {
                                    runCatching {
                                        scanResultDao.insertScanResult(
                                            failedScanResultEntity(
                                                imagePath = failedImagePath,
                                                message = message,
                                            ),
                                        )
                                    }
                                }
                            }
                            analysisErrorMessage = message
                            barcodeValue = null
                            navigateTo(AppDestination.AnalysisError)
                        },
                    )

                    AppDestination.Results -> {
                        val result = currentScanResult
                        if (result != null) {
                            ResultsScreen(
                                result = result,
                                onScanAgain = {
                                    navigateTo(AppDestination.Scanner)
                                },
                                onOpenHistory = {
                                    navigateTo(AppDestination.History, rememberCurrentForBack = true)
                                },
                                chatEnabled = hasLlmApiKey,
                                onSoundEffect = { event -> playSound(event) },
                                onAskAboutResult = { question, onStatus ->
                                    val current = currentScanResult
                                    if (current == null) {
                                        Result.failure(IllegalStateException("No scan result available."))
                                    } else {
                                        val chatContext = ResultChatContext(
                                            productName = current.productName,
                                            novaGroup = current.novaGroup,
                                            summary = current.summary,
                                            sourceLabel = current.sourceLabel,
                                            confidence = current.confidence,
                                            ingredients = current.allIngredients,
                                            ingredientAssessments = current.ingredientAssessments.map { assessment ->
                                                ResultChatIngredientSignal(
                                                    name = assessment.name,
                                                    verdict = "nova-${assessment.novaGroup}",
                                                    reason = assessment.reason,
                                                )
                                            },
                                            allergens = current.allergens,
                                            warnings = current.warnings,
                                        )
                                        resultChatWorkflow.askAboutResult(
                                            result = chatContext,
                                            question = question,
                                            modelId = selectedModelId,
                                            onStatus = onStatus,
                                        )
                                    }
                                },
                            )
                        } else {
                            LaunchedEffect(Unit) {
                                navigateTo(AppDestination.Scanner)
                            }
                        }
                    }

                    AppDestination.AnalysisError -> AnalysisErrorScreen(
                        message = analysisErrorMessage.ifBlank {
                            "Could not read enough ingredient text. Please try again."
                        },
                        onRetry = {
                            analysisErrorMessage = ""
                            navigateTo(AppDestination.Scanner)
                        },
                    )

                    AppDestination.Settings -> SettingsScreen(
                        hasLlmApiKey = hasLlmApiKey,
                        selectedModelId = selectedModelId,
                        modelOptions = AppCatalog.modelOptions,
                        llmKeyMetadata = llmKeyMetadata,
                        soundEffectsEnabled = soundEffectsEnabled,
                        onBack = { navigateBackWithinApp() },
                        onLlmApiKeySaved = { key ->
                            runCatching {
                                val provider = LlmProviderResolver.detectProvider(key)
                                    ?: return@runCatching KeySaveResult(
                                        success = false,
                                        message = "Unsupported API key format. Use Gemini/OpenAI/Grok keys.",
                                    )
                                val verification = llmApiKeyVerifier.ping(key)
                                if (!verification.valid) {
                                    return@runCatching KeySaveResult(
                                        success = false,
                                        message = verification.message,
                                    )
                                }
                                val saved = secretKeyManager.saveApiKey(SecretKeyManager.LLM_API_KEY, key)
                                hasLlmApiKey = saved &&
                                    secretKeyManager.hasApiKey(SecretKeyManager.LLM_API_KEY)
                                llmKeyMetadata = provider?.let { providerId ->
                                    LlmProviderResolver.defaultModelForProvider(providerId)?.let {
                                        KeyMetadata(
                                            modelName = it.modelName,
                                            provider = it.provider,
                                            acceptsImages = it.acceptsImages,
                                        )
                                    }
                                }
                                LlmProviderResolver.defaultModelForProvider(provider)?.let { model ->
                                    selectedModelId = model.modelId
                                }
                                if (saved) {
                                    KeySaveResult(
                                        success = true,
                                        message = "LLM key verified and saved securely.",
                                    )
                                } else {
                                    KeySaveResult(
                                        success = false,
                                        message = "Could not save key. Please try again.",
                                    )
                                }
                            }.getOrDefault(
                                KeySaveResult(
                                    success = false,
                                    message = "Could not save key. Please try again.",
                                ),
                            )
                        },
                        onLlmApiKeyPing = { typedKey ->
                            val keyToPing = typedKey
                                ?.takeIf { it.isNotBlank() }
                                ?: secretKeyManager.getApiKey(SecretKeyManager.LLM_API_KEY).orEmpty()
                            if (keyToPing.isBlank()) {
                                KeySaveResult(
                                    success = false,
                                    message = "Enter or save an API key before ping.",
                                )
                            } else {
                                val verification = llmApiKeyVerifier.ping(keyToPing)
                                KeySaveResult(
                                    success = verification.valid,
                                    message = verification.message,
                                )
                            }
                        },
                        onLlmApiKeyDeleted = {
                            runCatching {
                                val deleted = secretKeyManager.deleteApiKey(SecretKeyManager.LLM_API_KEY)
                                if (deleted) {
                                    hasLlmApiKey = false
                                    llmKeyMetadata = null
                                }
                                deleted
                            }.getOrDefault(false)
                        },
                        onModelSelected = { selectedModelId = it },
                        onSoundEffectsChanged = { enabled ->
                            soundEffectsEnabled = enabled
                            appPreferences.soundEffectsEnabled = enabled
                        },
                        onOpenDisclaimer = {
                            navigateTo(AppDestination.Disclaimer, rememberCurrentForBack = true)
                        },
                    )

                    AppDestination.History -> HistoryScreen(
                        historyItems = historyItems,
                        historySummary = historySummary,
                        onBack = { navigateBackWithinApp() },
                        onClearAll = {
                            coroutineScope.launch {
                                historyItems.forEach { item ->
                                    deleteLocalScanImage(appContext, item.capturedImagePath)
                                }
                                scanResultDao.deleteAllScanResults()
                            }
                        },
                        onClearItem = { item ->
                            item.id.toLongOrNull()?.let { id ->
                                coroutineScope.launch {
                                    scanResultDao.deleteScanResultById(id)
                                    deleteLocalScanImage(appContext, item.capturedImagePath)
                                }
                            }
                        },
                        onRerunItem = { item ->
                            val path = item.capturedImagePath
                            if (!path.isNullOrBlank()) {
                                lastCapturedPhotoPath = path
                                barcodeValue = null
                                analysisMode = AnalysisMode.LabelImage
                                scanSessionId++
                                navigateTo(AppDestination.Analyzing)
                            }
                        },
                    )
                }
            }

        }
    }
}

private fun decodeBootstrapSecret(encoded: String): String {
    val normalized = encoded.trim()
    if (normalized.isBlank()) return ""
    return runCatching {
        String(Base64.getDecoder().decode(normalized), Charsets.UTF_8).trim()
    }.getOrDefault("")
}

private fun ScanResultUi.toScanResultEntity(): ScanResultEntity =
    ScanResultEntity(
        productName = productName,
        novaGroup = novaGroup,
        ocrText = rawIngredientText.ifBlank { allIngredients.joinToString(", ") },
        cleanedIngredients = allIngredients.joinToString(", "),
        verdict = "NOVA $novaGroup",
        confidenceScore = confidence,
        detectedMarkers = JSONArray(
            problemIngredients.map {
                JSONObject()
                    .put("name", it.name)
                    .put("reason", it.reason)
            },
        ).toString(),
        allergens = JSONArray(allergens).toString(),
        explanation = summary,
        engineUsed = engineLabel,
        modelId = usageEstimate?.modelId.orEmpty(),
        modelName = usageEstimate?.modelName.orEmpty(),
        provider = usageEstimate?.provider.orEmpty(),
        estimatedInputTokens = usageEstimate?.estimatedInputTokens ?: 0,
        estimatedOutputTokens = usageEstimate?.estimatedOutputTokens ?: 0,
        estimatedTotalTokens = usageEstimate?.estimatedTotalTokens ?: 0,
        estimatedCostUsd = usageEstimate?.estimatedCostUsd ?: 0.0,
        capturedImagePath = labelImagePath,
        isBarcodeLookupOnly = isBarcodeLookupOnly,
        isFailed = false,
        failureMessage = "",
    )

private fun failedScanResultEntity(
    imagePath: String,
    message: String,
): ScanResultEntity =
    ScanResultEntity(
        productName = "Failed analysis",
        novaGroup = 0,
        ocrText = "",
        cleanedIngredients = "",
        verdict = "Failed",
        confidenceScore = 0f,
        detectedMarkers = "[]",
        allergens = "[]",
        explanation = message,
        engineUsed = "Analysis pipeline",
        capturedImagePath = imagePath,
        isBarcodeLookupOnly = false,
        isFailed = true,
        failureMessage = message,
    )

private fun ScanResultEntity.toHistoryItemUi(): HistoryItemUi =
    HistoryItemUi(
        id = id.toString(),
        productName = productName,
        novaGroup = novaGroup,
        scannedAt = SCAN_TIME_FORMAT.format(Date(scannedAt)),
        scannedAtMillis = scannedAt,
        summary = explanation,
        capturedImagePath = capturedImagePath,
        isBarcodeLookupOnly = isBarcodeLookupOnly,
        modelName = modelName.ifBlank { engineUsed },
        provider = provider.ifBlank { "" },
        estimatedTokens = estimatedTotalTokens,
        estimatedCostUsd = estimatedCostUsd,
        isFailed = isFailed,
        failureMessage = failureMessage,
    )

private fun List<ScanResultEntity>.toHistoryUsageSummaryUi(): HistoryUsageSummaryUi {
    val totalTokens = sumOf { it.estimatedTotalTokens.coerceAtLeast(0) }
    val totalCost = sumOf { it.estimatedCostUsd.coerceAtLeast(0.0) }
    val modelUsage = asSequence()
        .filter { it.modelId.isNotBlank() || it.estimatedTotalTokens > 0 || it.estimatedCostUsd > 0.0 }
        .groupBy { it.modelId.ifBlank { it.engineUsed } }
        .map { (modelKey, items) ->
            val first = items.first()
            val estimatedTokens = items.sumOf { it.estimatedTotalTokens.coerceAtLeast(0) }
            val estimatedCost = items.sumOf { it.estimatedCostUsd.coerceAtLeast(0.0) }
            val modelId = first.modelId.ifBlank { modelKey }
            val metadata = com.b2.ultraprocessed.network.llm.LlmProviderResolver.metadataFromModelId(modelId)
            ModelUsageUi(
                modelName = first.modelName.ifBlank { metadata?.modelName ?: modelKey },
                provider = first.provider.ifBlank { metadata?.provider ?: "Unknown" },
                scans = items.size,
                estimatedTokens = estimatedTokens,
                estimatedCostUsd = estimatedCost,
            )
        }
        .sortedByDescending { it.estimatedTokens }
        .toList()
    return HistoryUsageSummaryUi(
        totalScans = size,
        totalTokens = totalTokens,
        estimatedCostUsd = totalCost,
        modelUsage = modelUsage,
    )
}

private val SCAN_TIME_FORMAT = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)

private fun deleteLocalScanImage(
    appContext: android.content.Context,
    imagePath: String?,
) {
    if (imagePath.isNullOrBlank()) return
    runCatching {
        val target = File(imagePath).canonicalFile
        val allowedRoots = listOfNotNull(
            appContext.filesDir,
            appContext.cacheDir,
            appContext.getExternalFilesDir(null),
        ).map { it.canonicalFile }
        if (allowedRoots.any { root -> target.toPath().startsWith(root.toPath()) }) {
            target.delete()
        }
    }
}
