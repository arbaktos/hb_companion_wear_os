package com.arbaktos.babywatch.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.Text
import com.arbaktos.babywatch.R
import kotlin.math.PI
import kotlin.math.sin
import kotlinx.coroutines.delay

private val Comfortaa = FontFamily(Font(R.font.comfortaa))

/** All proportions are fractions of the 456px design canvas. */
private const val BLOB_FRACTION = 280f / 456f
private const val TIMER_FRACTION = 54f / 456f
private const val PATTERN_TILE_FRACTION = 300f / 456f
private const val PATTERN_ALPHA = 0.20f

@Composable
fun SquishScreen(viewModel: SleepViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Poll on every resume; actions refresh implicitly (they return status).
    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose { }
    }

    // 500ms display ticker on the monotonic clock (timer text only).
    val nowRealtime by produceState(SystemClock.elapsedRealtime()) {
        while (true) {
            value = SystemClock.elapsedRealtime()
            delay(500)
        }
    }

    // One continuous animation clock, in seconds. All idle motion is computed
    // as sine waves of this clock, so state changes never reset or jump the
    // motion — only the per-state amplitude WEIGHTS below crossfade. This is
    // what makes awake->asleep morph instead of freeze-and-relaunch.
    val clock = produceState(0f) {
        var start = -1L
        while (true) {
            androidx.compose.runtime.withFrameNanos { nanos ->
                if (start < 0) start = nanos
                value = (nanos - start) / 1_000_000_000f
            }
        }
    }

    // Per-state motion weights, crossfaded in sync with the palette (700ms).
    val awakeW by animateFloatAsState(
        if (state.phase == Phase.SLEEPING || state.phase == Phase.PAUSED) 0f else 1f,
        tween(700, easing = FastOutSlowInEasing), label = "awakeW",
    )
    val sleepW by animateFloatAsState(
        if (state.phase == Phase.SLEEPING) 1f else 0f,
        tween(700, easing = FastOutSlowInEasing), label = "sleepW",
    )
    val pausedW by animateFloatAsState(
        if (state.phase == Phase.PAUSED) 1f else 0f,
        tween(600, easing = FastOutSlowInEasing), label = "pausedW",
    )

    val palette = paletteFor(state.phase)
    // Palette crossfade per handoff: bg 800ms, blob/glow 700ms.
    val bgFrom by animateColorAsState(palette.bgFrom, tween(800), label = "bgFrom")
    val bgTo by animateColorAsState(palette.bgTo, tween(800), label = "bgTo")
    val blobFrom by animateColorAsState(palette.blobFrom, tween(700), label = "blobFrom")
    val blobTo by animateColorAsState(palette.blobTo, tween(700), label = "blobTo")
    val glow by animateColorAsState(palette.glow, tween(700), label = "glow")

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val screen = maxWidth
        val screenPx = with(LocalDensity.current) { maxWidth.toPx() }
        val blobSize = screen * BLOB_FRACTION
        val timerSize = (screen.value * TIMER_FRACTION).sp

        // Background: radial gradient at 50% 6% per handoff.
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        0f to bgFrom,
                        0.72f to bgTo,
                        1f to bgTo,
                        center = Offset(screenPx * 0.5f, screenPx * 0.06f),
                        radius = screenPx * 1.1f,
                    )
                )
        )

        // Doodle wallpaper: toys when awake, sheep+moons at night, white @20%,
        // crossfading with the same weights as the motion.
        PatternLayer(
            awakeAlpha = PATTERN_ALPHA * awakeW,
            nightAlpha = PATTERN_ALPHA * (sleepW + pausedW).coerceAtMost(1f),
            tilePx = (screenPx * PATTERN_TILE_FRACTION).toInt(),
        )

        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Blob(
                clock = clock,
                awakeW = awakeW,
                sleepW = sleepW,
                pausedW = pausedW,
                blobFrom = blobFrom,
                blobTo = blobTo,
                glow = glow,
                size = blobSize,
                onTap = {
                    when (state.phase) {
                        Phase.AWAKE -> viewModel.startSleep()
                        Phase.SLEEPING -> viewModel.pauseSleep()
                        Phase.PAUSED -> viewModel.resumeSleep()
                        Phase.ERROR -> viewModel.refresh()
                        Phase.LOADING -> Unit
                    }
                },
            ) {
                BlobContent(state, nowRealtime, timerSize, screenWidth = screen)
            }
        }

        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.BottomCenter,
        ) {
            AnimatedVisibility(
                visible = state.phase == Phase.SLEEPING || state.phase == Phase.PAUSED,
                enter = fadeIn(tween(400)),
                exit = fadeOut(tween(400)),
            ) {
                Box(Modifier.padding(bottom = screen * (30f / 456f))) {
                    StopButton(screenWidth = screen, enabled = !state.inFlight) {
                        viewModel.stopSleep()
                    }
                }
            }
        }

        // Paused dim scrim (approximates brightness 0.84 + desaturation).
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.16f * pausedW))
        )
    }
}

