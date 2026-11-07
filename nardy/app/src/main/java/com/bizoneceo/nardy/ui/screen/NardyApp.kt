package com.bizoneceo.nardy.ui.screen

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import com.bizoneceo.nardy.engine.AiLevel
import com.bizoneceo.nardy.engine.GameState
import com.bizoneceo.nardy.engine.Phase
import com.bizoneceo.nardy.engine.Variant
import com.bizoneceo.nardy.ui.board.themeById
import kotlin.random.Random

/** Where settings and the running game are kept between launches. */
interface Storage {
    fun loadSettings(): Settings
    fun saveSettings(settings: Settings)
    fun loadGame(): String?
    fun saveGame(encoded: String?)
}

enum class AppScreen { MENU, GAME }

/**
 * App-level state: settings, navigation and the one running match.
 *
 * [platformFeedback] is expected to honour the sound / vibration switches
 * itself (it can read [settings] from the model).
 */
class AppModel(private val storage: Storage, random: Random, platformFeedback: Feedback) {
    var settings by mutableStateOf(storage.loadSettings())
        private set
    var screen by mutableStateOf(AppScreen.MENU)

    var hasGame by mutableStateOf(false)
        private set

    val controller: GameController

    init {
        val (savedAi, saved) = decodeSave(storage.loadGame())
        hasGame = saved != null && saved.phase != Phase.MATCH_OVER
        controller = GameController(
            initial = saved ?: GameState.newMatch(Variant.LONG, settings.matchLength),
            random = random,
            feedback = platformFeedback,
            onStateChanged = { s ->
                hasGame = true
                storage.saveGame(encodeSave(s))
            },
        )
        controller.setOpponent(savedAi)
    }

    // Saved game: "ai=<LEVEL or ->|" followed by the engine's own format.
    private fun encodeSave(s: GameState): String {
        val ai = if (controller.aiPlayer != null) controller.aiLevel.name else "-"
        return "ai=$ai|" + s.encode()
    }

    private fun decodeSave(raw: String?): Pair<AiLevel?, GameState?> {
        if (raw == null) return null to null
        if (!raw.startsWith("ai=")) return null to GameState.decode(raw) // saves from version 1.0
        val bar = raw.indexOf('|')
        if (bar < 0) return null to null
        val ai = AiLevel.entries.firstOrNull { it.name == raw.substring(3, bar) }
        return ai to GameState.decode(raw.substring(bar + 1))
    }

    fun updateSettings(s: Settings) {
        settings = s
        storage.saveSettings(s)
    }

    fun newGame(variant: Variant) {
        controller.newMatch(variant, settings.matchLength, settings.opponent)
        screen = AppScreen.GAME
    }

    fun continueGame() {
        screen = AppScreen.GAME
    }

    /** Handles system back; returns false when the app should close. */
    fun back(): Boolean {
        if (screen == AppScreen.GAME) {
            screen = AppScreen.MENU
            return true
        }
        return false
    }
}

@Composable
fun NardyApp(model: AppModel, fold: FoldInfo?) {
    val theme = themeById(model.settings.themeId)
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = theme.accent,
            onPrimary = Color(0xFF1B1410),
            background = theme.background,
            surface = Color(0xFF1C140E),
        ),
    ) {
        when (model.screen) {
            AppScreen.MENU -> MenuScreen(
                settings = model.settings,
                saved = if (model.hasGame) model.controller.state else null,
                savedOpponent = if (model.controller.aiPlayer != null) "против ИИ · ${model.controller.aiLevel.title}" else "вдвоём",
                onSettings = model::updateSettings,
                onContinue = model::continueGame,
                onNewGame = model::newGame,
                physics3d = model.controller.world != null,
            )
            AppScreen.GAME -> GameScreen(
                controller = model.controller,
                settings = model.settings,
                fold = fold,
                onMenu = { model.screen = AppScreen.MENU },
            )
        }
    }
}
