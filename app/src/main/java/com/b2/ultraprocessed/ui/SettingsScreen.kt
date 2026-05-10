package com.b2.ultraprocessed.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.b2.ultraprocessed.BuildConfig
import com.b2.ultraprocessed.R
import com.b2.ultraprocessed.ui.theme.DarkBg
import com.b2.ultraprocessed.ui.theme.Emerald400
import com.b2.ultraprocessed.ui.theme.Emerald500
import com.b2.ultraprocessed.ui.theme.SpaceGroteskFontFamily
import com.b2.ultraprocessed.network.llm.LlmProviderResolver
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

data class KeyMetadata(
    val modelName: String,
    val provider: String,
    val acceptsImages: Boolean,
)

data class KeySaveResult(
    val success: Boolean,
    val message: String,
)

private enum class PingStatus {
    Idle,
    Loading,
    Success,
    Failure,
}

private object SettingsMetrics {
    val Grid = 8.dp
    val Space2 = 16.dp
    val Space3 = 24.dp
    val ScreenPadding = 24.dp
    val HeaderIcon = 40.dp
    val CardRadius = 18.dp
}

private object SettingsType {
    val PageTitle = UiTextSizes.ScreenTitle
    val Section = UiTextSizes.Caption
    val Title = UiTextSizes.BodySmall
    val Body = UiTextSizes.BodySmall
    val Meta = UiTextSizes.Caption
}

@Composable
fun SettingsScreen(
    hasLlmApiKey: Boolean,
    selectedModelId: String,
    modelOptions: List<ModelOption>,
    llmKeyMetadata: KeyMetadata? = null,
    soundEffectsEnabled: Boolean,
    onBack: () -> Unit,
    onLlmApiKeySaved: suspend (String) -> KeySaveResult,
    onLlmApiKeyPing: suspend (String?) -> KeySaveResult,
    onLlmApiKeyDeleted: suspend () -> Boolean,
    onModelSelected: (String) -> Unit,
    onSoundEffectsChanged: (Boolean) -> Unit,
    onOpenDisclaimer: () -> Unit,
) {
    val selectedModel = modelOptions.firstOrNull { it.id == selectedModelId } ?: modelOptions.firstOrNull()
    val uriHandler = LocalUriHandler.current

    LaunchedEffect(selectedModelId, modelOptions) {
        if (selectedModel == null && modelOptions.isNotEmpty()) {
            onModelSelected(modelOptions.first().id)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBg),
    ) {
        SettingsHeader(onBack = onBack)

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = SettingsMetrics.ScreenPadding)
                .padding(top = SettingsMetrics.Space2),
        ) {
            UiSectionHeader(text = stringResource(R.string.settings_llm_key_section), icon = Icons.Default.Key)
            SecureApiKeyCard(
                hasKey = hasLlmApiKey,
                storedDescription = stringResource(R.string.settings_llm_key_stored_description),
                emptyDescription = stringResource(R.string.settings_llm_key_empty_description),
                emptyLabel = stringResource(R.string.settings_llm_key_empty_label),
                replacementLabel = stringResource(R.string.settings_llm_key_replacement_label),
                saveLabel = stringResource(R.string.settings_llm_key_save_button),
                replaceLabel = stringResource(R.string.settings_llm_key_replace_button),
                deleteLabel = stringResource(R.string.settings_llm_key_delete_button),
                metadata = llmKeyMetadata ?: selectedModel?.let {
                    KeyMetadata(
                        modelName = it.name,
                        provider = it.provider,
                        acceptsImages = it.supportsImages,
                    )
                },
                onSave = onLlmApiKeySaved,
                onPing = onLlmApiKeyPing,
                onDelete = onLlmApiKeyDeleted,
            )

            Spacer(modifier = Modifier.height(SettingsMetrics.Space2))

            UiSectionHeader(text = stringResource(R.string.settings_sound_title), icon = Icons.AutoMirrored.Filled.VolumeUp)
            Surface(
                color = Color.White.copy(alpha = 0.03f),
                shape = RoundedCornerShape(SettingsMetrics.CardRadius),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.07f)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.settings_sound_title),
                            color = Color.White.copy(alpha = 0.78f),
                            fontFamily = SpaceGroteskFontFamily,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = SettingsType.Title,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.settings_sound_body),
                            color = Color.White.copy(alpha = 0.34f),
                            fontSize = SettingsType.Body,
                            lineHeight = 18.sp,
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Switch(
                        checked = soundEffectsEnabled,
                        onCheckedChange = onSoundEffectsChanged,
                    )
                }
            }

            Spacer(modifier = Modifier.height(SettingsMetrics.Space2))

            UiSectionHeader(text = stringResource(R.string.settings_app_features_section), icon = Icons.Default.Info)
            Surface(
                color = Color.White.copy(alpha = 0.03f),
                shape = RoundedCornerShape(SettingsMetrics.CardRadius),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.07f)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    TechRow(Icons.Default.CameraAlt, "Camera", stringResource(R.string.settings_feature_camera))
                    Spacer(modifier = Modifier.height(10.dp))
                    TechRow(Icons.Default.Visibility, "OCR", stringResource(R.string.settings_feature_ocr))
                    Spacer(modifier = Modifier.height(10.dp))
                    TechRow(Icons.Default.Storage, "History", stringResource(R.string.settings_feature_history))
                    Spacer(modifier = Modifier.height(10.dp))
                    TechRow(Icons.Default.Security, "Security", stringResource(R.string.settings_feature_security))
                    Spacer(modifier = Modifier.height(10.dp))
                    TechRow(Icons.Default.Memory, "Network", stringResource(R.string.settings_feature_network))
                }
            }

            Spacer(modifier = Modifier.height(SettingsMetrics.Space2))

            Surface(
                color = Color.White.copy(alpha = 0.035f),
                shape = RoundedCornerShape(SettingsMetrics.CardRadius),
                border = BorderStroke(1.dp, Emerald500.copy(alpha = 0.14f)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.settings_privacy_title),
                        color = Emerald400.copy(alpha = 0.82f),
                        fontFamily = SpaceGroteskFontFamily,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = SettingsType.Title,
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.settings_privacy_body),
                        color = Color.White.copy(alpha = 0.42f),
                        fontSize = SettingsType.Body,
                        lineHeight = 18.sp,
                    )
                }
            }

            Spacer(modifier = Modifier.height(SettingsMetrics.Space2))

            UiSectionHeader(
                text = stringResource(R.string.settings_disclaimer_link_title),
                icon = Icons.Default.Info,
            )
            SettingsActionCard(
                description = stringResource(R.string.settings_disclaimer_link_body),
                icon = Icons.Default.Info,
                onClick = onOpenDisclaimer,
            )

            Spacer(modifier = Modifier.height(SettingsMetrics.Space2))

            UiSectionHeader(
                text = stringResource(R.string.settings_repo_link_title),
                icon = Icons.AutoMirrored.Filled.OpenInNew,
            )
            SettingsActionCard(
                description = ZEST_REPOSITORY_URL,
                icon = Icons.AutoMirrored.Filled.OpenInNew,
                onClick = { uriHandler.openUri(ZEST_REPOSITORY_URL) },
            )

            Spacer(modifier = Modifier.height(SettingsMetrics.Space3))

            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                AppFooter()
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "${AppBrand.name} · v${BuildConfig.VERSION_NAME} · Kotlin · Jetpack Compose",
                    color = Color.White.copy(alpha = 0.12f),
                    fontSize = SettingsType.Meta,
                )
            }

            Spacer(modifier = Modifier.height(28.dp))
            Spacer(modifier = Modifier.navigationBarsPadding())
        }
    }
}

