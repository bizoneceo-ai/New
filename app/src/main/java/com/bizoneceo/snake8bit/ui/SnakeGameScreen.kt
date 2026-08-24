package com.bizoneceo.snake8bit.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bizoneceo.snake8bit.R
import com.bizoneceo.snake8bit.audio.SoundEngine
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
import kotlinx.coroutines.delay

@Composable
fun SnakeGameScreen() {
    val game = remember { SnakeGame() }

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
        Hud(game)
        Spacer(modifier = Modifier.size(8.dp))
        GameBoard(game, modifier = Modifier.weight(1f).fillMaxWidth())
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
private fun Hud(game: SnakeGame) {
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
private fun GameBoard(game: SnakeGame, modifier: Modifier = Modifier) {
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

        // Two giant tap zones covering the whole board area — left half
        // turns left, right half turns right, relative to the snake's
        // current heading. Meant to be playable without looking: any tap
        // on "your" side works, there's nothing small to aim for.
        if (game.started && !game.gameOver && !game.paused) {
            Row(modifier = Modifier.fillMaxSize()) {
                TurnZone(
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    onTap = {
                        game.turnLeft()
                        SoundEngine.playTurn()
                    },
                )
                TurnZone(
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    onTap = {
                        game.turnRight()
                        SoundEngine.playTurn()
                    },
                )
            }
        }

        if (!game.started && !game.gameOver) {
            StartOverlay(onStart = game::start)
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

@Composable
private fun TurnZone(modifier: Modifier, onTap: () -> Unit) {
    Box(modifier = modifier.clickable(onClick = onTap))
}

@Composable
private fun StartOverlay(onStart: () -> Unit) {
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
                text = stringResource(R.string.control_hint),
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
