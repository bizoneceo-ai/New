package com.bizoneceo.nardy.ui.dice

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.min
import kotlin.random.Random

enum class DiePhase { DOCKED, HELD, ROLLING, SETTLING, RESTING, DOCKING }

/** One physical die, in screen pixels. */
class DieBody {
    var value = 1
    var phase = DiePhase.DOCKED
    var pos = Offset.Zero
    var vel = Offset.Zero
    /** Height above the table (px) — drives scale and shadow. */
    var height = 0f
    var vHeight = 0f
    var q: Quat = restingOrientation(1)
    var omega = Vec3.ZERO
    var size = 0f

    internal var timer = 0f
    internal var fromQ = q
    internal var toQ = q
    internal var fromPos = Offset.Zero
    internal var fromSize = 0f
    internal var holdOffset = Offset.Zero
}

/** Sound/haptic hooks fired by the simulation. [strength] is 0..1. */
fun interface DiceListener {
    fun onImpact(strength: Float)
}

/**
 * 2.5D dice physics: the dice slide and bounce inside [arena] on the
 * screen plane, hop vertically under gravity, and tumble in full 3D.
 * The result of a throw is decided by the game (fair RNG) before the throw;
 * the simulation just makes sure each die comes to rest showing it.
 */
class DiceTray(private val random: Random = Random.Default) {
    val dice = listOf(DieBody(), DieBody())

    var arena = Rect.Zero
        private set
    private var docks = listOf(Offset.Zero, Offset.Zero)
    /** Edge length of a die while rolling. */
    var dieSize = 1f
        private set
    /** Edge length of a die resting in its dock. */
    var dockSize = 1f
        private set

    var listener: DiceListener? = null
    /** Called once both dice show their final values. */
    var onSettled: (() -> Unit)? = null

    private var settledNotified = true
    private var rollTime = 0f

    val isBusy: Boolean get() = dice.any { it.phase != DiePhase.DOCKED }
    val isRolling: Boolean
        get() = dice.any { it.phase == DiePhase.HELD || it.phase == DiePhase.ROLLING || it.phase == DiePhase.SETTLING }
    val isHeld: Boolean get() = dice[0].phase == DiePhase.HELD

    fun configure(arena: Rect, docks: List<Offset>, dieSize: Float, dockSize: Float) {
        val layoutChanged = arena != this.arena || dieSize != this.dieSize || dockSize != this.dockSize
        val docksChanged = docks != this.docks
        this.arena = arena
        this.docks = docks
        this.dieSize = dieSize
        this.dockSize = dockSize
        if (layoutChanged) {
            // Layout changed (fold/unfold, rotation): finish any throw instantly.
            val wasRolling = isRolling
            dice.forEachIndexed { i, d -> snapToDock(d, i) }
            if (wasRolling) notifySettled()
        } else if (docksChanged) {
            // Turn passed to the other side: resting dice move over, flying ones dock there later.
            dice.forEachIndexed { i, d -> if (d.phase == DiePhase.DOCKED) snapToDock(d, i) }
        }
    }

    /** Show the dice resting in the dock with the given values. */
    fun showDocked(values: List<Int>) {
        dice.forEachIndexed { i, d ->
            if (d.phase == DiePhase.DOCKED || values.getOrNull(i) != d.value) {
                d.value = values.getOrElse(i) { d.value }
                snapToDock(d, i)
            }
        }
    }

    private fun snapToDock(d: DieBody, i: Int) {
        d.phase = DiePhase.DOCKED
        d.pos = docks[i]
        d.vel = Offset.Zero
        d.height = 0f
        d.vHeight = 0f
        d.size = dockSize
        d.q = restingOrientation(d.value)
        d.omega = Vec3.ZERO
    }

    /** Pick the dice up under the finger. */
    fun hold(finger: Offset) {
        dice.forEachIndexed { i, d ->
            d.phase = DiePhase.HELD
            d.holdOffset = Offset((if (i == 0) -0.62f else 0.62f) * dieSize, (if (i == 0) -0.2f else 0.25f) * dieSize)
            d.pos = finger + d.holdOffset
            d.size = dieSize
            d.height = dieSize * 0.9f
            d.omega = randomAxis() * 4f
        }
    }

    fun moveHeld(finger: Offset) {
        for (d in dice) if (d.phase == DiePhase.HELD) d.pos = finger + d.holdOffset
    }