@Composable
private fun SettingsActionCard(
    description: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        color = Color.White.copy(alpha = 0.035f),
        shape = RoundedCornerShape(SettingsMetrics.CardRadius),
        border = BorderStroke(1.dp, Emerald500.copy(alpha = 0.14f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .background(Emerald500.copy(alpha = 0.10f), RoundedCornerShape(11.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = Emerald400.copy(alpha = 0.74f),
                    modifier = Modifier.size(17.dp),
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = description,
                    color = Color.White.copy(alpha = 0.56f),
                    fontSize = SettingsType.Body,
                    lineHeight = 18.sp,
                )
            }
        }
    }
}

@Composable
private fun SettingsHeader(onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(
                start = SettingsMetrics.ScreenPadding,
                top = SettingsMetrics.Space2,
                end = SettingsMetrics.ScreenPadding,
                bottom = SettingsMetrics.Grid,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            onClick = onBack,
            color = Color.Transparent,
            shape = CircleShape,
            modifier = Modifier.size(SettingsMetrics.HeaderIcon),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = Color.White.copy(alpha = 0.78f),
                    modifier = Modifier.size(24.dp),
                )
            }
        }

        Spacer(modifier = Modifier.width(SettingsMetrics.Space2))

        Text(
            text = stringResource(R.string.settings_title),
            color = Color.White.copy(alpha = 0.94f),
            fontFamily = SpaceGroteskFontFamily,
            fontSize = SettingsType.PageTitle,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = (-0.2).sp,
            modifier = Modifier.weight(1f),
        )

        Spacer(modifier = Modifier.size(SettingsMetrics.HeaderIcon))
    }
}

