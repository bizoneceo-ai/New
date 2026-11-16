package com.bizoneceo.nardy.ui.board

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.lerp
import kotlin.math.sin
import kotlin.random.Random

/*
 * Procedural board art. Everything here draws in board units (see
 * BoardGeometry) under a scaling transform, so it stays crisp at any size —
 * from the 6.5" cover screen to the 8" inner screen of a Fold.
 */

private fun DrawScope.woodGrain(r: Rect, color: Color, seed: Int, lines: Int, strength: Float) {
    val rnd = Random(seed)
    clipRect(r.left, r.top, r.right, r.bottom) {
        val step = 0.2f
        for (i in 0 until lines) {
            val baseY = r.top + (i + rnd.nextFloat()) * r.height / lines
            val a1 = 0.04f + rnd.nextFloat() * 0.12f
            val f1 = 0.3f + rnd.nextFloat() * 0.9f
            val p1 = rnd.nextFloat() * 6.28f
            val a2 = rnd.nextFloat() * 0.05f
            val f2 = 2f + rnd.nextFloat() * 3f
            val path = Path()
            var x = r.left - 0.5f
            path.moveTo(x, baseY)
            while (x < r.right + 0.5f) {
                x += step
                path.lineTo(x, baseY + a1 * sin(x * f1 + p1) + a2 * sin(x * f2 + p1 * 2))
            }
            val alpha = (0.05f + rnd.nextFloat() * 0.16f) * strength
            drawPath(path, color.copy(alpha = alpha), style = Stroke(width = 0.012f + rnd.nextFloat() * 0.05f))
        }
        // A couple of darker figure bands for a more natural look.
        repeat(3) {
            val y = r.top + rnd.nextFloat() * r.height
            drawRect(
                Brush.verticalGradient(
                    0f to Color.Transparent, 0.5f to color.copy(alpha = 0.10f * strength), 1f to Color.Transparent,
                    startY = y - 0.4f, endY = y + 0.4f,
                ),
                topLeft = Offset(r.left, y - 0.4f), size = Size(r.width, 0.8f),
            )
        }
    }
}

private fun DrawScope.clothTexture(r: Rect, color: Color, seed: Int) {
    val rnd = Random(seed)
    val n = (r.width * r.height * 28).toInt()
    for (i in 0 until n) {
        val p = Offset(r.left + rnd.nextFloat() * r.width, r.top + rnd.nextFloat() * r.height)
        drawCircle(color.copy(alpha = 0.06f + rnd.nextFloat() * 0.1f), radius = 0.012f + rnd.nextFloat() * 0.02f, center = p)
    }
}

/** Soft inner shadow along the edges of a recessed area. */
private fun DrawScope.innerShadow(r: Rect, depth: Float, alpha: Float) {
    val dark = Color.Black.copy(alpha = alpha)
    drawRect(Brush.verticalGradient(listOf(dark, Color.Transparent), r.top, r.top + depth), r.topLeft, Size(r.width, depth))
    drawRect(Brush.horizontalGradient(listOf(dark, Color.Transparent), r.left, r.left + depth), r.topLeft, Size(depth, r.height))
    val light = Color.Black.copy(alpha = alpha * 0.45f)
    drawRect(
        Brush.verticalGradient(listOf(Color.Transparent, light), r.bottom - depth * 0.6f, r.bottom),
        Offset(r.left, r.bottom - depth * 0.6f), Size(r.width, depth * 0.6f),
    )
    drawRect(
        Brush.horizontalGradient(listOf(Color.Transparent, light), r.right - depth * 0.6f, r.right),
        Offset(r.right - depth * 0.6f, r.top), Size(depth * 0.6f, r.height),
    )
}