    /**
     * Throw the dice with the finger's release [velocity] (px/s). They will
     * come to rest showing [values].
     */
    fun throwDice(velocity: Offset, values: List<Int>) {
        val minSpeed = dieSize * 9f
        val maxSpeed = dieSize * 45f
        var v = velocity
        var speed = hypot(v.x, v.y)
        if (speed < minSpeed) {
            // A tap or a lazy flick: toss them towards the arena centre.
            val c = arena.center - (dice[0].pos + dice[1].pos) / 2f
            val len = hypot(c.x, c.y).takeIf { it > 1f } ?: 1f
            val dir = if (len > dieSize) c / len else randomDir()
            v = dir * (minSpeed * 1.6f)
            speed = minSpeed * 1.6f
        } else if (speed > maxSpeed) {
            v = v * (maxSpeed / speed)
            speed = maxSpeed
        }
        rollTime = 0f
        settledNotified = false
        dice.forEachIndexed { i, d ->
            d.value = values[i]
            if (d.phase != DiePhase.HELD) {
                d.pos = docks[i]
                d.size = dieSize
            }
            d.pos = clampInside(d.pos)
            d.phase = DiePhase.ROLLING
            val spread = (random.nextFloat() - 0.5f) * 0.45f
            val k = 0.85f + random.nextFloat() * 0.3f
            d.vel = rotate(v, spread) * k
            d.height = maxOf(d.height, dieSize * 0.6f)
            d.vHeight = dieSize * (3f + random.nextFloat() * 3f)
            d.omega = randomAxis() * (14f + random.nextFloat() * 10f)
        }
    }

    /** Advance the simulation; returns true while anything still moves. */
    fun step(dtRaw: Float): Boolean {
        val dt = dtRaw.coerceIn(0f, 1f / 30f)
        if (dice.any { it.phase == DiePhase.ROLLING }) rollTime += dt
        for (d in dice) {
            when (d.phase) {
                DiePhase.HELD -> {
                    // Rattle in the hand.
                    d.omega = (d.omega + randomAxis() * (60f * dt)) * 0.97f
                    d.q = (Quat.fromAngularVelocity(d.omega, dt) * d.q).normalized()
                }
                DiePhase.ROLLING, DiePhase.SETTLING -> integrate(d, dt)
                DiePhase.RESTING -> {
                    d.timer += dt
                }
                DiePhase.DOCKING -> {
                    d.timer += dt
                    val t = min(1f, d.timer / DOCK_TIME)
                    val e = 1f - (1f - t) * (1f - t) * (1f - t)
                    d.pos = d.fromPos + (docks[dice.indexOf(d)] - d.fromPos) * e
                    d.size = d.fromSize + (dockSize - d.fromSize) * e
                    d.q = Quat.slerp(d.fromQ, d.toQ, e)
                    if (t >= 1f) d.phase = DiePhase.DOCKED
                }
                DiePhase.DOCKED -> Unit
            }
        }
        collide(dice[0], dice[1])

        // Force everything down if a throw takes too long.
        if (rollTime > 4f) {
            for (d in dice) if (d.phase == DiePhase.ROLLING) beginSettle(d)
        }
        if (dice.all { it.phase == DiePhase.RESTING }) {
            notifySettled()
            if (dice.all { it.timer > REST_TIME }) {
                dice.forEach { d ->
                    d.phase = DiePhase.DOCKING
                    d.timer = 0f
                    d.fromPos = d.pos
                    d.fromSize = d.size
                    d.fromQ = d.q
                    d.toQ = restingOrientation(d.value)
                }
            }
        }
        return isBusy
    }

    private fun notifySettled() {
        if (!settledNotified) {
            settledNotified = true
            onSettled?.invoke()
        }
    }

