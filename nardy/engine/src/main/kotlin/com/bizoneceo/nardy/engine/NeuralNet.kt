package com.bizoneceo.nardy.engine

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import kotlin.math.exp
import kotlin.random.Random

/**
 * Position evaluator learned by self-play (TD learning, as in TD-Gammon).
 *
 * The net looks at a position right after [Player] `me` has moved (the
 * opponent is to roll) and predicts, from `me`'s point of view:
 * `[win, win ≥ mars, win koks, lose ≥ mars, lose koks]`.
 */
class NeuralNet(val inputs: Int = INPUTS, val hidden: Int = HIDDEN, val outputs: Int = OUTPUTS) {
    /** Hidden weights, row per hidden unit: w1[h * inputs + i]. */
    val w1 = FloatArray(hidden * inputs)
    val b1 = FloatArray(hidden)
    /** Output weights, row per output: w2[o * hidden + h]. */
    val w2 = FloatArray(outputs * hidden)
    val b2 = FloatArray(outputs)

    fun randomize(random: Random) {
        for (i in w1.indices) w1[i] = (random.nextFloat() - 0.5f) * 0.2f
        for (i in w2.indices) w2[i] = (random.nextFloat() - 0.5f) * 0.2f
    }

    /** Scratch buffers for one evaluating thread. */
    class Work(net: NeuralNet) {
        val x = FloatArray(net.inputs)
        val active = IntArray(net.inputs)
        var activeCount = 0
        val h = FloatArray(net.hidden)
        val out = FloatArray(net.outputs)
    }

    fun forward(w: Work) {
        // Inputs are mostly zero, so only the non-zero ones are visited.
        var n = 0
        for (i in 0 until inputs) if (w.x[i] != 0f) w.active[n++] = i
        w.activeCount = n
        for (j in 0 until hidden) {
            var s = b1[j]
            val row = j * inputs
            for (k in 0 until n) {
                val i = w.active[k]
                s += w1[row + i] * w.x[i]
            }
            w.h[j] = sigmoid(s)
        }
        for (o in 0 until outputs) {
            var s = b2[o]
            val row = o * hidden
            for (j in 0 until hidden) s += w2[row + j] * w.h[j]
            w.out[o] = sigmoid(s)
        }
    }

    /** One gradient step pulling the last [forward] result towards [target]. */
    fun train(w: Work, target: FloatArray, rate: Float) {
        val dOut = FloatArray(outputs)
        for (o in 0 until outputs) {
            val y = w.out[o]
            dOut[o] = (target[o] - y) * y * (1 - y)
        }
        for (j in 0 until hidden) {
            val hj = w.h[j]
            var back = 0f
            for (o in 0 until outputs) back += dOut[o] * w2[o * hidden + j]
            val dH = back * hj * (1 - hj) * rate
            if (dH != 0f) {
                val row = j * inputs
                for (k in 0 until w.activeCount) {
                    val i = w.active[k]
                    w1[row + i] += dH * w.x[i]
                }
                b1[j] += dH
            }
        }
        for (o in 0 until outputs) {
            val d = dOut[o] * rate
            val row = o * hidden
            for (j in 0 until hidden) w2[row + j] += d * w.h[j]
            b2[o] += d
        }
    }

    fun save(out: OutputStream) {
        val d = DataOutputStream(out)
        d.writeInt(MAGIC)
        d.writeInt(inputs)
        d.writeInt(hidden)
        d.writeInt(outputs)
        for (a in listOf(w1, b1, w2, b2)) for (v in a) d.writeFloat(v)
        d.flush()
    }

    companion object {
        const val INPUTS = 2 * (24 * 4 + 2) + 2
        const val HIDDEN = 80
        const val OUTPUTS = 5
        private const val MAGIC = 0x4E415244 // "NARD"

        fun load(input: InputStream): NeuralNet {
            val d = DataInputStream(input.buffered())
            require(d.readInt() == MAGIC) { "not a nardy net" }
            val net = NeuralNet(d.readInt(), d.readInt(), d.readInt())
            for (a in listOf(net.w1, net.b1, net.w2, net.b2)) for (i in a.indices) a[i] = d.readFloat()
            return net
        }

        private fun sigmoid(x: Float): Float = 1f / (1f + exp(-x))

        /**
         * TD-Gammon style encoding, from [me]'s side: for each point and each
         * side, "1+", "2+", "3+" and the overflow; checkers on the bar and off;
         * and both pip counts.
         */
        fun encode(pos: Position, me: Player, x: FloatArray) {
            var i = 0
            for (side in listOf(me, me.opponent)) {
                for (r in 1..24) {
                    val n = pos.count(side, r)
                    x[i] = if (n >= 1) 1f else 0f
                    x[i + 1] = if (n >= 2) 1f else 0f
                    x[i + 2] = if (n >= 3) 1f else 0f
                    x[i + 3] = if (n > 3) (n - 3) / 2f else 0f
                    i += 4
                }
                x[i++] = pos.bar(side) / 2f
                x[i++] = pos.off(side) / 15f
            }
            x[i++] = pos.pipCount(me) / 200f
            x[i] = pos.pipCount(me.opponent) / 200f
        }

        /** Expected points for "me" from the five outputs. */
        fun equity(o: FloatArray): Float = 2 * o[0] - 1 + o[1] - o[3] + o[2] - o[4]

        /** Same prediction seen from the other side. */
        fun flip(o: FloatArray, into: FloatArray) {
            into[0] = 1 - o[0]
            into[1] = o[3]
            into[2] = o[4]
            into[3] = o[1]
            into[4] = o[2]
        }

        /** Final result of a finished game from [me]'s point of view. */
        fun outcome(result: GameResult, me: Player, into: FloatArray) {
            val won = result.winner == me
            val mars = result.kind != WinKind.SINGLE
            val koks = result.kind == WinKind.KOKS
            into[0] = if (won) 1f else 0f
            into[1] = if (won && mars) 1f else 0f
            into[2] = if (won && koks) 1f else 0f
            into[3] = if (!won && mars) 1f else 0f
            into[4] = if (!won && koks) 1f else 0f
        }
    }
}

/** Ready-to-use evaluator with its own scratch space (one per thread). */
class NetEvaluator(val net: NeuralNet) {
    private val work = NeuralNet.Work(net)

    /** Raw outputs for [pos] just after [me] moved. Returned array is reused. */
    fun outputs(pos: Position, me: Player): FloatArray {
        // A finished game needs no guessing.
        if (pos.off(me) == CHECKERS_PER_PLAYER || pos.off(me.opponent) == CHECKERS_PER_PLAYER) {
            val winner = if (pos.off(me) == CHECKERS_PER_PLAYER) me else me.opponent
            NeuralNet.outcome(GameResult(winner, winKindOf(pos, winner)), me, work.out)
            return work.out
        }
        NeuralNet.encode(pos, me, work.x)
        net.forward(work)
        return work.out
    }

    fun equity(pos: Position, me: Player): Float = NeuralNet.equity(outputs(pos, me))

    /** Teach the net that [pos] (after [me] moved) is worth [target]. */
    fun learn(pos: Position, me: Player, target: FloatArray, rate: Float) {
        NeuralNet.encode(pos, me, work.x)
        net.forward(work)
        net.train(work, target, rate)
    }
}
