package xyz.aresius.audiobooks.core.player

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.sqrt

/** Recognises a shake in accelerometer samples: [peaks] strong jolts (above [thresholdG]) within [windowMs]; quiet for [cooldownMs] after. */
class ShakeLogic(
    private val thresholdG: Float = 2.5f,
    private val peaks: Int = 3,
    private val windowMs: Long = 1_000,
    private val minGapMs: Long = 120,
    private val cooldownMs: Long = 2_000,
) {
    private val times = ArrayDeque<Long>()
    private var quietUntil = Long.MIN_VALUE

    /** [x], [y], [z] in m/s^2 (gravity included). True when this sample completes a shake. */
    fun onSample(x: Float, y: Float, z: Float, nowMs: Long): Boolean {
        if (nowMs < quietUntil) return false
        val g = sqrt(x * x + y * y + z * z) / SensorManager.GRAVITY_EARTH
        if (g < thresholdG) return false
        if (times.isNotEmpty() && nowMs - times.last() < minGapMs) return false
        times.addLast(nowMs)
        while (times.isNotEmpty() && nowMs - times.first() > windowMs) times.removeFirst()
        if (times.size < peaks) return false
        times.clear()
        quietUntil = nowMs + cooldownMs
        return true
    }
}

/** The accelerometer, only while the sleep timer runs (the foreground service keeps delivering events with the screen off). */
class AccelerometerShakeSource(context: Context, private val now: () -> Long = { android.os.SystemClock.elapsedRealtime() }) : ShakeSource {
    private val sensors = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private var listener: SensorEventListener? = null

    override fun start(onShake: () -> Unit) {
        if (listener != null) return
        val accelerometer = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
        val logic = ShakeLogic()
        val l = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (logic.onSample(event.values[0], event.values[1], event.values[2], now())) onShake()
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        listener = l
        sensors.registerListener(l, accelerometer, SensorManager.SENSOR_DELAY_UI)
    }

    override fun stop() {
        listener?.let(sensors::unregisterListener)
        listener = null
    }
}