/** Two stacked repeating tiles, crossfaded by alpha. */
@Composable
private fun PatternLayer(awakeAlpha: Float, nightAlpha: Float, tilePx: Int) {
    if (tilePx <= 0) return
    val awakeBrush = rememberTileBrush(R.drawable.pattern_awake, tilePx)
    val nightBrush = rememberTileBrush(R.drawable.pattern_night, tilePx)
    if (awakeAlpha > 0.005f) {
        Box(Modifier.fillMaxSize().background(awakeBrush, alpha = awakeAlpha.coerceIn(0f, 1f)))
    }
    if (nightAlpha > 0.005f) {
        Box(Modifier.fillMaxSize().background(nightBrush, alpha = nightAlpha.coerceIn(0f, 1f)))
    }
}

@Composable
private fun rememberTileBrush(resId: Int, tilePx: Int): Brush {
    val context = LocalContext.current
    return remember(resId, tilePx) {
        val src = BitmapFactory.decodeResource(context.resources, resId)
        val scaled = Bitmap.createScaledBitmap(src, tilePx, tilePx, true)
        if (scaled !== src) src.recycle()
        ShaderBrush(ImageShader(scaled.asImageBitmap(), TileMode.Repeated, TileMode.Repeated))
    }
}

@Composable
private fun Blob(
    clock: State<Float>,
    awakeW: Float,
    sleepW: Float,
    pausedW: Float,
    blobFrom: Color,
    blobTo: Color,
    glow: Color,
    size: Dp,
    onTap: () -> Unit,
    content: @Composable () -> Unit,
) {
    val t by clock
    // sin wave helper: period in seconds, phase in turns.
    fun wave(period: Float, phase: Float = 0f): Float =
        sin((t / period + phase) * 2f * PI.toFloat())

    // --- Continuous idle motion (per handoff §Motion) ------------------------
    // Jiggle (awake): ~1.5s rotation sway + faster secondary wobble.
    val rot = awakeW * (3.2f * wave(1.5f) + 0.9f * wave(0.9f, 0.33f))
    // Breathing (sleeping): 4.6s, scale 0.96<->1.06 (center 1.01, amp 0.05).
    val breathe = sleepW * (0.01f + 0.05f * wave(4.6f))
    // Awake micro-bounce.
    val bounceX = awakeW * 0.018f * wave(0.75f, 0.2f)
    val bounceY = awakeW * 0.022f * wave(0.75f, 0.45f)

    // Organic morph: four corner percentages drifting independently; paused
    // dampens to a settled round shape. Wider asymmetry than v1.
    val morphAmp = 1f - pausedW * 0.85f
    fun corner(base: Float, p1: Float, p2: Float, ph: Float) =
        (base + morphAmp * (8f * sin((t / p1 + ph) * 2f * PI.toFloat()) +
            4f * sin((t / p2 + ph * 2.7f) * 2f * PI.toFloat())))
            .toInt().coerceIn(34, 66)

    val shape = RoundedCornerShape(
        topStartPercent = corner(50f, 7.5f, 3.4f, 0.00f),
        topEndPercent = corner(50f, 8.3f, 4.1f, 0.31f),
        bottomEndPercent = corner(50f, 6.9f, 3.8f, 0.62f),
        bottomStartPercent = corner(50f, 7.9f, 4.5f, 0.87f),
    )

    // Tap squish: squash-and-stretch keyframes, ~550ms (handoff §Motion).
    val squishX = remember { Animatable(1f) }
    val squishY = remember { Animatable(1f) }
    val tapCount = remember { mutableLongStateOf(0L) }
    LaunchedEffect(tapCount.longValue) {
        if (tapCount.longValue == 0L) return@LaunchedEffect
        squishX.animateTo(
            1f,
            keyframes {
                durationMillis = 550
                1.18f at 110
                0.86f at 280
                1.06f at 430
            },
        )
    }
    LaunchedEffect(tapCount.longValue) {
        if (tapCount.longValue == 0L) return@LaunchedEffect
        squishY.animateTo(
            1f,
            keyframes {
                durationMillis = 550
                0.78f at 110
                1.16f at 280
                0.95f at 430
            },
        )
    }

    val scaleX = (1f + bounceX + breathe) * squishX.value
    val scaleY = (1f + bounceY + breathe) * lerp(1f, 0.94f, pausedW) * squishY.value

    val sizePx = with(LocalDensity.current) { size.toPx() }

    Box(contentAlignment = Alignment.Center) {
        // Glow halo behind the blob (stands in for the CSS outer box-shadow);
        // breathes gently along with the blob.
        Box(
            Modifier
                .size(size * 1.35f)
                .graphicsLayer {
                    val g = 1f + breathe * 0.8f
                    this.scaleX = g
                    this.scaleY = g
                }
                .background(
                    Brush.radialGradient(
                        0f to glow,
                        1f to Color.Transparent,
                    )
                )
        )
        Box(
            Modifier
                .size(size)
                .graphicsLayer {
                    rotationZ = rot
                    this.scaleX = scaleX
                    this.scaleY = scaleY
                }
                .clip(shape)
                .background(
                    Brush.radialGradient(
                        0f to blobFrom,
                        0.82f to blobTo,
                        1f to blobTo,
                        center = Offset(sizePx * 0.32f, sizePx * 0.24f),
                        radius = sizePx * 0.95f,
                    )
                )
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) {
                    tapCount.longValue++
                    onTap()
                },
            contentAlignment = Alignment.Center,
        ) {
            content()
        }
    }
}

