package com.bizoneceo.nardy.ui.screen

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas as GraphicsCanvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bizoneceo.nardy.engine.BAR
import com.bizoneceo.nardy.engine.Phase
import com.bizoneceo.nardy.engine.Player
import com.bizoneceo.nardy.engine.Variant
import com.bizoneceo.nardy.engine.WinKind
import com.bizoneceo.nardy.ui.board.BoardTheme
import com.bizoneceo.nardy.ui.board.CheckerColors
import com.bizoneceo.nardy.ui.board.Spot
import com.bizoneceo.nardy.ui.board.drawBoard
import com.bizoneceo.nardy.ui.board.drawChecker
import com.bizoneceo.nardy.ui.board.drawOffSlab
import com.bizoneceo.nardy.ui.board.spotOf
import com.bizoneceo.nardy.ui.board.themeById
import com.bizoneceo.nardy.ui.dice.DiePhase
import com.bizoneceo.nardy.ui.dice.EbonyDie
import com.bizoneceo.nardy.ui.dice.IvoryDie
import com.bizoneceo.nardy.ui.dice.drawDie
import kotlinx.coroutines.flow.first
import kotlin.math.roundToInt
import kotlin.math.sin

fun playerName(p: Player) = if (p == Player.WHITE) "Белые" else "Чёрные"

fun variantName(v: Variant) = if (v == Variant.LONG) "Длинные нарды" else "Короткие нарды"

private fun BoardTheme.checkers(p: Player): CheckerColors = if (p == Player.WHITE) white else black

@Composable
fun GameScreen(
    controller: GameController,
    settings: Settings,
    fold: FoldInfo?,
    onMenu: () -> Unit,
) {
    val theme = themeById(settings.themeId)
    val state = controller.state
    BoxWithConstraints(Modifier.fillMaxSize().background(theme.background)) {
        val density = LocalDensity.current
        val dir = LocalLayoutDirection.current
        val insets = WindowInsets.safeDrawing
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        val content = Rect(
            insets.getLeft(density, dir).toFloat(),
            insets.getTop(density).toFloat(),
            w - insets.getRight(density, dir),
            h - insets.getBottom(density),
        )
        val minPanel = with(density) { 76.dp.toPx() }
        val layout = remember(state.variant, content, fold, settings.faceToFace, minPanel) {
            computeGameLayout(state.variant, content, fold, minPanel, settings.faceToFace)
        }
        SideEffect {
            controller.layout = layout
            controller.syncTray()
        }

        StaticBoard(layout, theme, w, h)
        DynamicLayer(controller, layout, theme, settings.faceToFace)

        for ((player, spec) in layout.panels) {
            PanelHost(spec.rect, spec.upsideDown) {
                PlayerPanel(
                    controller = controller,
                    player = player,
                    vertical = spec.side == PanelSide.LEFT || spec.side == PanelSide.RIGHT,
                    theme = theme,
                    onMenu = if (player == Player.WHITE) onMenu else null,
                )
            }
        }

        if (state.phase == Phase.GAME_OVER || state.phase == Phase.MATCH_OVER) {
            val winner = state.result?.winner ?: Player.WHITE
            val flip = settings.faceToFace && winner == Player.BLACK && layout.panels[Player.BLACK]?.upsideDown == true
            PanelHost(layout.boardRect, flip) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    GameOverCard(controller, theme, onMenu)
                }
            }
        }
    }
    FrameLoop(controller)
}

@Composable
private fun FrameLoop(controller: GameController) {
    LaunchedEffect(controller) {
        while (true) {
            if (controller.needsFrames) {
                androidx.compose.runtime.withFrameNanos { controller.onFrame(it) }
            } else {
                controller.onIdle()
                snapshotFlow { controller.needsFrames }.first { it }
            }
        }
    }
}