private fun DrawScope.brassPlate(c: Offset, w: Float, h: Float, accent: Color) {
    val r = Rect(c.x - w / 2, c.y - h / 2, c.x + w / 2, c.y + h / 2)
    drawRoundRect(
        Brush.linearGradient(
            listOf(lerp(accent, Color.White, 0.35f), accent, lerp(accent, Color.Black, 0.45f)),
            start = r.topLeft, end = r.bottomRight,
        ),
        topLeft = r.topLeft, size = r.size, cornerRadius = CornerRadius(0.06f),
    )
    drawRoundRect(
        lerp(accent, Color.Black, 0.6f), topLeft = r.topLeft, size = r.size,
        cornerRadius = CornerRadius(0.06f), style = Stroke(0.015f),
    )
    // Hinge knuckles and screws.
    drawLine(lerp(accent, Color.Black, 0.5f), Offset(c.x, r.top + 0.04f), Offset(c.x, r.bottom - 0.04f), 0.02f)
    for (dy in listOf(-0.3f, 0.3f)) for (dx in listOf(-0.22f, 0.22f)) {
        val p = Offset(c.x + dx * w * 2f, c.y + dy * h)
        drawCircle(lerp(accent, Color.Black, 0.55f), 0.04f, p)
        drawCircle(lerp(accent, Color.White, 0.4f), 0.022f, p - Offset(0.01f, 0.01f))
    }
}

/** The static part of the board — drawn once into a cached bitmap. */
fun DrawScope.drawBoard(g: BoardGeometry, t: BoardTheme) {
    val all = Rect(0f, 0f, g.width, g.height)

    // Outer frame.
    drawRoundRect(
        Brush.linearGradient(listOf(t.frameLight, t.frameDark, t.frameLight), start = all.topLeft, end = all.bottomRight),
        size = all.size, cornerRadius = CornerRadius(0.45f),
    )
    woodGrain(all, t.frameGrain, seed = 7, lines = 70, strength = 1.2f)

    // Playing field halves.
    for ((i, half) in listOf(g.leftHalf, g.rightHalf).withIndex()) {
        drawRect(
            Brush.radialGradient(
                listOf(t.fieldLight, t.fieldDark),
                center = half.center, radius = half.maxDimension * 0.75f,
            ),
            topLeft = half.topLeft, size = half.size,
        )
        if (t.fieldIsCloth) clothTexture(half, t.fieldGrain, 100 + i)
        else woodGrain(half, t.fieldGrain, 20 + i, 46, 1f)
    }

    // Points.
    for (abs in 1..24) {
        val base = g.pointBase(abs)
        val tip = g.pointTip(abs)
        val even = (g.column(abs) + if (g.isTop(abs)) 0 else 1) % 2 == 0
        val c = if (even) t.pointA else t.pointB
        val hw = 0.47f
        val path = Path().apply {
            moveTo(base.x - hw, base.y)
            lineTo(base.x + hw, base.y)
            lineTo(tip.x, tip.y)
            close()
        }
        drawPath(
            path,
            Brush.linearGradient(
                listOf(lerp(c, Color.Black, 0.18f), c, lerp(c, Color.White, 0.12f)),
                start = base, end = tip,
            ),
        )
        drawPath(path, t.pointEdge.copy(alpha = 0.9f), style = Stroke(0.03f, join = StrokeJoin.Round))
        // Inlaid inner line.
        val dir = if (g.isTop(abs)) 1f else -1f
        val inner = Path().apply {
            moveTo(base.x - hw + 0.16f, base.y + dir * 0.08f)
            lineTo(base.x + hw - 0.16f, base.y + dir * 0.08f)
            lineTo(tip.x, tip.y - dir * 0.55f)
            close()
        }
        drawPath(inner, t.inlay.copy(alpha = 0.55f), style = Stroke(0.018f, join = StrokeJoin.Round))
        // Little diamond at the base, a classic nardy-board motif.
        val d = Offset(base.x, base.y + dir * 0.3f)
        val diamond = Path().apply {
            moveTo(d.x, d.y - 0.09f); lineTo(d.x + 0.07f, d.y); lineTo(d.x, d.y + 0.09f); lineTo(d.x - 0.07f, d.y); close()
        }
        drawPath(diamond, t.inlay.copy(alpha = 0.8f))
    }

    for (half in listOf(g.leftHalf, g.rightHalf)) {
        innerShadow(half, 0.32f, 0.55f)
        drawRect(t.inlay.copy(alpha = 0.7f), half.topLeft, half.size, style = Stroke(0.03f))
    }

    // Bear-off trays.
    for (tray in listOf(g.leftTray, g.rightTray)) {
        drawRoundRect(t.tray, tray.topLeft, tray.size, CornerRadius(0.12f))
        woodGrain(tray, Color.Black, 300 + tray.left.toInt(), 18, 0.8f)
        innerShadow(tray, 0.25f, 0.6f)
        drawRoundRect(t.inlay.copy(alpha = 0.5f), tray.topLeft, tray.size, CornerRadius(0.12f), style = Stroke(0.025f))
        drawLine(
            t.inlay.copy(alpha = 0.45f), Offset(tray.left + 0.15f, g.midY), Offset(tray.right - 0.15f, g.midY),
            0.025f, cap = StrokeCap.Round,
        )
    }

    // Bar: hinges and a central rosette.
    val bx = (g.barLeft + g.barRight) / 2
    brassPlate(Offset(bx, g.fieldTop + 1.4f), 0.36f, 1.1f, t.accent)
    brassPlate(Offset(bx, g.fieldBottom - 1.4f), 0.36f, 1.1f, t.accent)
    val rosette = Offset(bx, g.midY)
    drawCircle(t.inlay.copy(alpha = 0.85f), 0.28f, rosette, style = Stroke(0.03f))
    for (k in 0 until 8) {
        val a = k * Math.PI.toFloat() / 4
        drawCircle(t.inlay.copy(alpha = 0.8f), 0.05f, rosette + Offset(kotlin.math.cos(a) * 0.17f, sin(a) * 0.17f))
    }
    drawCircle(t.accent, 0.07f, rosette)

    // Outer bevel highlight.
    drawRoundRect(
        Brush.linearGradient(
            listOf(Color.White.copy(alpha = 0.25f), Color.Transparent, Color.Black.copy(alpha = 0.4f)),
            start = all.topLeft, end = all.bottomRight,
        ),
        topLeft = Offset(0.04f, 0.04f), size = Size(g.width - 0.08f, g.height - 0.08f),
        cornerRadius = CornerRadius(0.42f), style = Stroke(0.08f),
    )
}

