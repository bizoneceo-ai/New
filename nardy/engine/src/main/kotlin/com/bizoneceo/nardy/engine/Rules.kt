package com.bizoneceo.nardy.engine

/**
 * Everything the rules need to know about the turn in progress.
 *
 * @param headMoves checkers already taken from the head this turn (long nardy).
 * @param headLimit how many checkers may leave the head this turn.
 */
data class TurnContext(
    val position: Position,
    val player: Player,
    val dice: List<Int>,
    val headMoves: Int = 0,
    val headLimit: Int = 1,
) {
    fun after(move: Move): TurnContext = copy(
        position = position.apply(player, move),
        dice = dice.minusOne(move.die),
        headMoves = headMoves + if (isHeadMove(move)) 1 else 0,
    )

    fun isHeadMove(move: Move): Boolean = position.variant == Variant.LONG && move.from == 24
}

internal fun List<Int>.minusOne(value: Int): List<Int> {
    val i = indexOf(value)
    require(i >= 0) { "die $value not available in $this" }
    return toMutableList().also { it.removeAt(i) }
}

object Rules {

    /** Moves of a single checker by [die], ignoring the "use as many dice as possible" rules. */
    fun singleSteps(ctx: TurnContext, die: Int): List<Move> {
        val pos = ctx.position
        val player = ctx.player
        val opp = player.opponent
        val variant = pos.variant

        fun landing(to: Int): Move? {
            val oppCount = pos.count(opp, variant.rel(opp, variant.abs(player, to)))
            return when (variant) {
                Variant.SHORT -> when {
                    oppCount == 0 -> Move(0, to, die)
                    oppCount == 1 -> Move(0, to, die, hit = true)
                    else -> null
                }
                Variant.LONG -> if (oppCount == 0) Move(0, to, die) else null
            }
        }

        if (variant == Variant.SHORT && pos.bar(player) > 0) {
            val entry = landing(25 - die) ?: return emptyList()
            return listOf(entry.copy(from = BAR))
        }

        val result = ArrayList<Move>()
        val allHome = pos.allHome(player)
        val highest = pos.highestRel(player)
        for (from in 24 downTo 1) {
            if (pos.count(player, from) == 0) continue
            if (variant == Variant.LONG && from == 24 && ctx.headMoves >= ctx.headLimit) continue
            val to = from - die
            if (to >= 1) {
                val m = landing(to)?.copy(from = from) ?: continue
                if (variant == Variant.LONG && violatesBlock(pos.apply(player, m), player, to)) continue
                result += m
            } else if (allHome && (to == 0 || from == highest)) {
                // Exact bear-off, or a bigger die from the highest occupied point.
                result += Move(from, OFF, die)
            }
        }
        return result
    }

    /**
     * Long nardy: six or more own checkers in a row are only allowed if at
     * least one opponent checker is already in front of that block.
     * Only the run containing the freshly occupied point [toRel] is checked,
     * so an older legal block never makes unrelated moves illegal.
     */
    private fun violatesBlock(after: Position, player: Player, toRel: Int): Boolean {
        val variant = after.variant
        val opp = player.opponent
        // Work along the opponent's path, where "in front" means a lower point.
        fun mine(q: Int) = after.count(player, variant.rel(player, variant.abs(opp, q))) > 0
        val q0 = variant.rel(opp, variant.abs(player, toRel))
        var lo = q0
        while (lo > 1 && mine(lo - 1)) lo--
        var hi = q0
        while (hi < 24 && mine(hi + 1)) hi++
        if (hi - lo + 1 < 6) return false
        for (q in 1 until lo) if (after.count(opp, q) > 0) return false
        return true
    }

    /** Largest number of dice that can be played from [ctx]. */
    fun maxDiceUsable(ctx: TurnContext): Int {
        if (ctx.dice.isEmpty()) return 0
        var best = 0
        for (die in ctx.dice.distinct()) {
            for (m in singleSteps(ctx, die)) {
                val depth = 1 + maxDiceUsable(ctx.after(m))
                if (depth > best) {
                    best = depth
                    if (best == ctx.dice.size) return best
                }
            }
        }
        return best
    }

    /**
     * Moves that may be played next: each must keep the turn on a path that
     * uses the maximum possible number of dice, and if only one of two
     * different dice can be played, it must be the larger one.
     */
    fun legalSteps(ctx: TurnContext): List<Move> {
        val total = maxDiceUsable(ctx)
        if (total == 0) return emptyList()
        val candidates = ArrayList<Move>()
        for (die in ctx.dice.distinct()) {
            for (m in singleSteps(ctx, die)) {
                if (1 + maxDiceUsable(ctx.after(m)) == total) candidates += m
            }
        }
        if (total == 1 && ctx.dice.size == 2 && ctx.dice[0] != ctx.dice[1]) {
            val biggest = candidates.maxOf { it.die }
            return candidates.filter { it.die == biggest }
        }
        return candidates
    }
}
