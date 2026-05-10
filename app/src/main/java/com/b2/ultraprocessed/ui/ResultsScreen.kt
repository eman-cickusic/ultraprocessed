package com.b2.ultraprocessed.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.b2.ultraprocessed.R
import com.b2.ultraprocessed.ui.theme.DarkBg
import com.b2.ultraprocessed.ui.theme.Amber400
import com.b2.ultraprocessed.ui.theme.Emerald500
import com.b2.ultraprocessed.ui.audio.AppSoundEvent
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun ResultsScreen(
    result: ScanResultUi,
    onScanAgain: () -> Unit,
    onOpenHistory: () -> Unit,
    chatEnabled: Boolean,
    onSoundEffect: (AppSoundEvent) -> Unit = {},
    onAskAboutResult: suspend (String, (String) -> Unit) -> Result<com.b2.ultraprocessed.network.llm.ResultChatReply>,
) {
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBg),
    ) {
        AppHeader(
            title = if (result.isBarcodeLookupOnly) {
                stringResource(R.string.results_title_product_found)
            } else {
                stringResource(R.string.results_title_analysis)
            },
            subtitle = if (result.isBarcodeLookupOnly) {
                stringResource(R.string.results_subtitle_usda_lookup)
            } else {
                stringResource(R.string.results_subtitle_nova_classification)
            },
            navigationAction = backHeaderAction(onScanAgain),
            actions = listOf(
                AppHeaderAction(
                    icon = Icons.Default.History,
                    contentDescription = "History",
                    onClick = onOpenHistory,
                    testTag = AppTestTags.HEADER_ACTION_HISTORY,
                ),
            ),
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(scrollState)
                .padding(horizontal = 24.dp),
        ) {
            if (result.isBarcodeLookupOnly) {
                BarcodeLookupResultBody(result = result)
            } else {
                FullAnalysisResultBody(
                    result = result,
                    chatEnabled = chatEnabled,
                    onSoundEffect = onSoundEffect,
                    onAskAboutResult = onAskAboutResult,
                )
            }

            Spacer(modifier = Modifier.height(36.dp))
        }
    }
}

