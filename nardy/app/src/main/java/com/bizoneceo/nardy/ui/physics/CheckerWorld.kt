package com.bizoneceo.nardy.ui.physics

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.bizoneceo.nardy.engine.Player
import com.bizoneceo.nardy.engine.Position
import com.bizoneceo.nardy.ui.board.BoardGeometry
import com.bizoneceo.nardy.ui.board.Spot
import kotlin.math.hypot
import kotlin.math.min

/**
 * Device tilt / shake, updated by the platform from the accelerometer.
 * Screen axes (x right, y down), in g: (0, 1) means "things fall towards
 * the bottom edge of the screen".
 */
class TiltSource {
    @Volatile var x = 0f
    @Volatile var y = 0f
}

class CheckerBody(val player: Player, var pos: Offset) {
    var vel = Offset.Zero
    var target = pos
    var region = Rect.Zero
    var stack: Spot? = null
}

/**
 * Real-time physics for the checkers lying on the board (board units).
 *
 * Every checker is a disc sliding on the wood with friction. The game
 * still decides where each checker belongs: a soft spring pulls it towards
 * its place in the stack, while device tilt, shakes, a dragged checker and
 * landing checkers push it around. Discs collide with each other and with
 * the rails, and report impacts for sound and vibration.
 */
class CheckerWorld {
    private val stacks = HashMap<Spot, MutableList<CheckerBody>>()
    private val landings = HashMap<Spot, Offset>()

    /** Tilt in board axes, in g. */
    var gravity = Offset.Zero
    var onImpact: ((Float) -> Unit)? = null

    private var lastImpactAt = 0f
    private var clock = 0f

    fun clear() {
        stacks.clear()
        landings.clear()
    }

    /** The next checker appearing on [spot] arrives with [velocity] (board units/s). */
    fun landed(spot: Spot, velocity: Offset) {
        landings[spot] = velocity
    }

    fun bodies(spot: Spot): List<CheckerBody> = stacks[spot] ?: emptyList()

    /** Reconcile bodies with the visible checkers of the position. */
    fun sync(position: Position, g: BoardGeometry, visible: (Spot) -> Int, total: (Spot) -> Int) {
        val spots = (1..24).map { Spot.Point(it) } + Player.entries.map { Spot.Bar(it) }
        for (spot in spots) {
            val owner = when (spot) {
                is Spot.Point -> position.ownerAbs(spot.abs)
                is Spot.Bar -> spot.player.takeIf { position.bar(it) > 0 }
                is Spot.Off -> null
            }
            val list = stacks.getOrPut(spot) { ArrayList() }
            val n = visible(spot).coerceAtLeast(0)
            if (owner == null || n == 0) {
                list.clear()
                continue
            }
            if (list.isNotEmpty() && list[0].player != owner) list.clear()
            while (list.size > n) list.removeAt(list.size - 1)
            val t = total(spot)
            val region = regionOf(spot, g)
            while (list.size < n) {
                val body = CheckerBody(owner, slot(spot, list.size, t, g))
                landings.remove(spot)?.let { body.vel = it }
                list += body
            }
            for ((i, b) in list.withIndex()) {
                b.target = slot(spot, i, t, g)
                b.region = region
                b.stack = spot
            }
        }
    }

    private fun slot(spot: Spot, i: Int, total: Int, g: BoardGeometry): Offset = when (spot) {
        is Spot.Point -> g.checkerCenter(spot.abs, i, total)
        is Spot.Bar -> g.barCenter(spot.player, i, total)
        is Spot.Off -> g.offSlab(spot.player, i).center
    }

    /** Checkers stay inside their half and row (or their half of the bar). */
    private fun regionOf(spot: Spot, g: BoardGeometry): Rect = when (spot) {
        is Spot.Point -> {
            val half = if (g.column(spot.abs) < 6) g.leftHalf else g.rightHalf
            if (g.isTop(spot.abs)) Rect(half.left, g.fieldTop, half.right, g.midY)
            else Rect(half.left, g.midY, half.right, g.fieldBottom)
        }
        is Spot.Bar ->
            if (spot.player == Player.WHITE) Rect(g.barLeft, g.fieldTop, g.barRight, g.midY)
            else Rect(g.barLeft, g.midY, g.barRight, g.fieldBottom)
        is Spot.Off -> g.offTray(spot.player)
    }

