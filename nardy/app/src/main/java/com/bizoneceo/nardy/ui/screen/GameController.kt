package com.bizoneceo.nardy.ui.screen

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import com.bizoneceo.nardy.engine.Ai
import com.bizoneceo.nardy.engine.AiLevel
import com.bizoneceo.nardy.engine.GameState
import com.bizoneceo.nardy.engine.Move
import com.bizoneceo.nardy.engine.Phase
import com.bizoneceo.nardy.engine.Player
import com.bizoneceo.nardy.engine.Position
import com.bizoneceo.nardy.engine.Route
import com.bizoneceo.nardy.engine.Variant
import com.bizoneceo.nardy.ui.board.Spot
import com.bizoneceo.nardy.ui.board.relOf
import com.bizoneceo.nardy.ui.board.spotOf
import com.bizoneceo.nardy.ui.dice.DiceListener
import com.bizoneceo.nardy.ui.physics.CheckerWorld
import com.bizoneceo.nardy.ui.physics.TiltSource
import com.bizoneceo.nardy.ui.dice.DiceTray
import kotlin.concurrent.thread
import kotlin.math.hypot
import kotlin.random.Random

/** Sound and vibration hooks; the platform layer implements them. */
interface Feedback {
    fun dice(strength: Float) {}
    fun checker() {}
    fun tap() {}
    fun victory() {}
    /** Two checkers (or a checker and a rail) knocking together, 0..1. */
    fun knock(strength: Float) {}
}

data class Settings(
    val themeId: String = "walnut",
    /** Upper panel turned towards a player sitting opposite. */
    val faceToFace: Boolean = true,
    val sound: Boolean = true,
    val vibration: Boolean = true,
    val matchLength: Int = 0,
    /** Opponent for new games: null = a friend on the same device, else the computer. */
    val opponent: AiLevel? = null,
)

/** A checker gliding across the board, in board units. */
class Flyer(
    val player: Player,
    val path: List<Offset>,
    val target: Spot,
    val startNanos: Long,
    val hopSeconds: Float,
) {
    val durationSeconds: Float get() = hopSeconds * (path.size - 1)

    fun positionAt(nanos: Long): Offset {
        val t = (nanos - startNanos) / 1e9f
        if (t <= 0f || path.size == 1) return path.first()
        val hop = (t / hopSeconds).toInt()
        if (hop >= path.size - 1) return path.last()
        val local = (t - hop * hopSeconds) / hopSeconds
        val e = local * local * (3 - 2 * local)
        return path[hop] + (path[hop + 1] - path[hop]) * e
    }

    /** 0 on the table, 1 at the top of the arc — checkers lift while flying. */
    fun liftAt(nanos: Long): Float {
        val t = (nanos - startNanos) / 1e9f
        if (t <= 0f || t >= durationSeconds) return 0f
        val local = (t % hopSeconds) / hopSeconds
        return 4 * local * (1 - local)
    }

    fun isDone(nanos: Long) = (nanos - startNanos) / 1e9f >= durationSeconds
}

/** Checker currently dragged by a finger. */
data class Drag(val fromRel: Int, val board: Offset)

/**
 * Glue between the immutable rules engine, the dice simulation and touch
 * input. All mutable UI state lives here as Compose state.
 */