@Composable
private fun PanelHost(rect: Rect, upsideDown: Boolean, content: @Composable () -> Unit) {
    val density = LocalDensity.current
    Box(
        Modifier
            .offset { IntOffset(rect.left.roundToInt(), rect.top.roundToInt()) }
            .size(with(density) { rect.width.toDp() }, with(density) { rect.height.toDp() })
            .graphicsLayer { if (upsideDown) rotationZ = 180f },
    ) { content() }
}

// ------------------------------------------------------------------ static board

fun renderStaticBoard(
    layout: GameLayout,
    theme: BoardTheme,
    width: Int,
    height: Int,
    density: Density,
): ImageBitmap {
    val bmp = ImageBitmap(width.coerceAtLeast(1), height.coerceAtLeast(1))
    CanvasDrawScope().draw(density, LayoutDirection.Ltr, GraphicsCanvas(bmp), Size(width.toFloat(), height.toFloat())) {
        layout.trayRect?.let { drawDiceTray(it, theme) }
        withBoardTransform(layout) { drawBoard(layout.geometry, theme) }
    }
    return bmp
}

private fun DrawScope.drawDiceTray(r: Rect, t: BoardTheme) {
    val frame = r.height * 0.06f
    drawRoundRect(
        Brush.linearGradient(listOf(t.frameLight, t.frameDark), r.topLeft, r.bottomRight),
        r.topLeft, r.size, CornerRadius(frame * 1.5f),
    )
    val inner = Rect(r.left + frame, r.top + frame, r.right - frame, r.bottom - frame)
    drawRoundRect(
        Brush.radialGradient(listOf(t.fieldLight, t.fieldDark), inner.center, inner.maxDimension * 0.7f),
        inner.topLeft, inner.size, CornerRadius(frame),
    )
    drawRoundRect(t.inlay.copy(alpha = 0.6f), inner.topLeft, inner.size, CornerRadius(frame), style = Stroke(frame * 0.12f))
}

fun DrawScope.withBoardTransform(layout: GameLayout, block: DrawScope.() -> Unit) {
    val t = layout.transform
    withTransform({
        translate(t.origin.x, t.origin.y)
        if (t.rotated) rotate(90f, Offset.Zero)
        scale(t.scale, t.scale, Offset.Zero)
    }, block)
}

@Composable
private fun StaticBoard(layout: GameLayout, theme: BoardTheme, w: Float, h: Float) {
    val density = LocalDensity.current
    val bmp = remember(layout, theme, w, h) {
        renderStaticBoard(layout, theme, w.toInt(), h.toInt(), density)
    }
    Canvas(Modifier.fillMaxSize()) { drawImage(bmp) }
}

// ------------------------------------------------------------------ dynamic layer

@Composable
private fun DynamicLayer(c: GameController, layout: GameLayout, theme: BoardTheme, faceToFace: Boolean) {
    val measurer = rememberTextMeasurer()
    Canvas(
        Modifier
            .fillMaxSize()
            .pointerInput(c) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val tracker = VelocityTracker()
                    tracker.addPosition(down.uptimeMillis, down.position)
                    c.onDown(down.position)
                    var finished = false
                    while (!finished) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id }
                        if (change == null) {
                            c.onCancel()
                            finished = true
                        } else {
                            tracker.addPosition(change.uptimeMillis, change.position)
                            if (!change.pressed) {
                                val v = tracker.calculateVelocity()
                                c.onUp(change.position, Offset(v.x, v.y))
                                finished = true
                            } else {
                                c.onMove(change.position)
                            }
                            change.consume()
                        }
                    }
                }
            },
    ) {
        drawDynamic(c, layout, theme, faceToFace, measurer)
    }
}

