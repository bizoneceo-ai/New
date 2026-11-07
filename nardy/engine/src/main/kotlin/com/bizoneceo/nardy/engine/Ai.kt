package com.bizoneceo.nardy.engine

import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

enum class AiLevel(val title: String) {
    NOVICE("Новичок"),
    AMATEUR("Любитель"),
    MASTER("Мастер"),
}

/** A complete turn: the moves in order and the position they lead to. */
class TurnPlan(val moves: List<Move>, val result: Position)

/**
 * Computer opponent.
 *
 * Every distinct way to play the whole throw is generated (respecting the
 * "play as many dice as possible / the larger die" rules), then scored with a
 * hand-tuned evaluation for the variant. The master additionally looks one
 * throw ahead: for its best candidates it averages, over all 21 possible
 * opponent throws, the position after the opponent's best reply.
 */
object Ai {

    /** Moves for the current player of [state] (which must be in [Phase.MOVING]). */
    fun chooseTurn(state: GameState, level: AiLevel, random: Random, budgetMillis: Long = 2500): List<Move> {
        check(state.phase == Phase.MOVING)
        val me = state.current
        val plans = allTurns(state.context)
        if (plans.isEmpty()) return emptyList()
        if (plans.size == 1) return plans[0].moves

        val scored = plans.map { it to evaluate(it.result, me) }.sortedByDescending { it.second }
        return when (level) {
            AiLevel.NOVICE -> {
                // Plays sensibly most of the time, but often misses the best move.
                if (random.nextFloat() < 0.35f) scored.random(random).first.moves
                else scored.take(max(1, min(4, scored.size))).random(random).first.moves
            }
            AiLevel.AMATEUR -> scored.first().first.moves
            AiLevel.MASTER -> {
                val deadline = System.currentTimeMillis() + budgetMillis
                var best = scored.first()
                var bestValue = Double.NEGATIVE_INFINITY
                for ((plan, _) in scored.take(8)) {
                    if (System.currentTimeMillis() > deadline) break
                    val v = lookahead(state, plan.result, me, deadline) ?: break
                    if (v > bestValue) {
                        bestValue = v
                        best = plan to v
                    }
                }
                best.first.moves
            }
        }
    }

    /** Expected evaluation after every opponent throw answered with their best reply. */
    private fun lookahead(state: GameState, pos: Position, me: Player, deadline: Long): Double? {
        if (pos.off(me) == CHECKERS_PER_PLAYER) return WIN
        val opp = me.opponent
        var total = 0.0
        for (a in 1..6) for (b in a..6) {
            if (System.currentTimeMillis() > deadline) return null
            val dice = if (a == b) List(4) { a } else listOf(a, b)
            val ctx = TurnContext(pos, opp, dice, 0, state.headLimitFor(opp, a, b))
            val replies = allTurns(ctx)
            // The evaluation assumes the *other* side moves next, so a reply is
            // scored from the opponent's point of view (it is then my turn).
            val theirBest = if (replies.isEmpty()) -evaluate(pos, me)
            else replies.maxOf { evaluate(it.result, opp) }
            total += -theirBest * (if (a == b) 1.0 else 2.0)
        }
        return total / 36.0
    }

    /** All distinct complete turns from [ctx], following the "maximum dice" rules. */
    fun allTurns(ctx: TurnContext): List<TurnPlan> {
        val terminals = ArrayList<TurnPlan>()
        val seen = HashSet<Triple<Position, List<Int>, Int>>()
        fun dfs(c: TurnContext, path: List<Move>) {
            if (!seen.add(Triple(c.position, c.dice.sorted(), c.headMoves))) return
            var any = false
            for (die in c.dice.distinct()) {
                for (m in Rules.singleSteps(c, die)) {
                    any = true
                    dfs(c.after(m), path + m)
                }
            }
            if (!any) terminals += TurnPlan(path, c.position)
        }
        dfs(ctx, emptyList())

        val most = terminals.maxOfOrNull { it.moves.size } ?: 0
        if (most == 0) return emptyList()
        var plans = terminals.filter { it.moves.size == most }
        if (most == 1 && ctx.dice.size == 2 && ctx.dice[0] != ctx.dice[1]) {
            val biggest = plans.maxOf { it.moves[0].die }
            plans = plans.filter { it.moves[0].die == biggest }
        }
        val byResult = LinkedHashMap<Position, TurnPlan>()
        for (p in plans) byResult.putIfAbsent(p.result, p)
        return byResult.values.toList()
    }

    // ------------------------------------------------------------ evaluation

    private const val WIN = 10_000.0

