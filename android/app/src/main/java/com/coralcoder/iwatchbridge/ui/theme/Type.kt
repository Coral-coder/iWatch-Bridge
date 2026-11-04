package com.coralcoder.iwatchbridge.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** iOS Dynamic Type sizes at the default ("Large") setting. */
val IosTypography = Typography(
    displaySmall = TextStyle(fontSize = 34.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.37.sp), // Large Title
    headlineSmall = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.Bold),                          // Title 1
    titleLarge = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold),                             // Title 2
    titleMedium = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.41).sp), // Headline
    bodyLarge = TextStyle(fontSize = 17.sp, letterSpacing = (-0.41).sp),                                // Body
    bodyMedium = TextStyle(fontSize = 15.sp, letterSpacing = (-0.24).sp),                               // Subheadline
    labelLarge = TextStyle(fontSize = 17.sp, letterSpacing = (-0.41).sp),
    labelMedium = TextStyle(fontSize = 13.sp, letterSpacing = (-0.08).sp),                              // Footnote
    labelSmall = TextStyle(fontSize = 12.sp),                                                           // Caption 1
)