fun DrawScope.drawDynamic(
    c: GameController,
    layout: GameLayout,
    theme: BoardTheme,
    faceToFace: Boolean,
    measurer: TextMeasurer,
) {
    val now = c.frameNanos
    val s = c.state
    val g = layout.geometry
    val t = layout.transform
    val pos = s.position
    val pulse = (sin(now / 1e9 * 5.0) * 0.5 + 0.5).toFloat()
    val radius = g.checker / 2
    val interact = c.canInteract
    val movable = if (interact && c.selected == null && c.drag == null) s.movableFrom else emptySet()
    val labels = ArrayList<Triple<Offset, String, Boolean>>()

    withBoardTransform(layout) {
        fun ring(center: Offset, color: Color, width: Float, r: Float = radius) =
            drawCircle(color, r + width / 2, center, style = Stroke(width))

        for (abs in 1..24) {
            val owner = pos.ownerAbs(abs) ?: continue
            val spot = Spot.Point(abs)
            val total = GameController.countAt(pos, spot)
            val visible = c.visibleCount(pos, spot)
            for (i in 0 until visible) drawChecker(g.checkerCenter(abs, i, total), radius, theme.checkers(owner))
            if (visible <= 0) continue
            val top = g.checkerCenter(abs, visible - 1, total)
            if (owner == s.current) {
                val rel = s.variant.rel(s.current, abs)
                if (rel in movable) ring(top, theme.accent.copy(alpha = 0.35f + 0.4f * pulse), 0.05f)
                if (c.selected == rel && c.drag == null) {
                    ring(top, theme.accent, 0.09f)
                    ring(top, theme.accent.copy(alpha = 0.3f * pulse), 0.2f, radius + 0.08f)
                }
            }
            if (visible > 5) labels += Triple(top, visible.toString(), owner == Player.BLACK)
        }

        for (p in Player.entries) {
            val bar = Spot.Bar(p)
            val n = pos.bar(p)
            val visible = c.visibleCount(pos, bar)
            for (i in 0 until visible) drawChecker(g.barCenter(p, i, n), radius, theme.checkers(p))
            if (visible > 0) {
                val top = g.barCenter(p, visible - 1, n)
                if (p == s.current && BAR in movable) ring(top, theme.accent.copy(alpha = 0.35f + 0.4f * pulse), 0.05f)
                if (p == s.current && c.selected == BAR && c.drag == null) ring(top, theme.accent, 0.09f)
                if (visible > 1) labels += Triple(top, visible.toString(), p == Player.BLACK)
            }
            val off = Spot.Off(p)
            for (i in 0 until c.visibleCount(pos, off)) drawOffSlab(g.offSlab(p, i), theme.checkers(p))
        }

        // Where the selected checker can go.
        for (route in c.selectedRoutes) {
            val spot = s.variant.spotOf(s.current, route.to)
            if (spot is Spot.Off) {
                val tray = g.offTray(s.current)
                drawRoundRect(
                    theme.accent.copy(alpha = 0.18f + 0.25f * pulse), tray.topLeft, tray.size, CornerRadius(0.12f),
                )
                drawRoundRect(theme.accent, tray.topLeft, tray.size, CornerRadius(0.12f), style = Stroke(0.07f))
            } else {
                val at = g.stackTop(spot, GameController.countAt(pos, spot), extra = 1)
                drawChecker(at, radius, theme.checkers(s.current), alpha = 0.32f)
                ring(at, theme.accent.copy(alpha = 0.55f + 0.45f * pulse), 0.07f)
            }
        }

        for (f in c.flyers) {
            drawChecker(f.positionAt(now), radius, theme.checkers(f.player), lifted = f.liftAt(now))
        }
        c.drag?.let { drawChecker(it.board, radius, theme.checkers(s.current), lifted = 1f) }
    }

    // Stack counters, upright for the owner of the stack (black sits opposite in face-to-face mode).
    val fontPx = t.scale * 0.42f
    val style = TextStyle(
        fontSize = (fontPx / fontScale / density).sp,
        fontWeight = FontWeight.Bold,
        color = Color(0xFF1B1410),
        textAlign = TextAlign.Center,
    )
    for ((boardPos, text, blackOwned) in labels) {
        val center = t.toScreen(boardPos)
        drawCircle(theme.accent, fontPx * 0.72f, center)
        val measured = measurer.measure(text, style)
        val tl = center - Offset(measured.size.width / 2f, measured.size.height / 2f)
        val flip = faceToFace && blackOwned
        withTransform({ if (flip) rotate(180f, center) }) { drawText(measured, topLeft = tl) }
    }

    // Dice.
    val canThrow = c.canThrow
    for ((i, d) in c.tray.dice.withIndex()) {
        val colors = if (c.openingThrow) (if (i == 0) IvoryDie else EbonyDie) else theme.dice
        val alpha = when {
            d.phase != DiePhase.DOCKED -> 1f
            canThrow -> 0.5f + 0.35f * pulse
            c.dieUsed(i) -> 0.3f
            else -> 1f
        }
        if (d.phase == DiePhase.DOCKED && canThrow) {
            drawCircle(theme.accent.copy(alpha = 0.25f * pulse), d.size * 0.95f, d.pos)
        }
        drawDie(d.pos, d.size, d.q, d.height, colors, alpha)
    }
    if (s.phase == Phase.MOVING && s.dice.size == 2 && s.dice[0] == s.dice[1] && !c.tray.isBusy) {
        val a = c.tray.dice[0].pos
        val b = c.tray.dice[1].pos
        val mid = (a + b) / 2f
        val dir = (b - a).let { if (it.getDistance() < 1f) Offset(1f, 0f) else it / it.getDistance() }
        // Beside the pair of dice, whichever way they are lined up.
        val side = Offset(-dir.y, dir.x).let { if (it.y < 0 || (it.y == 0f && it.x < 0)) -it else it }
        val badge = "×${s.remaining.size}"
        val m = measurer.measure(badge, style.copy(color = theme.accent, fontSize = style.fontSize * 0.9f))
        val at = mid + side * (c.tray.dockSize * 0.95f) - Offset(m.size.width / 2f, m.size.height / 2f)
        drawText(m, topLeft = at)
    }
}

