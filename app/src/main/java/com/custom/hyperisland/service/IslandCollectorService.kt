package com.custom.hyperisland.service

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

object IslandStateRepository {
    private val _state = MutableStateFlow(com.custom.hyperisland.model.IslandUiState())
    val state: StateFlow<com.custom.hyperisland.model.IslandUiState> = _state.asStateFlow()

    fun updateState(reducer: (com.custom.hyperisland.model.IslandUiState) -> com.custom.hyperisland.model.IslandUiState) {
        _state.update(reducer)
    }
}

class IslandCollectorService : NotificationListenerService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var mediaSessionManager: MediaSessionManager
    private var activeController: MediaController? = null

    private val hyperOsNativePackages = setOf(
        "com.android.systemui",
        "com.miui.securitycenter",
        "com.android.deskclock",
        "com.android.soundrecorder",
        "com.miui.screenrecorder"
    )

    private val sessionListener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
        bindToPrimaryController(controllers)
    }

    private val mediaCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) {
            syncMediaSlice()
        }
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            syncMediaSlice()
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        mediaSessionManager = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        val componentName = ComponentName(this, IslandCollectorService::class.java)
        try {
            mediaSessionManager.addOnActiveSessionsChangedListener(sessionListener, componentName)
            bindToPrimaryController(mediaSessionManager.getActiveSessions(componentName))
        } catch (_: SecurityException) {}
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        try {
            mediaSessionManager.removeOnActiveSessionsChangedListener(sessionListener)
        } catch (_: Exception) {}
    }

    private fun bindToPrimaryController(controllers: List<MediaController>?) {
        activeController?.unregisterCallback(mediaCallback)
        activeController = controllers?.firstOrNull { controller ->
            val state = controller.playbackState?.state
            state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_BUFFERING
        } ?: controllers?.firstOrNull()
        activeController?.registerCallback(mediaCallback)
        syncMediaSlice()
    }

    private fun syncMediaSlice() {
        val controller = activeController
        if (controller == null) {
            IslandStateRepository.updateState { it.copy(media = null) }
            recalculateDisplayMode()
            return
        }

        val metadata = controller.metadata
        val playbackState = controller.playbackState
        val isPlaying = playbackState?.state == PlaybackState.STATE_PLAYING
        val title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "Неизвестный трек"
        val artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: ""
        val art = metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART)
        val duration = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 1L
        val position = playbackState?.position ?: 0L
        val ratio = if (duration > 0L) (position.toFloat() / duration.toFloat()).coerceIn(0f, 1f) else 0f
        val dominantColor = extractQuickDominantColor(art)

        val slice = com.custom.hyperisland.model.MediaSlice(
            packageName = controller.packageName,
            title = title,
            artist = artist,
            albumArt = art,
            isPlaying = isPlaying,
            progressRatio = ratio,
            dominantColor = dominantColor
        )
        IslandStateRepository.updateState { it.copy(media = slice) }
        recalculateDisplayMode()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val extras = sbn.notification.extras
        val isNativeHyperFocus = detectHyperOsNativeIsland(sbn, extras)

        if (isNativeHyperFocus) {
            IslandStateRepository.updateState { it.copy(isHyperOsNativeActive = true) }
            recalculateDisplayMode()
            return
        }

        if (sbn.isOngoing && extras.containsKey(Notification.EXTRA_MEDIA_SESSION)) return

        val title = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: return
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""

        val actions = sbn.notification.actions?.mapNotNull { action ->
            val label = action.title?.toString() ?: return@mapNotNull null
            val intent = action.actionIntent ?: return@mapNotNull null
            label to intent
        } ?: emptyList()

        val notifSlice = com.custom.hyperisland.model.NotificationSlice(
            key = sbn.key,
            packageName = sbn.packageName,
            senderOrTitle = title,
            message = text,
            contentIntent = sbn.notification.contentIntent,
            actions = actions,
            isHyperOsNativeFocus = false
        )

        IslandStateRepository.updateState {
            it.copy(
                activeNotification = notifSlice,
                mode = com.custom.hyperisland.model.IslandDisplayMode.ExpandedCard
            )
        }

        scope.launch {
            delay(4500)
            IslandStateRepository.updateState { current ->
                if (current.activeNotification?.key == sbn.key) {
                    current.copy(activeNotification = null)
                } else current
            }
            recalculateDisplayMode()
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        val extras = sbn.notification.extras
        if (detectHyperOsNativeIsland(sbn, extras)) {
            IslandStateRepository.updateState { it.copy(isHyperOsNativeActive = false) }
        }
        IslandStateRepository.updateState { current ->
            if (current.activeNotification?.key == sbn.key) {
                current.copy(activeNotification = null)
            } else current
        }
        recalculateDisplayMode()
    }

    private fun detectHyperOsNativeIsland(sbn: StatusBarNotification, extras: Bundle): Boolean {
        val hasMiuiFocusParam = extras.containsKey("miui.focus.param") ||
                extras.containsKey("miui.focus.pics") ||
                extras.containsKey("miui.focus.ticker") ||
                extras.containsKey("miui.focus.rv")
        val isSystemOngoingCapsule = sbn.isOngoing && sbn.packageName in hyperOsNativePackages
        return hasMiuiFocusParam || isSystemOngoingCapsule
    }

    private fun recalculateDisplayMode() {
        IslandStateRepository.updateState { current ->
            val cfg = current.config
            val newMode = when {
                current.isHyperOsNativeActive && cfg.coexistenceMode == com.custom.hyperisland.model.CoexistenceMode.YIELD_TO_NATIVE ->
                    com.custom.hyperisland.model.IslandDisplayMode.IdleCutout
                current.isHyperOsNativeActive && cfg.coexistenceMode == com.custom.hyperisland.model.CoexistenceMode.AURA_RING ->
                    com.custom.hyperisland.model.IslandDisplayMode.AuraWrap
                current.activeNotification != null ->
                    com.custom.hyperisland.model.IslandDisplayMode.ExpandedCard
                current.media != null ->
                    com.custom.hyperisland.model.IslandDisplayMode.CompactPill(
                        hasDetachedDroplet = cfg.enableMetaballLiquid && current.media.isPlaying
                    )
                else -> com.custom.hyperisland.model.IslandDisplayMode.IdleCutout
            }
            current.copy(mode = newMode)
        }
    }

    private fun extractQuickDominantColor(bitmap: Bitmap?): Color {
        if (bitmap == null) return Color(0xFF00E5FF)
        val scaled = Bitmap.createScaledBitmap(bitmap, 1, 1, true)
        val pixel = scaled.getPixel(0, 0)
        return Color(pixel)
    }

    companion object {
        fun dispatchMediaCommand(context: Context, action: com.custom.hyperisland.model.IslandGestureAction) {
            val mm = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val cn = ComponentName(context, IslandCollectorService::class.java)
            val controller = try {
                mm.getActiveSessions(cn).firstOrNull()
            } catch (_: SecurityException) {
                null
            } ?: return

            when (action) {
                com.custom.hyperisland.model.IslandGestureAction.MEDIA_PLAY_PAUSE -> {
                    if (controller.playbackState?.state == PlaybackState.STATE_PLAYING) {
                        controller.transportControls.pause()
                    } else {
                        controller.transportControls.play()
                    }
                }
                com.custom.hyperisland.model.IslandGestureAction.MEDIA_NEXT -> controller.transportControls.skipToNext()
                com.custom.hyperisland.model.IslandGestureAction.MEDIA_PREV -> controller.transportControls.skipToPrevious()
                else -> {}
            }
        }
    }
}
