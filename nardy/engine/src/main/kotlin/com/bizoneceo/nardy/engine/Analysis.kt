package com.bizoneceo.nardy.engine

import kotlin.math.exp
import kotlin.math.max

/** One turn as it was played: the state when the dice landed and the moves made. */
class TurnRecord(val start: GameState, val moves: List<Move>)

enum class PlayStyle(val title: String) {
    ATTACKING("атакующий"),
    BALANCED("сбалансированный"),
    DEFENSIVE("защитный"),
}

class Mistake(
    val turn: Int,
    val dice: List<Int>,
    val played: String,
    val best: String,
    /** Equity lost, in points per game. */
    val cost: Float,
)

/** Short post-game review of one player. */
class GameReview(
    val player: Player,
    /** 0..100, 100 = always the computer's top choice. */
    val accuracy: Int,
    val decisions: Int,
    val errors: Int,
    val blunders: Int,
    val style: PlayStyle,
    val styleNote: String,
    val mistakes: List<Mistake>,
)

/**
 * Reviews a finished game with the same evaluator the computer plays with:
 * every non-forced turn is compared with the best move, which gives the
 * accuracy and the biggest mistakes; how much more (or less) risk the player
 * took than the best move gives the style.
 */
object Analysis {
    private const val ERROR = 0.04f
    private const val BLUNDER = 0.10f

    fun review(history: List<TurnRecord>, player: Player): GameReview {
        val variant = history.firstOrNull()?.start?.variant ?: Variant.LONG
        val net = Ai.netFor(variant)
        val ev = net?.let { NetEvaluator(it) }
        fun value(pos: Position, me: Player): Float =
            ev?.equity(pos, me) ?: (Ai.evaluate(pos, me) / 30.0).toFloat()

        var decisions = 0
        var scoreSum = 0.0
        var errors = 0
        var blunders = 0
        var styleSum = 0f
        var attackFeatures = 0f
        val mistakes = ArrayList<Mistake>()
        var turnNo = 0

        for (rec in history) {
            val s = rec.start
            if (s.current != player) continue
            turnNo++
            val plans = Ai.allTurns(s.context)
            if (plans.size <= 1) continue
            var played = s.position
            for (m in rec.moves) played = played.apply(player, m)
            val playedValue = value(played, player)
            var best = plans[0]
            var bestValue = Float.NEGATIVE_INFINITY
            for (p in plans) {
                val v = value(p.result, player)
                if (v > bestValue) {
                    bestValue = v
                    best = p
                }
            }
            val loss = max(0f, bestValue - playedValue)
            decisions++
            scoreSum += exp(-loss / 0.07)
            if (loss >= ERROR) errors++
            if (loss >= BLUNDER) blunders++
            if (loss >= ERROR) {
                mistakes += Mistake(turnNo, s.dice, notation(rec.moves, variant, player), notation(best.moves, variant, player), loss)
            }
            val a = aggression(s.position, played, player, rec.moves)
            styleSum += a - aggression(s.position, best.result, player, best.moves)
            attackFeatures += a
        }

        val accuracy = if (decisions == 0) 100 else (100 * scoreSum / decisions).toInt()
        val lean = if (decisions == 0) 0f else styleSum / decisions
        val style = when {
            lean > 0.25f -> PlayStyle.ATTACKING
            lean < -0.25f -> PlayStyle.DEFENSIVE
            else -> PlayStyle.BALANCED
        }
        val note = when (variant) {
            Variant.SHORT -> when (style) {
                PlayStyle.ATTACKING -> "вы чаще компьютера били блоты и оставляли одиночные шашки под ударом"
                PlayStyle.DEFENSIVE -> "вы реже компьютера шли на риск: меньше боя и открытых шашек, чем было выгодно"
                PlayStyle.BALANCED -> "риск и бой примерно как у компьютера"
            }
            Variant.LONG -> when (style) {
                PlayStyle.ATTACKING -> "вы активнее компьютера строили заборы и занимали пункты на пути соперника"
                PlayStyle.DEFENSIVE -> "вы больше гнали шашки домой, чем перекрывали дорогу сопернику"
                PlayStyle.BALANCED -> "блокировка и продвижение примерно как у компьютера"
            }
        }
        return GameReview(
            player = player,
            accuracy = accuracy,
            decisions = decisions,
            errors = errors,
            blunders = blunders,
            style = style,
            styleNote = note,
            mistakes = mistakes.sortedByDescending { it.cost }.take(3).sortedBy { it.turn },
        )
    }

    /** Rough "how much pressure / risk" score of a turn. */
    private fun aggression(before: Position, after: Position, me: Player, moves: List<Move>): Float {
        val opp = me.opponent
        val v = after.variant
        return when (v) {
            Variant.SHORT -> {
                val hits = moves.count { it.hit }
                // Blots the opponent can actually reach.
                var exposed = 0
                for (r in 1..24) {
                    if (after.count(me, r) != 1) continue
                    var reachable = after.bar(opp) > 0 && r <= 6
                    for (q in 1 until r) if (r - q <= 6 && after.count(opp, 25 - q) > 0) reachable = true
                    if (reachable) exposed++
                }
                hits * 1.0f + exposed * 0.5f
            }
            Variant.LONG -> {
                // Points held on the opponent's path in front of their checkers.
                fun blockScore(p: Position): Float {
                    var score = 0f
                    var run = 0
                    for (q in 1..24) {
                        val mine = p.count(me, v.rel(me, v.abs(opp, q))) > 0
                        run = if (mine) run + 1 else 0
                        if (mine && (q + 1..24).any { p.count(opp, it) > 0 }) score += 0.3f + run * 0.15f
                    }
                    return score
                }
                blockScore(after) - blockScore(before)
            }
        }
    }

    /** "24/18 13/9*" with the point numbers printed on the board frame. */
    fun notation(moves: List<Move>, variant: Variant, player: Player): String =
        if (moves.isEmpty()) "нет хода"
        else moves.joinToString(" ") { m ->
            val from = if (m.from == BAR) "бар" else variant.abs(player, m.from).toString()
            val to = if (m.to == OFF) "выброс" else variant.abs(player, m.to).toString()
            "$from/$to" + if (m.hit) "*" else ""
        }
}
