package com.b2.ultraprocessed.ui

import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

object UiTextSizes {
    // 1.25 type scale anchored on a compact 10.24sp caption.
    val Micro: TextUnit = 8.2.sp
    val Caption: TextUnit = 10.24.sp
    val BodySmall: TextUnit = 12.8.sp
    val Body: TextUnit = 16.sp
    val ScreenTitle: TextUnit = 20.sp
    val Display: TextUnit = 25.sp
    val HeroValue: TextUnit = 31.25.sp

    val ScreenSubtitle: TextUnit = BodySmall
    val SectionHeader: TextUnit = BodySmall
    val Chip: TextUnit = Caption
    val MetricValue: TextUnit = ScreenTitle
}
