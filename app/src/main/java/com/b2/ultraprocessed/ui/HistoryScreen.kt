package com.b2.ultraprocessed.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.b2.ultraprocessed.R
import com.b2.ultraprocessed.ui.theme.DarkBg
import com.b2.ultraprocessed.ui.theme.Emerald400
import com.b2.ultraprocessed.ui.theme.SpaceGroteskFontFamily
import com.b2.ultraprocessed.ui.theme.SkyBlue400
import java.util.Locale
import kotlin.math.roundToInt

private object HistoryMetrics {
    val Grid = 8.dp
    val Space2 = 16.dp
    val Space3 = 24.dp
    val ScreenPadding = 24.dp
    val HeaderIcon = 40.dp
    val CardRadius = 18.dp
    val CardMinHeight = 76.dp
    val StatusIconBox = 40.dp
    val MetricHeight = 48.dp
}

private object HistoryType {
    val PageTitle = UiTextSizes.ScreenTitle
    val MetricValue = UiTextSizes.Body
    val MetricLabel = UiTextSizes.Micro
    val Notice = UiTextSizes.Caption
    val CardTitle = UiTextSizes.BodySmall
    val CardMeta = UiTextSizes.Caption
    val CardUsage = UiTextSizes.Micro
    val Status = UiTextSizes.Caption
}

@Composable
fun HistoryScreen(
    historyItems: List<HistoryItemUi>,
    historySummary: HistoryUsageSummaryUi,
    onBack: () -> Unit,
    onClearAll: () -> Unit,
    @Suppress("UNUSED_PARAMETER")
    onClearItem: (HistoryItemUi) -> Unit,
    onRerunItem: (HistoryItemUi) -> Unit,
) {
    var pendingRerunItem by remember { mutableStateOf<HistoryItemUi?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBg),
    ) {
        HistoryHeader(
            hasHistory = historyItems.isNotEmpty(),
            onBack = onBack,
            onClearAll = onClearAll,
        )

        Spacer(modifier = Modifier.height(HistoryMetrics.Grid))

        UsageSummaryStrip(summary = historySummary)

        LocalStorageNotice()

        if (historyItems.isEmpty()) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = HistoryMetrics.ScreenPadding)
                    .padding(top = HistoryMetrics.Space3),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                EmptyHistoryState()
                AppFooter(
                    modifier = Modifier
                        .navigationBarsPadding()
                        .padding(bottom = HistoryMetrics.Space2),
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(
                    horizontal = HistoryMetrics.ScreenPadding,
                    vertical = HistoryMetrics.Grid,
                ),
                verticalArrangement = Arrangement.spacedBy(HistoryMetrics.Space2),
            ) {
                items(historyItems, key = { it.id }) { item ->
                    HistoryVerdictCard(
                        item = item,
                        onRerunClick = { pendingRerunItem = item },
                    )
                }
                item {
                    AppFooter(
                        modifier = Modifier
                            .padding(top = HistoryMetrics.Space2)
                            .navigationBarsPadding()
                            .padding(bottom = HistoryMetrics.Space2),
                    )
                }
            }
        }
    }

    val rerunItem = pendingRerunItem
    if (rerunItem != null) {
        AlertDialog(
            onDismissRequest = { pendingRerunItem = null },
            containerColor = DarkBg,
            title = {
                Text(
                    text = "Rerun this scan?",
                    color = Color.White.copy(alpha = 0.92f),
                    fontFamily = SpaceGroteskFontFamily,
                    fontWeight = FontWeight.SemiBold,
                )
            },
            text = {
                Text(
                    text = "${historyAnalysisTitle(rerunItem)} will be preprocessed and analyzed again.",
                    color = Color.White.copy(alpha = 0.64f),
                    fontSize = HistoryType.CardMeta,
                    lineHeight = 16.sp,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingRerunItem = null
                        onRerunItem(rerunItem)
                    },
                ) {
                    Text("Rerun", color = Emerald400, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingRerunItem = null }) {
                    Text("Cancel", color = Color.White.copy(alpha = 0.64f))
                }
            },
        )
    }
}

