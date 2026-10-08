package org.linuxdo.android.ui.design

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * iOS Text Styles 的字号与行高。SF Pro 不能打包(授权),用系统 sans-serif 近似字形,
 * 但字号、字重、行高严格照 iOS,这几项对观感的影响比字形本身更大。
 */
data class IosTypography(
    val largeTitle: TextStyle,
    val title3: TextStyle,
    val headline: TextStyle,
    val body: TextStyle,
    val subheadline: TextStyle,
    val footnote: TextStyle,
    val caption: TextStyle,
    val navTitle: TextStyle,
    val tabLabel: TextStyle,
    val code: TextStyle,
)

val DefaultIosTypography = IosTypography(
    largeTitle = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 34.sp,
        lineHeight = 41.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.37.sp,
    ),
    title3 = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 20.sp,
        lineHeight = 25.sp,
        fontWeight = FontWeight.SemiBold,
    ),
    headline = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 17.sp,
        lineHeight = 22.sp,
        fontWeight = FontWeight.SemiBold,
    ),
    body = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 17.sp,
        lineHeight = 22.sp,
        fontWeight = FontWeight.Normal,
    ),
    subheadline = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 15.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.Normal,
    ),
    footnote = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        fontWeight = FontWeight.Normal,
    ),
    caption = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        fontWeight = FontWeight.Normal,
    ),
    navTitle = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 17.sp,
        lineHeight = 22.sp,
        fontWeight = FontWeight.SemiBold,
    ),
    tabLabel = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 10.sp,
        lineHeight = 12.sp,
        fontWeight = FontWeight.Medium,
    ),
    code = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.Normal,
    ),
)
