package com.vpngate.client.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.vpngate.client.R

// Load Outfit geometric font family from local resources
val OutfitFontFamily = FontFamily(
    Font(R.font.outfit_regular, FontWeight.Normal),
    Font(R.font.outfit_medium, FontWeight.Medium),
    Font(R.font.outfit_bold, FontWeight.Bold)
)

// Default Material3 Typography definitions
private val defaultTypography = Typography()

// Apply Outfit font globally to all 15 typography levels in Material You
val Typography = Typography(
    displayLarge = defaultTypography.displayLarge.copy(fontFamily = OutfitFontFamily),
    displayMedium = defaultTypography.displayMedium.copy(fontFamily = OutfitFontFamily),
    displaySmall = defaultTypography.displaySmall.copy(fontFamily = OutfitFontFamily),
    
    headlineLarge = defaultTypography.headlineLarge.copy(fontFamily = OutfitFontFamily),
    headlineMedium = defaultTypography.headlineMedium.copy(fontFamily = OutfitFontFamily),
    headlineSmall = defaultTypography.headlineSmall.copy(fontFamily = OutfitFontFamily),
    
    titleLarge = defaultTypography.titleLarge.copy(fontFamily = OutfitFontFamily),
    titleMedium = defaultTypography.titleMedium.copy(fontFamily = OutfitFontFamily),
    titleSmall = defaultTypography.titleSmall.copy(fontFamily = OutfitFontFamily),
    
    bodyLarge = defaultTypography.bodyLarge.copy(fontFamily = OutfitFontFamily),
    bodyMedium = defaultTypography.bodyMedium.copy(fontFamily = OutfitFontFamily),
    bodySmall = defaultTypography.bodySmall.copy(fontFamily = OutfitFontFamily),
    
    labelLarge = defaultTypography.labelLarge.copy(fontFamily = OutfitFontFamily),
    labelMedium = defaultTypography.labelMedium.copy(fontFamily = OutfitFontFamily),
    labelSmall = defaultTypography.labelSmall.copy(fontFamily = OutfitFontFamily)
)