@Composable
private fun EmptyHistoryState() {
    Surface(
        color = Color.White.copy(alpha = 0.025f),
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.06f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = HistoryMetrics.Space3, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .background(Emerald400.copy(alpha = 0.10f), RoundedCornerShape(18.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.History,
                    contentDescription = null,
                    tint = Emerald400.copy(alpha = 0.78f),
                    modifier = Modifier.size(26.dp),
                )
            }
            Spacer(modifier = Modifier.height(HistoryMetrics.Space2))
            Text(
                text = stringResource(R.string.history_empty_title),
                color = Color.White.copy(alpha = 0.82f),
                fontFamily = SpaceGroteskFontFamily,
                fontSize = UiTextSizes.Body,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(HistoryMetrics.Grid))
            Text(
                text = stringResource(R.string.history_empty_body),
                color = Color.White.copy(alpha = 0.42f),
                fontSize = UiTextSizes.BodySmall,
                lineHeight = 18.sp,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun HistoryHeader(
    hasHistory: Boolean,
    onBack: () -> Unit,
    onClearAll: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(
                start = HistoryMetrics.Space2,
                top = HistoryMetrics.Space2,
                end = HistoryMetrics.Space2,
                bottom = HistoryMetrics.Grid,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            onClick = onBack,
            color = Color.Transparent,
            shape = CircleShape,
            modifier = Modifier.size(HistoryMetrics.HeaderIcon),
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

        Spacer(modifier = Modifier.width(HistoryMetrics.Space2))

        Text(
            text = stringResource(R.string.history_title),
            color = Color.White.copy(alpha = 0.94f),
            fontFamily = SpaceGroteskFontFamily,
            fontSize = HistoryType.PageTitle,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = (-0.2).sp,
            modifier = Modifier.weight(1f),
        )

        Surface(
            onClick = {
                if (hasHistory) onClearAll()
            },
            color = Color.Transparent,
            shape = CircleShape,
            modifier = Modifier.size(HistoryMetrics.HeaderIcon),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Default.DeleteOutline,
                    contentDescription = "Clear history",
                    tint = Color.White.copy(alpha = if (hasHistory) 0.54f else 0.20f),
                    modifier = Modifier.size(24.dp),
                )
            }
        }
    }
}

@Composable
private fun UsageSummaryStrip(summary: HistoryUsageSummaryUi) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = HistoryMetrics.ScreenPadding),
        horizontalArrangement = Arrangement.spacedBy(HistoryMetrics.Grid),
    ) {
        UsageMiniPill(
            label = stringResource(R.string.history_tokens_label),
            value = formatTokens(summary.totalTokens),
            modifier = Modifier.weight(1f),
        )
        UsageMiniPill(
            label = stringResource(R.string.history_estimated_cost_label),
            value = formatMoney(summary.estimatedCostUsd),
            modifier = Modifier.weight(1f),
        )
        UsageMiniPill(
            label = stringResource(R.string.history_scans_label),
            value = summary.totalScans.toString(),
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun UsageMiniPill(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = Color.White.copy(alpha = 0.035f),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.07f)),
        modifier = modifier.height(HistoryMetrics.MetricHeight),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = HistoryMetrics.Grid, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = value,
                color = Color.White.copy(alpha = 0.82f),
                fontFamily = SpaceGroteskFontFamily,
                fontSize = HistoryType.MetricValue,
                fontWeight = FontWeight.Medium,
                lineHeight = 18.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = label,
                color = Color.White.copy(alpha = 0.34f),
                fontSize = HistoryType.MetricLabel,
                lineHeight = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun LocalStorageNotice() {
    Surface(
        color = Color.White.copy(alpha = 0.035f),
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = HistoryMetrics.ScreenPadding)
            .padding(top = HistoryMetrics.Space2, bottom = HistoryMetrics.Grid),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = HistoryMetrics.Space2, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.AccessTime,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.28f),
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(HistoryMetrics.Grid))
            Text(
                text = stringResource(R.string.history_local_only_notice),
                color = Color.White.copy(alpha = 0.34f),
                fontSize = HistoryType.Notice,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun HistoryVerdictCard(
    item: HistoryItemUi,
    onRerunClick: () -> Unit,
) {
    val style = historyVerdictStyle(item)
    Surface(
        color = style.color.copy(alpha = 0.11f),
        shape = RoundedCornerShape(HistoryMetrics.CardRadius),
        border = BorderStroke(1.dp, style.color.copy(alpha = 0.28f)),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = HistoryMetrics.CardMinHeight),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = HistoryMetrics.Space2, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(HistoryMetrics.StatusIconBox)
                    .background(style.color.copy(alpha = 0.14f), RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = style.icon,
                    contentDescription = null,
                    tint = style.color,
                    modifier = Modifier.size(20.dp),
                )
            }
            if (item.isFailed) {
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Failed",
                    color = style.color,
                    fontSize = HistoryType.CardUsage,
                    fontWeight = FontWeight.Bold,
                    lineHeight = 10.sp,
                )
            }

            Spacer(modifier = Modifier.width(HistoryMetrics.Space2))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = historyAnalysisTitle(item),
                    color = Color.White.copy(alpha = 0.80f),
                    fontFamily = SpaceGroteskFontFamily,
                    fontSize = HistoryType.CardTitle,
                    fontWeight = FontWeight.SemiBold,
                    lineHeight = 16.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(HistoryMetrics.Grid / 2))
                Text(
                    text = if (item.isBarcodeLookupOnly) {
                        "Barcode lookup · ${relativeScanTime(item)}"
                    } else if (item.isFailed) {
                        "Failed · ${relativeScanTime(item)}"
                    } else {
                        "${displayProductName(item.productName.ifBlank { stringResource(R.string.results_scan_title) })} · ${relativeScanTime(item)}"
                    },
                    color = Color.White.copy(alpha = 0.34f),
                    fontSize = HistoryType.CardMeta,
                    fontWeight = FontWeight.Medium,
                    lineHeight = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val usageLine = historyUsageLine(item)
                if (usageLine.isNotBlank()) {
                    Spacer(modifier = Modifier.height(HistoryMetrics.Grid / 2))
                    Text(
                        text = usageLine,
                        color = Color.White.copy(alpha = 0.26f),
                        fontSize = HistoryType.CardUsage,
                        lineHeight = 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Spacer(modifier = Modifier.width(HistoryMetrics.Space2))

            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = style.label,
                    color = style.color,
                    fontFamily = SpaceGroteskFontFamily,
                    fontSize = HistoryType.Status,
                    fontWeight = FontWeight.Bold,
                    lineHeight = 12.sp,
                    letterSpacing = 1.4.sp,
                )
                if (!item.capturedImagePath.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(HistoryMetrics.Grid / 2))
                    IconButton(
                        onClick = onRerunClick,
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Rerun scan",
                            tint = Color.White.copy(alpha = 0.68f),
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }
    }
}