// ------------------------------------------------------------------ panels

private fun statusFor(c: GameController, player: Player): String {
    val s = c.state
    val mine = s.current == player
    return when (s.phase) {
        Phase.OPENING_ROLL -> when {
            s.openingDice.size == 2 && s.openingDice[0] == s.openingDice[1] && c.diceSettled ->
                "Поровну! Бросайте ещё раз"
            !c.diceSettled -> "Кубики катятся…"
            else -> "Кто ходит первым? Смахните кубики"
        }
        Phase.AWAIT_ROLL -> when {
            !mine -> "Ход соперника"
            c.openingThrow && s.openingDice.size == 2 ->
                "Вы начинаете (${s.openingDice[0]}:${s.openingDice[1]}) — бросайте"
            else -> "Ваш бросок — смахните кубики"
        }
        Phase.MOVING -> when {
            !mine -> "Ход соперника"
            !c.diceSettled -> "Кубики катятся…"
            s.isBlocked -> "Нет ходов — передайте ход"
            s.canMove -> "Ходите: " + s.remaining.joinToString(" · ")
            else -> "Ход сделан"
        }
        Phase.GAME_OVER, Phase.MATCH_OVER -> if (s.result?.winner == player) "Победа!" else "Партия проиграна"
    }
}

@Composable
private fun PlayerPanel(
    controller: GameController,
    player: Player,
    vertical: Boolean,
    theme: BoardTheme,
    onMenu: (() -> Unit)?,
) {
    val s = controller.state
    val active = s.current == player &&
        (s.phase == Phase.MOVING || s.phase == Phase.AWAIT_ROLL) || s.phase == Phase.OPENING_ROLL
    val canAct = s.current == player && s.phase == Phase.MOVING && controller.diceSettled
    val score = if (s.matchLength > 0) "${s.score(player)} / ${s.matchLength}" else s.score(player).toString()
    val info = "Пипсы ${s.position.pipCount(player)} · Снято ${s.position.off(player)}"

    val name: @Composable () -> Unit = {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Canvas(Modifier.size(26.dp)) {
                drawChecker(center, size.minDimension * 0.42f, theme.checkers(player))
            }
            Spacer(Modifier.width(8.dp))
            Column {
                Text(
                    "${playerName(player)}  ·  $score",
                    color = if (active) theme.accent else Color(0xFFBFB3A0),
                    fontWeight = FontWeight.Bold, fontSize = 16.sp, fontFamily = FontFamily.Serif,
                )
                Text(info, color = Color(0xFF9C9080), fontSize = 12.sp)
            }
        }
    }
    val status: @Composable () -> Unit = {
        Text(
            statusFor(controller, player),
            color = if (active) Color(0xFFF2E6CF) else Color(0xFF8E8475),
            fontSize = 15.sp,
            textAlign = TextAlign.Center,
        )
    }
    val compact = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
    val buttons: @Composable () -> Unit = {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (canAct && s.canUndo) {
                OutlinedButton(onClick = { controller.undo() }, contentPadding = compact) { Text("↶", fontSize = 18.sp, color = theme.accent) }
            }
            if (canAct && !s.canMove) {
                Button(
                    onClick = { controller.endTurn() },
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = theme.accent, contentColor = Color(0xFF1B1410)),
                ) { Text(if (s.isBlocked) "Передать ход" else "Готово ✓", fontWeight = FontWeight.Bold) }
            }
            if (onMenu != null) {
                OutlinedButton(onClick = onMenu, contentPadding = compact) { Text("☰", fontSize = 18.sp, color = Color(0xFFBFB3A0)) }
            }
        }
    }

    val shape = RoundedCornerShape(18.dp)
    val bg = Modifier
        .padding(6.dp)
        .background(Color(0x66000000), shape)
        .border(1.dp, if (active) theme.accent.copy(alpha = 0.7f) else Color(0x33FFFFFF), shape)
        .padding(horizontal = 12.dp, vertical = 6.dp)

    if (vertical) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                bg.widthIn(max = 280.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                name()
                status()
                buttons()
            }
        }
    } else {
        BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (maxWidth < 560.dp && maxHeight > 120.dp) {
                // Phone-width panel (cover screen): stack the info above the buttons.
                Column(bg, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        name()
                        Spacer(Modifier.weight(1f))
                        buttons()
                    }
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { status() }
                }
            } else {
                Row(bg, verticalAlignment = Alignment.CenterVertically) {
                    name()
                    Box(Modifier.weight(1f).padding(horizontal = 10.dp), contentAlignment = Alignment.Center) { status() }
                    buttons()
                }
            }
        }
    }
}