@Composable
private fun BlobContent(
    state: SleepUiState,
    nowRealtime: Long,
    timerSize: TextUnit,
    screenWidth: Dp,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(screenWidth * (8f / 456f)),
    ) {
        val elapsed = state.displayElapsedSec(nowRealtime)
        val timerText = when (state.phase) {
            Phase.LOADING -> "…"
            Phase.ERROR -> "!"
            Phase.AWAKE -> elapsed?.let(::formatAwake) ?: "—"
            else -> elapsed?.let(::formatSleep) ?: "—"
        }
        Text(
            text = timerText,
            fontFamily = Comfortaa,
            fontWeight = FontWeight.Bold,
            fontSize = timerSize,
            color = if (state.phase == Phase.PAUSED) Color.White.copy(alpha = 0.72f) else Color.White,
            maxLines = 1,
        )

        val action: Pair<ImageVector, String>? = when (state.phase) {
            Phase.AWAKE -> Icons.Rounded.Bedtime to "Start"
            Phase.SLEEPING -> Icons.Rounded.Pause to "Pause"
            Phase.PAUSED -> Icons.Rounded.PlayArrow to "Resume"
            Phase.ERROR -> Icons.Rounded.Refresh to "Retry"
            Phase.LOADING -> null
        }
        if (action != null) {
            ActionChip(action.first, action.second, screenWidth)
        }
        if (state.phase == Phase.ERROR) {
            Text(
                text = "phone nearby?",
                fontFamily = Comfortaa,
                fontSize = (screenWidth.value * 13f / 456f).sp,
                color = Color.White.copy(alpha = 0.8f),
            )
        }
    }
}

@Composable
private fun ActionChip(icon: ImageVector, verb: String, screenWidth: Dp) {
    val s = screenWidth.value / 456f
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape((22 * s).dp))
            .background(Color.Black.copy(alpha = 0.18f))
            .padding(horizontal = (16 * s).dp, vertical = (6 * s).dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy((6 * s).dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size((16 * s).dp),
        )
        Text(
            text = verb,
            fontFamily = Comfortaa,
            fontWeight = FontWeight.SemiBold,
            fontSize = (18 * s).sp,
            color = Color.White.copy(alpha = 0.94f),
        )
    }
}

@Composable
private fun StopButton(screenWidth: Dp, enabled: Boolean, onClick: () -> Unit) {
    val s = screenWidth.value / 456f
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape((32 * s).dp))
            .background(Color.White.copy(alpha = 0.07f))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = (28 * s).dp, vertical = (14 * s).dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy((8 * s).dp),
    ) {
        Icon(
            imageVector = Icons.Rounded.Stop,
            contentDescription = "Stop",
            tint = Color.White.copy(alpha = 0.8f),
            modifier = Modifier.size((20 * s).dp),
        )
        Text(
            text = "Stop",
            fontFamily = Comfortaa,
            fontWeight = FontWeight.ExtraBold,
            fontSize = (22 * s).sp,
            color = Color.White.copy(alpha = 0.8f),
        )
    }
}