class GameController(
    initial: GameState,
    private val random: Random,
    var feedback: Feedback,
    private val onStateChanged: (GameState) -> Unit,
) {
    var state by mutableStateOf(initial)
        private set

    var selected by mutableStateOf<Int?>(null)
        private set
    var drag by mutableStateOf<Drag?>(null)
        private set

    /** False while the dice are flying — moves are blocked until they land. */
    var diceSettled by mutableStateOf(true)
        private set

    /** The last throw was the opening roll-off (one die per player). */
    var openingThrow by mutableStateOf(initial.phase == Phase.OPENING_ROLL)
        private set

    /** Redraw ticker, in frame nanoseconds. */
    var frameNanos by mutableLongStateOf(0L)
        private set

    val flyers = mutableStateListOf<Flyer>()
    val tray = DiceTray(random)

    var layout: GameLayout? = null

    /** Physics for the 3D build: null in the classic build. */
    var world: CheckerWorld? = null
        private set
    private var tilt: TiltSource? = null

    /** How the top of a checker shifts per unit of height on screen (3D build). */
    var camera = Offset(0f, -0.45f)
        private set

    /** Turns on 3D checkers driven by [tiltSource] (accelerometer). */
    fun enablePhysics(tiltSource: TiltSource) {
        tilt = tiltSource
        world = CheckerWorld().also { w -> w.onImpact = { feedback.knock(it) } }
    }

    /** The side played by the computer, or null for two people. */
    var aiPlayer by mutableStateOf<Player?>(null)
        private set
    var aiLevel by mutableStateOf(AiLevel.AMATEUR)
        private set
    /** True while the computer is working out its move. */
    var aiThinking by mutableStateOf(false)
        private set

    private var aiWaitUntil = 0L
    private var aiQueue: ArrayDeque<Move>? = null
    private var aiGeneration = 0
    @Volatile private var aiResult: Pair<GameState, List<Move>>? = null

    private var lastFrame = 0L
    private var gesture: Gesture = Gesture.None

    private sealed interface Gesture {
        data object None : Gesture
        data object Dice : Gesture
        data class Press(val rel: Int, val down: Offset) : Gesture
        data class Tap(val spot: Spot?) : Gesture
        data object Dragging : Gesture
    }

    init {
        tray.listener = DiceListener { feedback.dice(it) }
        tray.onSettled = {
            diceSettled = true
            if (state.isBlocked) feedback.tap()
        }
        if (initial.dice.isNotEmpty()) tray.showDocked(initial.dice)
        else if (initial.openingDice.isNotEmpty()) tray.showDocked(initial.openingDice)
    }

    // ---------------------------------------------------------------- derived

    /** It is the computer's turn (to throw or to move). */
    val isAiTurn: Boolean
        get() = aiPlayer != null && state.current == aiPlayer &&
            (state.phase == Phase.AWAIT_ROLL || state.phase == Phase.MOVING)

    /** The dice may be thrown by a person now (the opening roll-off is always thrown by a person). */
    val canThrow: Boolean
        get() = (state.phase == Phase.OPENING_ROLL || state.phase == Phase.AWAIT_ROLL) &&
            !tray.isRolling && flyers.isEmpty() && !isAiTurn

    val canInteract: Boolean
        get() = state.phase == Phase.MOVING && diceSettled && flyers.isEmpty() && !isAiTurn

    val selectedRoutes: List<Route>
        get() = selected?.let { s -> if (canInteract) state.routesFrom(s) else emptyList() } ?: emptyList()

    /** Values the two dice should show, or null to keep whatever they show now. */
    val shownDice: List<Int>?
        get() = when {
            state.phase == Phase.MOVING -> state.dice
            openingThrow && state.openingDice.isNotEmpty() -> state.openingDice
            else -> null
        }

    /** Whether die [i] has already been played this turn. */
    fun dieUsed(i: Int): Boolean {
        val s = state
        if (s.phase != Phase.MOVING || s.dice.size != 2) return false
        if (s.dice[0] == s.dice[1]) return s.remaining.isEmpty() || (i == 0 && s.remaining.size <= 2)
        return s.dice[i] !in s.remaining
    }

    val needsFrames: Boolean
        get() = tray.isBusy || flyers.isNotEmpty() || drag != null || selected != null || canThrow || isAiTurn ||
            world != null

    // ---------------------------------------------------------------- frames

    /** Keep the dice docked where the current layout and player want them. */
    fun syncTray() {
        val l = layout ?: return
        tray.configure(l.diceArena, l.docks.getValue(state.current), l.dieSize, l.dockSize)
        if (!tray.isBusy) shownDice?.let { tray.showDocked(it) }
    }

    fun onFrame(nanos: Long) {
        val dt = if (lastFrame == 0L) 0f else (nanos - lastFrame) / 1e9f
        lastFrame = nanos
        syncTray()
        if (tray.isBusy) tray.step(dt)
        if (flyers.isNotEmpty()) {
            val done = flyers.filter { it.isDone(nanos) }
            if (done.isNotEmpty()) {
                world?.let { w ->
                    for (f in done) {
                        // Carry the checker's momentum into the stack it lands on.
                        val a = f.path[maxOf(0, f.path.size - 2)]
                        val b = f.path.last()
                        val dir = (b - a).let { if (it.getDistance() < 1e-3f) Offset.Zero else it / it.getDistance() }
                        w.landed(f.target, dir * 2.5f)
                    }
                }
                flyers.removeAll(done)
                feedback.checker()
            }
        }
        if (isAiTurn) aiTick(nanos)
        world?.let { stepPhysics(it, dt) }
        frameNanos = nanos
    }

    private fun stepPhysics(w: CheckerWorld, dt: Float) {
        val l = layout ?: return
        val src = tilt
        val screen = if (src != null) Offset(src.x, src.y) else Offset.Zero
        // Screen axes → board axes (the board may be drawn turned by 90°).
        val board = if (l.transform.rotated) Offset(screen.y, -screen.x) else screen
        w.gravity = board
        camera = Offset(0f, -0.45f) - board * 0.6f
        val pos = state.position
        w.sync(pos, l.geometry, { visibleCount(pos, it) }, { countAt(pos, it) })
        w.step(dt, l.geometry.checker / 2, drag?.board)
    }

    // ---------------------------------------------------------------- computer

    /** Drives the computer's turn a little further; called every frame while it is its turn. */
    private fun aiTick(now: Long) {
        if (now < aiWaitUntil) return
        when (state.phase) {
            Phase.AWAIT_ROLL -> if (!tray.isRolling && flyers.isEmpty()) {
                aiThrow()
                aiWaitUntil = now + 400_000_000L
            }
            Phase.MOVING -> {
                if (!diceSettled || flyers.isNotEmpty() || tray.isRolling) return
                val queue = aiQueue
                if (queue == null) {
                    val result = aiResult
                    if (result != null && result.first === state) {
                        aiResult = null
                        aiThinking = false
                        aiQueue = ArrayDeque(result.second)
                        aiWaitUntil = now + 350_000_000L
                    } else if (!aiThinking) {
                        startThinking()
                    }
                    return
                }
                val next = queue.removeFirstOrNull()
                when {
                    next != null && next in state.legalSteps -> {
                        play(Route(listOf(next)))
                        aiWaitUntil = now + 420_000_000L
                    }
                    next != null -> aiQueue = null // position changed under us: think again
                    !state.canMove -> {
                        aiQueue = null
                        endTurn()
                        aiWaitUntil = now + 300_000_000L
                    }
                    else -> aiQueue = null
                }
            }
            else -> Unit
        }
    }

    private fun startThinking() {
        val snapshot = state
        val level = aiLevel
        val generation = aiGeneration
        aiThinking = true
        thread(isDaemon = true, name = "nardy-ai") {
            val moves = runCatching { Ai.chooseTurn(snapshot, level, random) }.getOrDefault(emptyList())
            if (generation == aiGeneration) aiResult = snapshot to moves
        }
    }

    private fun aiThrow() {
        val l = layout ?: return
        // A flick from the computer's side towards the middle of the arena.
        val from = l.docks.getValue(state.current).let { (it[0] + it[1]) / 2f }
        val dir = (l.diceArena.center - from).let { if (it.getDistance() < 1f) Offset(1f, 0f) else it / it.getDistance() }
        val angle = (random.nextFloat() - 0.5f) * 1.2f
        val c = kotlin.math.cos(angle)
        val sn = kotlin.math.sin(angle)
        val v = Offset(dir.x * c - dir.y * sn, dir.x * sn + dir.y * c) * (l.dieSize * (18f + random.nextFloat() * 14f))
        throwDice(v)
    }

    private fun resetAi() {
        aiGeneration++
        aiQueue = null
        aiResult = null
        aiThinking = false
        aiWaitUntil = 0L
    }

    fun onIdle() {
        lastFrame = 0L
    }

    // ---------------------------------------------------------------- input

    fun onDown(p: Offset) {
        val l = layout ?: return
        if (canThrow) {
            gesture = Gesture.Dice
            tray.hold(p)
            feedback.tap()
            return
        }
        if (!canInteract) {
            gesture = Gesture.None
            return
        }
        val spot = l.geometry.spotAt(l.transform.toBoard(p))
        val rel = spot?.let { state.variant.relOf(state.current, it) }
        gesture = if (rel != null && rel in state.movableFrom) Gesture.Press(rel, p) else Gesture.Tap(spot)
    }

    fun onMove(p: Offset) {
        val l = layout ?: return
        when (val g = gesture) {
            Gesture.Dice -> tray.moveHeld(p)
            is Gesture.Press -> {
                if (hypot(p.x - g.down.x, p.y - g.down.y) > l.transform.scale * 0.35f) {
                    gesture = Gesture.Dragging
                    selected = g.rel
                    drag = Drag(g.rel, l.transform.toBoard(p))
                }
            }
            Gesture.Dragging -> drag = drag?.copy(board = l.transform.toBoard(p))
            else -> Unit
        }
    }

    fun onUp(p: Offset, velocity: Offset) {
        val l = layout ?: return
        when (val g = gesture) {
            Gesture.Dice -> throwDice(velocity)
            is Gesture.Press -> tapChecker(g.rel)
            is Gesture.Tap -> tapSpot(g.spot)
            Gesture.Dragging -> {
                val d = drag
                drag = null
                if (d != null) {
                    val spot = l.geometry.spotAt(d.board)
                    val to = spot?.let { state.variant.relOf(state.current, it) }
                    val route = state.routesFrom(d.fromRel).firstOrNull { it.to == to }
                    if (route != null) {
                        play(route, start = d.board)
                    } else {
                        // Glide back home.
                        val home = state.variant.spotOf(state.current, d.fromRel)
                        flyers += Flyer(
                            state.current,
                            listOf(d.board, l.geometry.stackTop(home, countAt(state.position, home))),
                            home, frameNanos, 0.16f,
                        )
                        selected = d.fromRel
                    }
                }
            }
            Gesture.None -> Unit
        }
        gesture = Gesture.None
    }

    fun onCancel() {
        if (gesture == Gesture.Dice) throwDice(Offset.Zero)
        drag = null
        gesture = Gesture.None
    }

    private fun tapChecker(rel: Int) {
        val sel = selected
        if (sel == rel) {
            selected = null
            return
        }
        if (sel != null) {
            state.routesFrom(sel).firstOrNull { it.to == rel }?.let {
                play(it)
                return
            }
        }
        selected = rel
        feedback.tap()
    }

    private fun tapSpot(spot: Spot?) {
        val sel = selected ?: return
        val to = spot?.let { state.variant.relOf(state.current, it) }
        val route = state.routesFrom(sel).firstOrNull { it.to == to }
        if (route != null) play(route) else selected = null
    }

    // ---------------------------------------------------------------- actions

    private fun throwDice(velocity: Offset) {
        val s = state
        val values = listOf(random.nextInt(1, 7), random.nextInt(1, 7))
        diceSettled = false
        selected = null
        when (s.phase) {
            Phase.OPENING_ROLL -> {
                openingThrow = true
                update(s.rollOpening(values[0], values[1]))
            }
            Phase.AWAIT_ROLL -> {
                openingThrow = false
                update(s.roll(values[0], values[1]))
            }
            else -> return
        }
        tray.throwDice(velocity, values)
    }

    private fun play(route: Route, start: Offset? = null) {
        val l = layout ?: return
        val g = l.geometry
        val player = state.current
        val variant = state.variant
        var pos = state.position
        val path = ArrayList<Offset>()
        path += start ?: g.stackTop(variant.spotOf(player, route.from), countAt(pos, variant.spotOf(player, route.from)))
        val hop = if (start != null) 0.14f else 0.2f
        val hits = ArrayList<Flyer>()
        for ((i, m) in route.moves.withIndex()) {
            val next = pos.apply(player, m)
            val to = variant.spotOf(player, m.to)
            if (m.hit) {
                val bar = Spot.Bar(player.opponent)
                hits += Flyer(
                    player.opponent,
                    listOf(g.stackTop(to, 1), g.stackTop(bar, next.bar(player.opponent))),
                    bar, frameNanos + ((i + 1) * hop * 1e9f).toLong(), 0.28f,
                )
            }
            // Skip intermediate hops when dropped by hand: it already travelled.
            if (start == null || i == route.moves.lastIndex) path += g.stackTop(to, countAt(next, to))
            pos = next
        }
        flyers += Flyer(player, path, variant.spotOf(player, route.to), frameNanos, hop)
        flyers += hits
        selected = null
        val before = state.phase
        update(state.play(route))
        if (before == Phase.MOVING && state.phase != Phase.MOVING) feedback.victory()
    }

    fun undo() {
        if (!state.canUndo || flyers.isNotEmpty() || isAiTurn) return
        selected = null
        update(state.undo())
        feedback.tap()
    }

    fun endTurn() {
        if (state.phase != Phase.MOVING || state.canMove || !diceSettled) return
        selected = null
        update(state.endTurn())
        feedback.tap()
    }

    fun nextGame() {
        if (state.phase != Phase.GAME_OVER) return
        resetAi()
        world?.clear()
        openingThrow = true
        update(state.nextGame())
    }

    fun newMatch(variant: Variant, matchLength: Int, opponent: AiLevel? = aiPlayer?.let { aiLevel }) {
        setOpponent(opponent)
        world?.clear()
        flyers.clear()
        selected = null
        drag = null
        openingThrow = true
        diceSettled = true
        update(GameState.newMatch(variant, matchLength))
    }

    /** The computer always takes black; the person plays white from the near side. */
    fun setOpponent(level: AiLevel?) {
        resetAi()
        aiPlayer = if (level != null) Player.BLACK else null
        if (level != null) aiLevel = level
    }

    private fun update(s: GameState) {
        state = s
        onStateChanged(s)
    }

    /** Number of checkers on [spot] that are not still flying in. */
    fun visibleCount(position: Position, spot: Spot): Int {
        val inFlight = flyers.count { it.target == spot }
        val dragging = drag?.let { if (state.variant.spotOf(state.current, it.fromRel) == spot) 1 else 0 } ?: 0
        return countAt(position, spot) - inFlight - dragging
    }

    companion object {
        fun countAt(position: Position, spot: Spot): Int = when (spot) {
            is Spot.Point -> position.countAbs(Player.WHITE, spot.abs) + position.countAbs(Player.BLACK, spot.abs)
            is Spot.Bar -> position.bar(spot.player)
            is Spot.Off -> position.off(spot.player)
        }
    }
}