@Composable
private fun BarcodeLookupResultBody(result: ScanResultUi) {
    Text(
        text = result.productName,
        color = Color.White.copy(alpha = 0.92f),
        fontSize = UiTextSizes.ScreenTitle,
        fontWeight = FontWeight.SemiBold,
        lineHeight = 22.sp,
    )
    Spacer(modifier = Modifier.height(10.dp))
    Surface(
        color = Color.White.copy(alpha = 0.04f),
        shape = RoundedCornerShape(18.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.06f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            UiMetaLine(
                label = stringResource(R.string.results_source_label),
                value = result.sourceLabel,
            )
            if (!result.brandOwner.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                UiMetaLine(
                    label = stringResource(R.string.results_brand_label),
                    value = result.brandOwner.orEmpty(),
                )
            }
            if (!result.scannedBarcode.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                UiMetaLine(
                    label = stringResource(R.string.results_barcode_label),
                    value = result.scannedBarcode.orEmpty(),
                )
            }
        }
    }
    Spacer(modifier = Modifier.height(20.dp))
    Text(
        text = result.summary,
        color = Color.White.copy(alpha = 0.55f),
        fontSize = UiTextSizes.Body,
        lineHeight = 20.sp,
    )
    Spacer(modifier = Modifier.height(16.dp))
    Text(
        text = stringResource(R.string.results_image_note),
        color = Color.White.copy(alpha = 0.34f),
        fontSize = UiTextSizes.Caption,
        lineHeight = 16.sp,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FullAnalysisResultBody(
    result: ScanResultUi,
    chatEnabled: Boolean,
    onSoundEffect: (AppSoundEvent) -> Unit,
    onAskAboutResult: suspend (String, (String) -> Unit) -> Result<com.b2.ultraprocessed.network.llm.ResultChatReply>,
) {
    val verdict = verdictColors(result.novaGroup)
    val headline = novaStaticComment(result.novaGroup, result.analyzedAtMillis)
    val ingredientItems = remember(result.ingredientAssessments) {
        result.ingredientAssessments
            .sortedWith(compareBy<IngredientBubbleUi> { it.novaGroup }.thenBy { it.name.lowercase() })
    }

    Text(
        text = analysisTitle(result),
        color = Color.White.copy(alpha = 0.92f),
        fontSize = UiTextSizes.ScreenTitle,
        fontWeight = FontWeight.SemiBold,
        lineHeight = 24.sp,
    )
    Spacer(modifier = Modifier.height(16.dp))
    ScannedLabelPhotoSection(imagePath = result.labelImagePath)

    Spacer(modifier = Modifier.height(16.dp))

    Surface(
        color = verdict.cardColor,
        shape = RoundedCornerShape(24.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, verdict.borderColor),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .background(verdict.pillColor, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = verdict.label.take(1),
                    color = verdict.textColor,
                    fontWeight = FontWeight.Bold,
                    fontSize = UiTextSizes.Body,
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            Text(
                text = "NOVA ${result.novaGroup}",
                color = verdict.textColor,
                fontSize = UiTextSizes.Display,
                fontWeight = FontWeight.ExtraBold,
            )
            Text(
                text = verdict.label,
                color = Color.White.copy(alpha = 0.58f),
                fontSize = UiTextSizes.BodySmall,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                lineHeight = 18.sp,
                modifier = Modifier.padding(top = 4.dp),
            )
            Text(
                text = headline,
                color = Color.White.copy(alpha = 0.72f),
                fontSize = UiTextSizes.BodySmall,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                lineHeight = 18.sp,
                modifier = Modifier.padding(top = 8.dp, start = 4.dp, end = 4.dp),
            )
        }
    }

    Spacer(modifier = Modifier.height(16.dp))

    AnalysisOneLinerBlock(summary = result.summary)

    Spacer(modifier = Modifier.height(16.dp))

    Surface(
        color = Color.White.copy(alpha = 0.04f),
        shape = RoundedCornerShape(18.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.06f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            UiSectionHeader(text = analysisTitle(result))
            Spacer(modifier = Modifier.height(10.dp))
            IngredientChips(items = ingredientItems)
        }
    }

    Spacer(modifier = Modifier.height(16.dp))

    if (result.allergens.isNotEmpty()) {
        AllergenSection(allergens = result.allergens)
        Spacer(modifier = Modifier.height(16.dp))
    }

    ResultChatSection(
        enabled = chatEnabled,
        onAskAboutResult = onAskAboutResult,
        result = result,
        onSoundEffect = onSoundEffect,
    )

    Spacer(modifier = Modifier.height(16.dp))

    Text(
        text = stringResource(R.string.results_footer_note),
        color = Color.White.copy(alpha = 0.34f),
        fontSize = UiTextSizes.Caption,
        lineHeight = 14.sp,
    )
}

@Composable
private fun AnalysisOneLinerBlock(summary: String) {
    Surface(
        color = Color.White.copy(alpha = 0.045f),
        shape = RoundedCornerShape(18.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.07f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = summary,
            color = Color.White.copy(alpha = 0.58f),
            fontSize = UiTextSizes.Caption,
            lineHeight = 14.sp,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
        )
    }
}

@Composable
private fun ScannedLabelPhotoSection(imagePath: String?) {
    if (imagePath.isNullOrBlank()) return
    val file = File(imagePath)
    if (!file.isFile || !file.canRead()) return

    val bitmap = remember(imagePath) { decodeSampledBitmap(imagePath, maxSidePx = 960) }
    if (bitmap == null) return

    UiSectionHeader(text = stringResource(R.string.results_scan_title))
    Spacer(modifier = Modifier.height(8.dp))
    Surface(
        color = Color.White.copy(alpha = 0.04f),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "Scanned label photo",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 240.dp)
                    .clip(RoundedCornerShape(12.dp)),
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.results_scan_note),
                color = Color.White.copy(alpha = 0.38f),
                fontSize = UiTextSizes.Caption,
                lineHeight = 13.sp,
            )
        }
    }
}

