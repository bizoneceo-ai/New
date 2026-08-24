package com.bizoneceo.snake8bit.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.concurrent.thread

/**
 * Tiny chiptune-style sound effects, synthesized on the fly as square-wave
 * beeps — no audio assets needed, and it fits the 8-bit look of the game.
 */
object SoundEngine {
    private const val SAMPLE_RATE = 44100

    fun playEat() = playSequence(660f to 55, 990f to 65)
    fun playGameOver() = playSequence(440f to 110, 330f to 110, 220f to 160)
    fun playPauseToggle() = playSequence(520f to 45)

    /** Very short, quiet tick — this fires on every turn, so it needs to
     * stay unobtrusive rather than compete with the other cues. */
    fun playTurn() = playSequence(300f to 20)

    private fun playSequence(vararg notes: Pair<Float, Int>) {
        thread(isDaemon = true) {
            notes.forEach { (freqHz, durationMs) -> playTone(freqHz, durationMs) }
        }
    }

    private fun playTone(freqHz: Float, durationMs: Int) {
        val sampleCount = SAMPLE_RATE * durationMs / 1000
        val buffer = ShortArray(sampleCount)
        val period = SAMPLE_RATE / freqHz
        // Short fade in/out so each beep doesn't click at its edges.
        val rampSamples = minOf(200, sampleCount / 4)

        for (i in buffer.indices) {
            val phase = (i % period) / period
            val envelope = when {
                i < rampSamples -> i.toFloat() / rampSamples
                i > sampleCount - rampSamples -> (sampleCount - i).toFloat() / rampSamples
                else -> 1f
            }
            val amplitude = if (phase < 0.5f) Short.MAX_VALUE / 4 else -(Short.MAX_VALUE / 4)
            buffer[i] = (amplitude * envelope).toInt().toShort()
        }

        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(buffer.size * 2)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()

        track.write(buffer, 0, buffer.size)
        track.play()
        Thread.sleep(durationMs.toLong() + 15)
        track.stop()
        track.release()
    }
}