    /**
     * Advance by [dt] seconds. [pusher] is a checker held by a finger that
     * shoves the others aside (infinite mass).
     */
    fun step(dt: Float, radius: Float, pusher: Offset?) {
        val all = stacks.values.flatten()
        if (all.isEmpty()) return
        val h = dt.coerceIn(0f, 1f / 30f) / SUBSTEPS
        val d = radius * 2
        repeat(SUBSTEPS) {
            clock += h
            for (b in all) {
                val acc = (b.target - b.pos) * SPRING - b.vel * DAMPING + gravity * TILT
                b.vel += acc * h
                // Sliding friction of wood on wood.
                val speed = hypot(b.vel.x, b.vel.y)
                val dec = FRICTION * h
                b.vel = if (speed <= dec) Offset.Zero else b.vel * ((speed - dec) / speed)
                b.pos += b.vel * h
            }
            for (i in all.indices) {
                val a = all[i]
                for (j in i + 1 until all.size) {
                    val b = all[j]
                    val dx = b.pos.x - a.pos.x
                    val dy = b.pos.y - a.pos.y
                    if (dx > d || dx < -d || dy > d || dy < -d) continue
                    val dist = hypot(dx, dy)
                    // Tall stacks are drawn overlapping (squeezed onto the point):
                    // within one stack only keep the spacing the stack is drawn with.
                    val minDist = if (a.stack == b.stack) {
                        min(d, (b.target - a.target).getDistance())
                    } else {
                        d
                    }
                    if (dist >= minDist) continue
                    val n = if (dist < 1e-4f) Offset(0f, 1f) else Offset(dx / dist, dy / dist)
                    val push = (minDist - dist) / 2
                    a.pos -= n * push
                    b.pos += n * push
                    val vn = (b.vel.x - a.vel.x) * n.x + (b.vel.y - a.vel.y) * n.y
                    if (vn < 0) {
                        val j2 = -vn * (1 + RESTITUTION) / 2
                        a.vel -= n * j2
                        b.vel += n * j2
                        impact(-vn)
                    }
                }
            }
            if (pusher != null) {
                for (b in all) {
                    val dx = b.pos.x - pusher.x
                    val dy = b.pos.y - pusher.y
                    val dist = hypot(dx, dy)
                    if (dist >= d || dist < 1e-4f) continue
                    val n = Offset(dx / dist, dy / dist)
                    b.pos = pusher + n * d
                    val vn = b.vel.x * n.x + b.vel.y * n.y
                    if (vn < 0) b.vel -= n * (vn * (1 + RESTITUTION))
                    b.vel += n * 0.6f
                    impact(1.5f)
                }
            }
            for (b in all) {
                val r = b.region
                var hit = 0f
                if (b.pos.x < r.left + radius) {
                    b.pos = Offset(r.left + radius, b.pos.y); hit = -b.vel.x
                    b.vel = Offset(-b.vel.x * WALL_BOUNCE, b.vel.y)
                } else if (b.pos.x > r.right - radius) {
                    b.pos = Offset(r.right - radius, b.pos.y); hit = b.vel.x
                    b.vel = Offset(-b.vel.x * WALL_BOUNCE, b.vel.y)
                }
                if (b.pos.y < r.top + radius) {
                    b.pos = Offset(b.pos.x, r.top + radius); hit = maxOf(hit, -b.vel.y)
                    b.vel = Offset(b.vel.x, -b.vel.y * WALL_BOUNCE)
                } else if (b.pos.y > r.bottom - radius) {
                    b.pos = Offset(b.pos.x, r.bottom - radius); hit = maxOf(hit, b.vel.y)
                    b.vel = Offset(b.vel.x, -b.vel.y * WALL_BOUNCE)
                }
                if (hit > 0f) impact(hit)
            }
        }
    }

    private fun impact(speed: Float) {
        if (speed < 1.1f || clock - lastImpactAt < 0.045f) return
        lastImpactAt = clock
        onImpact?.invoke(min(1f, speed / 7f))
    }

    private companion object {
        const val SUBSTEPS = 4
        const val SPRING = 28f
        const val DAMPING = 3.2f
        const val TILT = 16f
        const val FRICTION = 1.4f
        const val RESTITUTION = 0.45f
        const val WALL_BOUNCE = 0.35f
    }
}
