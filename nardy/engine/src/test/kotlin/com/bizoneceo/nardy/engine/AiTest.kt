package com.bizoneceo.nardy.engine

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

class AiTest {

    /** Plays a full game; [white] and [black] pick the moves for each side. */
    private fun play(
        variant: Variant,
        rnd: Random,
        white: (GameState) -> List<Move>,
        black: (GameState) -> List<Move>,
    ): GameState {
        var s = GameState.newMatch(variant)
        var guard = 0
        while (s.phase != Phase.GAME_OVER && s.phase != Phase.MATCH_OVER) {
            check(guard++ < 3000)
            s = when (s.phase) {
                Phase.OPENING_ROLL -> s.rollOpening(rnd.nextInt(1, 7), rnd.nextInt(1, 7))
                Phase.AWAIT_ROLL -> s.roll(rnd.nextInt(1, 7), rnd.nextInt(1, 7))
                Phase.MOVING -> {
                    val moves = if (s.current == Player.WHITE) white(s) else black(s)
                    // Every move the AI suggests must be accepted by the rules.
                    var t = s
                    for (m in moves) t = t.play(m)
                    if (t.phase == Phase.MOVING) {
                        check(!t.canMove) { "AI left playable dice: ${t.remaining}" }
                        t.endTurn()
                    } else {
                        t
                    }
                }
                else -> s
            }
        }
        return s
    }

    private fun randomPlayer(rnd: Random): (GameState) -> List<Move> = { s ->
        val moves = ArrayList<Move>()
        var t = s
        while (t.phase == Phase.MOVING && t.canMove) {
            val m = t.legalSteps.random(rnd)
            moves += m
            t = t.play(m)
        }
        moves
    }

    @Test
    fun aiPlaysLegalTurnsAndBeatsRandom() {
        for (variant in Variant.entries) {
            val rnd = Random(7 + variant.ordinal)
            var aiWins = 0
            val games = 12
            repeat(games) { i ->
                val aiIsWhite = i % 2 == 0
                val ai: (GameState) -> List<Move> = { Ai.chooseTurn(it, AiLevel.AMATEUR, rnd) }
                val r = randomPlayer(rnd)
                val end = if (aiIsWhite) play(variant, rnd, ai, r) else play(variant, rnd, r, ai)
                val aiPlayer = if (aiIsWhite) Player.WHITE else Player.BLACK
                if (end.result!!.winner == aiPlayer) aiWins++
            }
            assertTrue(aiWins >= games - 1, "$variant: AI won only $aiWins of $games against random play")
        }
    }

    @Test
    fun masterAndNoviceProduceLegalTurns() {
        for (variant in Variant.entries) {
            val rnd = Random(99 + variant.ordinal)
            val master: (GameState) -> List<Move> = { Ai.chooseTurn(it, AiLevel.MASTER, rnd, budgetMillis = 400) }
            val novice: (GameState) -> List<Move> = { Ai.chooseTurn(it, AiLevel.NOVICE, rnd) }
            play(variant, rnd, master, novice)
        }
    }

    @Test
    fun shortAiHitsAnExposedBlotInItsHome() {
        // Black blot on black rel 22 (abs 3) sits in white's home; white has
        // checkers 2 away on abs 5 and plenty elsewhere. Hitting is clearly right.
        val c = IntArray(24)
        c[5 - 1] = 2; c[6 - 1] = 4; c[8 - 1] = 3; c[13 - 1] = 6
        c[3 - 1] = -1; c[19 - 1] = -4; c[20 - 1] = -4; c[12 - 1] = -6
        val pos = Position.of(Variant.SHORT, c)
        val s = GameState.forPosition(pos, Player.WHITE, listOf(2, 1))
        val turn = Ai.chooseTurn(s, AiLevel.AMATEUR, Random(1))
        assertTrue(turn.any { it.hit }, "expected a hit, got $turn")
    }
}
