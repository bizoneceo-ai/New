package com.bizoneceo.nardy.ui.board

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.bizoneceo.nardy.engine.BAR
import com.bizoneceo.nardy.engine.OFF
import com.bizoneceo.nardy.engine.Player
import com.bizoneceo.nardy.engine.Variant

/** Something on the board a checker can be picked from or dropped onto. */
sealed interface Spot {
    /** A point, absolute numbering 1..24. */
    data class Point(val abs: Int) : Spot
    data class Bar(val player: Player) : Spot
    data class Off(val player: Player) : Spot
}

/** Converts between the mover's relative coordinates and board spots. */
fun Variant.spotOf(player: Player, rel: Int): Spot = when (rel) {
    BAR -> Spot.Bar(player)
    OFF -> Spot.Off(player)
    else -> Spot.Point(abs(player, rel))
}

fun Variant.relOf(player: Player, spot: Spot): Int? = when (spot) {
    is Spot.Point -> rel(player, spot.abs)
    is Spot.Bar -> if (spot.player == player) BAR else null
    is Spot.Off -> if (spot.player == player) OFF else null
}

/**
 * Board layout in "board units" (1 unit = width of one point), always
 * described horizontally; [BoardTransform] maps it to the screen, rotated
 * if needed. Absolute points: 1..6 bottom-right, 7..12 bottom-left,
 * 13..18 top-left, 19..24 top-right.
 *
 * [barWidth] is variable so that on dual-screen devices the bar can be
 * stretched to cover the physical hinge.
 */
class BoardGeometry(val variant: Variant, val barWidth: Float = 1.0f) {
    val frame = 0.42f
    val trayWidth = 1.15f
    val rail = 0.22f
    val pointLength = 4.6f
    val fieldHeight = 10.6f
    val checker = 0.9f

    val width: Float = 2 * (frame + trayWidth + rail) + 12f + barWidth
    val height: Float = 2 * frame + fieldHeight

    val fieldLeft = frame + trayWidth + rail
    val fieldTop = frame
    val fieldBottom = frame + fieldHeight
    val barLeft = fieldLeft + 6f
    val barRight = barLeft + barWidth
    val fieldRight = barRight + 6f
    val midY = fieldTop + fieldHeight / 2

    val leftHalf = Rect(fieldLeft, fieldTop, barLeft, fieldBottom)
    val rightHalf = Rect(barRight, fieldTop, fieldRight, fieldBottom)
    val bar = Rect(barLeft, fieldTop, barRight, fieldBottom)
    val field = Rect(fieldLeft, fieldTop, fieldRight, fieldBottom)
    val leftTray = Rect(frame, fieldTop, frame + trayWidth, fieldBottom)
    val rightTray = Rect(width - frame - trayWidth, fieldTop, width - frame, fieldBottom)

    /** Column 0..11 left to right, and whether it is in the top row. */
    fun column(abs: Int): Int = if (abs <= 12) 12 - abs else abs - 13
    fun isTop(abs: Int): Boolean = abs > 12

    fun columnCenterX(col: Int): Float = fieldLeft + col + 0.5f + if (col >= 6) barWidth else 0f

    /** Triangle of a point: base centre, tip. */
    fun pointBase(abs: Int): Offset =
        Offset(columnCenterX(column(abs)), if (isTop(abs)) fieldTop else fieldBottom)

    fun pointTip(abs: Int): Offset =
        Offset(columnCenterX(column(abs)), if (isTop(abs)) fieldTop + pointLength else fieldBottom - pointLength)

    private fun spacing(count: Int, room: Float): Float =
        if (count <= 1) checker else minOf(checker, (room - checker) / (count - 1))

    /** Centre of the [index]-th checker (0 = against the rail) of a stack of [count] on point [abs]. */
    fun checkerCenter(abs: Int, index: Int, count: Int): Offset {
        // Up to five sit side by side; taller stacks squeeze to stay on the point.
        val sp = if (count <= 5) checker else spacing(count, pointLength + 0.35f)
        val x = columnCenterX(column(abs))
        val offset = checker / 2 + index * sp
        return Offset(x, if (isTop(abs)) fieldTop + offset else fieldBottom - offset)
    }

