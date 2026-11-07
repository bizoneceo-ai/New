package com.bizoneceo.nardy.platform

import android.app.Activity
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.view.Surface
import com.bizoneceo.nardy.ui.physics.TiltSource

/**
 * Feeds the accelerometer into [TiltSource] in screen axes. The raw
 * accelerometer (not the fused gravity sensor) is used on purpose: besides
 * slow tilting, a quick jolt of the phone also kicks the checkers.
 */
class TiltSensor(private val activity: Activity, private val out: TiltSource) : SensorEventListener {
    private val manager = activity.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sensor: Sensor? = manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    fun start() {
        sensor?.let { manager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    fun stop() {
        manager.unregisterListener(this)
        out.x = 0f
        out.y = 0f
    }

    override fun onSensorChanged(event: SensorEvent) {
        val ax = event.values[0] / SensorManager.GRAVITY_EARTH
        val ay = event.values[1] / SensorManager.GRAVITY_EARTH
        // Direction things fall, in device axes turned to the current screen orientation.
        val (sx, sy) = when (rotation()) {
            Surface.ROTATION_90 -> ay to ax
            Surface.ROTATION_180 -> ax to -ay
            Surface.ROTATION_270 -> -ay to -ax
            else -> -ax to ay
        }
        // Light smoothing: keeps sensor noise out but lets jolts through.
        out.x += (sx - out.x) * 0.35f
        out.y += (sy - out.y) * 0.35f
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    @Suppress("DEPRECATION")
    private fun rotation(): Int =
        if (Build.VERSION.SDK_INT >= 30) activity.display?.rotation ?: Surface.ROTATION_0
        else activity.windowManager.defaultDisplay.rotation
}
