package com.custom.hyperisland.model

import android.app.PendingIntent
import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color

enum class CoexistenceMode {
    STACK_BELOW,
    AURA_RING,
    YIELD_TO_NATIVE,
    OVERRIDE_ALL
}

enum class CutoutRingStyle {
    NONE,
    BATTERY_ARC,
    AUDIO_SPECTRUM,
    NOTIFICATION_LED
}

enum class IslandGestureAction {
    EXPAND_COLLAPSE,
    OPEN_FREEFORM_WINDOW,
    MEDIA_PLAY_PAUSE,
    MEDIA_NEXT,
    MEDIA_PREV,
    DISMISS_CURRENT,
    TOGGLE_FLASHLIGHT
}

data class IslandCustomizationConfig(
    val cutoutOffsetX: Float = 0f,
    val cutoutOffsetY: Float = 0f,
    val cutoutRadiusPx: Float = 38f,
    val autoDetectCutout: Boolean = true,
    val collapsedWidthDp: Float = 126f,
    val collapsedHeightDp: Float = 36f,
    val expandedWidthDp: Float = 364f,
    val expandedHeightDp: Float = 172f,
    val topCornerRadiusDp: Float = 28f,
    val bottomCornerRadiusDp: Float = 34f,
    val springStiffness: Float = 380f,
    val springDampingRatio: Float = 0.62f,
    val enableMetaballLiquid: Boolean = true,
    val metaballThreshold: Float = 0.45f,
    val glassBlurRadiusDp: Float = 20f,
    val cutoutRingStyle: CutoutRingStyle = CutoutRingStyle.BATTERY_ARC,
    val adaptivePaletteAccent: Boolean = true,
    val customAccentColor: Color = Color(0xFF00E5FF),
    val coexistenceMode: CoexistenceMode = CoexistenceMode.STACK_BELOW,
    val nativeStackOffsetYDp: Float = 46f,
    val onSingleTap: IslandGestureAction = IslandGestureAction.EXPAND_COLLAPSE,
    val onDoubleTap: IslandGestureAction = IslandGestureAction.MEDIA_PLAY_PAUSE,
    val onLongPress: IslandGestureAction = IslandGestureAction.OPEN_FREEFORM_WINDOW,
    val onSwipeLeft: IslandGestureAction = IslandGestureAction.MEDIA_NEXT,
    val onSwipeRight: IslandGestureAction = IslandGestureAction.MEDIA_PREV,
    val onSwipeDown: IslandGestureAction = IslandGestureAction.OPEN_FREEFORM_WINDOW
)

sealed interface IslandDisplayMode {
    data object IdleCutout : IslandDisplayMode
    data class CompactPill(val hasDetachedDroplet: Boolean = false) : IslandDisplayMode
    data object ExpandedCard : IslandDisplayMode
    data object AuraWrap : IslandDisplayMode
}

data class MediaSlice(
    val packageName: String,
    val title: String,
    val artist: String,
    val albumArt: Bitmap?,
    val isPlaying: Boolean,
    val progressRatio: Float,
    val dominantColor: Color = Color(0xFF7C4DFF)
)

data class NotificationSlice(
    val key: String,
    val packageName: String,
    val senderOrTitle: String,
    val message: String,
    val contentIntent: PendingIntent?,
    val actions: List<Pair<String, PendingIntent>>,
    val isHyperOsNativeFocus: Boolean,
    val timestamp: Long = System.currentTimeMillis()
)

data class IslandUiState(
    val mode: IslandDisplayMode = IslandDisplayMode.IdleCutout,
    val isHyperOsNativeActive: Boolean = false,
    val media: MediaSlice? = null,
    val activeNotification: NotificationSlice? = null,
    val batteryLevel: Int = 100,
    val isCharging: Boolean = false,
    val config: IslandCustomizationConfig = IslandCustomizationConfig()
)
