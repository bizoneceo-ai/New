package com.bizoneceo.nardy.trainer

import com.bizoneceo.nardy.engine.Ai
import com.bizoneceo.nardy.engine.AiLevel
import com.bizoneceo.nardy.engine.CHECKERS_PER_PLAYER
import com.bizoneceo.nardy.engine.GameResult
import com.bizoneceo.nardy.engine.GameState
import com.bizoneceo.nardy.engine.NetEvaluator
import com.bizoneceo.nardy.engine.NeuralNet
import com.bizoneceo.nardy.engine.Phase
import com.bizoneceo.nardy.engine.Player
import com.bizoneceo.nardy.engine.Position
import com.bizoneceo.nardy.engine.TurnContext
import com.bizoneceo.nardy.engine.Variant
import com.bizoneceo.nardy.engine.winKindOf
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.random.Random

/**
 * Trains a position evaluator by TD(0) self-play.
 *
 * Usage: trainer <SHORT|LONG> <games> <out.bin> [threads]
 * Continues from <out.bin> if it already exists.
 */
fun main(args: Array<String>) {
    val variant = Variant.valueOf(args[0])
    val games = args[1].toInt()
    val out = File(args[2])
    val threads = args.getOrNull(3)?.toInt() ?: Runtime.getRuntime().availableProcessors()

    val net = if (out.exists()) out.inputStream().use { NeuralNet.load(it) }
    else NeuralNet().also { it.randomize(Random(1)) }
    println("training $variant for $games games on $threads threads, from ${if (out.exists()) "checkpoint" else "scratch"}")

    val counter = AtomicInteger()
    val started = System.currentTimeMillis()
    val workers = (0 until threads).map { t ->
        thread(name = "selfplay-$t") {
            val ev = NetEvaluator(net)
            val rnd = Random(System.nanoTime() + t)
            while (true) {
                val n = counter.incrementAndGet()
                if (n > games) break
                val progress = n / games.toFloat()
                val rate = maxOf(0.02f, 0.1f * (1 - progress))
                selfPlay(variant, ev, rnd, rate)
                if (t == 0 && n % 5000 < threads) {
                    synchronized(net) {
                        out.outputStream().use { net.save(it) }
                    }
                    val secs = (System.currentTimeMillis() - started) / 1000
                    println("games=$n rate=${"%.3f".format(rate)} ${secs}s  ${benchmark(variant, net, 200)}")
                }
            }
        }
    }
    workers.forEach { it.join() }
    out.outputStream().use { net.save(it) }
    println("final: ${benchmark(variant, net, 1000)}")
}

private fun headLimit(variant: Variant, firstTurn: Boolean, d1: Int, d2: Int) =
    if (variant == Variant.LONG && firstTurn && d1 == d2 && d1 in setOf(3, 4, 6)) 2 else 1

/** One self-play game; every position is pulled towards the next one's prediction. */
private fun selfPlay(variant: Variant, ev: NetEvaluator, rnd: Random, rate: Float) {
    var pos = Position.initial(variant)
    val firstTurn = booleanArrayOf(true, true)
    var current: Player
    var dice: List<Int>
    // Opening roll-off.
    while (true) {
        val w = rnd.nextInt(1, 7)
        val b = rnd.nextInt(1, 7)
        if (w == b) continue
        current = if (w > b) Player.WHITE else Player.BLACK
        dice = if (variant == Variant.SHORT) listOf(w, b) else roll(rnd)
        break
    }
    var prevPos: Position? = null
    var prevMover: Player? = null
    val target = FloatArray(NeuralNet.OUTPUTS)
    var plies = 0
    while (plies++ < 2000) {
        val ctx = TurnContext(
            pos, current, expand(dice), 0, headLimit(variant, firstTurn[current.ordinal], dice[0], dice[1]),
        )
        val plans = Ai.allTurns(ctx)
        var next = pos
        if (plans.isNotEmpty()) {
            var best = Float.NEGATIVE_INFINITY
            for (p in plans) {
                val e = ev.equity(p.result, current)
                if (e > best) {
                    best = e
                    next = p.result
                }
            }
        }
        firstTurn[current.ordinal] = false

        // The opponent's previous position should predict what this one predicts.
        if (prevPos != null && prevMover != null) {
            NeuralNet.flip(ev.outputs(next, current), target)
            ev.learn(prevPos, prevMover, target, rate)
        }
        if (next.off(current) == CHECKERS_PER_PLAYER) {
            NeuralNet.outcome(GameResult(current, winKindOf(next, current)), current, target)
            ev.learn(next, current, target, rate)
            return
        }
        prevPos = next
        prevMover = current
        pos = next
        current = current.opponent
        dice = roll(rnd)
    }
}

private fun roll(rnd: Random) = listOf(rnd.nextInt(1, 7), rnd.nextInt(1, 7))

private fun expand(d: List<Int>) = if (d[0] == d[1]) List(4) { d[0] } else d

/** Net (greedy, one ply) against the hand-written "amateur" heuristic. */
private fun benchmark(variant: Variant, net: NeuralNet, games: Int): String {
    val snapshot = NeuralNet(net.inputs, net.hidden, net.outputs)
    net.w1.copyInto(snapshot.w1); net.b1.copyInto(snapshot.b1)
    net.w2.copyInto(snapshot.w2); net.b2.copyInto(snapshot.b2)
    val ev = NetEvaluator(snapshot)
    val rnd = Random(12345)
    var wins = 0
    var points = 0
    repeat(games) { g ->
        val netSide = if (g % 2 == 0) Player.WHITE else Player.BLACK
        var s = GameState.newMatch(variant)
        while (s.phase != Phase.GAME_OVER && s.phase != Phase.MATCH_OVER) {
            s = when (s.phase) {
                Phase.OPENING_ROLL -> s.rollOpening(rnd.nextInt(1, 7), rnd.nextInt(1, 7))
                Phase.AWAIT_ROLL -> s.roll(rnd.nextInt(1, 7), rnd.nextInt(1, 7))
                else -> {
                    val moves = if (s.current == netSide) Ai.chooseWithNet(s, ev)
                    else Ai.chooseHeuristic(s, AiLevel.MASTER, rnd, 300)
                    var t = s
                    for (m in moves) t = t.play(m)
                    if (t.phase == Phase.MOVING) t.endTurn() else t
                }
            }
        }
        val r = s.result!!
        if (r.winner == netSide) {
            wins++
            points += r.points
        } else {
            points -= r.points
        }
    }
    return "vs heuristic: won $wins/$games, ${"%+.2f".format(points / games.toFloat())} pts/game"
}
