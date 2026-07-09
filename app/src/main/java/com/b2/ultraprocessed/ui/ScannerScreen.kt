package com.b2.ultraprocessed.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.camera.core.CameraSelector
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.b2.ultraprocessed.R
import com.b2.ultraprocessed.barcode.BarcodeLiveScanController
import com.b2.ultraprocessed.camera.CameraCaptureController
import com.b2.ultraprocessed.camera.LocalImageImportController
import com.b2.ultraprocessed.ui.audio.AppSoundEvent
import com.b2.ultraprocessed.ui.theme.Amber400
import com.b2.ultraprocessed.ui.theme.Amber500
import com.b2.ultraprocessed.ui.theme.DarkBg
import com.b2.ultraprocessed.ui.theme.Emerald400
import com.b2.ultraprocessed.ui.theme.Emerald500
import com.b2.ultraprocessed.ui.theme.Emerald600
import com.b2.ultraprocessed.ui.theme.SpaceGroteskFontFamily

private enum class ScannerMode {
    /** Preview + still capture for the food-label analysis path. */
    Label,
    /** Preview + ML Kit live barcode → USDA path. */
    BarcodeLive,
}

private object ScannerMetrics {
    val Grid = 8.dp
    val Space2 = 16.dp
    val Space3 = 24.dp
    val ScreenPadding = 24.dp
    val HeaderTitle = UiTextSizes.ScreenTitle
    val HeaderSubtitle = UiTextSizes.BodySmall
    val SecondaryText = UiTextSizes.BodySmall
    val Caption = UiTextSizes.Caption
    val PrimaryText = UiTextSizes.Body
    val PillHeight = 48.dp
    val PrimaryHeight = 56.dp
    val PillRadius = 12.dp
    val PrimaryRadius = 16.dp
    val IconBubble = 24.dp
    val IconSmall = 14.dp
    val IconMedium = 20.dp
    val ViewfinderInset = 32.dp
    val ViewfinderSize = 288.dp
}

