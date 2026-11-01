package com.custom.hyperisland.overlay

import android.app.ActivityOptions
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.os.Bundle
import android.os.IBinder
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.custom.hyperisland.model.*
import com.custom.hyperisland.service.IslandCollectorService
import com.custom.hyperisland.service.IslandStateRepository
import kotlin.math.roundToInt

private const val METABALL_AGSL_SHADER = """
    uniform shader content;
    uniform float threshold;
    half4 main(float2 coord) {
        half4 c = content.eval(coord);
        float smoothAlpha = smoothstep(threshold - 0.08, threshold + 0.08, c.a);
        return half4(c.rgb * smoothAlpha, smoothAlpha);
    }
"""

class IslandOverlayService : Service(), LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {
    private lateinit var windowManager: WindowManager
    private lateinit var composeView: ComposeView
    private lateinit var layoutParams: WindowManager.LayoutParams
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    private val appViewModelStore = ViewModelStore()

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry
    override val viewModelStore: ViewModelStore get() = appViewModelStore

    override fun onCreate() {
        super.onCreate()
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            y = 12
        }

        composeView = ComposeView(this).apply {
            setContent {
                val uiState by IslandStateRepository.state.collectAsState()
                DynamicIslandRoot(
                    state = uiState,
                    onUpdateWindowBounds = { widthPx, heightPx, yOffsetPx ->
                        updateWindowLayout(widthPx, heightPx, yOffsetPx)
                    },
                    onLaunchFreeform = { pkg -> launchInHyperOsFreeform(pkg) }
                )
            }
        }

        composeView.setViewTreeLifecycleOwner(this)
        composeView.setViewTreeSavedStateRegistryOwner(this)
        composeView.setViewTreeViewModelStoreOwner(this)