@Composable
private fun GameOverCard(c: GameController, theme: BoardTheme, onMenu: () -> Unit) {
    val s = c.state
    val r = s.result ?: return
    val kind = when (r.kind) {
        WinKind.SINGLE -> "Ойн · +1"
        WinKind.MARS -> "Марс! · +2"
        WinKind.KOKS -> "Кокс! · +3"
    }
    val matchOver = s.phase == Phase.MATCH_OVER
    Column(
        Modifier
            .background(Color(0xEE1A120C), RoundedCornerShape(24.dp))
            .border(2.dp, theme.accent, RoundedCornerShape(24.dp))
            .padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            (if (matchOver) "Матч: победа — " else "Победа — ") + playerName(r.winner).lowercase(),
            color = theme.accent, fontSize = 28.sp, fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold,
        )
        Text(kind, color = Color(0xFFF2E6CF), fontSize = 20.sp)
        Text(
            "Счёт  ${s.score(Player.WHITE)} : ${s.score(Player.BLACK)}",
            color = Color(0xFFBFB3A0), fontSize = 16.sp,
        )
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onMenu) { Text("Меню", color = Color(0xFFBFB3A0)) }
            Button(
                onClick = { if (matchOver) c.newMatch(s.variant, s.matchLength) else c.nextGame() },
                colors = ButtonDefaults.buttonColors(containerColor = theme.accent, contentColor = Color(0xFF1B1410)),
            ) { Text(if (matchOver) "Новый матч" else "Следующая партия", fontWeight = FontWeight.Bold) }
        }
    }
}