@Composable
fun ScannerScreen(
    hasUsdaApiKey: Boolean,
    enableLiveCamera: Boolean = true,
    onScan: (String) -> Unit,
    onBarcodeScanned: (String) -> Unit,
    onSettings: () -> Unit,
    onHistory: () -> Unit,
    onSoundEffect: (AppSoundEvent) -> Unit = {},
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraController = remember(context) { CameraCaptureController(context) }
    val barcodeLiveController = remember(context) { BarcodeLiveScanController(context) }
    val importController = remember(context) { LocalImageImportController(context) }
    var scannerMode by remember { mutableStateOf(ScannerMode.Label) }
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA,
            ) == PackageManager.PERMISSION_GRANTED || !enableLiveCamera,
        )
    }
    var isCapturing by remember { mutableStateOf(false) }
    var isImporting by remember { mutableStateOf(false) }
    var cameraStatusMessage by remember { mutableStateOf<String?>(null) }
    /** False until CameraX label bind completes; avoids capture while [ImageCapture] is still null. */
    var isCameraPipelineReady by remember { mutableStateOf(false) }
    /** False until live barcode analysis use case is bound. */
    var isBarcodeLiveReady by remember { mutableStateOf(false) }
    var hasDetectedBarcode by remember { mutableStateOf(false) }
    var useFrontCamera by rememberSaveable { mutableStateOf(false) }
    val cameraSelector = remember(useFrontCamera) {
        if (useFrontCamera) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
    }
    val hasFrontCamera = remember {
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FRONT)
    }
    val permissionRequiredText = stringResource(R.string.scanner_camera_permission_required)
    val noPhotoSelectedText = stringResource(R.string.scanner_no_photo_selected)
    val enableCameraFeedText = stringResource(R.string.scanner_enable_camera_feed)
    val previewDisabledTestSafeText = stringResource(R.string.scanner_preview_disabled_test_safe)
    val cameraPreviewDisabledBuildText = stringResource(R.string.scanner_camera_preview_disabled_build)
    val cameraStillStartingText = stringResource(R.string.scanner_camera_still_starting)
    val usdaMissingLookupText = stringResource(R.string.scanner_usda_missing_lookup)
    val barcodeListeningText = stringResource(R.string.scanner_listening_barcode)
    val barcodeStartingText = stringResource(R.string.scanner_starting_barcode_camera)
    val controlScrollState = rememberScrollState()
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasCameraPermission = granted
        cameraStatusMessage = if (granted) {
            null
        } else {
            permissionRequiredText
        }
    }
    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri == null) {
            cameraStatusMessage = noPhotoSelectedText
            return@rememberLauncherForActivityResult
        }

        isImporting = true
        cameraStatusMessage = null
        importController.importFromUri(
            uri = uri,
            onSuccess = { capture ->
                isImporting = false
                scannerMode = ScannerMode.Label
                onScan(capture.absolutePath)
            },
            onError = { throwable ->
                isImporting = false
                cameraStatusMessage = throwable.message ?: "Failed to import image."
            },
        )
    }

    DisposableEffect(lifecycleOwner, hasCameraPermission, enableLiveCamera) {
        onDispose {
            if (enableLiveCamera && hasCameraPermission) {
                isCameraPipelineReady = false
                isBarcodeLiveReady = false
                cameraController.unbind()
                barcodeLiveController.unbind()
            }
        }
    }

    val scannerTransition = rememberInfiniteTransition(label = "scanner-line")
    val scanProgress by scannerTransition.animateFloat(
        initialValue = 0.12f,
        targetValue = 0.88f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "scan-progress",
    )

    LaunchedEffect(Unit) {
        if (enableLiveCamera && !hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    LaunchedEffect(scannerMode) {
        when (scannerMode) {
            ScannerMode.Label -> {
                isBarcodeLiveReady = false
                hasDetectedBarcode = false
            }
            ScannerMode.BarcodeLive -> {
                isCameraPipelineReady = false
                hasDetectedBarcode = false
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBg),
    ) {
        ScannerHomeHeader(
            onHistory = onHistory,
            onSettings = onSettings,
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(controlScrollState)
                .padding(horizontal = ScannerMetrics.ScreenPadding)
                .padding(top = ScannerMetrics.Space3, bottom = ScannerMetrics.Grid),
            verticalArrangement = Arrangement.spacedBy(ScannerMetrics.Space2),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .widthIn(max = ScannerMetrics.ViewfinderSize)
                    .fillMaxWidth()
                    .aspectRatio(1f),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize(),
                ) {
                    if (enableLiveCamera && hasCameraPermission) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(ScannerMetrics.ViewfinderInset)
                                .clip(RoundedCornerShape(8.dp)),
                        ) {
                            key(scannerMode, useFrontCamera) {
                                AndroidView(
                                    factory = { viewContext ->
                                        when (scannerMode) {
                                            ScannerMode.Label -> barcodeLiveController.unbind()
                                            ScannerMode.BarcodeLive -> cameraController.unbind()
                                        }
                                        PreviewView(viewContext).apply {
                                            scaleType = PreviewView.ScaleType.FILL_CENTER
                                            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                                            when (scannerMode) {
                                                ScannerMode.Label -> {
                                                    cameraController.bind(
                                                        previewView = this,
                                                        lifecycleOwner = lifecycleOwner,
                                                        cameraSelector = cameraSelector,
                                                        onBound = { isCameraPipelineReady = true },
                                                    )
                                                }
                                                ScannerMode.BarcodeLive -> {
                                                    barcodeLiveController.bind(
                                                        previewView = this,
                                                        lifecycleOwner = lifecycleOwner,
                                                        onBarcodeDetected = { barcode ->
                                                            hasDetectedBarcode = true
                                                            onBarcodeScanned(barcode)
                                                        },
                                                        cameraSelector = cameraSelector,
                                                        onBound = { isBarcodeLiveReady = true },
                                                    )
                                                }
                                            }
                                        }
                                    },
                                    update = {
                                        if (scannerMode == ScannerMode.BarcodeLive) {
                                            barcodeLiveController.updateBarcodeCallback { barcode ->
                                                hasDetectedBarcode = true
                                                onBarcodeScanned(barcode)
                                            }
                                        }
                                    },
                                    // Do not call bind() from [update]: scan-line animation recomposes every frame.
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .testTag(AppTestTags.SCANNER_PREVIEW),
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color.Black.copy(alpha = 0.18f)),
                            )
                        }
                    } else {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(ScannerMetrics.ViewfinderInset)
                                .clip(RoundedCornerShape(8.dp))
                                .background(DarkBg)
                                .testTag(AppTestTags.SCANNER_PREVIEW),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                text = if (enableLiveCamera) {
                                    stringResource(R.string.analysis_camera_permission_needed)
                                } else {
                                    stringResource(R.string.analysis_scanner_disabled)
                                },
                                color = Color.White.copy(alpha = 0.82f),
                                fontWeight = FontWeight.SemiBold,
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = if (enableLiveCamera) {
                                    cameraStatusMessage ?: enableCameraFeedText
                                } else {
                                    previewDisabledTestSafeText
                                },
                                color = Color.White.copy(alpha = 0.38f),
                                fontSize = UiTextSizes.BodySmall,
                            )
                            if (enableLiveCamera) {
                                Spacer(modifier = Modifier.height(14.dp))
                                Surface(
                                    onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                                    color = Emerald500.copy(alpha = 0.16f),
                                    shape = RoundedCornerShape(12.dp),
                                    border = BorderStroke(1.dp, Emerald500.copy(alpha = 0.24f)),
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.CameraAlt,
                                            contentDescription = null,
                                            tint = Emerald400,
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = stringResource(R.string.analysis_enable_camera),
                                            color = Emerald400,
                                            fontWeight = FontWeight.SemiBold,
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(ScannerMetrics.ViewfinderInset)
                            .border(
                                width = 1.dp,
                                color = Color.White.copy(alpha = 0.03f),
                                shape = RoundedCornerShape(8.dp),
                            ),
                    )

                    ScannerCornerFrame(
                        accentColor = when {
                            scannerMode == ScannerMode.BarcodeLive && !hasDetectedBarcode -> Amber400
                            else -> Emerald400
                        },
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(0.dp),
                    )

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp)
                            .fillMaxHeight(scanProgress)
                            .align(Alignment.TopCenter),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(2.dp)
                                .align(Alignment.BottomCenter)
                                .background(
                                    Brush.horizontalGradient(
                                        listOf(
                                            Color.Transparent,
                                            Emerald400.copy(alpha = 0.70f),
                                            Color.Transparent,
                                        ),
                                    ),
                                ),
                        )
                    }

                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .align(Alignment.Center)
                            .border(
                                width = 1.dp,
                                color = Color.White.copy(alpha = 0.15f),
                                shape = RoundedCornerShape(8.dp),
                            ),
                    )
                }
            }

            Text(
                text = when (scannerMode) {
                    ScannerMode.Label -> stringResource(R.string.analysis_aim_label).uppercase()
                    ScannerMode.BarcodeLive -> stringResource(R.string.analysis_aim_barcode).uppercase()
                },
                color = Color.White.copy(alpha = 0.36f),
                fontFamily = SpaceGroteskFontFamily,
                fontSize = ScannerMetrics.SecondaryText,
                fontWeight = FontWeight.Normal,
                letterSpacing = 2.4.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = ScannerMetrics.Grid),
                horizontalArrangement = Arrangement.spacedBy(ScannerMetrics.Grid),
            ) {
                ScannerActionPill(
                    text = stringResource(R.string.scanner_mode_label),
                    icon = Icons.Default.CameraAlt,
                    selected = scannerMode == ScannerMode.Label,
                    onClick = {
                        if (scannerMode != ScannerMode.Label) {
                            onSoundEffect(AppSoundEvent.Click)
                            scannerMode = ScannerMode.Label
                            cameraStatusMessage = null
                        }
                    },
                    modifier = Modifier.weight(1f),
                )

                ScannerActionPill(
                    text = stringResource(R.string.scanner_mode_barcode),
                    icon = Icons.Filled.QrCodeScanner,
                    selected = scannerMode == ScannerMode.BarcodeLive,
                    onClick = {
                        if (!enableLiveCamera) {
                            cameraStatusMessage = cameraPreviewDisabledBuildText
                            return@ScannerActionPill
                        }

                        if (!hasCameraPermission) {
                            permissionLauncher.launch(Manifest.permission.CAMERA)
                            return@ScannerActionPill
                        }

                        if (scannerMode != ScannerMode.BarcodeLive) {
                            onSoundEffect(AppSoundEvent.Click)
                            scannerMode = ScannerMode.BarcodeLive
                            cameraStatusMessage = null
                        }

                        if (!hasUsdaApiKey) {
                            cameraStatusMessage = usdaMissingLookupText
                        }
                    },
                    modifier = Modifier
                        .weight(1f)
                        .testTag(AppTestTags.SCANNER_BARCODE_BUTTON),
                )
            }

            if (scannerMode == ScannerMode.Label) {
                ScannerActionPill(
                    text = if (isImporting) {
                        stringResource(R.string.scanner_importing)
                    } else {
                        stringResource(R.string.scanner_upload_photo)
                    },
                    icon = Icons.Default.Image,
                    enabled = !isImporting,
                    selected = false,
                    onClick = {
                        onSoundEffect(AppSoundEvent.Click)
                        galleryLauncher.launch("image/*")
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = ScannerMetrics.Grid)
                        .testTag(AppTestTags.SCANNER_UPLOAD_BUTTON),
                )

                Button(
                    onClick = {
                        if (!enableLiveCamera) {
                            cameraStatusMessage = cameraPreviewDisabledBuildText
                            return@Button
                        }

                        if (!hasCameraPermission) {
                            permissionLauncher.launch(Manifest.permission.CAMERA)
                            return@Button
                        }

                        if (!isCameraPipelineReady) {
                            cameraStatusMessage = cameraStillStartingText
                            return@Button
                        }

                        onSoundEffect(AppSoundEvent.Click)
                        isCapturing = true
                        cameraStatusMessage = null
                        cameraController.capturePhoto(
                            onSuccess = { capture ->
                                isCapturing = false
                                onScan(capture.absolutePath)
                            },
                            onError = { throwable ->
                                isCapturing = false
                                onSoundEffect(AppSoundEvent.Error)
                                cameraStatusMessage = throwable.message ?: "Failed to capture image."
                            },
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = ScannerMetrics.Grid)
                        .height(ScannerMetrics.PrimaryHeight)
                        .testTag(AppTestTags.SCANNER_CAPTURE_BUTTON),
                    shape = RoundedCornerShape(ScannerMetrics.PrimaryRadius),
                    colors = ButtonDefaults.buttonColors(containerColor = Emerald500),
                    enabled = !isCapturing && (!enableLiveCamera || hasCameraPermission),
                ) {
                    if (isCapturing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = Color.Black,
                            strokeWidth = 2.dp,
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = stringResource(R.string.scanner_capturing),
                            color = Color.Black,
                            fontFamily = SpaceGroteskFontFamily,
                            fontSize = ScannerMetrics.PrimaryText,
                            fontWeight = FontWeight.Bold,
                        )
                    } else {
                        Icon(
                            Icons.Default.CameraAlt,
                            contentDescription = null,
                            tint = Color.Black,
                            modifier = Modifier.size(ScannerMetrics.IconMedium),
                        )
                        Spacer(modifier = Modifier.width(ScannerMetrics.Space2))
                        Text(
                            text = stringResource(R.string.scanner_scan_label),
                            color = Color.Black,
                            fontFamily = SpaceGroteskFontFamily,
                            fontSize = ScannerMetrics.PrimaryText,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }

            if (cameraStatusMessage != null) {
                Text(
                    text = cameraStatusMessage ?: "",
                    color = Amber400.copy(alpha = 0.84f),
                    fontSize = UiTextSizes.Caption,
                    textAlign = TextAlign.Center,
                )
            }
        }

        AppFooter(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun ScannerHomeHeader(
    onHistory: () -> Unit,
    onSettings: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(
                start = ScannerMetrics.ScreenPadding,
                top = ScannerMetrics.Space2,
                end = ScannerMetrics.ScreenPadding,
                bottom = ScannerMetrics.Space2,
            )
            .testTag(AppTestTags.HEADER),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppBrandMark(sizeDp = 48)

        Spacer(modifier = Modifier.width(ScannerMetrics.Space2))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.scanner_home_title),
                color = Color.White.copy(alpha = 0.92f),
                fontFamily = SpaceGroteskFontFamily,
                fontSize = ScannerMetrics.HeaderTitle,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = (-0.4).sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = stringResource(R.string.scanner_home_subtitle),
                color = Emerald500.copy(alpha = 0.82f),
                fontFamily = SpaceGroteskFontFamily,
                fontSize = ScannerMetrics.HeaderSubtitle,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        ScannerHeaderAction(
            icon = Icons.Default.History,
            contentDescription = "History",
            onClick = onHistory,
            testTag = AppTestTags.HEADER_ACTION_HISTORY,
        )

        Spacer(modifier = Modifier.width(ScannerMetrics.Grid))

        ScannerHeaderAction(
            icon = Icons.Default.Settings,
            contentDescription = "Settings",
            onClick = onSettings,
            testTag = AppTestTags.HEADER_ACTION_SETTINGS,
        )
    }
}

@Composable
private fun ScannerHeaderAction(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    badgeVisible: Boolean = false,
    testTag: String? = null,
) {
    val buttonModifier = Modifier
        .size(40.dp)
        .let { base ->
            if (testTag != null) base.testTag(testTag) else base
        }

    Box {
        Surface(
            onClick = onClick,
            color = Color.White.copy(alpha = 0.02f),
            shape = CircleShape,
            border = null,
            modifier = buttonModifier,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = contentDescription,
                    tint = Color.White.copy(alpha = 0.58f),
                    modifier = Modifier.size(23.dp),
                )
            }
        }

        if (badgeVisible) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(9.dp)
                    .background(Amber400, CircleShape),
            )
        }
    }
}

