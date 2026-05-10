package com.b2.ultraprocessed.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.b2.ultraprocessed.ui.theme.Emerald400
import com.b2.ultraprocessed.ui.theme.Emerald500
import com.b2.ultraprocessed.ui.theme.SpaceGroteskFontFamily

private val UiSectionShape = RoundedCornerShape(18.dp)

@Composable
fun UiSectionHeader(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    accentColor: Color = Emerald400,
) {
    Row(
        modifier = modifier.padding(bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Box(
                modifier = Modifier
                    .size(18.dp)
                    .background(accentColor.copy(alpha = 0.14f), RoundedCornerShape(6.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = accentColor,
                    modifier = Modifier.size(11.dp),
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
        }
        Text(
            text = text,
            color = Color.White.copy(alpha = 0.62f),
            fontFamily = SpaceGroteskFontFamily,
            fontSize = UiTextSizes.SectionHeader,
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.4.sp,
        )
    }
}

@Composable
fun UiInfoCard(
    title: String,
    body: String,
    accentColor: Color = Emerald500,
    modifier: Modifier = Modifier,
    chipLabels: List<String> = emptyList(),
) {
    Surface(
        color = accentColor.copy(alpha = 0.05f),
        shape = UiSectionShape,
        border = BorderStroke(1.dp, accentColor.copy(alpha = 0.12f)),
        modifier = modifier,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                color = accentColor.copy(alpha = 0.72f),
                fontFamily = SpaceGroteskFontFamily,
                fontSize = UiTextSizes.BodySmall,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = body,
                color = Color.White.copy(alpha = 0.34f),
                fontSize = UiTextSizes.BodySmall,
                lineHeight = 16.sp,
            )
            if (chipLabels.isNotEmpty()) {
                Spacer(modifier = Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    chipLabels.forEach { label ->
                        Surface(
                            color = Color.White.copy(alpha = 0.06f),
                            shape = RoundedCornerShape(999.dp),
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
                        ) {
                            Text(
                                text = label,
                                color = Color.White.copy(alpha = 0.8f),
                                fontSize = UiTextSizes.Chip,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun UiMetaLine(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "$label ",
            color = Color.White.copy(alpha = 0.42f),
            fontSize = UiTextSizes.BodySmall,
        )
        Text(
            text = value,
            color = Color.White.copy(alpha = 0.72f),
            fontSize = UiTextSizes.BodySmall,
        )
    }
}

@Composable
fun UiMetricPill(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = Color.White.copy(alpha = 0.03f),
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.06f)),
        modifier = modifier,
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text(
                text = label,
                color = Color.White.copy(alpha = 0.34f),
                fontSize = UiTextSizes.Caption,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = value,
                color = Color.White.copy(alpha = 0.92f),
                fontFamily = SpaceGroteskFontFamily,
                fontSize = UiTextSizes.MetricValue,
            )
        }
    }
}
