package com.bizoneceo.snake8bit.game

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.math.abs
import kotlin.random.Random

enum class Direction(val dx: Int, val dy: Int) {
    UP(0, -1),
    DOWN(0, 1),
    LEFT(-1, 0),
    RIGHT(1, 0);

    fun isOppositeOf(other: Direction): Boolean =
        dx == -other.dx && dy == -other.dy

    /** 90° turn relative to this direction, steering-wheel style. */
    fun turnedLeft(): Direction = fromDelta(dy, -dx)

    fun turnedRight(): Direction = fromDelta(-dy, dx)

    companion object {
        private fun fromDelta(dx: Int, dy: Int): Direction =
            entries.first { it.dx == dx && it.dy == dy }
    }
}

data class Point(val x: Int, val y: Int) {
    fun moved(direction: Direction): Point = Point(x + direction.dx, y + direction.dy)
}

private fun wrap(value: Int, size: Int): Int = ((value % size) + size) % size

/**
 * Classic snake, 8-bit style. Every apple eaten drops a new permanent
 * obstacle block somewhere on the board. Walking off an edge wraps the
 * snake around to the opposite side instead of ending the run. Every 10th
 * apple opens a short window where obstacles can be eaten like apples.
 */
class SnakeGame(val cols: Int = 20, val rows: Int = 30) {

    /** Obstacles never spawn within this many cells of the head, so a new
     * block can't kill the snake on the very next move. */
    private val obstacleSafeRadius = 2

    private val powerModeDurationMs = 10_000L
    private val powerModeEveryNApples = 10

    var direction by mutableStateOf(Direction.RIGHT)
        private set

    private var pendingDirection = Direction.RIGHT

    val snake = mutableStateListOf<Point>()

    val obstacles = mutableStateListOf<Point>()

    var apple by mutableStateOf(Point(0, 0))
        private set

    var score by mutableIntStateOf(0)
        private set

    var gameOver by mutableStateOf(false)
        private set

    var started by mutableStateOf(false)
        private set

    var paused by mutableStateOf(false)
        private set

    /** True while obstacles are temporarily edible, like apples. */
    var powerModeActive by mutableStateOf(false)
        private set

    private var powerModeEndsAt = 0L

    /** Increments on every start() call so the game loop can key off a value
     * that is guaranteed to change even when reset() + start() happen inside
     * the same event (started would otherwise end up back at its old value
     * before recomposition ever observes the intermediate false). */
    var runId by mutableIntStateOf(0)
        private set

    private val occupied = HashSet<Point>()
    private val obstacleSet = HashSet<Point>()

    init {
        reset()
    }

    fun start() {
        started = true
        paused = false
        runId++
    }

    fun togglePause() {
        if (!started || gameOver) return
        paused = !paused
    }

    fun reset() {
        snake.clear()
        occupied.clear()
        obstacles.clear()
        obstacleSet.clear()

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
        paused = false
        powerModeActive = false
        powerModeEndsAt = 0L
        spawnApple()
    }

    private fun queueDirection(newDirection: Direction) {
        if (gameOver || newDirection.isOppositeOf(direction)) return
        pendingDirection = newDirection
    }

    /** Steering-wheel style controls: turn relative to the current heading
     * rather than pick an absolute direction. Much easier to hit blind
     * than a 4-way pad. */
    fun turnLeft() = queueDirection(direction.turnedLeft())

    fun turnRight() = queueDirection(direction.turnedRight())

    fun tick() {
        if (gameOver || !started || paused) return

        if (powerModeActive && System.currentTimeMillis() >= powerModeEndsAt) {
            powerModeActive = false
        }

        direction = pendingDirection
        val head = snake.first()
        val rawHead = head.moved(direction)
        val newHead = Point(wrap(rawHead.x, cols), wrap(rawHead.y, rows))

        val willEatApple = newHead == apple
        val willEatObstacle = powerModeActive && obstacleSet.contains(newHead)
        val isGrowing = willEatApple || willEatObstacle

        val tail = snake.last()
        val hitsBody = occupied.contains(newHead) && !(newHead == tail && !isGrowing)
        val hitsObstacle = !powerModeActive && obstacleSet.contains(newHead)
        if (hitsBody || hitsObstacle) {
            gameOver = true
            return
        }

        snake.add(0, newHead)
        occupied.add(newHead)

        if (willEatObstacle) {
            obstacles.remove(newHead)
            obstacleSet.remove(newHead)
        }

        if (isGrowing) {
            score++
            if (score % powerModeEveryNApples == 0) {
                powerModeActive = true
                powerModeEndsAt = System.currentTimeMillis() + powerModeDurationMs
            }
            if (willEatApple) {
                spawnApple()
            }
            spawnObstacle()
        } else {
            val removedTail = snake.removeAt(snake.size - 1)
            occupied.remove(removedTail)
        }
    }

    /** Tick delay in ms; speeds up gradually as the snake eats more apples. */
    fun tickDelayMillis(): Long = (170L - score * 3L).coerceIn(60L, 170L)

    private fun spawnApple() {
        val candidate = randomFreeCell() ?: return
        apple = candidate
    }

    private fun spawnObstacle() {
        val candidate = randomFreeCell(avoidHead = true) ?: return
        obstacles.add(candidate)
        obstacleSet.add(candidate)
    }

    private fun randomFreeCell(avoidHead: Boolean = false): Point? {
        val head = snake.firstOrNull()
        repeat(500) {
            val candidate = Point(Random.nextInt(cols), Random.nextInt(rows))
            val tooCloseToHead = avoidHead && head != null &&
                abs(candidate.x - head.x) <= obstacleSafeRadius &&
                abs(candidate.y - head.y) <= obstacleSafeRadius
            if (!occupied.contains(candidate) &&
                !obstacleSet.contains(candidate) &&
                candidate != apple &&
                !tooCloseToHead
            ) {
                return candidate
            }
        }
        return null
    }
}
