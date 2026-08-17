package com.bizoneceo.snake8bit.game

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.random.Random

enum class Direction(val dx: Int, val dy: Int) {
    UP(0, -1),
    DOWN(0, 1),
    LEFT(-1, 0),
    RIGHT(1, 0);

    fun isOppositeOf(other: Direction): Boolean =
        dx == -other.dx && dy == -other.dy
}

data class Point(val x: Int, val y: Int) {
    fun moved(direction: Direction): Point = Point(x + direction.dx, y + direction.dy)
}

/**
 * Classic snake on a large 200x100 grid. The whole grid is rendered on
 * screen at once (tiny cells), which is what gives the 8-bit pixel look.
 */
class SnakeGame(val cols: Int = 200, val rows: Int = 100) {

    var direction by mutableStateOf(Direction.RIGHT)
        private set

    private var pendingDirection = Direction.RIGHT

    val snake = mutableStateListOf<Point>()

    var apple by mutableStateOf(Point(0, 0))
        private set

    var score by mutableIntStateOf(0)
        private set

    var gameOver by mutableStateOf(false)
        private set

    var started by mutableStateOf(false)
        private set

    /** Increments on every start() call so the game loop can key off a value
     * that is guaranteed to change even when reset() + start() happen inside
     * the same event (started would otherwise end up back at its old value
     * before recomposition ever observes the intermediate false). */
    var runId by mutableIntStateOf(0)
        private set

    private val occupied = HashSet<Point>()

    init {
        reset()
    }

    fun start() {
        started = true
        runId++
    }

    fun reset() {
        snake.clear()
        occupied.clear()

        val startX = cols / 2
        val startY = rows / 2
        for (i in 0 until 4) {
            val p = Point(startX - i, startY)
            snake.add(p)
            occupied.add(p)
        }

        direction = Direction.RIGHT
        pendingDirection = Direction.RIGHT
        score = 0
        gameOver = false
        started = false
        spawnApple()
    }

    fun queueDirection(newDirection: Direction) {
        if (gameOver || newDirection.isOppositeOf(direction)) return
        pendingDirection = newDirection
    }

    fun tick() {
        if (gameOver || !started) return

        direction = pendingDirection
        val head = snake.first()
        val newHead = head.moved(direction)

        if (newHead.x !in 0 until cols || newHead.y !in 0 until rows) {
            gameOver = true
            return
        }

        val willGrow = newHead == apple
        val tail = snake.last()
        val hitsBody = occupied.contains(newHead) && !(newHead == tail && !willGrow)
        if (hitsBody) {
            gameOver = true
            return
        }

        snake.add(0, newHead)
        occupied.add(newHead)

        if (willGrow) {
            score++
            spawnApple()
        } else {
            val removedTail = snake.removeAt(snake.size - 1)
            occupied.remove(removedTail)
        }
    }

    /** Tick delay in ms; speeds up gradually as the snake eats more apples. */
    fun tickDelayMillis(): Long = (170L - score * 3L).coerceIn(45L, 170L)

    private fun spawnApple() {
        while (true) {
            val candidate = Point(Random.nextInt(cols), Random.nextInt(rows))
            if (!occupied.contains(candidate)) {
                apple = candidate
                return
            }
        }
    }
}