        windowManager.addView(composeView, layoutParams)
    }

    private fun updateWindowLayout(widthPx: Int, heightPx: Int, yOffsetPx: Int) {
        if (layoutParams.width != widthPx || layoutParams.height != heightPx || layoutParams.y != yOffsetPx) {
            layoutParams.width = widthPx
            layoutParams.height = heightPx
            layoutParams.y = yOffsetPx
            try {
                windowManager.updateViewLayout(composeView, layoutParams)
            } catch (_: Exception) {}
        }
    }

    private fun launchInHyperOsFreeform(packageName: String) {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName) ?: return
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        val options = ActivityOptions.makeBasic()
        try {
            val method = ActivityOptions::class.java.getMethod("setLaunchWindowingMode", Int::class.javaPrimitiveType)
            method.invoke(options, 5)
        } catch (_: Exception) {}
        val bundle = options.toBundle() ?: Bundle()
        bundle.putBoolean("android.activity.launchWindowingMode", true)
        bundle.putInt("miui.WindowingMode", 5)
        startActivity(launchIntent, bundle)
    }

    override fun onDestroy() {
        super.onDestroy()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        appViewModelStore.clear()
        if (::composeView.isInitialized) {
            windowManager.removeView(composeView)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

@Composable
fun DynamicIslandRoot(
    state: IslandUiState,
    onUpdateWindowBounds: (widthPx: Int, heightPx: Int, yOffsetPx: Int) -> Unit,
    onLaunchFreeform: (String) -> Unit
) {
    val cfg = state.config
    val density = LocalDensity.current

    val springSpecDp = remember(cfg.springStiffness, cfg.springDampingRatio) {
        spring<androidx.compose.ui.unit.Dp>(
            dampingRatio = cfg.springDampingRatio,
            stiffness = cfg.springStiffness
        )
    }

    val targetWidth = when (state.mode) {
        IslandDisplayMode.IdleCutout -> (cfg.cutoutRadiusPx * 2 / density.density).dp + 8.dp
        is IslandDisplayMode.CompactPill -> if (state.mode.hasDetachedDroplet) (cfg.collapsedWidthDp + 48f).dp else cfg.collapsedWidthDp.dp
        IslandDisplayMode.ExpandedCard -> cfg.expandedWidthDp.dp
        IslandDisplayMode.AuraWrap -> (cfg.collapsedWidthDp + 24f).dp
    }

    val targetHeight = when (state.mode) {
        IslandDisplayMode.IdleCutout -> (cfg.cutoutRadiusPx * 2 / density.density).dp + 8.dp
        is IslandDisplayMode.CompactPill -> cfg.collapsedHeightDp.dp
        IslandDisplayMode.ExpandedCard -> cfg.expandedHeightDp.dp
        IslandDisplayMode.AuraWrap -> (cfg.collapsedHeightDp + 10f).dp
    }

    val targetYOffsetDp = if (state.isHyperOsNativeActive && cfg.coexistenceMode == CoexistenceMode.STACK_BELOW) {
        cfg.nativeStackOffsetYDp.dp
    } else {
        6.dp
    }

    val animatedWidth by animateDpAsState(targetValue = targetWidth, animationSpec = springSpecDp, label = "w")
    val animatedHeight by animateDpAsState(targetValue = targetHeight, animationSpec = springSpecDp, label = "h")
    val animatedYOffset by animateDpAsState(targetValue = targetYOffsetDp, animationSpec = springSpecDp, label = "y")
    val dropletSeparation by animateFloatAsState(
        targetValue = if (state.mode is IslandDisplayMode.CompactPill && state.mode.hasDetachedDroplet) 14f else 0f,
        animationSpec = spring(dampingRatio = cfg.springDampingRatio, stiffness = cfg.springStiffness),
        label = "droplet"
    )

    LaunchedEffect(animatedWidth, animatedHeight, animatedYOffset, cfg.cutoutOffsetY) {
        val wPx = with(density) { (animatedWidth + 24.dp).toPx().roundToInt() }
        val hPx = with(density) { (animatedHeight + 16.dp).toPx().roundToInt() }
        val yPx = with(density) { animatedYOffset.toPx().roundToInt() } + cfg.cutoutOffsetY.roundToInt()
        onUpdateWindowBounds(wPx, hPx, yPx)
    }

    val metaballShader = remember { RuntimeShader(METABALL_AGSL_SHADER) }
    val accentColor = if (cfg.adaptivePaletteAccent && state.media != null) {
        state.media.dominantColor
    } else {
        cfg.customAccentColor
    }
    val context = androidx.compose.ui.platform.LocalContext.current

    Box(
        modifier = Modifier
            .width(animatedWidth)
            .height(animatedHeight)
            .graphicsLayer {
                translationX = cfg.cutoutOffsetX
                if (cfg.enableMetaballLiquid && state.mode is IslandDisplayMode.CompactPill) {
                    metaballShader.setFloatUniform("threshold", cfg.metaballThreshold)
                    val blur = RenderEffect.createBlurEffect(18f, 18f, android.graphics.Shader.TileMode.DECAL)
                    val shaderEffect = RenderEffect.createRuntimeShaderEffect(metaballShader, "content")
                    renderEffect = RenderEffect.createChainEffect(shaderEffect, blur).asComposeRenderEffect()
                }
            }
            .pointerInput(state.mode, state.media, state.activeNotification) {
                detectTapGestures(
                    onTap = {
                        IslandStateRepository.updateState { curr ->
                            val nextMode = if (curr.mode == IslandDisplayMode.ExpandedCard) {
                                IslandDisplayMode.CompactPill(curr.media?.isPlaying == true)
                            } else {
                                IslandDisplayMode.ExpandedCard
                            }
                            curr.copy(mode = nextMode)
                        }
                    },
                    onDoubleTap = {
                        IslandCollectorService.dispatchMediaCommand(context, cfg.onDoubleTap)
                    },
                    onLongPress = {
                        val pkg = state.activeNotification?.packageName ?: state.media?.packageName
                        if (pkg != null) onLaunchFreeform(pkg)
                    }
                )
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures { _, dragAmount ->
                    if (dragAmount < -35f) {
                        IslandCollectorService.dispatchMediaCommand(context, cfg.onSwipeLeft)
                    } else if (dragAmount > 35f) {
                        IslandCollectorService.dispatchMediaCommand(context, cfg.onSwipeRight)
                    }
                }
            }
            .pointerInput(state.media, state.activeNotification) {
                detectVerticalDragGestures { _, dragAmount ->
                    if (dragAmount > 40f) {
                        val pkg = state.activeNotification?.packageName ?: state.media?.packageName
                        if (pkg != null) onLaunchFreeform(pkg)
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        when (state.mode) {
            IslandDisplayMode.IdleCutout -> {
                if (cfg.cutoutRingStyle != CutoutRingStyle.NONE) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val sweep = if (cfg.cutoutRingStyle == CutoutRingStyle.BATTERY_ARC) {
                            (state.batteryLevel / 100f) * 360f
                        } else 360f
                        drawArc(
                            color = accentColor,
                            startAngle = -90f,
                            sweepAngle = sweep,
                            useCenter = false,
                            style = Stroke(width = 2.5.dp.toPx())
                        )
                    }
                }
            }
            IslandDisplayMode.AuraWrap -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(50))
                        .background(Color.Transparent)
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        drawRoundRect(
                            color = accentColor,
                            style = Stroke(width = 3.dp.toPx())
                        )
                    }
                }
            }
            is IslandDisplayMode.CompactPill -> {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier.fillMaxSize()
                ) {
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(50))
                            .background(Color.Black)
                            .padding(horizontal = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        state.media?.albumArt?.let { bmp ->
                            Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = null,
                                modifier = Modifier
                                    .size(22.dp)
                                    .clip(CircleShape)
                            )
                        } ?: Box(
                            modifier = Modifier
                                .size(18.dp)
                                .clip(CircleShape)
                                .background(accentColor)
                        )

                        Spacer(modifier = Modifier.width(34.dp))
                        MiniWaveformIndicator(color = accentColor, isPlaying = state.media?.isPlaying == true)
                    }

                    if (state.mode.hasDetachedDroplet) {
                        Spacer(modifier = Modifier.width(dropletSeparation.dp))
                        Box(
                            modifier = Modifier
                                .size(cfg.collapsedHeightDp.dp)
                                .clip(CircleShape)
                                .background(Color.Black),
                            contentAlignment = Alignment.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(accentColor)
                            )
                        }
                    }
                }
            }
            IslandDisplayMode.ExpandedCard -> {
                val shape = RoundedCornerShape(
                    topStart = cfg.topCornerRadiusDp.dp,
                    topEnd = cfg.topCornerRadiusDp.dp,
                    bottomStart = cfg.bottomCornerRadiusDp.dp,
                    bottomEnd = cfg.bottomCornerRadiusDp.dp
                )
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(shape)
                        .background(Color(0xF20A0A0E))
                        .padding(horizontal = 18.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    Spacer(modifier = Modifier.height(18.dp))
                    if (state.activeNotification != null) {
                        val notif = state.activeNotification
                        Column {
                            Text(
                                text = notif.senderOrTitle,
                                color = accentColor,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = notif.message,
                                color = Color.White,
                                fontSize = 13.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    } else if (state.media != null) {
                        val media = state.media
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            media.albumArt?.let { bmp ->
                                Image(
                                    bitmap = bmp.asImageBitmap(),
                                    contentDescription = null,
                                    modifier = Modifier
                                        .size(54.dp)
                                        .clip(RoundedCornerShape(14.dp))
                                )
                                Spacer(modifier = Modifier.width(14.dp))
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = media.title,
                                    color = Color.White,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = media.artist,
                                    color = Color(0xFFB0B0B8),
                                    fontSize = 13.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(5.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF2A2A35))
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(media.progressRatio)
                                    .fillMaxHeight()
                                    .clip(CircleShape)
                                    .background(accentColor)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MiniWaveformIndicator(color: Color, isPlaying: Boolean) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val heights = if (isPlaying) listOf(10.dp, 16.dp, 8.dp, 14.dp) else listOf(4.dp, 4.dp, 4.dp, 4.dp)
        heights.forEach { barHeight ->
            Box(
                modifier = Modifier
                    .width(2.5.dp)
                    .height(barHeight)
                    .clip(CircleShape)
                    .background(color)
            )
        }
    }
}
