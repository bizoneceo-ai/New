package com.bizoneceo.snake8bit.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bizoneceo.snake8bit.R
import com.bizoneceo.snake8bit.game.Direction
import com.bizoneceo.snake8bit.game.SnakeGame
import com.bizoneceo.snake8bit.ui.theme.AppleColor
import com.bizoneceo.snake8bit.ui.theme.HudText
import com.bizoneceo.snake8bit.ui.theme.ObstacleColor
import com.bizoneceo.snake8bit.ui.theme.OverlayScrim
import com.bizoneceo.snake8bit.ui.theme.PixelDark
import com.bizoneceo.snake8bit.ui.theme.PixelDarkest
import com.bizoneceo.snake8bit.ui.theme.SnakeBody
import com.bizoneceo.snake8bit.ui.theme.SnakeHead
import kotlinx.coroutines.delay
import kotlin.math.abs

@Composable
fun SnakeGameScreen() {
    val game = remember { SnakeGame() }

    LaunchedEffectGameLoop(game)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(PixelDarkest)
            .padding(12.dp),
    ) {
        Hud(game)
        Spacer(modifier = Modifier.size(8.dp))
        GameBoard(game, modifier = Modifier.weight(1f).fillMaxWidth())
        Spacer(modifier = Modifier.size(12.dp))
        DPad(onDirection = game::queueDirection)
    }
}

@Composable
private fun LaunchedEffectGameLoop(game: SnakeGame) {
    LaunchedEffect(game.runId) {
        if (game.runId == 0) return@LaunchedEffect
        while (!game.gameOver) {
            delay(game.tickDelayMillis())
            game.tick()
        }
    }
}

@Composable
private fun Hud(game: SnakeGame) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
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
                .background(PixelDarkest)
                .pointerInput(Unit) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        val direction = if (abs(dragAmount.x) > abs(dragAmount.y)) {
                            if (dragAmount.x > 0) Direction.RIGHT else Direction.LEFT
                        } else {
                            if (dragAmount.y > 0) Direction.DOWN else Direction.UP
                        }
                        game.queueDirection(direction)
                    }
                },
        ) {
            val cellPx = size.width / game.cols
            val gap = cellPx * 0.15f

            game.obstacles.forEach { point ->
                drawRect(
                    color = ObstacleColor,
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

        if (!game.started && !game.gameOver) {
            StartOverlay(onStart = game::start)
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
                text = stringResource(R.string.swipe_hint),
                color = HudText,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
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

@Composable
private fun DPad(onDirection: (Direction) -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        DPadButton(label = "▲", onClick = { onDirection(Direction.UP) })
        Spacer(modifier = Modifier.size(4.dp))
        Row {
            DPadButton(label = "◀", onClick = { onDirection(Direction.LEFT) })
            Spacer(modifier = Modifier.width(64.dp))
            DPadButton(label = "▶", onClick = { onDirection(Direction.RIGHT) })
        }
        Spacer(modifier = Modifier.size(4.dp))
        DPadButton(label = "▼", onClick = { onDirection(Direction.DOWN) })
    }
}

@Composable
private fun DPadButton(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(56.dp)
            .background(PixelDark, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = HudText,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 22.sp,
        )
    }
}
