package com.bizoneceo.nardy.engine

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

class AnalysisTest {

    /** Plays a game where white moves at random and black plays well; returns the turn log. */
    private fun playLogged(variant: Variant, seed: Int): List<TurnRecord> {
        val rnd = Random(seed)
        val log = ArrayList<TurnRecord>()
        var s = GameState.newMatch(variant)
        while (s.phase != Phase.GAME_OVER && s.phase != Phase.MATCH_OVER) {
            s = when (s.phase) {
                Phase.OPENING_ROLL -> s.rollOpening(rnd.nextInt(1, 7), rnd.nextInt(1, 7))
                Phase.AWAIT_ROLL -> s.roll(rnd.nextInt(1, 7), rnd.nextInt(1, 7))
                else -> {
                    val moves = if (s.current == Player.WHITE) {
                        val plans = Ai.allTurns(s.context)
                        if (plans.isEmpty()) emptyList() else plans.random(rnd).moves
                    } else {
                        Ai.chooseTurn(s, AiLevel.AMATEUR, rnd)
                    }
                    log += TurnRecord(s, moves)
                    var t = s
                    for (m in moves) t = t.play(m)
                    if (t.phase == Phase.MOVING) t.endTurn() else t
                }
            }
        }
        return log
    }

    @Test
    fun randomPlayIsLessAccurateThanTheComputer() {
        for (variant in Variant.entries) {
            val log = playLogged(variant, 5 + variant.ordinal)
            val random = Analysis.review(log, Player.WHITE)
            val ai = Analysis.review(log, Player.BLACK)
            assertTrue(random.accuracy < ai.accuracy, "$variant: random ${random.accuracy} vs ai ${ai.accuracy}")
            assertTrue(ai.accuracy >= 95, "$variant: the computer's own moves should score ~100, got ${ai.accuracy}")
            assertTrue(random.mistakes.isNotEmpty() && random.mistakes.size <= 3)
            assertTrue(random.mistakes.all { it.cost > 0f && it.played != it.best })
        }
    }
}
