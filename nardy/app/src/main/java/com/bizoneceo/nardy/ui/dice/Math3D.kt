package com.bizoneceo.nardy.ui.dice

import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class Vec3(val x: Float, val y: Float, val z: Float) {
    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
    operator fun times(k: Float) = Vec3(x * k, y * k, z * k)
    operator fun unaryMinus() = Vec3(-x, -y, -z)
    infix fun dot(o: Vec3) = x * o.x + y * o.y + z * o.z
    infix fun cross(o: Vec3) = Vec3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
    val length: Float get() = sqrt(x * x + y * y + z * z)
    fun normalized(): Vec3 = length.let { if (it < 1e-6f) this else this * (1f / it) }

    companion object {
        val X = Vec3(1f, 0f, 0f)
        val Y = Vec3(0f, 1f, 0f)
        val Z = Vec3(0f, 0f, 1f)
        val ZERO = Vec3(0f, 0f, 0f)
    }
}

/** Unit quaternion for die orientation (local → world). */
data class Quat(val w: Float, val x: Float, val y: Float, val z: Float) {
    operator fun times(q: Quat) = Quat(
        w * q.w - x * q.x - y * q.y - z * q.z,
        w * q.x + x * q.w + y * q.z - z * q.y,
        w * q.y - x * q.z + y * q.w + z * q.x,
        w * q.z + x * q.y - y * q.x + z * q.w,
    )

    fun rotate(v: Vec3): Vec3 {
        // v' = v + 2w(u × v) + 2 u × (u × v), u = (x, y, z)
        val u = Vec3(x, y, z)
        val t = (u cross v) * 2f
        return v + t * w + (u cross t)
    }

    fun normalized(): Quat {
        val n = sqrt(w * w + x * x + y * y + z * z)
        return Quat(w / n, x / n, y / n, z / n)
    }

    fun dot(q: Quat) = w * q.w + x * q.x + y * q.y + z * q.z

    companion object {
        val IDENTITY = Quat(1f, 0f, 0f, 0f)

        fun axisAngle(axis: Vec3, angle: Float): Quat {
            val a = axis.normalized()
            val s = sin(angle / 2)
            return Quat(cos(angle / 2), a.x * s, a.y * s, a.z * s)
        }

        /** Rotation by angular velocity [omega] (rad/s) over [dt] seconds. */
        fun fromAngularVelocity(omega: Vec3, dt: Float): Quat {
            val angle = omega.length * dt
            return if (angle < 1e-6f) IDENTITY else axisAngle(omega, angle)
        }

        /** Shortest rotation turning unit vector [a] into unit vector [b]. */
        fun fromTo(a: Vec3, b: Vec3): Quat {
            val d = (a dot b).coerceIn(-1f, 1f)
            if (d > 0.99999f) return IDENTITY
            if (d < -0.99999f) {
                var axis = Vec3.X cross a
                if (axis.length < 1e-3f) axis = Vec3.Y cross a
                return axisAngle(axis, Math.PI.toFloat())
            }
            return axisAngle(a cross b, acos(d))
        }

        /** Rotation whose rows are the given orthonormal right-handed basis (maps u→X, v→Y, n→Z). */
        fun fromRows(u: Vec3, v: Vec3, n: Vec3): Quat {
            val m00 = u.x; val m01 = u.y; val m02 = u.z
            val m10 = v.x; val m11 = v.y; val m12 = v.z
            val m20 = n.x; val m21 = n.y; val m22 = n.z
            val trace = m00 + m11 + m22
            return when {
                trace > 0 -> {
                    val s = sqrt(trace + 1f) * 2
                    Quat(0.25f * s, (m21 - m12) / s, (m02 - m20) / s, (m10 - m01) / s)
                }
                m00 > m11 && m00 > m22 -> {
                    val s = sqrt(1f + m00 - m11 - m22) * 2
                    Quat((m21 - m12) / s, 0.25f * s, (m01 + m10) / s, (m02 + m20) / s)
                }
                m11 > m22 -> {
                    val s = sqrt(1f + m11 - m00 - m22) * 2
                    Quat((m02 - m20) / s, (m01 + m10) / s, 0.25f * s, (m12 + m21) / s)
                }
                else -> {
                    val s = sqrt(1f + m22 - m00 - m11) * 2
                    Quat((m10 - m01) / s, (m02 + m20) / s, (m12 + m21) / s, 0.25f * s)
                }
            }.normalized()
        }

        fun slerp(a: Quat, b0: Quat, t: Float): Quat {
            var b = b0
            var d = a.dot(b)
            if (d < 0) {
                b = Quat(-b.w, -b.x, -b.y, -b.z)
                d = -d
            }
            if (d > 0.9995f) {
                return Quat(
                    a.w + (b.w - a.w) * t, a.x + (b.x - a.x) * t,
                    a.y + (b.y - a.y) * t, a.z + (b.z - a.z) * t,
                ).normalized()
            }
            val theta = acos(d)
            val sa = sin((1 - t) * theta) / sin(theta)
            val sb = sin(t * theta) / sin(theta)
            return Quat(
                a.w * sa + b.w * sb, a.x * sa + b.x * sb,
                a.y * sa + b.y * sb, a.z * sa + b.z * sb,
            )
        }
    }
}

/** A die face: outward normal and two in-plane axes, all in die-local space (u × v = n). */
data class Face(val value: Int, val n: Vec3, val u: Vec3, val v: Vec3)

/** Opposite faces sum to seven, like a real die. */
val DIE_FACES: List<Face> = listOf(
    Face(1, Vec3.Z, Vec3.X, Vec3.Y),
    Face(6, -Vec3.Z, Vec3.X, -Vec3.Y),
    Face(2, Vec3.X, Vec3.Y, Vec3.Z),
    Face(5, -Vec3.X, Vec3.Y, -Vec3.Z),
    Face(3, Vec3.Y, Vec3.Z, Vec3.X),
    Face(4, -Vec3.Y, Vec3.Z, -Vec3.X),
)

fun faceOf(value: Int): Face = DIE_FACES.first { it.value == value }

/** Orientation showing [value] towards the viewer (+Z), rotated by [yaw] around the view axis. */
fun restingOrientation(value: Int, yaw: Float = 0f): Quat {
    val f = faceOf(value)
    return Quat.axisAngle(Vec3.Z, yaw) * Quat.fromRows(f.u, f.v, f.n)
}

/** Pip layout of each value, in face coordinates (-1..1). */
val PIPS: Map<Int, List<Pair<Float, Float>>> = mapOf(
    1 to listOf(0f to 0f),
    2 to listOf(-1f to -1f, 1f to 1f),
    3 to listOf(-1f to -1f, 0f to 0f, 1f to 1f),
    4 to listOf(-1f to -1f, 1f to -1f, -1f to 1f, 1f to 1f),
    5 to listOf(-1f to -1f, 1f to -1f, 0f to 0f, -1f to 1f, 1f to 1f),
    6 to listOf(-1f to -1f, -1f to 0f, -1f to 1f, 1f to -1f, 1f to 0f, 1f to 1f),
)