@Composable
private fun ScannerCornerFrame(
    accentColor: Color,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        ScannerCorner(
            accentColor = accentColor,
            horizontalAlignment = Alignment.Start,
            verticalAlignment = Alignment.Top,
            modifier = Modifier.align(Alignment.TopStart),
        )
        ScannerCorner(
            accentColor = accentColor,
            horizontalAlignment = Alignment.End,
            verticalAlignment = Alignment.Top,
            modifier = Modifier.align(Alignment.TopEnd),
        )
        ScannerCorner(
            accentColor = accentColor,
            horizontalAlignment = Alignment.Start,
            verticalAlignment = Alignment.Bottom,
            modifier = Modifier.align(Alignment.BottomStart),
        )
        ScannerCorner(
            accentColor = accentColor,
            horizontalAlignment = Alignment.End,
            verticalAlignment = Alignment.Bottom,
            modifier = Modifier.align(Alignment.BottomEnd),
        )
    }
}

@Composable
private fun ScannerCorner(
    accentColor: Color,
    horizontalAlignment: Alignment.Horizontal,
    verticalAlignment: Alignment.Vertical,
    modifier: Modifier = Modifier,
) {
    val glowColor = accentColor.copy(alpha = 0.20f)
    val coreColor = accentColor.copy(alpha = 0.34f)
    Box(
        modifier = modifier.size(48.dp),
    ) {
        Box(
            modifier = Modifier
                .width(2.dp)
                .height(48.dp)
                .align(
                    when (horizontalAlignment) {
                        Alignment.Start -> Alignment.CenterStart
                        else -> Alignment.CenterEnd
                    },
                )
                .graphicsLayer { alpha = 0.48f }
                .background(
                    Brush.verticalGradient(
                        when (verticalAlignment) {
                            Alignment.Top -> listOf(coreColor, glowColor, Color.Transparent)
                            else -> listOf(Color.Transparent, glowColor, coreColor)
                        },
                    ),
                    RoundedCornerShape(999.dp),
                ),
        )
        Box(
            modifier = Modifier
                .width(48.dp)
                .height(2.dp)
                .align(
                    when (verticalAlignment) {
                        Alignment.Top -> Alignment.TopCenter
                        else -> Alignment.BottomCenter
                    },
                )
                .graphicsLayer { alpha = 0.48f }
                .background(
                    Brush.horizontalGradient(
                        when (horizontalAlignment) {
                            Alignment.Start -> listOf(coreColor, glowColor, Color.Transparent)
                            else -> listOf(Color.Transparent, glowColor, coreColor)
                        },
                    ),
                    RoundedCornerShape(999.dp),
                ),
        )
    }
}

