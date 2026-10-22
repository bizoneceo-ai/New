package com.bizoneceo.nardy.engine

enum class Phase {
    /** Each player throws one die; the higher one starts. */
    OPENING_ROLL,

    /** [GameState.current] has to throw the dice. */
    AWAIT_ROLL,

    /** [GameState.current] is moving; once [GameState.canMove] is false the turn can be ended. */
    MOVING,

    /** A game of the match is over, [GameState.result] is set. */
    GAME_OVER,

    /** The match target was reached. */
    MATCH_OVER,
}

/** A chain of moves of one checker, e.g. 3 then 5 to land 8 points away. */
data class Route(val moves: List<Move>) {
    val from: Int get() = moves.first().from
    val to: Int get() = moves.last().to
}

private data class Snapshot(val position: Position, val remaining: List<Int>, val headMoves: Int)

/**
 * Complete, immutable state of a match. Every action returns a new state, which
 * keeps the UI layer trivial (one observable value) and makes undo free.
 */
class GameState private constructor(
    val variant: Variant,
    /** Points needed to win the match; 0 means an endless series. */
    val matchLength: Int,
    private val scores: List<Int>,
    val position: Position,
    val phase: Phase,
    val current: Player,
    /** The two dice as thrown this turn (empty before the throw). */
    val dice: List<Int>,
    /** Dice values still to be played this turn (four entries for doubles). */
    val remaining: List<Int>,
    private val headMoves: Int,
    private val turnsPlayed: List<Int>,
    /** Opening throw: [white die, black die], kept visible after the throw. */
    val openingDice: List<Int>,
    val result: GameResult?,
    val gameNumber: Int,
    private val undoStack: List<Snapshot>,
) {
    fun score(player: Player): Int = scores[player.ordinal]

    val canUndo: Boolean get() = phase == Phase.MOVING && undoStack.isNotEmpty()

    /** Moves made so far this turn. */
    val movesMadeThisTurn: Int get() = undoStack.size

    private val headLimit: Int
        get() {
            val firstTurn = turnsPlayed[current.ordinal] == 0
            val bigDouble = dice.size == 2 && dice[0] == dice[1] && dice[0] in setOf(3, 4, 6)
            // On the very first throw 6-6, 4-4 and 3-3 cannot be played with a
            // single head checker, so two may leave the head.
            return if (variant == Variant.LONG && firstTurn && bigDouble) 2 else 1
        }

    private val context: TurnContext
        get() = TurnContext(position, current, remaining, headMoves, headLimit)

    /** Legal next single moves for the current player. */
    val legalSteps: List<Move> by lazy {
        if (phase == Phase.MOVING) Rules.legalSteps(context) else emptyList()
    }

    val canMove: Boolean get() = legalSteps.isNotEmpty()

    /** The player rolled but has no legal move at all this turn. */
    val isBlocked: Boolean get() = phase == Phase.MOVING && undoStack.isEmpty() && !canMove

    /** Relative points (or BAR) the current player may pick a checker from. */
    val movableFrom: Set<Int> get() = legalSteps.mapTo(HashSet()) { it.from }

    /**
     * Every destination reachable from [from] this turn, including ones that
     * chain several dice with the same checker, each with the shortest route.
     */
    fun routesFrom(from: Int): List<Route> {
        val best = LinkedHashMap<Int, Route>()
        fun explore(state: GameState, at: Int, path: List<Move>) {
            for (m in state.legalSteps) {
                if (m.from != at) continue
                val route = path + m
                val known = best[m.to]
                if (known == null || known.moves.size > route.size) best[m.to] = Route(route)
                if (m.to == OFF) continue
                val next = state.play(m)
                if (next.phase == Phase.MOVING) explore(next, m.to, route)
            }
        }
        explore(this, from, emptyList())
        return best.values.toList()
    }

    // ---------------------------------------------------------------- actions

    /**
     * Opening throw. A tie leaves the phase unchanged (throw again).
     * In short nardy the winner plays the opening dice directly, in long
     * nardy the winner throws again.
     */
    fun rollOpening(whiteDie: Int, blackDie: Int): GameState {
        check(phase == Phase.OPENING_ROLL)
        require(whiteDie in 1..6 && blackDie in 1..6)
        val opening = listOf(whiteDie, blackDie)
        if (whiteDie == blackDie) return copy(openingDice = opening)
        val starter = if (whiteDie > blackDie) Player.WHITE else Player.BLACK
        return when (variant) {
            Variant.SHORT -> copy(
                openingDice = opening,
                current = starter,
                phase = Phase.MOVING,
                dice = opening,
                remaining = opening,
                headMoves = 0,
                undoStack = emptyList(),
            )
            Variant.LONG -> copy(openingDice = opening, current = starter, phase = Phase.AWAIT_ROLL)
        }
    }

    fun roll(d1: Int, d2: Int): GameState {
        check(phase == Phase.AWAIT_ROLL)
        require(d1 in 1..6 && d2 in 1..6)
        return copy(
            phase = Phase.MOVING,
            dice = listOf(d1, d2),
            remaining = if (d1 == d2) List(4) { d1 } else listOf(d1, d2),
            headMoves = 0,
            undoStack = emptyList(),
        )
    }

    /** Play one legal move. */
    fun play(move: Move): GameState {
        check(phase == Phase.MOVING)
        require(move in legalSteps) { "illegal move $move" }
        val ctx = context
        val nextPos = position.apply(current, move)
        val moved = copy(
            position = nextPos,
            remaining = remaining.minusOne(move.die),
            headMoves = headMoves + if (ctx.isHeadMove(move)) 1 else 0,
            undoStack = undoStack + Snapshot(position, remaining, headMoves),
        )
        return if (nextPos.off(current) == CHECKERS_PER_PLAYER) moved.finishGame() else moved
    }

    fun play(route: Route): GameState = route.moves.fold(this) { s, m -> s.play(m) }

    fun undo(): GameState {
        check(canUndo)
        val snap = undoStack.last()
        return copy(
            position = snap.position,
            remaining = snap.remaining,
            headMoves = snap.headMoves,
            undoStack = undoStack.dropLast(1),
        )
    }

    /** Pass the turn once no more moves are possible. */
    fun endTurn(): GameState {
        check(phase == Phase.MOVING && !canMove)
        val turns = turnsPlayed.toMutableList().also { it[current.ordinal]++ }
        return copy(
            phase = Phase.AWAIT_ROLL,
            current = current.opponent,
            dice = emptyList(),
            remaining = emptyList(),
            headMoves = 0,
            turnsPlayed = turns,
            undoStack = emptyList(),
        )
    }

    /** Start the next game of the match. */
    fun nextGame(): GameState {
        check(phase == Phase.GAME_OVER)
        return fresh(variant, matchLength, scores, gameNumber + 1)
    }

    private fun finishGame(): GameState {
        val winner = current
        val loser = winner.opponent
        val kind = when {
            position.off(loser) > 0 -> WinKind.SINGLE
            variant == Variant.SHORT && (
                position.bar(loser) > 0 ||
                    (19..24).any { position.count(loser, it) > 0 }
                ) -> WinKind.KOKS
            else -> WinKind.MARS
        }
        val res = GameResult(winner, kind)
        val newScores = scores.toMutableList().also { it[winner.ordinal] += res.points }
        val matchOver = matchLength > 0 && newScores[winner.ordinal] >= matchLength
        return copy(
            phase = if (matchOver) Phase.MATCH_OVER else Phase.GAME_OVER,
            result = res,
            scores = newScores,
            remaining = emptyList(),
            undoStack = emptyList(),
        )
    }

    private fun copy(
        position: Position = this.position,
        phase: Phase = this.phase,
        current: Player = this.current,
        dice: List<Int> = this.dice,
        remaining: List<Int> = this.remaining,
        headMoves: Int = this.headMoves,
        turnsPlayed: List<Int> = this.turnsPlayed,
        openingDice: List<Int> = this.openingDice,
        result: GameResult? = this.result,
        scores: List<Int> = this.scores,
        undoStack: List<Snapshot> = this.undoStack,
    ) = GameState(
        variant, matchLength, scores, position, phase, current, dice, remaining,
        headMoves, turnsPlayed, openingDice, result, gameNumber, undoStack,
    )

    // ---------------------------------------------------------- persistence

    /**
     * Single-line save format. The undo stack is deliberately not stored: a
     * restored game resumes from the current position of the turn.
     */
    fun encode(): String = listOf(
        "v1",
        variant.name,
        matchLength,
        scores.joinToString(","),
        phase.name,
        current.name,
        dice.joinToString(","),
        remaining.joinToString(","),
        headMoves,
        turnsPlayed.joinToString(","),
        openingDice.joinToString(","),
        result?.let { "${it.winner.name},${it.kind.name}" } ?: "",
        gameNumber,
        position.encode(),
    ).joinToString(";")

    companion object {
        fun newMatch(variant: Variant, matchLength: Int = 0): GameState =
            fresh(variant, matchLength, listOf(0, 0), 1)

        private fun fresh(variant: Variant, matchLength: Int, scores: List<Int>, gameNumber: Int) =
            GameState(
                variant = variant,
                matchLength = matchLength,
                scores = scores,
                position = Position.initial(variant),
                phase = Phase.OPENING_ROLL,
                current = Player.WHITE,
                dice = emptyList(),
                remaining = emptyList(),
                headMoves = 0,
                turnsPlayed = listOf(0, 0),
                openingDice = emptyList(),
                result = null,
                gameNumber = gameNumber,
                undoStack = emptyList(),
            )

        /** Builds a mid-turn state from an arbitrary position (tests, puzzles). */
        fun forPosition(
            position: Position,
            current: Player,
            dice: List<Int>,
            turnsPlayed: List<Int> = listOf(5, 5),
        ): GameState = GameState(
            variant = position.variant,
            matchLength = 0,
            scores = listOf(0, 0),
            position = position,
            phase = Phase.MOVING,
            current = current,
            dice = dice,
            remaining = if (dice.size == 2 && dice[0] == dice[1]) List(4) { dice[0] } else dice,
            headMoves = 0,
            turnsPlayed = turnsPlayed,
            openingDice = emptyList(),
            result = null,
            gameNumber = 1,
            undoStack = emptyList(),
        )

        fun decode(s: String): GameState? = runCatching {
            val f = s.split(";")
            require(f[0] == "v1" && f.size == 14)
            fun ints(x: String) = if (x.isEmpty()) emptyList() else x.split(",").map { it.toInt() }
            val variant = Variant.valueOf(f[1])
            val result = f[11].takeIf { it.isNotEmpty() }?.split(",")?.let {
                GameResult(Player.valueOf(it[0]), WinKind.valueOf(it[1]))
            }
            GameState(
                variant = variant,
                matchLength = f[2].toInt(),
                scores = ints(f[3]).also { require(it.size == 2) },
                phase = Phase.valueOf(f[4]),
                current = Player.valueOf(f[5]),
                dice = ints(f[6]),
                remaining = ints(f[7]),
                headMoves = f[8].toInt(),
                turnsPlayed = ints(f[9]).also { require(it.size == 2) },
                openingDice = ints(f[10]),
                result = result,
                gameNumber = f[12].toInt(),
                position = Position.decode(variant, f[13]) ?: error("bad position"),
                undoStack = emptyList(),
            )
        }.getOrNull()
    }
}
