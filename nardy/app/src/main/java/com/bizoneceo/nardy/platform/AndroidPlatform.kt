package com.bizoneceo.nardy.platform

import android.app.Application
import android.content.Context
import androidx.compose.ui.geometry.Rect
import androidx.lifecycle.AndroidViewModel
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowLayoutInfo
import com.bizoneceo.nardy.BuildConfig
import com.bizoneceo.nardy.engine.AiLevel
import com.bizoneceo.nardy.ui.physics.TiltSource
import com.bizoneceo.nardy.ui.screen.AppModel
import com.bizoneceo.nardy.ui.screen.FoldInfo
import com.bizoneceo.nardy.ui.screen.Settings
import com.bizoneceo.nardy.ui.screen.Storage
import java.security.SecureRandom
import kotlin.random.asKotlinRandom

/** Survives configuration changes, so the match outlives any fold/unfold. */
class NardyViewModel(app: Application) : AndroidViewModel(app) {
    private val feedback = AndroidFeedback(app)

    // Dice must be fair: a cryptographic RNG, never a seeded one.
    val model = AppModel(PrefsStorage(app), SecureRandom().asKotlinRandom(), feedback)

    /** Device tilt for the 3D test build; null in the classic build. */
    val tilt: TiltSource? = if (BuildConfig.PHYSICS_3D) TiltSource() else null

    init {
        feedback.settings = { model.settings }
        tilt?.let { model.controller.enablePhysics(it) }
    }

    override fun onCleared() {
        feedback.release()
    }
}

/** First folding feature of the window, in window pixels. */
fun WindowLayoutInfo.toFoldInfo(): FoldInfo? {
    val f = displayFeatures.filterIsInstance<FoldingFeature>().firstOrNull() ?: return null
    val b = f.bounds
    val vertical = f.orientation == FoldingFeature.Orientation.VERTICAL
    return FoldInfo(
        bounds = Rect(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat()),
        vertical = vertical,
        separating = f.isSeparating,
        tabletop = !vertical && f.state == FoldingFeature.State.HALF_OPENED,
    )
}

class PrefsStorage(context: Context) : Storage {
    private val prefs = context.getSharedPreferences("nardy", Context.MODE_PRIVATE)

    override fun loadSettings(): Settings {
        val d = Settings()
        return Settings(
            themeId = prefs.getString("theme", d.themeId) ?: d.themeId,
            faceToFace = prefs.getBoolean("faceToFace", d.faceToFace),
            sound = prefs.getBoolean("sound", d.sound),
            vibration = prefs.getBoolean("vibration", d.vibration),
            matchLength = prefs.getInt("matchLength", d.matchLength),
            opponent = AiLevel.entries.firstOrNull { it.name == prefs.getString("opponent", null) },
        )
    }

    override fun saveSettings(settings: Settings) {
        prefs.edit()
            .putString("theme", settings.themeId)
            .putBoolean("faceToFace", settings.faceToFace)
            .putBoolean("sound", settings.sound)
            .putBoolean("vibration", settings.vibration)
            .putInt("matchLength", settings.matchLength)
            .putString("opponent", settings.opponent?.name)
            .apply()
    }

    override fun loadGame(): String? = prefs.getString("game", null)

    override fun saveGame(encoded: String?) {
        prefs.edit().putString("game", encoded).apply()
    }
}