@Composable
private fun ScannerActionPill(
    text: String,
    icon: ImageVector,
    selected: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val contentColor = when {
        selected -> Emerald400
        enabled -> Color.White.copy(alpha = 0.56f)
        else -> Color.White.copy(alpha = 0.30f)
    }
    val iconColor = when {
        selected -> Emerald400
        enabled -> Color.White.copy(alpha = 0.52f)
        else -> Color.White.copy(alpha = 0.28f)
    }
    val containerColor = if (selected) Emerald500.copy(alpha = 0.09f) else Color.White.copy(alpha = 0.035f)
    val borderColor = if (selected) Emerald500.copy(alpha = 0.26f) else Color.White.copy(alpha = 0.055f)
    val iconContainerColor = if (selected) Emerald500.copy(alpha = 0.16f) else Color.White.copy(alpha = 0.045f)
    Surface(
        onClick = {
            if (enabled) onClick()
        },
        color = containerColor,
        shape = RoundedCornerShape(ScannerMetrics.PillRadius),
        border = BorderStroke(1.dp, borderColor),
        modifier = modifier.heightIn(min = ScannerMetrics.PillHeight),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = ScannerMetrics.Space2, vertical = 4.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(ScannerMetrics.IconBubble)
                    .background(iconContainerColor, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconColor,
                    modifier = Modifier.size(ScannerMetrics.IconSmall),
                )
            }
            Spacer(modifier = Modifier.width(ScannerMetrics.Grid))
            Text(
                text = text,
                color = contentColor,
                fontFamily = SpaceGroteskFontFamily,
                fontSize = ScannerMetrics.SecondaryText,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }
    }
}