private fun decodeSampledBitmap(path: String, maxSidePx: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (bounds.outWidth / sample > maxSidePx || bounds.outHeight / sample > maxSidePx) {
        sample *= 2
    }
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    return BitmapFactory.decodeFile(path, opts)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun IngredientChips(
    items: List<IngredientBubbleUi>,
) {
    if (items.isEmpty()) {
        Text(
            text = stringResource(R.string.results_no_ingredient_nova),
            color = Color.White.copy(alpha = 0.38f),
            fontSize = UiTextSizes.Caption,
            lineHeight = 14.sp,
        )
        return
    }
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items.forEach { item ->
            IngredientBubble(
                item = item,
            )
        }
    }
}

@Composable
private fun IngredientBubble(
    item: IngredientBubbleUi,
) {
    val palette = ingredientPalette(item.novaGroup)
    Surface(
        color = palette.fill,
        shape = RoundedCornerShape(999.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, palette.border),
        modifier = Modifier
            .height(28.dp)
            .widthIn(max = 220.dp),
    ) {
        Box(
            modifier = Modifier
                .height(28.dp)
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = item.name.toChipLabel(),
                color = palette.text,
                fontSize = UiTextSizes.Chip,
                fontWeight = FontWeight.Medium,
                lineHeight = 12.sp,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 196.dp),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AllergenSection(allergens: List<String>) {
    Surface(
        color = Color(0x0E38BDF8),
        shape = RoundedCornerShape(18.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x2638BDF8)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            UiSectionHeader(
                text = stringResource(R.string.results_allergens_section),
                accentColor = Color(0xFF7DD3FC),
            )
            Text(
                text = stringResource(R.string.results_allergens_disclaimer),
                color = Color.White.copy(alpha = 0.36f),
                fontSize = UiTextSizes.Caption,
                lineHeight = 13.sp,
            )
            Spacer(modifier = Modifier.height(12.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                allergens.forEach { allergen ->
                    AllergenBubble(allergen)
                }
            }
        }
    }
}

@Composable
private fun AllergenBubble(label: String) {
    Surface(
        color = Color(0x1438BDF8),
        shape = RoundedCornerShape(999.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x2D38BDF8)),
        modifier = Modifier
            .height(28.dp)
            .widthIn(max = 180.dp),
    ) {
        Box(
            modifier = Modifier
                .height(28.dp)
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label.toChipLabel(),
                color = Color(0xFFBDE7FF),
                fontSize = UiTextSizes.Chip,
                fontWeight = FontWeight.Medium,
                lineHeight = 12.sp,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 156.dp),
            )
        }
    }
}

private fun String.toChipLabel(): String =
    trim()
        .trimEnd('.', ',', ';')
        .replace(Regex("\\s+"), " ")
        .split(" ")
        .joinToString(" ") { token -> token.toReadableToken() }

private fun String.toReadableToken(): String {
    if (length <= 4 && any(Char::isLetter) && all { it.isUpperCase() || !it.isLetter() }) {
        return this
    }
    val firstLetter = indexOfFirst(Char::isLetter)
    if (firstLetter == -1) return this
    val chars = lowercase().toCharArray()
    chars[firstLetter] = chars[firstLetter].uppercaseChar()
    return String(chars)
}

private fun novaStaticComment(novaGroup: Int, seed: Long): String {
    val options = when (novaGroup) {
        1 -> NOVA_1_COMMENTS
        2 -> NOVA_2_COMMENTS
        3 -> NOVA_3_COMMENTS
        else -> NOVA_4_COMMENTS
    }
    val index = (seed.takeIf { it != 0L } ?: novaGroup.toLong())
        .mod(options.size)
    return options[index]
}

private val NOVA_1_COMMENTS = listOf(
    "A clean-label direction with minimal processing signals.",
    "Closest to whole-food territory in the NOVA framework.",
    "A simpler processing profile than most packaged options.",
    "Minimal processing signals make this the lightest NOVA tier.",
    "This classification points toward basic, recognizable food handling.",
    "A lower-processing result with fewer industrial cues.",
    "The ingredient evidence fits a minimally processed pattern.",
    "A straightforward profile with limited formulation complexity.",
    "This sits near the fresh-and-simple end of the scale.",
    "Processing appears restrained by NOVA standards.",
    "A short path from source food to label.",
    "The label evidence stays close to minimally processed food.",
    "A calmer ingredient profile with limited industrial intervention.",
    "This result suggests simplicity is doing the heavy lifting.",
    "A favorable processing class for shoppers avoiding complexity.",
    "Minimal-processing evidence is the main story here.",
    "A practical green-light category from a processing lens.",
    "This reads more like food than formulation.",
    "The NOVA signal is comparatively gentle here.",
    "A simpler classification with fewer processing red flags.",
)

