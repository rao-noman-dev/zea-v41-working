package com.raomuhammadnoman.zea

import androidx.compose.ui.graphics.Color

/**
 * Every literal colour the app draws with. The first group is copied verbatim
 * from the existing home screen so nothing there shifts; the rest are new and
 * used only by the Apps management screens.
 */
object ZeaColors {
    val ResultCardBackground = Color(0xFFE9ECFF)
    val ResultCardBorder = Color(0xFFCCD3FF)
    val ResultTitle = Color(0xFF1F2430)
    val BodyText = Color(0xFF374151)
    val SecondaryText = Color(0xFF6B7280)

    val BannerSuccessBackground = Color(0xFFE8F5E9)
    val BannerSuccessText = Color(0xFF1B5E20)
    val BannerWarningBackground = Color(0xFFFFF8E1)
    val BannerWarningText = Color(0xFF7A4F01)
    val BannerErrorBackground = Color(0xFFFFEBEE)
    val BannerErrorText = Color(0xFFB71C1C)
    val BannerInfoBackground = Color(0xFFE3F2FD)
    val BannerInfoText = Color(0xFF0D47A1)
    val InlineError = Color(0xFFD32F2F)

    val CardBackground = Color(0xFFF7F2FB)
    val CardBorder = Color(0xFFE7E0EC)
    val IconContainer = Color(0xFFEADDFF)
    val InfoBannerBackground = Color(0xFFEDE7F6)
    val BadgeBackground = Color(0xFFE8DEF8)

    val StatusVisible = Color(0xFF2E7D32)
    val StatusHidden = Color(0xFFC62828)
    val StatusTimed = Color(0xFF6650A4)
    val StatusBlocked = Color(0xFF8A8A8E)
}