@Composable
private fun SecureApiKeyCard(
    hasKey: Boolean,
    storedDescription: String,
    emptyDescription: String,
    emptyLabel: String,
    replacementLabel: String,
    saveLabel: String,
    replaceLabel: String,
    deleteLabel: String,
    metadata: KeyMetadata?,
    onSave: suspend (String) -> KeySaveResult,
    onPing: (suspend (String?) -> KeySaveResult)?,
    onDelete: suspend () -> Boolean,
) {
    val scope = rememberCoroutineScope()
    var localKey by remember { mutableStateOf("") }
    var showKey by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var isSaving by remember { mutableStateOf(false) }
    var pingStatus by remember { mutableStateOf(PingStatus.Idle) }
    var pingMessage by remember { mutableStateOf<String?>(null) }
    val detectedMetadata = LlmProviderResolver.detectProvider(localKey)?.let { providerId ->
        LlmProviderResolver.defaultModelForProvider(providerId)?.let {
            KeyMetadata(
                modelName = it.modelName,
                provider = it.provider,
                acceptsImages = it.acceptsImages,
            )
        }
    }
    val displayMetadata = detectedMetadata ?: metadata

    suspend fun pingCurrentKey(key: String?): KeySaveResult? {
        if (onPing == null) return null
        pingStatus = PingStatus.Loading
        val result = onPing(key)
        pingStatus = if (result.success) PingStatus.Success else PingStatus.Failure
        pingMessage = result.message
        return result
    }

    LaunchedEffect(hasKey, localKey) {
        if (onPing == null) return@LaunchedEffect
        if (!hasKey && localKey.isBlank()) return@LaunchedEffect
        if (localKey.isNotBlank()) {
            delay(650)
            pingCurrentKey(localKey)
        } else {
            pingCurrentKey(null)
        }
    }

    Surface(
        color = Color.White.copy(alpha = 0.04f),
        shape = RoundedCornerShape(SettingsMetrics.CardRadius),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text(
                text = if (hasKey) storedDescription else emptyDescription,
                color = Color.White.copy(alpha = 0.45f),
                fontSize = SettingsType.Body,
                lineHeight = 19.sp,
            )
            Spacer(modifier = Modifier.height(14.dp))
            OutlinedTextField(
                value = localKey,
                onValueChange = {
                    localKey = it
                    statusMessage = null
                },
                modifier = Modifier.fillMaxWidth(),
                label = {
                    Text(
                        text = if (hasKey) replacementLabel else emptyLabel,
                        fontSize = SettingsType.Meta,
                    )
                },
                singleLine = true,
                visualTransformation = if (showKey) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    Row {
                        IconButton(onClick = { showKey = !showKey }) {
                            Icon(
                                imageVector = if (showKey) {
                                    Icons.Default.VisibilityOff
                                } else {
                                    Icons.Default.Visibility
                                },
                                contentDescription = "Toggle visibility",
                            )
                        }
                        if (localKey.isNotBlank()) {
                            IconButton(
                                onClick = {
                                    localKey = ""
                                    statusMessage = null
                                },
                            ) {
                                Icon(Icons.Default.Clear, contentDescription = "Clear typed key")
                            }
                        }
                    }
                },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Emerald500,
                    unfocusedBorderColor = Color.White.copy(alpha = 0.12f),
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White.copy(alpha = 0.84f),
                    focusedLabelColor = Emerald400,
                    unfocusedLabelColor = Color.White.copy(alpha = 0.5f),
                ),
            )
            Spacer(modifier = Modifier.height(14.dp))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = {
                        if (isSaving) return@Button
                        isSaving = true
                        scope.launch {
                            try {
                                val result = onSave(localKey)
                                if (result.success) {
                                    localKey = ""
                                    showKey = false
                                    pingStatus = PingStatus.Success
                                } else {
                                    pingStatus = PingStatus.Failure
                                }
                                statusMessage = result.message
                                pingMessage = result.message
                            } finally {
                                isSaving = false
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Emerald500),
                    shape = RoundedCornerShape(14.dp),
                    enabled = localKey.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (isSaving) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Color.Black, strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Default.Check, contentDescription = null, tint = Color.Black)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (hasKey) replaceLabel else saveLabel,
                            color = Color.Black,
                            fontFamily = SpaceGroteskFontFamily,
                            fontSize = SettingsType.Title,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
                if (onPing != null) {
                    Button(
                        onClick = {
                            if (isSaving) return@Button
                            isSaving = true
                            scope.launch {
                                try {
                                    val result = pingCurrentKey(localKey.takeIf { it.isNotBlank() })
                                    statusMessage = result?.message
                                } finally {
                                    isSaving = false
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.White.copy(alpha = 0.08f),
                            contentColor = Color.White.copy(alpha = 0.9f),
                        ),
                        shape = RoundedCornerShape(14.dp),
                        enabled = hasKey || localKey.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (isSaving) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Color.White.copy(alpha = 0.9f), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.NetworkCheck, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.settings_ping_api),
                                fontFamily = SpaceGroteskFontFamily,
                                fontSize = SettingsType.Title,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
                if (hasKey) {
                    Button(
                        onClick = {
                            if (isSaving) return@Button
                            isSaving = true
                            scope.launch {
                                try {
                                    val deleted = onDelete()
                                    if (deleted) {
                                        localKey = ""
                                        showKey = false
                                        statusMessage = "Saved key deleted."
                                    } else {
                                        statusMessage = "Could not delete key. Please try again."
                                    }
                                } finally {
                                    isSaving = false
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.White.copy(alpha = 0.08f),
                            contentColor = Color.White.copy(alpha = 0.78f),
                        ),
                        shape = RoundedCornerShape(14.dp),
                        enabled = true,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (isSaving) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Color.White.copy(alpha = 0.78f), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.Clear, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = deleteLabel,
                                fontFamily = SpaceGroteskFontFamily,
                                fontSize = SettingsType.Title,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
                Surface(
                    color = if (hasKey) Emerald500.copy(alpha = 0.12f) else Color.White.copy(alpha = 0.05f),
                    shape = RoundedCornerShape(999.dp),
                    border = BorderStroke(
                        1.dp,
                        if (hasKey) Emerald500.copy(alpha = 0.3f) else Color.White.copy(alpha = 0.1f),
                    ),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .background(
                                    if (hasKey) Emerald400 else Color.White.copy(alpha = 0.3f),
                                    CircleShape,
                                ),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (hasKey) {
                                stringResource(R.string.settings_key_stored)
                            } else {
                                stringResource(R.string.settings_key_not_stored)
                            },
                            color = if (hasKey) Emerald400.copy(alpha = 0.9f) else Color.White.copy(alpha = 0.4f),
                            fontFamily = SpaceGroteskFontFamily,
                            fontSize = SettingsType.Meta,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
                if (onPing != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = when (pingStatus) {
                                PingStatus.Success -> Icons.Default.Check
                                PingStatus.Failure -> Icons.Default.Clear
                                PingStatus.Loading -> Icons.Default.NetworkCheck
                                PingStatus.Idle -> Icons.Default.NetworkCheck
                            },
                            contentDescription = null,
                            tint = when (pingStatus) {
                                PingStatus.Success -> Emerald500
                                PingStatus.Failure -> Color(0xFFF87171)
                                PingStatus.Loading -> Color.White.copy(alpha = 0.45f)
                                PingStatus.Idle -> Color.White.copy(alpha = 0.25f)
                            },
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = when (pingStatus) {
                                PingStatus.Success -> pingMessage ?: "Auto-verified"
                                PingStatus.Failure -> pingMessage ?: "Key verification failed"
                                PingStatus.Loading -> "Verifying key..."
                                PingStatus.Idle -> "Auto-verification runs on save and when the key changes."
                            },
                            color = when (pingStatus) {
                                PingStatus.Success -> Emerald500
                                PingStatus.Failure -> Color(0xFFF87171)
                                PingStatus.Loading -> Color.White.copy(alpha = 0.38f)
                                PingStatus.Idle -> Color.White.copy(alpha = 0.24f)
                            },
                            fontSize = SettingsType.Meta,
                        )
                    }
                }
                displayMetadata?.let {
                    Surface(
                        color = Color.White.copy(alpha = 0.03f),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                            Text(
                                text = stringResource(R.string.settings_api_metadata),
                                color = Emerald400.copy(alpha = 0.72f),
                                fontFamily = SpaceGroteskFontFamily,
                                fontSize = SettingsType.Meta,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "Model: ${it.modelName}",
                                color = Color.White.copy(alpha = 0.72f),
                                fontSize = SettingsType.Body,
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "Provider: ${it.provider}",
                                color = Color.White.copy(alpha = 0.58f),
                                fontSize = SettingsType.Body,
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "Images sent to API: Never",
                                color = Color.White.copy(alpha = 0.58f),
                                fontSize = SettingsType.Body,
                            )
                        }
                    }
                }
            }
            statusMessage?.let { message ->
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = message,
                    color = Color.White.copy(alpha = 0.45f),
                    fontSize = SettingsType.Body,
                )
            }
        }
    }
}


@Composable
private fun TechRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    description: String,
) {
    Row(verticalAlignment = Alignment.Top) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .background(Emerald500.copy(alpha = 0.1f), RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = Emerald400.copy(alpha = 0.7f), modifier = Modifier.size(16.dp))
        }
        Spacer(modifier = Modifier.width(10.dp))
        Column {
            Text(
                text = title,
                color = Color.White.copy(alpha = 0.68f),
                fontFamily = SpaceGroteskFontFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = SettingsType.Title,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = description,
                color = Color.White.copy(alpha = 0.28f),
                fontSize = SettingsType.Body,
                lineHeight = 18.sp,
            )
        }
    }
}