private val NOVA_2_COMMENTS = listOf(
    "A culinary ingredient classification, useful mainly in cooking.",
    "This belongs more in the pantry than the snack aisle.",
    "A cooking-helper profile rather than a complete food verdict.",
    "The NOVA signal points to extracted or prepared culinary basics.",
    "Best understood as an ingredient used to make other foods.",
    "This is processing for preparation, not necessarily formulation.",
    "A kitchen-building-block classification.",
    "The evidence fits seasoning, cooking, or preparation use.",
    "This category is about culinary function, not meal completeness.",
    "A pantry-style NOVA result with a narrow role.",
    "Think cooking input, not finished-product assessment.",
    "The processing signal is functional and culinary.",
    "This sits in the ingredient-support tier of NOVA.",
    "A simple culinary ingredient profile.",
    "Useful context: this class is usually consumed with other foods.",
    "This classification flags a cooking component.",
    "A refined or extracted ingredient, not a full food evaluation.",
    "The NOVA pattern suggests preparation utility.",
    "A category for things that help build meals.",
    "Culinary ingredient territory: context matters.",
)

private val NOVA_3_COMMENTS = listOf(
    "A processed food profile with recognizable ingredients.",
    "Processing is present, but not automatically ultra-processed.",
    "This sits in the middle: convenient, but worth comparing.",
    "A familiar processed-food classification.",
    "Recognizable ingredients with added processing steps.",
    "This result suggests preservation or preparation beyond basics.",
    "A moderate processing signal in NOVA terms.",
    "Not the simplest tier, not the most industrial tier.",
    "A reasonable place to compare labels side by side.",
    "The evidence points to processed, not necessarily ultra-processed.",
    "This category often rewards shorter ingredient lists.",
    "A processed result where formulation details still matter.",
    "A middle-tier classification with room for judgment.",
    "Processing is doing some work here.",
    "This lands in a practical watch-and-compare zone.",
    "A processed-food signal with recognizable structure.",
    "The label suggests added culinary ingredients or preservation.",
    "A moderate NOVA class: not a free pass, not a panic button.",
    "This is where ingredient-list length starts to matter.",
    "A balanced classification that benefits from label comparison.",
)

private val NOVA_4_COMMENTS = listOf(
    "A strong ultra-processing signal worth a closer look.",
    "This classification points to industrial formulation territory.",
    "A high-processing result for shoppers trying to reduce UPFs.",
    "The NOVA signal suggests more formulation than simple cooking.",
    "This is the class to compare carefully against simpler options.",
    "Ultra-processed territory: convenience may be doing the talking.",
    "A heavier processing profile by NOVA standards.",
    "This result suggests industrial ingredients or additive systems.",
    "A clear prompt to check for shorter-label alternatives.",
    "The processing signal is strong enough to slow down and compare.",
    "This sits at the most processed end of the NOVA scale.",
    "A formulation-heavy classification.",
    "This is where the label deserves a second read.",
    "A high-UPF signal without needing nutrition-panel gymnastics.",
    "The ingredient evidence points beyond ordinary kitchen processing.",
    "A strong candidate for swap-or-compare shopping.",
    "This classification suggests complexity is carrying the product.",
    "Ultra-processed by pattern, not by vibes.",
    "A red-tier processing result from the label evidence.",
    "This is the NOVA group most shoppers use as a caution flag.",
)

