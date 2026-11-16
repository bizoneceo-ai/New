package com.bizoneceo.nardy.platform

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.bizoneceo.nardy.ui.screen.Feedback
import com.bizoneceo.nardy.ui.screen.Settings
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/**
 * Sounds are synthesised once at start-up (no audio assets in the APK),
 * written to the cache as WAV and played through a low-latency SoundPool.
 */
class AndroidFeedback(context: Context) : Feedback {
    var settings: () -> Settings = { Settings() }

    private val pool = SoundPool.Builder()
        .setMaxStreams(6)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
        )
        .build()

    private val vibrator: Vibrator? =
        if (Build.VERSION.SDK_INT >= 31) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }

    @Volatile private var diceSounds = IntArray(0)
    @Volatile private var checkerSound = 0
    @Volatile private var winSound = 0
    private var lastDice = 0L

    init {
        val dir = File(context.cacheDir, "sfx").apply { mkdirs() }
        thread(isDaemon = true, name = "sfx-synth") {
            diceSounds = IntArray(3) { i -> load(dir, "dice$i", Synth.diceClack(i)) }
            checkerSound = load(dir, "checker", Synth.checker())
            winSound = load(dir, "win", Synth.victory())
        }
    }

    private fun load(dir: File, name: String, pcm: ShortArray): Int {
        val f = File(dir, "$name.wav")
        FileOutputStream(f).use { it.write(Synth.wav(pcm)) }
        return pool.load(f.path, 1)
    }

    override fun dice(strength: Float) {
        val now = System.currentTimeMillis()
        if (now - lastDice < 35) return // two dice landing together sound like one clack
        lastDice = now
        val s = settings()
        if (s.sound && diceSounds.isNotEmpty()) {
            val v = 0.25f + 0.75f * strength
            pool.play(diceSounds[Random.nextInt(diceSounds.size)], v, v, 1, 0, 0.9f + Random.nextFloat() * 0.25f)
        }
        if (s.vibration) vibrate(8 + (strength * 22).toLong(), (40 + strength * 200).toInt())
    }

    override fun checker() {
        val s = settings()
        if (s.sound && checkerSound != 0) pool.play(checkerSound, 0.8f, 0.8f, 1, 0, 0.95f + Random.nextFloat() * 0.1f)
        if (s.vibration) vibrate(14, 110)
    }

    override fun tap() {
        if (settings().vibration) vibrate(6, 60)
    }

    override fun victory() {
        val s = settings()
        if (s.sound && winSound != 0) pool.play(winSound, 0.9f, 0.9f, 2, 0, 1f)
        if (s.vibration) vibrate(60, 180)
    }

    private fun vibrate(ms: Long, amplitude: Int) {
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        val amp = if (v.hasAmplitudeControl()) amplitude.coerceIn(1, 255) else VibrationEffect.DEFAULT_AMPLITUDE
        v.vibrate(VibrationEffect.createOneShot(ms, amp))
    }

    fun release() = pool.release()
}

/** Tiny procedural synth for the wooden board sounds. */
internal object Synth {
    private const val RATE = 44100

    private fun render(seconds: Float, f: (t: Float, noise: Float) -> Float): ShortArray {
        val n = (RATE * seconds).toInt()
        val rnd = Random(n)
        return ShortArray(n) { i ->
            val t = i / RATE.toFloat()
            val attack = (i / (RATE * 0.0008f)).coerceAtMost(1f)
            (f(t, rnd.nextFloat() * 2 - 1) * attack * Short.MAX_VALUE * 0.9f)
                .coerceIn(Short.MIN_VALUE.toFloat(), Short.MAX_VALUE.toFloat()).toInt().toShort()
        }
    }

    private fun tone(freq: Float, t: Float) = sin(2 * PI.toFloat() * freq * t)

    /** Bone/resin die hitting a wooden board. */
    fun diceClack(variant: Int): ShortArray {
        val f1 = listOf(2150f, 2480f, 1900f)[variant]
        val f2 = f1 * 1.62f
        return render(0.09f) { t, noise ->
            val body = exp(-t / 0.011f) * (0.55f * tone(f1, t) + 0.3f * tone(f2, t))
            val wood = exp(-t / 0.02f) * 0.35f * tone(380f + variant * 40f, t)
            val click = exp(-t / 0.0025f) * 0.6f * noise
            body + wood + click
        }
    }

    /** Checker set down on a point. */
    fun checker(): ShortArray = render(0.14f) { t, noise ->
        exp(-t / 0.028f) * (0.55f * tone(520f, t) + 0.25f * tone(1180f, t)) +
            exp(-t / 0.003f) * 0.45f * noise
    }

    /** Soft bell arpeggio for a won game. */
    fun victory(): ShortArray {
        val notes = listOf(523.25f, 659.25f, 783.99f, 1046.5f)
        return render(1.4f) { t, _ ->
            var s = 0f
            for ((i, f) in notes.withIndex()) {
                val start = i * 0.12f
                if (t >= start) {
                    val lt = t - start
                    s += exp(-lt / 0.45f) * (0.5f * tone(f, lt) + 0.18f * tone(f * 2.01f, lt) + 0.08f * tone(f * 3f, lt))
                }
            }
            s * 0.45f
        }
    }

    fun wav(pcm: ShortArray): ByteArray {
        val dataLen = pcm.size * 2
        val b = ByteBuffer.allocate(44 + dataLen).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt(36 + dataLen).put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
            .putInt(RATE).putInt(RATE * 2).putShort(2).putShort(16)
        b.put("data".toByteArray()).putInt(dataLen)
        for (s in pcm) b.putShort(s)
        return b.array()
    }
}
