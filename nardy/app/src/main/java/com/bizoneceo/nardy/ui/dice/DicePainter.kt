package com.bizoneceo.nardy.ui.dice

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

data class DieColors(val body: Color, val pip: Color, val edge: Color)

val IvoryDie = DieColors(Color(0xFFF6F0E2), Color(0xFF1E1A17), Color(0xFFB9AE98))
val EbonyDie = DieColors(Color(0xFF2A2421), Color(0xFFF1E6CC), Color(0xFF0D0A09))
val RubyDie = DieColors(Color(0xFFB0182A), Color(0xFFFFF4DE), Color(0xFF5E0812))

private val LIGHT = Vec3(-0.45f, -0.6f, 0.75f).normalized()
private val CORNERS: List<Vec3> = buildList {
    for (x in listOf(-1f, 1f)) for (y in listOf(-1f, 1f)) for (z in listOf(-1f, 1f)) add(Vec3(x, y, z))
}

/**
 * Draws a die as a true 3D cube (perspective projected) with bevelled
 * edges, lambert-shaded faces and round pips, plus a soft contact shadow.
 */
fun DrawScope.drawDie(
    center: Offset,
    size: Float,
    q: Quat,
    height: Float,
    colors: DieColors,
    alpha: Float = 1f,
) {
    val lift = 1f + height / (size * 7f)
    val h = size * 0.5f * lift
    val focal = size * 6f

    fun project(p: Vec3): Offset {
        val k = focal / (focal - p.z)
        return Offset(center.x + p.x * k, center.y + p.y * k)
    }

    // Contact shadow, drifting away and fading as the die lifts.
    val sh = Offset(size * 0.12f + height * 0.35f, size * 0.18f + height * 0.5f)
    val shR = size * (0.78f + height / (size * 6f))
    drawCircle(
        brush = Brush.radialGradient(
            0f to Color.Black.copy(alpha = 0.45f * alpha / lift),
            0.6f to Color.Black.copy(alpha = 0.22f * alpha / lift),
            1f to Color.Transparent,
            center = center + sh,
            radius = shR,
        ),
        radius = shR,
        center = center + sh,
    )

    val world = CORNERS.map { q.rotate(it * h) }
    val projected = world.map(::project)

    // Silhouette in the edge colour gives the rounded bevel between faces.
    val hull = convexHull(projected)
    val hullPath = Path().apply {
        moveTo(hull[0].x, hull[0].y)
        for (i in 1 until hull.size) lineTo(hull[i].x, hull[i].y)
        close()
    }
    val edge = colors.edge.copy(alpha = alpha)
    drawPath(hullPath, edge, style = Fill)
    drawPath(hullPath, edge, style = Stroke(width = size * 0.12f, join = StrokeJoin.Round))

    for (face in DIE_FACES) {
        val n = q.rotate(face.n)
        if (n.z <= 0.02f) continue
        val u = q.rotate(face.u)
        val v = q.rotate(face.v)
        val c = n * h
        val inset = h * 0.9f
        val pts = listOf(
            c + u * inset + v * inset,
            c - u * inset + v * inset,
            c - u * inset - v * inset,
            c + u * inset - v * inset,
        ).map(::project)
        val light = 0.58f + 0.42f * max(0f, n dot LIGHT)
        val shade = lerp(colors.edge, colors.body, light.coerceIn(0f, 1f))
        val facePath = Path().apply {
            moveTo(pts[0].x, pts[0].y)
            for (i in 1..3) lineTo(pts[i].x, pts[i].y)
            close()
        }
        val faceAlpha = alpha * (n.z * 3f).coerceIn(0f, 1f)
        drawPath(facePath, shade.copy(alpha = faceAlpha))
        drawPath(
            facePath, shade.copy(alpha = faceAlpha),
            style = Stroke(width = size * 0.07f, join = StrokeJoin.Round),
        )
        // Subtle sheen towards the light.
        if (light > 0.8f) {
            drawPath(facePath, Color.White.copy(alpha = (light - 0.8f) * 0.6f * faceAlpha))
        }

        if (n.z < 0.12f) continue
        val pipR = h * 0.2f
        val spread = h * 0.52f
        val pipColor = colors.pip.copy(alpha = faceAlpha)
        for ((pu, pv) in PIPS.getValue(face.value)) {
            val pc = c + u * (pu * spread) + v * (pv * spread)
            val pipPath = Path()
            for (i in 0 until 14) {
                val a = i / 14f * 6.2832f
                val p = project(pc + u * (cos(a) * pipR) + v * (sin(a) * pipR))
                if (i == 0) pipPath.moveTo(p.x, p.y) else pipPath.lineTo(p.x, p.y)
            }
            pipPath.close()
            drawPath(pipPath, pipColor)
        }
    }
}

private fun convexHull(points: List<Offset>): List<Offset> {
    val pts = points.sortedWith(compareBy({ it.x }, { it.y }))
    fun cross(o: Offset, a: Offset, b: Offset) = (a.x - o.x) * (b.y - o.y) - (a.y - o.y) * (b.x - o.x)
    val lower = ArrayList<Offset>()
    for (p in pts) {
        while (lower.size >= 2 && cross(lower[lower.size - 2], lower.last(), p) <= 0) lower.removeAt(lower.size - 1)
        lower += p
    }
    val upper = ArrayList<Offset>()
    for (p in pts.asReversed()) {
        while (upper.size >= 2 && cross(upper[upper.size - 2], upper.last(), p) <= 0) upper.removeAt(upper.size - 1)
        upper += p
    }
    lower.removeAt(lower.size - 1)
    upper.removeAt(upper.size - 1)
    return lower + upper
}
