package com.bizoneceo.snake8bit.ui

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bizoneceo.snake8bit.R
import com.bizoneceo.snake8bit.audio.SoundEngine
import com.bizoneceo.snake8bit.game.Direction
import com.bizoneceo.snake8bit.game.SnakeGame
import com.bizoneceo.snake8bit.ui.theme.AppleColor
import com.bizoneceo.snake8bit.ui.theme.HudText
import com.bizoneceo.snake8bit.ui.theme.ObstacleColor
import com.bizoneceo.snake8bit.ui.theme.ObstacleEdibleColor
import com.bizoneceo.snake8bit.ui.theme.OverlayScrim
import com.bizoneceo.snake8bit.ui.theme.PixelDark
import com.bizoneceo.snake8bit.ui.theme.PixelDarkest
import com.bizoneceo.snake8bit.ui.theme.SnakeBody
import com.bizoneceo.snake8bit.ui.theme.SnakeHead
import com.bizoneceo.snake8bit.ui.theme.ZoneFlashColor
import kotlinx.coroutines.delay
import kotlin.math.abs

/** Two control schemes to compare: relative steering (tap left/right half
 * to turn) vs an absolute 4-way screen split. Both drive the same grid
 * game — this only changes how a tap becomes a direction. */
private enum class ControlScheme {
    STEERING,
    FOUR_ZONE,
}

private const val PrefsName = "snake_prefs"
private const val KeyControlScheme = "control_scheme"

@Composable
fun SnakeGameScreen() {
    val game = remember { SnakeGame() }

    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PrefsName, Context.MODE_PRIVATE) }
    var controlScheme by remember {
        val saved = prefs.getString(KeyControlScheme, ControlScheme.STEERING.name)
        mutableStateOf(runCatching { ControlScheme.valueOf(saved ?: "") }.getOrDefault(ControlScheme.STEERING))
    }

    LaunchedEffectGameLoop(game)

    LaunchedEffect(game.score) {
        if (game.score > 0) SoundEngine.playEat()
    }
    LaunchedEffect(game.gameOver) {
        if (game.gameOver) SoundEngine.playGameOver()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(PixelDarkest)
            .padding(12.dp),
    ) {
        Hud(
            game = game,
            controlScheme = controlScheme,
            onToggleControlScheme = {
                controlScheme = if (controlScheme == ControlScheme.STEERING) {
                    ControlScheme.FOUR_ZONE
                } else {
                    ControlScheme.STEERING
                }
                prefs.edit().putString(KeyControlScheme, controlScheme.name).apply()
                SoundEngine.playPauseToggle()
            },
        )
        Spacer(modifier = Modifier.size(8.dp))
        GameBoard(game, controlScheme, modifier = Modifier.weight(1f).fillMaxWidth())
    }
}

/** How often to re-check for an unpause while the game is paused. */
private const val PauseCheckIntervalMs = 80L

/** Half-period, in ms, of the gray/red obstacle blink during power mode. */
private const val ObstacleBlinkHalfPeriodMs = 250L

private fun isBlinkOn(): Boolean = (System.currentTimeMillis() / ObstacleBlinkHalfPeriodMs) % 2 == 0L

@Composable
private fun LaunchedEffectGameLoop(game: SnakeGame) {
    LaunchedEffect(game.runId) {
        if (game.runId == 0) return@LaunchedEffect
        while (!game.gameOver) {
            if (game.paused) {
                delay(PauseCheckIntervalMs)
            } else {
                delay(game.tickDelayMillis())
                game.tick()
            }
        }
    }
}

@Composable
private fun Hud(game: SnakeGame, controlScheme: ControlScheme, onToggleControlScheme: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        PauseButton(
            paused = game.paused,
            enabled = game.started && !game.gameOver,
            onClick = {
                game.togglePause()
                SoundEngine.playPauseToggle()
            },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(
                text = stringResource(R.string.score_label, game.score),
                color = HudText,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
            )
            Text(
                text = stringResource(R.string.length_label, game.snake.size),
                color = HudText,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
            )
        }
        ControlSchemeToggleButton(controlScheme = controlScheme, onClick = onToggleControlScheme)
    }
}

/** Lets you switch between the two control schemes to compare them,
 * without reinstalling anything; the choice is remembered. */
private val ControlToggleTouchTarget = 56.dp
private val ControlToggleVisibleSize = 40.dp