    /** Bar stacks: white waits in the top half (it re-enters top-right), black in the bottom. */
    fun barCenter(player: Player, index: Int, count: Int): Offset {
        val room = fieldHeight / 2 - 0.9f
        val sp = if (count <= 1) checker else minOf(checker, (room - checker) / (count - 1))
        val x = (barLeft + barRight) / 2
        val off = 0.75f + checker / 2 + index * sp
        return Offset(x, if (player == Player.WHITE) midY - off else midY + off)
    }

    /** Which tray and half a player bears off into: next to their home. */
    fun offTray(player: Player): Rect {
        val home = variant.abs(player, 1)
        val right = home <= 6 || home >= 19
        val tray = if (right) rightTray else leftTray
        return if (home > 12) Rect(tray.left, tray.top, tray.right, midY - 0.1f)
        else Rect(tray.left, midY + 0.1f, tray.right, tray.bottom)
    }

    val offSlabHeight = 0.3f

    /** Rect of the [index]-th borne-off checker (seen edge-on), stacked from the outer end. */
    fun offSlab(player: Player, index: Int): Rect {
        val t = offTray(player)
        val fromTop = t.top < midY - 1f
        val y = if (fromTop) t.top + 0.12f + index * offSlabHeight else t.bottom - 0.12f - (index + 1) * offSlabHeight
        return Rect(t.left + 0.12f, y, t.right - 0.12f, y + offSlabHeight - 0.04f)
    }

    /** Where the top checker of [spot] is (or would go, for [extra] = 1). */
    fun stackTop(spot: Spot, count: Int, extra: Int = 0): Offset = when (spot) {
        is Spot.Point -> {
            val n = count + extra
            checkerCenter(spot.abs, (n - 1).coerceAtLeast(0), n.coerceAtLeast(1))
        }
        is Spot.Bar -> {
            val n = count + extra
            barCenter(spot.player, (n - 1).coerceAtLeast(0), n.coerceAtLeast(1))
        }
        is Spot.Off -> offSlab(spot.player, (count + extra - 1).coerceAtLeast(0)).center
    }

    /** Dice rest in the gap between the two rows of the half nearer to the player's home. */
    fun diceDock(player: Player, index: Int): Offset {
        val half = if (variant.abs(player, 1).let { it <= 6 || it >= 19 }) rightHalf else leftHalf
        val cx = half.center.x
        return Offset(cx + (if (index == 0) -0.62f else 0.62f), midY)
    }

    /** Hit-testing in board units. */
    fun spotAt(p: Offset): Spot? {
        if (p.y < fieldTop - 0.3f || p.y > fieldBottom + 0.3f) return null
        val top = p.y < midY
        return when {
            p.x in barLeft..barRight -> Spot.Bar(if (top) Player.WHITE else Player.BLACK)
            p.x in fieldLeft..barLeft || p.x in barRight..fieldRight -> {
                val local = if (p.x < barLeft) p.x - fieldLeft else p.x - barRight + 6f
                val col = local.toInt().coerceIn(0, 11)
                Spot.Point(if (top) 13 + col else 12 - col)
            }
            p.x < fieldLeft || p.x > fieldRight -> {
                val right = p.x > fieldRight
                Player.entries.firstOrNull { pl ->
                    val t = offTray(pl)
                    (t.left > field.center.x) == right && (t.top < midY) == top
                }?.let { Spot.Off(it) }
            }
            else -> null
        }
    }
}

/**
 * Board units → screen pixels. When [rotated], the board is turned 90°
 * clockwise (board top edge on the right of the screen) to fit portrait screens.
 */
data class BoardTransform(val scale: Float, val rotated: Boolean, val origin: Offset) {
    fun toScreen(p: Offset): Offset =
        if (!rotated) Offset(origin.x + p.x * scale, origin.y + p.y * scale)
        else Offset(origin.x - p.y * scale, origin.y + p.x * scale)

    fun toBoard(s: Offset): Offset =
        if (!rotated) Offset((s.x - origin.x) / scale, (s.y - origin.y) / scale)
        else Offset((s.y - origin.y) / scale, (origin.x - s.x) / scale)

    fun rectToScreen(r: Rect): Rect {
        val a = toScreen(r.topLeft)
        val b = toScreen(r.bottomRight)
        return Rect(minOf(a.x, b.x), minOf(a.y, b.y), maxOf(a.x, b.x), maxOf(a.y, b.y))
    }
}
