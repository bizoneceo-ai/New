package com.bizoneceo.nardy.engine

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RulesTest {

    private fun cells(vararg pairs: Pair<Int, Int>) = IntArray(24).also { c ->
        for ((abs, n) in pairs) c[abs - 1] = n
    }

    @Test
    fun variantMappingIsAnInvolution() {
        for (v in Variant.entries) for (p in Player.entries) for (r in 1..24) {
            assertEquals(r, v.rel(p, v.abs(p, r)))
        }
        assertEquals(12, Variant.LONG.abs(Player.BLACK, 24))
        assertEquals(13, Variant.LONG.abs(Player.BLACK, 1))
        assertEquals(1, Variant.SHORT.abs(Player.BLACK, 24))
    }

    @Test
    fun initialPositionsHaveFifteenEach() {
        for (v in Variant.entries) {
            val p = Position.initial(v)
            for (pl in Player.entries) {
                assertEquals(15, (1..24).sumOf { p.count(pl, it) })
            }
        }
        assertEquals(167, Position.initial(Variant.SHORT).pipCount(Player.WHITE))
        assertEquals(360, Position.initial(Variant.LONG).pipCount(Player.BLACK))
    }

    @Test
    fun shortOpening31MakesThePoint() {
        val s = GameState.newMatch(Variant.SHORT).rollOpening(3, 1)
        assertEquals(Player.WHITE, s.current)
        val routes = s.routesFrom(8)
        assertTrue(routes.any { it.to == 5 })
        val after = s.play(Move(8, 5, 3)).play(Move(6, 5, 1))
        assertEquals(2, after.position.count(Player.WHITE, 5))
        assertFalse(after.canMove)
        assertEquals(Phase.AWAIT_ROLL, after.endTurn().phase)
    }

    @Test
    fun shortHitSendsBlotToBarAndBarMustEnterFirst() {
        // White blot on abs 5, black checker on abs 3 (black rel 22) with die 2 hits.
        val pos = Position.of(Variant.SHORT, cells(5 to 1, 24 to 14, 3 to -1, 1 to -14))
        val s = GameState.forPosition(pos, Player.BLACK, listOf(2, 6))
        val hit = s.legalSteps.first { it.from == 22 && it.die == 2 }
        assertTrue(hit.hit)
        val after = s.play(hit)
        assertEquals(1, after.position.bar(Player.WHITE))
        // White now must enter from the bar before anything else.
        val w = GameState.forPosition(after.position, Player.WHITE, listOf(3, 4))
        assertTrue(w.legalSteps.all { it.from == BAR })
    }

    @Test
    fun shortBlockedPointCannotBeEntered() {
        // Black holds black rel 1..6 = abs 19..24 entirely, white on bar.
        val black = (19..24).map { it to -2 }.toTypedArray()
        val pos = Position.of(Variant.SHORT, cells(*black, 1 to -3, 6 to 14), bar = intArrayOf(1, 0))
        val s = GameState.forPosition(pos, Player.WHITE, listOf(5, 2))
        assertTrue(s.isBlocked)
    }

    @Test
    fun mustPlayLargerDieWhenOnlyOneCanBePlayed() {
        // White checker on abs 10. Black blocks abs 4 and abs 2, so 10-6=4 and
        // 10-8=2 are blocked except one order. Construct: die 6 -> 4 open, then
        // die 2 -> 2 blocked; die 2 -> 8 open, then 6 -> 2 blocked.
        val pos = Position.of(Variant.SHORT, cells(10 to 1, 1 to 14, 2 to -2, 20 to -13))
        val s = GameState.forPosition(pos, Player.WHITE, listOf(6, 2))
        // Only one die can be used overall from abs 10 (abs 1 stack is home-blocked by abs 10 too).
        assertTrue(s.legalSteps.isNotEmpty())
        assertTrue(s.legalSteps.all { it.die == 6 }, "${s.legalSteps}")
    }

    @Test
    fun bearOffWithBiggerDieOnlyFromHighestPoint() {
        val pos = Position.of(Variant.SHORT, cells(3 to 1, 2 to 1, 24 to -15), off = intArrayOf(13, 0))
        val s = GameState.forPosition(pos, Player.WHITE, listOf(6, 1))
        val offMoves = s.legalSteps.filter { it.to == OFF }
        assertEquals(listOf(3), offMoves.filter { it.die == 6 }.map { it.from })
    }

    @Test
    fun winKinds() {
        val pos = Position.of(Variant.SHORT, cells(1 to 1, 2 to -15), off = intArrayOf(14, 0))
        val s = GameState.forPosition(pos, Player.WHITE, listOf(1, 2)).play(Move(1, OFF, 2))
        assertEquals(Phase.GAME_OVER, s.phase)
        // Black's 15 sit on abs 2 = black rel 23, inside white's home: koks.
        assertEquals(WinKind.KOKS, s.result!!.kind)
        assertEquals(3, s.score(Player.WHITE))

        val pos2 = Position.of(Variant.LONG, cells(1 to 1, 20 to -15), off = intArrayOf(14, 0))
        val s2 = GameState.forPosition(pos2, Player.WHITE, listOf(1, 2)).play(Move(1, OFF, 2))
        assertEquals(WinKind.MARS, s2.result!!.kind)
    }

    @Test
    fun longOnlyOneCheckerFromHeadNormally() {
        val s = GameState.newMatch(Variant.LONG).rollOpening(5, 2).roll(5, 2)
        assertEquals(Player.WHITE, s.current)
        val first = s.play(Move(24, 19, 5))
        assertTrue(first.legalSteps.none { it.from == 24 })
        assertTrue(first.legalSteps.any { it.from == 19 && it.to == 17 })
    }

    @Test
    fun longFirstThrowSixSixTakesTwoFromHead() {
        val s = GameState.newMatch(Variant.LONG).rollOpening(5, 2).roll(6, 6)
        val a = s.play(Move(24, 18, 6))
        val b = a.play(Move(24, 18, 6))
        assertEquals(2, b.position.count(Player.WHITE, 18))
        // 18 - 6 = 12 is black's head: nothing left to play.
        assertFalse(b.canMove)
    }

    @Test
    fun longCannotLandOnOpponent() {
        val s = GameState.newMatch(Variant.LONG).rollOpening(5, 2).roll(6, 6)
        // White's 24-12 would land on black's head.
        assertTrue(s.routesFrom(24).none { it.to == 12 })
    }

    @Test
    fun longSixBlockWithoutOpponentAheadIsIllegal() {
        // White holds abs 20..24. Landing on 19 would build a 6-block 19..24,
        // which is black rel 7..12. Nobody of black is ahead of it (black
        // rel < 7) while all of black still sits on its head, so it is illegal.
        val whites = arrayOf(24 to 5, 23 to 2, 22 to 2, 21 to 2, 20 to 2, 10 to 2)
        val pos = Position.of(Variant.LONG, cells(*whites, 12 to -15))
        val s = GameState.forPosition(pos, Player.WHITE, listOf(5, 1))
        assertTrue(s.legalSteps.none { it.to == 19 }, "${s.legalSteps}")

        // With a black checker already past the block (abs 15 = black rel 3) it is fine.
        val pos2 = Position.of(Variant.LONG, cells(*whites, 12 to -14, 15 to -1))
        val s2 = GameState.forPosition(pos2, Player.WHITE, listOf(5, 1))
        assertTrue(s2.legalSteps.any { it.from == 24 && it.to == 19 })
        assertTrue(s2.legalSteps.any { it.from == 20 && it.to == 19 })
    }

    @Test
    fun undoRestoresPosition() {
        val s = GameState.newMatch(Variant.SHORT).rollOpening(6, 5)
        val moved = s.play(s.legalSteps.first())
        assertTrue(moved.canUndo)
        assertEquals(s.position, moved.undo().position)
        assertEquals(s.remaining, moved.undo().remaining)
    }

    @Test
    fun encodeDecodeRoundTrip() {
        val s = GameState.newMatch(Variant.LONG, 5).rollOpening(4, 2).roll(3, 1)
        val back = GameState.decode(s.encode())
        assertNotNull(back)
        assertEquals(s.encode(), back.encode())
        assertEquals(s.legalSteps, back.legalSteps)
    }

    /** Plays many random full games to make sure the engine never gets stuck or breaks invariants. */
    @Test
    fun randomGamesTerminate() {
        for (variant in Variant.entries) {
            val rnd = Random(42 + variant.ordinal)
            repeat(40) {
                var s = GameState.newMatch(variant)
                var guard = 0
                while (s.phase != Phase.GAME_OVER && s.phase != Phase.MATCH_OVER) {
                    check(guard++ < 5000) { "game did not finish" }
                    s = when (s.phase) {
                        Phase.OPENING_ROLL -> s.rollOpening(rnd.nextInt(1, 7), rnd.nextInt(1, 7))
                        Phase.AWAIT_ROLL -> s.roll(rnd.nextInt(1, 7), rnd.nextInt(1, 7))
                        Phase.MOVING -> {
                            if (s.canMove) {
                                // Pick a random route sometimes, a single step otherwise.
                                val m = s.legalSteps.random(rnd)
                                if (rnd.nextBoolean()) s.play(s.routesFrom(m.from).random(rnd)) else s.play(m)
                            } else {
                                s.endTurn()
                            }
                        }
                        else -> s
                    }
                    for (p in Player.entries) {
                        val pos = s.position
                        val total = pos.bar(p) + pos.off(p) + (1..24).sumOf { pos.count(p, it) }
                        check(total == 15)
                    }
                }
                assertNotNull(s.result)
            }
        }
    }
}
