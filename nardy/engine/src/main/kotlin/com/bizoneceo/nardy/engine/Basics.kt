package com.bizoneceo.nardy.engine

/** Point index meaning "on the bar" (only used in short nardy). */
const val BAR = 25

/** Point index meaning "borne off the board". */
const val OFF = 0

const val CHECKERS_PER_PLAYER = 15

enum class Player {
    WHITE, BLACK;

    val opponent: Player get() = if (this == WHITE) BLACK else WHITE
}

/**
 * Game variant.
 *
 * Points are addressed in two ways:
 *  - **absolute** (1..24): white's numbering, fixed to the physical board.
 *    1..6 is the bottom-right quadrant, 7..12 bottom-left, 13..18 top-left,
 *    19..24 top-right.
 *  - **relative** (1..24): from the moving player's point of view. Every
 *    player always moves from 24 towards 1 and bears off past 1, so the rules
 *    can be written once for both colours.
 */
enum class Variant {
    /** Короткие нарды (backgammon): opposite directions, hitting, bar. */
    SHORT,

    /** Длинные нарды: same direction, no hitting, start from the "head". */
    LONG;

    /** Absolute point of [player]'s relative point [rel] (1..24). */
    fun abs(player: Player, rel: Int): Int = when {
        player == Player.WHITE -> rel
        this == SHORT -> 25 - rel
        // Black's head (rel 24) is white's 12: a half-board shift, which is
        // its own inverse.
        else -> (rel + 11) % 24 + 1
    }

    /** Relative point of absolute point [abs] for [player]. */
    fun rel(player: Player, abs: Int): Int = abs(player, abs)
}

/**
 * One checker moving by one die, in the mover's relative coordinates.
 * [from] may be [BAR]; [to] may be [OFF].
 */
data class Move(val from: Int, val to: Int, val die: Int, val hit: Boolean = false)

enum class WinKind(val points: Int) {
    /** Обычная победа (ойн). */
    SINGLE(1),

    /** Марс: loser has not borne off a single checker. */
    MARS(2),

    /** Кокс (backgammon): mars and a loser checker still on the bar or in the winner's home. */
    KOKS(3),
}

data class GameResult(val winner: Player, val kind: WinKind) {
    val points: Int get() = kind.points
}