/** A turned-wood / bone checker seen from above. */
fun DrawScope.drawChecker(center: Offset, radius: Float, c: CheckerColors, alpha: Float = 1f, lifted: Float = 0f) {
    val sh = Offset(0.05f + lifted * 0.25f, 0.08f + lifted * 0.35f)
    drawCircle(
        Brush.radialGradient(
            0f to Color.Black.copy(alpha = 0.5f * alpha), 0.75f to Color.Black.copy(alpha = 0.25f * alpha),
            1f to Color.Transparent, center = center + sh, radius = radius * (1.12f + lifted * 0.2f),
        ),
        radius = radius * (1.12f + lifted * 0.2f), center = center + sh,
    )
    val r = radius * (1f + lifted * 0.08f)
    drawCircle(
        Brush.radialGradient(
            0f to c.light, 0.45f to c.base, 1f to c.dark,
            center = center + Offset(-r * 0.35f, -r * 0.4f), radius = r * 1.5f,
        ),
        radius = r, center = center, alpha = alpha,
    )
    drawCircle(c.rim, r, center, alpha = alpha * 0.9f, style = Stroke(r * 0.07f))
    // Engraved concentric grooves.
    drawCircle(Color.Black.copy(alpha = 0.28f * alpha), r * 0.7f, center + Offset(r * 0.02f, r * 0.02f), style = Stroke(r * 0.06f))
    drawCircle(c.engrave, r * 0.68f, center, alpha = alpha * 0.9f, style = Stroke(r * 0.05f))
    drawCircle(Color.Black.copy(alpha = 0.22f * alpha), r * 0.36f, center, style = Stroke(r * 0.05f))
    drawCircle(c.engrave, r * 0.12f, center, alpha = alpha)
    // Specular highlight.
    drawCircle(
        Brush.radialGradient(
            listOf(Color.White.copy(alpha = 0.45f * alpha), Color.Transparent),
            center = center + Offset(-r * 0.38f, -r * 0.42f), radius = r * 0.5f,
        ),
        radius = r * 0.5f, center = center + Offset(-r * 0.38f, -r * 0.42f),
    )
}

/** A borne-off checker standing on its edge in the tray. */
fun DrawScope.drawOffSlab(r: Rect, c: CheckerColors) {
    drawRoundRect(
        Brush.verticalGradient(listOf(c.light, c.base, c.dark), r.top, r.bottom),
        r.topLeft, r.size, CornerRadius(r.height / 2),
    )
    drawRoundRect(c.rim, r.topLeft, r.size, CornerRadius(r.height / 2), style = Stroke(0.015f))
}
