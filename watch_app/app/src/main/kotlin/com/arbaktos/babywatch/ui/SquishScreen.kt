package com.arbaktos.babywatch.ui

import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.Text
import com.arbaktos.babywatch.R
import kotlinx.coroutines.delay

private val Comfortaa = FontFamily(Font(R.font.comfortaa))

/** All proportions are fractions of the 456px design canvas. */
private const val BLOB_FRACTION = 280f / 456f
private const val TIMER_FRACTION = 54f / 456f

@Composable
fun SquishScreen(viewModel: SleepViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Poll on every resume; actions refresh implicitly (they return status).
    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose { }
    }

    // 500ms display ticker on the monotonic clock.
    val nowRealtime by produceState(SystemClock.elapsedRealtime()) {
        while (true) {
            value = SystemClock.elapsedRealtime()
            delay(500)
        }
    }

    val palette = paletteFor(state.phase)
    // Palette crossfade per handoff: bg 800ms, blob/glow 700ms, dim 600ms.
    val bgFrom by animateColorAsState(palette.bgFrom, tween(800), label = "bgFrom")
    val bgTo by animateColorAsState(palette.bgTo, tween(800), label = "bgTo")
    val blobFrom by animateColorAsState(palette.blobFrom, tween(700), label = "blobFrom")
    val blobTo by animateColorAsState(palette.blobTo, tween(700), label = "blobTo")
    val glow by animateColorAsState(palette.glow, tween(700), label = "glow")
    // Approximates the handoff's brightness(0.84) saturate(0.72) paused filter.
    val dim by animateColorAsState(
        if (state.phase == Phase.PAUSED) Color.Black.copy(alpha = 0.16f) else Color.Transparent,
        tween(600),
        label = "dim",
    )

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

        // TODO(design): tiled doodle wallpaper layer (awake-pattern.svg /
        // night-pattern.svg, white @ 20%) goes here, between gradient and blob.

        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Blob(
                phase = state.phase,
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

        // Paused dim scrim over everything.
        Box(
            Modifier
                .fillMaxSize()
                .background(dim)
        )
    }
}

@Composable
private fun Blob(
    phase: Phase,
    blobFrom: Color,
    blobTo: Color,
    glow: Color,
    size: Dp,
    onTap: () -> Unit,
    content: @Composable () -> Unit,
) {
    // --- Idle motion (per handoff §Motion) ----------------------------------
    val idle = rememberInfiniteTransition(label = "idle")

    // Jiggle (awake): ~1.5s rotation sway.
    val jiggleRot by idle.animateFloat(
        initialValue = -4f,
        targetValue = -4f,
        animationSpec = infiniteRepeatable(
            keyframes {
                durationMillis = 1500
                -4f at 0
                3f at 500
                -3f at 900
                4f at 1200
                -4f at 1500
            },
        ),
        label = "jiggleRot",
    )
    // Breathing (sleeping): 4.6s loop, scale 0.96 <-> 1.06.
    val breath by idle.animateFloat(
        initialValue = 0.96f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(
            tween(2300, easing = FastOutSlowInEasing),
            RepeatMode.Reverse,
        ),
        label = "breath",
    )
    // Organic morph: corner percentages slowly cycling (~7.5s), approximating
    // the CSS border-radius keyframes. Two independent periods so the shape
    // never repeats exactly.
    val morphA by idle.animateFloat(
        initialValue = 44f,
        targetValue = 58f,
        animationSpec = infiniteRepeatable(
            tween(3700, easing = FastOutSlowInEasing),
            RepeatMode.Reverse,
        ),
        label = "morphA",
    )
    val morphB by idle.animateFloat(
        initialValue = 56f,
        targetValue = 42f,
        animationSpec = infiniteRepeatable(
            tween(4100, easing = FastOutSlowInEasing),
            RepeatMode.Reverse,
        ),
        label = "morphB",
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

    val rot: Float
    val idleScaleX: Float
    val idleScaleY: Float
    when (phase) {
        Phase.SLEEPING -> { rot = 0f; idleScaleX = breath; idleScaleY = breath }
        Phase.PAUSED -> { rot = 0f; idleScaleX = 1f; idleScaleY = 0.94f } // motion halts
        else -> { // awake/loading/error: jiggle
            rot = jiggleRot
            val s = 1f + (breath - 1f) * 0.3f
            idleScaleX = s; idleScaleY = s
        }
    }

    val morphShape = if (phase == Phase.PAUSED) {
        RoundedCornerShape(50) // settles round
    } else {
        RoundedCornerShape(
            topStartPercent = morphA.toInt(),
            topEndPercent = (100 - morphA).toInt(),
            bottomEndPercent = morphB.toInt(),
            bottomStartPercent = (100 - morphB).toInt(),
        )
    }

    val sizePx = with(LocalDensity.current) { size.toPx() }

    Box(contentAlignment = Alignment.Center) {
        // Glow halo behind the blob (stands in for the CSS outer box-shadow).
        Box(
            Modifier
                .size(size * 1.35f)
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
                    scaleX = idleScaleX * squishX.value
                    scaleY = idleScaleY * squishY.value
                }
                .clip(morphShape)
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