    private fun integrate(d: DieBody, dt: Float) {
        val s = dieSize
        // Vertical hop.
        if (d.height > 0f || d.vHeight > 0f) {
            d.vHeight -= s * 38f * dt
            d.height += d.vHeight * dt
            if (d.height <= 0f) {
                d.height = 0f
                if (d.vHeight < -s * 2.2f) {
                    listener?.onImpact(min(1f, -d.vHeight / (s * 14f)))
                    d.vHeight = -d.vHeight * 0.38f
                    // A bounce kicks the tumble around.
                    d.omega = d.omega * 0.7f + randomAxis() * (6f + random.nextFloat() * 6f)
                } else {
                    d.vHeight = 0f
                }
            }
        }
        val onGround = d.height <= 0.5f

        // Sliding with friction.
        d.pos += d.vel * dt
        if (onGround) {
            val speed = hypot(d.vel.x, d.vel.y)
            val decel = s * 6f * dt
            val k = exp(-2.2f * dt)
            d.vel = if (speed <= decel) Offset.Zero else d.vel * ((speed - decel) / speed * k)
        } else {
            d.vel *= exp(-0.25f * dt)
        }
        if (d.phase == DiePhase.SETTLING) d.vel *= exp(-9f * dt)

        // Rails.
        // A tumbling cube reaches out to ~0.7 of its edge from the centre.
        val half = d.size * 0.7f
        var impact = 0f
        if (d.pos.x < arena.left + half) {
            d.pos = Offset(arena.left + half, d.pos.y); impact = -d.vel.x
            d.vel = Offset(-d.vel.x * 0.55f, d.vel.y)
        } else if (d.pos.x > arena.right - half) {
            d.pos = Offset(arena.right - half, d.pos.y); impact = d.vel.x
            d.vel = Offset(-d.vel.x * 0.55f, d.vel.y)
        }
        if (d.pos.y < arena.top + half) {
            d.pos = Offset(d.pos.x, arena.top + half); impact = maxOf(impact, -d.vel.y)
            d.vel = Offset(d.vel.x, -d.vel.y * 0.55f)
        } else if (d.pos.y > arena.bottom - half) {
            d.pos = Offset(d.pos.x, arena.bottom - half); impact = maxOf(impact, d.vel.y)
            d.vel = Offset(d.vel.x, -d.vel.y * 0.55f)
        }
        if (impact > s * 2f) {
            listener?.onImpact(min(1f, impact / (s * 30f)))
            d.omega = d.omega + randomAxis() * 8f
        }

        // Tumbling: on the table the die rolls in the direction it slides.
        if (d.phase == DiePhase.ROLLING) {
            if (onGround) {
                val r = s * 0.5f
                val target = Vec3(-d.vel.y / r, d.vel.x / r, d.omega.z * 0.9f)
                val blend = min(1f, 9f * dt)
                d.omega = d.omega + (target - d.omega) * blend
            } else {
                d.omega = d.omega * exp(-0.4f * dt)
            }
            d.q = (Quat.fromAngularVelocity(d.omega, dt) * d.q).normalized()

            val speed = hypot(d.vel.x, d.vel.y)
            if (onGround && d.vHeight == 0f && speed < s * 2.4f && rollTime > 0.45f) beginSettle(d)
        } else {
            // Settling: ease into the face that shows the decided value.
            d.timer += dt
            val t = min(1f, d.timer / SETTLE_TIME)
            val e = 1f - (1f - t) * (1f - t)
            d.q = Quat.slerp(d.fromQ, d.toQ, e)
            if (t >= 1f) {
                d.phase = DiePhase.RESTING
                d.timer = 0f
                d.vel = Offset.Zero
                listener?.onImpact(0.25f)
            }
        }
    }

    private fun beginSettle(d: DieBody) {
        d.phase = DiePhase.SETTLING
        d.timer = 0f
        d.height = 0f
        d.vHeight = 0f
        d.fromQ = d.q
        val n = d.q.rotate(faceOf(d.value).n)
        d.toQ = (Quat.fromTo(n, Vec3.Z) * d.q).normalized()
    }

    private fun collide(a: DieBody, b: DieBody) {
        val moving = setOf(DiePhase.ROLLING, DiePhase.SETTLING)
        if (a.phase !in moving && b.phase !in moving) return
        val minDist = dieSize * 1.08f
        val dx = b.pos.x - a.pos.x
        val dy = b.pos.y - a.pos.y
        val dist = hypot(dx, dy)
        if (dist >= minDist) return
        val n = if (dist < 1e-3f) Offset(1f, 0f) else Offset(dx / dist, dy / dist)
        val push = (minDist - dist) / 2f
        a.pos -= n * push
        b.pos += n * push
        val rel = (b.vel.x - a.vel.x) * n.x + (b.vel.y - a.vel.y) * n.y
        if (rel < 0) {
            val j = -rel * 0.85f
            a.vel -= n * j
            b.vel += n * j
            if (-rel > dieSize * 2f) listener?.onImpact(min(1f, -rel / (dieSize * 25f)))
        }
    }

    private fun clampInside(p: Offset): Offset {
        val h = dieSize * 0.6f
        if (arena.width < 2 * h || arena.height < 2 * h) return arena.center
        return Offset(p.x.coerceIn(arena.left + h, arena.right - h), p.y.coerceIn(arena.top + h, arena.bottom - h))
    }

    private fun randomAxis() = Vec3(
        random.nextFloat() * 2 - 1, random.nextFloat() * 2 - 1, random.nextFloat() * 2 - 1,
    ).normalized()

    private fun randomDir(): Offset {
        val a = random.nextFloat() * 6.2832f
        return Offset(kotlin.math.cos(a), kotlin.math.sin(a))
    }

    private fun rotate(v: Offset, angle: Float): Offset {
        val c = kotlin.math.cos(angle)
        val s = kotlin.math.sin(angle)
        return Offset(v.x * c - v.y * s, v.x * s + v.y * c)
    }

    private companion object {
        const val SETTLE_TIME = 0.3f
        const val REST_TIME = 0.55f
        const val DOCK_TIME = 0.42f
    }
}