@Composable
private fun ControlSchemeToggleButton(controlScheme: ControlScheme, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(ControlToggleTouchTarget)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(ControlToggleVisibleSize)
                .background(PixelDark, RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = if (controlScheme == ControlScheme.STEERING) "↺" else "✛",
                color = HudText,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
            )
        }
    }
}

/** The tappable area is bigger than the visible icon so it's easy to hit
 * without looking — the extra ring around the icon still counts as a hit. */
private val PauseTouchTarget = 56.dp
private val PauseVisibleSize = 40.dp

@Composable
private fun PauseButton(paused: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(PauseTouchTarget)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(PauseVisibleSize)
                .background(PixelDark, RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = if (paused) "▶" else "⏸",
                color = HudText,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
            )
        }
    }
}

@Composable
private fun GameBoard(game: SnakeGame, controlScheme: ControlScheme, modifier: Modifier = Modifier) {
    BoxWithConstraints(
        modifier = modifier.background(PixelDark),
        contentAlignment = Alignment.Center,
    ) {
        // The whole board fits on screen at once; the grid is small enough
        // that this still gives each cell a chunky, clearly-visible size.
        val cellSize = minOf(maxWidth / game.cols, maxHeight / game.rows)
        val boardWidth = cellSize * game.cols
        val boardHeight = cellSize * game.rows

        Canvas(
            modifier = Modifier
                .size(boardWidth, boardHeight)
                .background(PixelDarkest),
        ) {
            val cellPx = size.width / game.cols
            val gap = cellPx * 0.15f

            val obstacleColor = if (game.powerModeActive && isBlinkOn()) {
                ObstacleEdibleColor
            } else {
                ObstacleColor
            }
            game.obstacles.forEach { point ->
                drawRect(
                    color = obstacleColor,
                    topLeft = Offset(point.x * cellPx + gap / 2, point.y * cellPx + gap / 2),
                    size = Size(cellPx - gap, cellPx - gap),
                )
            }

            game.snake.forEachIndexed { index, point ->
                drawRect(
                    color = if (index == 0) SnakeHead else SnakeBody,
                    topLeft = Offset(point.x * cellPx + gap / 2, point.y * cellPx + gap / 2),
                    size = Size(cellPx - gap, cellPx - gap),
                )
            }

            drawRect(
                color = AppleColor,
                topLeft = Offset(game.apple.x * cellPx + gap / 2, game.apple.y * cellPx + gap / 2),
                size = Size(cellPx - gap, cellPx - gap),
            )
        }

        // The whole board area doubles as the controls — huge targets so
        // it's playable without looking down at your thumbs.
        if (game.started && !game.gameOver && !game.paused) {
            when (controlScheme) {
                ControlScheme.STEERING -> SteeringControls(
                    onTurnLeft = {
                        game.turnLeft()
                        SoundEngine.playTurn()
                    },
                    onTurnRight = {
                        game.turnRight()
                        SoundEngine.playTurn()
                    },
                )
                ControlScheme.FOUR_ZONE -> FourZoneControls(
                    onDirection = {
                        game.queueDirection(it)
                        SoundEngine.playTurn()
                    },
                )
            }
        }

        if (!game.started && !game.gameOver) {
            StartOverlay(controlScheme = controlScheme, onStart = game::start)
        }
        if (game.started && !game.gameOver && game.paused) {
            PausedOverlay(
                onResume = {
                    game.togglePause()
                    SoundEngine.playPauseToggle()
                },
            )
        }
        if (game.gameOver) {
            GameOverOverlay(
                score = game.score,
                onRestart = {
                    game.reset()
                    game.start()
                },
            )
        }
    }
}

/** How long a zone stays lit after a tap — a deliberate flash, not a
 * lingering glow, so it reads as "your tap registered" rather than as
 * decoration. */
private const val ZoneFlashDurationMs = 120L

/** Tap the left half to turn left, the right half to turn right, relative
 * to the snake's current heading — like a steering wheel. The whole tapped
 * half flashes so a blind tap gets clear visual confirmation. */
@Composable
private fun SteeringControls(onTurnLeft: () -> Unit, onTurnRight: () -> Unit) {
    Row(modifier = Modifier.fillMaxSize()) {
        FlashZone(modifier = Modifier.weight(1f).fillMaxHeight(), onTap = onTurnLeft)
        FlashZone(modifier = Modifier.weight(1f).fillMaxHeight(), onTap = onTurnRight)
    }
}

