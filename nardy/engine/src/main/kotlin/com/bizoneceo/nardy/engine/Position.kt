package com.bizoneceo.nardy.engine

/**
 * Immutable board position. Cells are stored by absolute point: a positive
 * value is a stack of white checkers, negative is black.
 */
class Position private constructor(
    val variant: Variant,
    private val cells: IntArray,
    private val bar: IntArray,
    private val off: IntArray,
) {
    /** Number of [player]'s checkers on absolute point [abs]. */
    fun countAbs(player: Player, abs: Int): Int {
        val v = cells[abs - 1]
        return if (player == Player.WHITE) maxOf(v, 0) else maxOf(-v, 0)
    }

    /** Number of [player]'s checkers on their relative point [rel]. */
    fun count(player: Player, rel: Int): Int = countAbs(player, variant.abs(player, rel))

    /** Owner of absolute point [abs], or null if it is empty. */
    fun ownerAbs(abs: Int): Player? {
        val v = cells[abs - 1]
        return when {
            v > 0 -> Player.WHITE
            v < 0 -> Player.BLACK
            else -> null
        }
    }

    fun bar(player: Player): Int = bar[player.ordinal]
    fun off(player: Player): Int = off[player.ordinal]

    /** All of [player]'s remaining checkers are in their home (rel 1..6). */
    fun allHome(player: Player): Boolean {
        if (bar(player) > 0) return false
        for (rel in 7..24) if (count(player, rel) > 0) return false
        return true
    }

    /** Highest relative point holding one of [player]'s checkers (BAR if on the bar, 0 if none). */
    fun highestRel(player: Player): Int {
        if (bar(player) > 0) return BAR
        for (rel in 24 downTo 1) if (count(player, rel) > 0) return rel
        return 0
    }

    /** Pips left to bear everything off. */
    fun pipCount(player: Player): Int {
        var sum = bar(player) * 25
        for (rel in 1..24) sum += count(player, rel) * rel
        return sum
    }

    /** Position after [player] makes [move]. Does not validate legality. */
    fun apply(player: Player, move: Move): Position {
        val c = cells.copyOf()
        val b = bar.copyOf()
        val o = off.copyOf()
        val sign = if (player == Player.WHITE) 1 else -1
        if (move.from == BAR) {
            b[player.ordinal]--
        } else {
            c[variant.abs(player, move.from) - 1] -= sign
        }
        if (move.to == OFF) {
            o[player.ordinal]++
        } else {
            val i = variant.abs(player, move.to) - 1
            if (c[i] == -sign) {
                // Blot hit (short nardy only — long nardy never lands on an opponent).
                c[i] = 0
                b[player.opponent.ordinal]++
            }
            c[i] += sign
        }
        return Position(variant, c, b, o)
    }

    override fun equals(other: Any?): Boolean =
        other is Position && other.variant == variant &&
            other.cells.contentEquals(cells) && other.bar.contentEquals(bar) &&
            other.off.contentEquals(off)

    override fun hashCode(): Int =
        (variant.hashCode() * 31 + cells.contentHashCode()) * 31 +
            bar.contentHashCode() * 7 + off.contentHashCode()

    override fun toString(): String =
        "Position($variant, cells=${cells.toList()}, bar=${bar.toList()}, off=${off.toList()})"

    /** Compact text form used for saving games: `cells|barW,barB|offW,offB`. */
    fun encode(): String =
        cells.joinToString(",") + "|" + bar.joinToString(",") + "|" + off.joinToString(",")

    companion object {
        fun initial(variant: Variant): Position {
            val c = IntArray(24)
            when (variant) {
                Variant.SHORT -> {
                    // Standard backgammon set-up, from each player's own view:
                    // 2 on 24, 5 on 13, 3 on 8, 5 on 6.
                    for ((rel, n) in listOf(24 to 2, 13 to 5, 8 to 3, 6 to 5)) {
                        c[variant.abs(Player.WHITE, rel) - 1] += n
                        c[variant.abs(Player.BLACK, rel) - 1] -= n
                    }
                }
                Variant.LONG -> {
                    // Everything on the "head".
                    c[variant.abs(Player.WHITE, 24) - 1] = CHECKERS_PER_PLAYER
                    c[variant.abs(Player.BLACK, 24) - 1] = -CHECKERS_PER_PLAYER
                }
            }
            return Position(variant, c, IntArray(2), IntArray(2))
        }

        /** Build a position from explicit absolute cells (for tests and set-ups). */
        fun of(
            variant: Variant,
            cells: IntArray,
            bar: IntArray = IntArray(2),
            off: IntArray = IntArray(2),
        ): Position {
            require(cells.size == 24 && bar.size == 2 && off.size == 2)
            return Position(variant, cells.copyOf(), bar.copyOf(), off.copyOf())
        }

        fun decode(variant: Variant, s: String): Position? = runCatching {
            val parts = s.split("|")
            val c = parts[0].split(",").map { it.toInt() }.toIntArray()
            val b = parts[1].split(",").map { it.toInt() }.toIntArray()
            val o = parts[2].split(",").map { it.toInt() }.toIntArray()
            val p = of(variant, c, b, o)
            for (pl in Player.entries) {
                var total = p.bar(pl) + p.off(pl)
                for (abs in 1..24) total += p.countAbs(pl, abs)
                require(total == CHECKERS_PER_PLAYER)
            }
            p
        }.getOrNull()
    }
}