private fun historyUsageLine(item: HistoryItemUi): String =
    if (item.isFailed) {
        item.failureMessage.take(72)
    } else {
        listOfNotNull(
            item.modelName.takeIf { it.isNotBlank() },
            if (item.estimatedTokens > 0) "${formatTokens(item.estimatedTokens)} tokens" else null,
            if (item.estimatedCostUsd > 0.0) formatMoney(item.estimatedCostUsd) else null,
        ).joinToString(" · ")
    }

private fun historyAnalysisTitle(item: HistoryItemUi): String =
    "Analysis - ${historyGroupHeading(item)} - ${item.scannedAt}"

private fun historyGroupHeading(item: HistoryItemUi): String =
    when {
        item.isFailed -> "Failed"
        item.isBarcodeLookupOnly -> "Barcode Lookup"
        item.novaGroup == 1 -> "Unprocessed"
        item.novaGroup == 2 -> "Culinary Ingredient"
        item.novaGroup == 3 -> "Processed"
        item.novaGroup == 4 -> "Ultra-Processed"
        else -> "NOVA ${item.novaGroup}"
    }

private fun displayProductName(name: String): String {
    val compact = name.trim().replace(Regex("\\s+"), " ")
    if (compact.isBlank()) return compact
    val letterCount = compact.count { it.isLetter() }
    val uppercaseCount = compact.count { it.isLetter() && it.isUpperCase() }
    if (letterCount == 0 || uppercaseCount < letterCount * 0.70f) return compact
    return compact
        .lowercase(Locale.US)
        .split(" ")
        .joinToString(" ") { word ->
            word.replaceFirstChar { char ->
                if (char.isLowerCase()) char.titlecase(Locale.US) else char.toString()
            }
        }
}

@Composable
private fun historyVerdictStyle(item: HistoryItemUi): HistoryVerdictStyle =
    when {
        item.isFailed -> HistoryVerdictStyle("FAILED", Color(0xFFFF6B6B), Icons.Default.Cancel)
        item.isBarcodeLookupOnly -> HistoryVerdictStyle("LOOKUP", SkyBlue400, Icons.Default.QrCode)
        item.novaGroup == 1 -> HistoryVerdictStyle("UNPROCESSED", Emerald400, Icons.Default.CheckCircle)
        item.novaGroup == 2 -> HistoryVerdictStyle("CULINARY", Color(0xFFFEF08A), Icons.Default.Warning)
        item.novaGroup == 3 -> HistoryVerdictStyle("PROCESSED", Color(0xFFFB923C), Icons.Default.Warning)
        else -> HistoryVerdictStyle("AVOID", Color(0xFFFF6B6B), Icons.Default.Cancel)
    }

private data class HistoryVerdictStyle(
    val label: String,
    val color: Color,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
)

private fun relativeScanTime(item: HistoryItemUi): String {
    val timestamp = item.scannedAtMillis
    if (timestamp <= 0L) return item.scannedAt
    val elapsedMillis = (System.currentTimeMillis() - timestamp).coerceAtLeast(0L)
    val elapsedMinutes = (elapsedMillis / 60_000L).toInt()
    return when {
        elapsedMinutes < 1 -> "just now"
        elapsedMinutes < 60 -> "$elapsedMinutes min ago"
        elapsedMinutes < 60 * 24 -> "${elapsedMinutes / 60} hr ago"
        elapsedMinutes < 60 * 24 * 7 -> "${elapsedMinutes / (60 * 24)} days ago"
        else -> item.scannedAt
    }
}

private fun formatTokens(tokens: Int): String {
    val safeTokens = tokens.coerceAtLeast(0)
    return if (safeTokens >= 10_000) {
        String.format(Locale.US, "%.1fk", safeTokens / 1_000.0)
    } else {
        String.format(Locale.US, "%,d", safeTokens)
    }
}

private fun formatMoney(value: Double): String {
    val safeValue = value.coerceAtLeast(0.0)
    return if (safeValue > 0.0 && safeValue < 0.0001) {
        "\$<0.0001"
    } else {
        val roundedCents = (safeValue * 100.0).roundToInt()
        if (roundedCents >= 1) {
            String.format(Locale.US, "\$%.2f", safeValue)
        } else {
            String.format(Locale.US, "\$%.4f", safeValue)
        }
    }
}