@Composable
private fun ResultChatSection(
    enabled: Boolean,
    result: ScanResultUi,
    onSoundEffect: (AppSoundEvent) -> Unit,
    onAskAboutResult: suspend (String, (String) -> Unit) -> Result<com.b2.ultraprocessed.network.llm.ResultChatReply>,
) {
    val scope = rememberCoroutineScope()
    val chatScrollState = rememberScrollState()
    var input by remember(result.productName, result.rawIngredientText, result.summary) { mutableStateOf("") }
    var isSending by remember(result.productName, result.rawIngredientText, result.summary) { mutableStateOf(false) }
    var statusMessage by remember(result.productName, result.rawIngredientText, result.summary) { mutableStateOf<String?>(null) }
    val checkingContextText = stringResource(R.string.results_chat_status_checking)
    val assistantUnavailableText = stringResource(R.string.results_chat_unavailable)
    val messages = remember(result.productName, result.rawIngredientText, result.summary) {
        mutableStateListOf<ResultChatMessageUi>()
    }
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            delay(60)
            chatScrollState.animateScrollTo(chatScrollState.maxValue)
        }
    }

    Surface(
        color = Color.White.copy(alpha = 0.04f),
        shape = RoundedCornerShape(18.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.06f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            UiSectionHeader(
                text = stringResource(R.string.results_chat_section),
            )

            if (messages.isNotEmpty()) {
                Surface(
                    color = Color.White.copy(alpha = 0.03f),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 156.dp),
                ) {
                    Column(
                        modifier = Modifier
                            .padding(10.dp)
                            .verticalScroll(chatScrollState)
                            .testTag(AppTestTags.RESULT_CHAT_MESSAGES),
                    ) {
                        messages.forEach { message ->
                            ChatMessageBubble(message = message)
                            Spacer(modifier = Modifier.height(10.dp))
                        }
                    }
                }
            }

            if (!statusMessage.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = statusMessage.orEmpty(),
                    color = Amber400.copy(alpha = 0.9f),
                    fontSize = UiTextSizes.Caption,
                    lineHeight = 14.sp,
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    color = Color.Transparent,
                    shape = RoundedCornerShape(999.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x4438BDF8)),
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag(AppTestTags.RESULT_CHAT_INPUT),
                ) {
                    Box(
                        modifier = Modifier.padding(horizontal = 18.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        BasicTextField(
                            value = input,
                            onValueChange = { input = it },
                            enabled = enabled && !isSending,
                            singleLine = true,
                            textStyle = androidx.compose.ui.text.TextStyle(
                                color = Color.White.copy(alpha = 0.84f),
                                fontSize = UiTextSizes.Caption,
                                lineHeight = 13.sp,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        if (input.isBlank()) {
                            Text(
                                text = if (enabled) {
                                    stringResource(R.string.results_chat_placeholder_enabled)
                                } else {
                                    stringResource(R.string.results_chat_placeholder_disabled)
                                },
                                color = Color.White.copy(alpha = 0.62f),
                                fontSize = UiTextSizes.Caption,
                                lineHeight = 13.sp,
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.width(8.dp))
                IconButton(
                    onClick = {
                        val question = input.trim()
                        if (!enabled || isSending || question.isBlank()) return@IconButton
                        onSoundEffect(AppSoundEvent.Click)
                        messages.add(
                            ResultChatMessageUi(
                                id = "u${messages.size}",
                                role = ResultChatRole.User,
                                text = question,
                            ),
                        )
                        input = ""
                        statusMessage = checkingContextText
                        isSending = true
                        scope.launch {
                            val reply = onAskAboutResult(question) { status ->
                                scope.launch {
                                    statusMessage = status
                                }
                            }
                            isSending = false
                            reply.onSuccess { resultReply ->
                                onSoundEffect(AppSoundEvent.Success)
                                messages.add(
                                    ResultChatMessageUi(
                                        id = "a${messages.size}",
                                        role = ResultChatRole.Assistant,
                                        text = resultReply.answer,
                                        allowed = resultReply.allowed,
                                    ),
                                )
                                statusMessage = null
                            }.onFailure { error ->
                                onSoundEffect(AppSoundEvent.Error)
                                messages.add(
                                    ResultChatMessageUi(
                                        id = "e${messages.size}",
                                        role = ResultChatRole.Assistant,
                                        text = error.message.orEmpty().ifBlank {
                                            assistantUnavailableText
                                        },
                                        allowed = false,
                                    ),
                                )
                                statusMessage = null
                            }
                        }
                    },
                    enabled = enabled && !isSending,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag(AppTestTags.RESULT_CHAT_SEND),
                ) {
                    if (isSending) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = Emerald500,
                        )
                    } else {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Send question",
                            tint = Emerald500,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatMessageBubble(message: ResultChatMessageUi) {
    val isUser = message.role == ResultChatRole.User
    val palette = if (isUser) {
        ChatPalette(
            fill = Color(0x1810B981),
            border = Color(0x4410B981),
            text = Color.White.copy(alpha = 0.88f),
        )
    } else if (message.allowed) {
        ChatPalette(
            fill = Color.White.copy(alpha = 0.06f),
            border = Color.White.copy(alpha = 0.09f),
            text = Color.White.copy(alpha = 0.82f),
        )
    } else {
        ChatPalette(
            fill = Color(0x16EF4444),
            border = Color(0x44EF4444),
            text = Color(0xFFFCA5A5),
        )
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            color = palette.fill,
            shape = RoundedCornerShape(16.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, palette.border),
            modifier = Modifier.widthIn(max = 280.dp),
        ) {
            Text(
                text = message.text,
                color = palette.text,
                fontSize = UiTextSizes.Caption,
                lineHeight = 13.sp,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}

private data class ChatPalette(
    val fill: Color,
    val border: Color,
    val text: Color,
)

private data class VerdictPalette(
    val label: String,
    val cardColor: Color,
    val borderColor: Color,
    val pillColor: Color,
    val textColor: Color,
)

private fun verdictColors(novaGroup: Int): VerdictPalette =
    when (novaGroup) {
        1 -> VerdictPalette(
            label = "Unprocessed",
            cardColor = Color(0x1622C55E),
            borderColor = Color(0x4422C55E),
            pillColor = Color(0x2422C55E),
            textColor = Color(0xFF4ADE80),
        )
        2 -> VerdictPalette(
            label = "Culinary Ingredient",
            cardColor = Color(0x14FACC15),
            borderColor = Color(0x44FACC15),
            pillColor = Color(0x22FACC15),
            textColor = Color(0xFFFEF08A),
        )
        3 -> VerdictPalette(
            label = "Processed",
            cardColor = Color(0x16FB923C),
            borderColor = Color(0x44FB923C),
            pillColor = Color(0x24FB923C),
            textColor = Color(0xFFFED7AA),
        )
        else -> VerdictPalette(
            label = "Ultra-Processed",
            cardColor = Color(0x16EF4444),
            borderColor = Color(0x44EF4444),
            pillColor = Color(0x24EF4444),
            textColor = Color(0xFFF87171),
        )
    }

private data class IngredientPalette(
    val fill: Color,
    val border: Color,
    val text: Color,
)

private fun ingredientPalette(novaGroup: Int): IngredientPalette =
    when (novaGroup) {
        1 -> IngredientPalette(
            fill = Color(0x1422C55E),
            border = Color(0x3322C55E),
            text = Color(0xFFD1FAE5),
        )
        2 -> IngredientPalette(
            fill = Color(0x14F59E0B),
            border = Color(0x33F59E0B),
            text = Color(0xFFFDE68A),
        )
        3 -> IngredientPalette(
            fill = Color(0x16FB923C),
            border = Color(0x44FB923C),
            text = Color(0xFFFED7AA),
        )
        else -> IngredientPalette(
            fill = Color(0x16EF4444),
            border = Color(0x44EF4444),
            text = Color(0xFFFCA5A5),
        )
    }

private fun analysisTitle(result: ScanResultUi): String =
    "Analysis - ${novaGroupHeading(result.novaGroup)} - ${formatAnalysisTimestamp(result.analyzedAtMillis)}"

private fun novaGroupHeading(novaGroup: Int): String =
    when (novaGroup) {
        1 -> "Unprocessed"
        2 -> "Culinary Ingredient"
        3 -> "Processed"
        4 -> "Ultra-Processed"
        else -> "NOVA $novaGroup"
    }

private fun formatAnalysisTimestamp(timestampMillis: Long): String =
    SimpleDateFormat("MMM d, h:mm a", Locale.US).format(Date(timestampMillis))