@Composable
private fun FlashZone(modifier: Modifier, onTap: () -> Unit) {
    var flashKey by remember { mutableStateOf(0) }
    var flashing by remember { mutableStateOf(false) }
    LaunchedEffect(flashKey) {
        if (flashKey == 0) return@LaunchedEffect
        flashing = true
        delay(ZoneFlashDurationMs)
        flashing = false
    }
    Box(
        modifier = modifier
            .background(if (flashing) ZoneFlashColor else Color.Transparent)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) {
                flashKey++
                onTap()
            },
    )
}

/** Splits the whole area into 4 triangular sectors by its diagonals — tap
 * the top sector for up, bottom for down, left for left, right for right.
 * Absolute directions, but the target for each one is enormous. The tapped
 * sector flashes so a blind tap gets clear visual confirmation. */
@Composable
private fun FourZoneControls(onDirection: (Direction) -> Unit) {
    var flashKey by remember { mutableStateOf(0) }
    var flashDirection by remember { mutableStateOf<Direction?>(null) }
    LaunchedEffect(flashKey) {
        if (flashKey == 0) return@LaunchedEffect
        delay(ZoneFlashDurationMs)
        flashDirection = null
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(onTap = { offset ->
                    val nx = offset.x / size.width - 0.5f
                    val ny = offset.y / size.height - 0.5f
                    val direction = when {
                        ny <= -abs(nx) -> Direction.UP
                        ny >= abs(nx) -> Direction.DOWN
                        nx < 0f -> Direction.LEFT
                        else -> Direction.RIGHT
                    }
                    flashDirection = direction
                    flashKey++
                    onDirection(direction)
                })
            },
    ) {
        val direction = flashDirection
        if (direction != null) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height
                val center = Offset(w / 2f, h / 2f)
                val path = Path().apply {
                    when (direction) {
                        Direction.UP -> {
                            moveTo(0f, 0f)
                            lineTo(w, 0f)
                            lineTo(center.x, center.y)
                        }
                        Direction.DOWN -> {
                            moveTo(0f, h)
                            lineTo(w, h)
                            lineTo(center.x, center.y)
                        }
                        Direction.LEFT -> {
                            moveTo(0f, 0f)
                            lineTo(0f, h)
                            lineTo(center.x, center.y)
                        }
                        Direction.RIGHT -> {
                            moveTo(w, 0f)
                            lineTo(w, h)
                            lineTo(center.x, center.y)
                        }
                    }
                    close()
                }
                drawPath(path, color = ZoneFlashColor)
            }
        }
    }
}

@Composable
private fun StartOverlay(controlScheme: ControlScheme, onStart: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(OverlayScrim)
            .clickable(onClick = onStart),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = stringResource(R.string.app_title),
                color = HudText,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 26.sp,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.size(16.dp))
            Text(
                text = stringResource(R.string.tap_to_start),
                color = HudText,
                fontFamily = FontFamily.Monospace,
                fontSize = 16.sp,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.size(8.dp))
            Text(
                text = stringResource(
                    if (controlScheme == ControlScheme.STEERING) {
                        R.string.control_hint_steering
                    } else {
                        R.string.control_hint_four_zone
                    },
                ),
                color = HudText,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun PausedOverlay(onResume: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(OverlayScrim)
            .clickable(onClick = onResume),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = stringResource(R.string.paused_title),
                color = HudText,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 26.sp,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.size(16.dp))
            Text(
                text = stringResource(R.string.tap_to_resume),
                color = HudText,
                fontFamily = FontFamily.Monospace,
                fontSize = 16.sp,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun GameOverOverlay(score: Int, onRestart: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(OverlayScrim)
            .clickable(onClick = onRestart),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = stringResource(R.string.game_over),
                color = AppleColor,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 26.sp,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.size(12.dp))
            Text(
                text = stringResource(R.string.score_label, score),
                color = HudText,
                fontFamily = FontFamily.Monospace,
                fontSize = 18.sp,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.size(16.dp))
            Text(
                text = stringResource(R.string.tap_to_restart),
                color = HudText,
                fontFamily = FontFamily.Monospace,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
            )
        }
    }
}