    /**
     * Higher is better for [me], for a position where [me] has just moved
     * (the opponent is to move): only [me]'s blots are in immediate danger.
     */
    fun evaluate(pos: Position, me: Player): Double {
        val opp = me.opponent
        if (pos.off(me) == CHECKERS_PER_PLAYER) return WIN
        if (pos.off(opp) == CHECKERS_PER_PLAYER) return -WIN
        val pips = (pos.pipCount(opp) - pos.pipCount(me)).toDouble()
        return when (pos.variant) {
            Variant.SHORT -> {
                if (!shortContact(pos)) {
                    // Pure race: pips and checkers already off are all that matter.
                    pips + 2.0 * (pos.off(me) - pos.off(opp))
                } else {
                    0.6 * pips + shortFeatures(pos, me, exposed = true) - shortFeatures(pos, opp, exposed = false)
                }
            }
            Variant.LONG -> pips + longFeatures(pos, me) - longFeatures(pos, opp)
        }
    }

    /** Can the two armies still meet? (relative points of white vs black in short nardy). */
    private fun shortContact(pos: Position): Boolean {
        val w = pos.highestRel(Player.WHITE)
        val b = pos.highestRel(Player.BLACK)
        if (w == BAR || b == BAR) return true
        if (w == 0 || b == 0) return false
        // White's rearmost checker at white rel w is black rel 25 - w.
        return w + b > 25
    }

    private val SHOTS = intArrayOf(0, 11, 12, 14, 15, 15, 17, 6, 6, 5, 3, 2, 3)

    private fun shortFeatures(pos: Position, me: Player, exposed: Boolean): Double {
        val opp = me.opponent
        var score = 0.0
        val made = BooleanArray(26)
        var homePoints = 0
        for (r in 1..24) {
            val n = pos.count(me, r)
            if (n >= 2) {
                made[r] = true
                score += when (r) {
                    in 4..6 -> 4.0
                    in 1..3 -> 2.5
                    7 -> 3.5
                    in 8..11 -> 1.5
                    else -> 0.8
                }
                if (r <= 6) homePoints++
                if (n > 4) score -= (n - 4) * 0.6
            }
        }
        var run = 0
        var longest = 0
        for (r in 1..24) {
            run = if (made[r]) run + 1 else 0
            longest = max(longest, run)
        }
        score += longest * longest * 0.8

        // Blots: risk of being hit times what a hit costs.
        val oppOnBar = pos.bar(opp) > 0
        for (r in 1..24) {
            if (!exposed) break
            if (pos.count(me, r) != 1) continue
            var shots = 0
            // Opponent checkers travel through my rel numbers upwards; from the
            // bar they enter at my rel = die.
            if (oppOnBar && r <= 12) shots += SHOTS[r]
            for (q in 1 until r) {
                val d = r - q
                if (d > 12) continue
                if (pos.count(opp, 25 - q) > 0) shots += SHOTS[d]
            }
            val risk = min(1.0, shots / 36.0)
            score -= risk * (4.0 + (25 - r) * 0.18)
        }

        // Back checkers: an anchor in the opponent's home is safe, but checkers
        // stuck behind an opponent prime are a liability.
        if ((19..24).any { pos.count(me, it) >= 2 }) score += 2.5
        var oppRun = 0
        var oppPrime = 0
        for (r in 1..24) {
            // Opponent points as seen from my side, in front of my back checkers.
            oppRun = if (pos.count(opp, 25 - r) >= 2) oppRun + 1 else 0
            if (r in 13..23) oppPrime = max(oppPrime, oppRun)
        }
        val back = (19..24).sumOf { pos.count(me, it) } + pos.bar(me)
        if (oppPrime >= 3) score -= back * (oppPrime - 2) * 1.2

        val oppHome = (1..6).count { pos.count(opp, it) >= 2 }
        score -= pos.bar(me) * (5.0 + oppHome * 1.6)
        score += pos.off(me) * 1.0
        score += homePoints * 0.3
        return score
    }

    private fun longFeatures(pos: Position, me: Player): Double {
        val v = pos.variant
        val opp = me.opponent
        var score = 0.0

        // Spread: occupied points give flexibility and block the opponent.
        var occupied = 0
        for (r in 1..23) {
            val n = pos.count(me, r)
            if (n > 0) occupied++
            if (n > 4) score -= (n - 4) * 0.7
        }
        score += min(occupied, 10) * 1.6

        // Blocks along the opponent's path, valuable when opponent checkers are behind them.
        fun mineAtOpp(q: Int) = pos.count(me, v.rel(me, v.abs(opp, q))) > 0
        var q = 1
        while (q <= 24) {
            if (!mineAtOpp(q)) {
                q++
                continue
            }
            var hi = q
            while (hi + 1 <= 24 && mineAtOpp(hi + 1)) hi++
            val len = hi - q + 1
            if (len >= 2) {
                var behind = 0
                for (b in hi + 1..24) behind += pos.count(opp, b)
                if (behind > 0) score += len * len * 0.9 * min(1.0, behind / 5.0 + 0.4)
            }
            q = hi + 1
        }

        score += (1..6).sumOf { pos.count(me, it) } * 0.4
        score += pos.off(me) * 1.5
        return score
    }
}
