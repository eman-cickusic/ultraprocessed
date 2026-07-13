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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import android.os.Environment
import com.b2.ultraprocessed.BuildConfig
import com.b2.ultraprocessed.storage.preferences.AppPreferences
import com.b2.ultraprocessed.analysis.FoodAnalysisPipeline
import com.b2.ultraprocessed.network.llm.ProxyResultChatWorkflow
import com.b2.ultraprocessed.network.llm.ResultChatContext
import com.b2.ultraprocessed.network.llm.ResultChatHistoryMessage
import com.b2.ultraprocessed.network.llm.ResultChatIngredientSignal
import com.b2.ultraprocessed.storage.secrets.SecretKeyManager
import com.b2.ultraprocessed.ui.audio.AppSoundEvent
import com.b2.ultraprocessed.ui.audio.AppSoundManager
import com.b2.ultraprocessed.ui.theme.DarkBg
import java.io.File
import java.util.Base64

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
    val resultChatWorkflow = remember { ProxyResultChatWorkflow() }
    DisposableEffect(appContext) {
        deleteSessionFiles(appContext)
        onDispose { deleteSessionFiles(appContext) }
    }
    var hasUsdaApiKey by rememberSaveable { mutableStateOf(false) }
    var soundEffectsEnabled by rememberSaveable { mutableStateOf(appPreferences.soundEffectsEnabled) }
    var selectedModelId by rememberSaveable {
        mutableStateOf(FoodAnalysisPipeline.DEFAULT_MODEL_ID)
    }
    var lastCapturedPhotoPath by remember { mutableStateOf<String?>(null) }
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

    fun clearCurrentResultAndImage() {
        deleteLocalScanImage(appContext, currentScanResult?.labelImagePath)
        currentScanResult = null
        lastCapturedPhotoPath = null
        barcodeValue = null
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
            AppDestination.Analyzing -> {
                deleteLocalScanImage(appContext, lastCapturedPhotoPath)
                lastCapturedPhotoPath = null
                barcodeValue = null
                navigateTo(AppDestination.Scanner)
            }
            AppDestination.Results -> {
                clearCurrentResultAndImage()
                navigateTo(AppDestination.Scanner)
            }
            AppDestination.AnalysisError -> {
                analysisErrorMessage = ""
                navigateTo(AppDestination.Scanner)
            }
            AppDestination.Settings -> {
                navigateTo(AppDestination.Scanner)
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
        runCatching { secretKeyManager.deleteApiKey(SecretKeyManager.LLM_API_KEY) }
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
                            currentScanResult = result
                            lastCapturedPhotoPath = null
                            playSound(AppSoundEvent.Success)
                            navigateTo(AppDestination.Results)
                        },
                        onFailure = { message ->
                            playSound(AppSoundEvent.Error)
                            deleteLocalScanImage(appContext, lastCapturedPhotoPath)
                            lastCapturedPhotoPath = null
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
                                    clearCurrentResultAndImage()
                                    navigateTo(AppDestination.Scanner)
                                },
                                chatEnabled = true,
                                onSoundEffect = { event -> playSound(event) },
                                onAskAboutResult = { question, history, onStatus ->
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
                                            history = history.map { message ->
                                                ResultChatHistoryMessage(
                                                    role = when (message.role) {
                                                        ResultChatRole.User -> "user"
                                                        ResultChatRole.Assistant -> "assistant"
                                                        ResultChatRole.System -> "system"
                                                    },
                                                    text = message.text,
                                                )
                                            },
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
                        soundEffectsEnabled = soundEffectsEnabled,
                        onBack = { navigateBackWithinApp() },
                        onSoundEffectsChanged = { enabled ->
                            soundEffectsEnabled = enabled
                            appPreferences.soundEffectsEnabled = enabled
                        },
                        onOpenDisclaimer = {
                            navigateTo(AppDestination.Disclaimer, rememberCurrentForBack = true)
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

/** Removes capture/import files left by an interrupted session. */
private fun deleteSessionFiles(appContext: android.content.Context) {
    listOfNotNull(
        appContext.getExternalFilesDir(Environment.DIRECTORY_PICTURES),
        File(appContext.filesDir, "captures"),
        File(appContext.filesDir, "imports"),
    ).forEach { directory ->
        runCatching {
            val targets = if (directory.name == Environment.DIRECTORY_PICTURES) {
                listOf(File(directory, "captures"), File(directory, "imports"))
            } else {
                listOf(directory)
            }
            targets.forEach { target ->
                target.listFiles()?.forEach { file -> if (file.isFile) file.delete() }
            }
        }
    }
}
