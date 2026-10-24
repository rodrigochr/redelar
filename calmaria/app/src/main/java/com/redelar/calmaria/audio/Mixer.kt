package com.redelar.calmaria.audio

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * Sums the active sounds with smooth per-channel volume ramps, applies the master fade and a
 * peak limiter so any combination of sounds stays clean. Control methods may be called from any
 * thread; [render] runs on the audio thread and does not allocate.
 */
class Mixer(private val sampleRate: Int) {
    private class Channel(val id: String, val gen: SoundGenerator) {
        @Volatile var target = 0f
        @Volatile var step = 1f
        @Volatile var removing = false
        var current = 0f
    }

    private val lock = Any()

    @Volatile
    private var channels: Array<Channel> = emptyArray()

    @Volatile
    private var masterTarget = 0f

    @Volatile
    private var masterStep = 1f

    /** Current master fade position (0..1), read by the output thread to know when a fade-out has finished. */
    @Volatile
    var master = 0f
        private set

    /** A jump requested by [resetMaster], applied by the audio thread so it can't be overwritten mid-block. */
    @Volatile
    private var pendingMaster = Float.NaN

    private var limiterEnv = 0f
    private val limiterRelease = exp(-1f / (0.15f * sampleRate))
    private val scratch = FloatArray(MAX_FRAMES * 2)

    // Long math: a one-minute fade at 48 kHz is 2.88 billion samples' worth of ms * Hz, past Int range.
    private fun stepFor(fadeMs: Int): Float = 1f / max(1L, fadeMs.toLong() * sampleRate / 1000)

    /** Sets a sound's gain, creating its generator with [create] if it is not already in the mix. */
    fun set(id: String, gain: Float, fadeMs: Int, create: () -> SoundGenerator) {
        synchronized(lock) {
            val existing = channels.firstOrNull { it.id == id }
            val ch = existing ?: Channel(id, create()).also { channels = channels + it }
            ch.step = stepFor(fadeMs)
            ch.target = gain
            ch.removing = false
        }
    }

    /** Fades a sound out and drops it from the mix once silent. */
    fun remove(id: String, fadeMs: Int) {
        synchronized(lock) {
            channels.firstOrNull { it.id == id }?.let {
                it.step = stepFor(fadeMs)
                it.target = 0f
                it.removing = true
            }
        }
    }

    fun ids(): Set<String> = channels.filter { !it.removing }.mapTo(HashSet()) { it.id }

    fun setMaster(target: Float, fadeMs: Int) {
        masterStep = stepFor(fadeMs)
        masterTarget = target
    }

    /** Jumps the master gain without a ramp, e.g. to start an alarm from silence. */
    fun resetMaster(value: Float) {
        masterTarget = value
        master = value
        pendingMaster = value
    }

    fun render(out: FloatArray, frames: Int) {
        out.fill(0f, 0, frames * 2)
        val snapshot = channels
        var finished = false
        for (ch in snapshot) {
            val t = ch.target
            var g = ch.current
            if (g == 0f && t == 0f) {
                if (ch.removing) finished = true
                continue
            }
            try {
                ch.gen.render(scratch, frames)
            } catch (e: RuntimeException) {
                // A misbehaving generator must never take the whole mix down with it.
                ch.target = 0f
                ch.current = 0f
                ch.removing = true
                finished = true
                continue
            }
            val step = ch.step
            for (i in 0 until frames) {
                if (g < t) g = min(t, g + step) else if (g > t) g = max(t, g - step)
                out[2 * i] += scratch[2 * i] * g
                out[2 * i + 1] += scratch[2 * i + 1] * g
            }
            ch.current = g
            if (ch.removing && g <= 0f) finished = true
        }
        if (finished) dropFinished()

        val reset = pendingMaster
        if (!reset.isNaN()) {
            pendingMaster = Float.NaN
            master = reset
        }
        var m = master
        val mt = masterTarget
        val ms = masterStep
        var env = limiterEnv
        for (i in 0 until frames) {
            if (m < mt) m = min(mt, m + ms) else if (m > mt) m = max(mt, m - ms)
            // Squared so fades sound even to the ear: a linear ramp is loud almost at once.
            val g = m * m * OUTPUT_GAIN
            var l = out[2 * i] * g
            var r = out[2 * i + 1] * g
            val a = max(abs(l), abs(r))
            env = max(a, env * limiterRelease)
            if (env > LIMIT) {
                val k = LIMIT / env
                l *= k
                r *= k
            }
            out[2 * i] = l
            out[2 * i + 1] = r
        }
        limiterEnv = env + 1e-20f
        master = m
    }

    private fun dropFinished() {
        synchronized(lock) {
            channels = channels.filterNot { it.removing && it.current <= 0f }.toTypedArray()
        }
    }

    companion object {
        /** Overall make-up gain; the limiter keeps louder mixes in check. */
        const val OUTPUT_GAIN = 2f
        const val LIMIT = 0.9f
    }
}
