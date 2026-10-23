package com.redelar.calmaria.audio

import com.redelar.calmaria.audio.dsp.Rng

/** Largest block, in frames, the engine ever asks a generator to render. */
const val MAX_FRAMES = 1024

/**
 * A procedurally synthesised, endless sound. Implementations keep all state between calls and
 * must not allocate inside [render]; it runs on the audio thread.
 */
abstract class SoundGenerator(protected val sr: Int) {
    protected val rng = Rng()

    /** Overwrites the first [frames] interleaved stereo frames of [out]. */
    abstract fun render(out: FloatArray, frames: Int)
}

/** Several generators summed with fixed gains, e.g. heavy rain plus thunder. */
class Layered(sr: Int, layers: List<Pair<SoundGenerator, Float>>) : SoundGenerator(sr) {
    private val gens = Array(layers.size) { layers[it].first }
    private val gains = FloatArray(layers.size) { layers[it].second }
    private val scratch = FloatArray(MAX_FRAMES * 2)

    override fun render(out: FloatArray, frames: Int) {
        out.fill(0f, 0, frames * 2)
        for (k in gens.indices) {
            gens[k].render(scratch, frames)
            val g = gains[k]
            for (i in 0 until frames * 2) out[i] += scratch[i] * g
        }
    }
}
