package com.redelar.calmaria.audio

import androidx.annotation.StringRes
import com.redelar.calmaria.R
import com.redelar.calmaria.audio.sounds.AmbientPad
import com.redelar.calmaria.audio.sounds.Binaural
import com.redelar.calmaria.audio.sounds.BirdKind
import com.redelar.calmaria.audio.sounds.Birds
import com.redelar.calmaria.audio.sounds.ColoredNoise
import com.redelar.calmaria.audio.sounds.Crickets
import com.redelar.calmaria.audio.sounds.DropLayer
import com.redelar.calmaria.audio.sounds.FlowSpec
import com.redelar.calmaria.audio.sounds.FlowingWater
import com.redelar.calmaria.audio.sounds.Frogs
import com.redelar.calmaria.audio.sounds.Heartbeat
import com.redelar.calmaria.audio.sounds.Keyboard
import com.redelar.calmaria.audio.sounds.Machine
import com.redelar.calmaria.audio.sounds.MachineSpec
import com.redelar.calmaria.audio.sounds.NoiseColor
import com.redelar.calmaria.audio.sounds.OmDrone
import com.redelar.calmaria.audio.sounds.Owl
import com.redelar.calmaria.audio.sounds.PluckSpec
import com.redelar.calmaria.audio.sounds.Plucked
import com.redelar.calmaria.audio.sounds.Rain
import com.redelar.calmaria.audio.sounds.RainSpec
import com.redelar.calmaria.audio.sounds.Shush
import com.redelar.calmaria.audio.sounds.SingingBowl
import com.redelar.calmaria.audio.sounds.Wind
import com.redelar.calmaria.audio.sounds.WindChimes
import com.redelar.calmaria.audio.sounds.WindSpec

enum class Category(@param:StringRes val title: Int) {
    RAIN(R.string.cat_rain),
    NATURE(R.string.cat_nature),
    CITY(R.string.cat_city),
    NOISE(R.string.cat_noise),
    MEDITATION(R.string.cat_meditation),
}

/**
 * A sound in the library. [gain] levels every sound to a similar loudness at full volume,
 * [create] builds a fresh generator for the given sample rate. Real recordings stream their
 * [asset]; the rest (noises, binaural beats, generative music and a few others) are synthesized.
 */
class Sound(
    val id: String,
    @param:StringRes val title: Int,
    val category: Category,
    val gain: Float,
    val asset: String? = null,
    val create: (sampleRate: Int) -> SoundGenerator,
)

/** A bundled field recording (see assets/sounds/credits.json for who recorded it). */
private fun recording(id: String, @StringRes title: Int, category: Category, gain: Float): Sound {
    val asset = "sounds/$id.ogg"
    return Sound(id, title, category, gain, asset) { sr -> StreamingLoop(sr) { Recordings.open(asset) } }
}

private val GENTLE_TREES = WindSpec(0.2f, 0.7f, 3f, 8f, 500f, 1100f, q = 0.8f, tone = 0.5f, body = 0.2f, rustle = 0.7f)

private val PENTATONIC = floatArrayOf(60f, 62f, 64f, 67f, 69f, 72f, 74f, 76f, 79f, 81f)

object SoundCatalog {
    val all: List<Sound> = listOf(
        // Rain & water
        recording("rain_light", R.string.s_rain_light, Category.RAIN, 1.05f),
        recording("rain_medium", R.string.s_rain_medium, Category.RAIN, 1.05f),
        recording("rain_heavy", R.string.s_rain_heavy, Category.RAIN, 1.04f),
        recording("rain_thunder", R.string.s_rain_thunder, Category.RAIN, 1.7f),
        Sound("rain_umbrella", R.string.s_rain_umbrella, Category.RAIN, 1.39f) {
            Rain(
                it,
                RainSpec(
                    bed = 0.35f, bedHp = 450f, bedLp = 5000f,
                    layers = listOf(
                        DropLayer(55f, 0.9f, 260f, 700f, 0.015f, 0.04f, noise = 0.35f, noiseDecay = 0.004f, bright = 0.25f, width = 0.4f),
                        DropLayer(140f, 0.25f, 1500f, 5000f, 0.003f, 0.008f, noise = 0.6f, noiseDecay = 0.0015f, bright = 0.8f),
                    ),
                ),
            )
        },
        recording("rain_roof", R.string.s_rain_roof, Category.RAIN, 1.82f),
        recording("rain_window", R.string.s_rain_window, Category.RAIN, 1.04f),
        recording("rain_leaves", R.string.s_rain_leaves, Category.RAIN, 1.19f),
        Sound("rain_tent", R.string.s_rain_tent, Category.RAIN, 0.98f) {
            Rain(
                it,
                RainSpec(
                    bed = 0.45f, bedHp = 200f, bedLp = 3000f, rumble = 0.15f,
                    layers = listOf(
                        DropLayer(70f, 0.85f, 180f, 500f, 0.02f, 0.045f, noise = 0.3f, noiseDecay = 0.004f, bright = 0.2f, width = 0.7f),
                        DropLayer(40f, 0.2f, 900f, 2200f, 0.004f, 0.01f, noise = 0.5f, bright = 0.6f),
                    ),
                ),
            )
        },
        recording("water_drops", R.string.s_water_drops, Category.RAIN, 1.43f),
        recording("ocean", R.string.s_ocean, Category.RAIN, 1.04f),
        recording("shore", R.string.s_shore, Category.RAIN, 1.04f),
        recording("stream", R.string.s_stream, Category.RAIN, 1.05f),
        recording("river", R.string.s_river, Category.RAIN, 1.04f),
        recording("waterfall", R.string.s_waterfall, Category.RAIN, 1.04f),
        Sound("underwater", R.string.s_underwater, Category.RAIN, 0.67f) {
            FlowingWater(
                it,
                FlowSpec(0f, 200f, 400f, 0.25f, 500f, 0.9f, 0.25f, 0.5f, 150f, 600f, 0.03f, 0.09f, 1.4f, 1.9f, bursts = true, muffle = 1000f, reverb = 0.2f),
            )
        },

        // Nature & forest
        recording("wind", R.string.s_wind, Category.NATURE, 1.3f),
        recording("wind_trees", R.string.s_wind_trees, Category.NATURE, 1.04f),
        recording("howling_wind", R.string.s_howling_wind, Category.NATURE, 1.02f),
        Sound("blizzard", R.string.s_blizzard, Category.NATURE, 0.57f) {
            Wind(it, WindSpec(0.5f, 1f, 1.5f, 5f, 400f, 1500f, q = 0.8f, tone = 0.9f, body = 0.6f, whistle = 0.25f, hiss = 0.35f))
        },
        recording("thunder", R.string.s_thunder, Category.NATURE, 1.19f),
        recording("forest_birds", R.string.s_forest_birds, Category.NATURE, 1.04f),
        recording("morning_birds", R.string.s_morning_birds, Category.NATURE, 1.04f),
        Sound("doves", R.string.s_doves, Category.NATURE, 0.87f) {
            Birds(it, listOf(BirdKind.DOVE, BirdKind.DOVE, BirdKind.CHIRPS), 5f, 12f, 0.05f, 0.25f)
        },
        Sound("owl", R.string.s_owl, Category.NATURE, 0.97f) { Owl(it) },
        recording("crickets", R.string.s_crickets, Category.NATURE, 1.05f),
        recording("cicadas", R.string.s_cicadas, Category.NATURE, 1.04f),
        Sound("frogs", R.string.s_frogs, Category.NATURE, 1.4f) { Frogs(it) },
        recording("campfire", R.string.s_campfire, Category.NATURE, 1.19f),
        Sound("night_forest", R.string.s_night_forest, Category.NATURE, 0.94f) {
            Layered(it, listOf(Crickets(it, 5) to 0.7f, Owl(it) to 0.6f, Wind(it, GENTLE_TREES) to 0.35f, Frogs(it, 3) to 0.3f))
        },

        // City & home
        recording("city", R.string.s_city, Category.CITY, 1.04f),
        recording("train", R.string.s_train, Category.CITY, 1.01f),
        Sound("airplane", R.string.s_airplane, Category.CITY, 0.5f) {
            Machine(it, MachineSpec(pink = 0.9f, brown = 0.5f, hp = 30f, lp = 600f, humFreq = 96f, hum = 0.025f, wanderMin = 0.96f, wanderMax = 1.04f))
        },
        recording("car", R.string.s_car, Category.CITY, 1.15f),
        recording("cafe", R.string.s_cafe, Category.CITY, 1.26f),
        Sound("keyboard", R.string.s_keyboard, Category.CITY, 2.83f) { Keyboard(it) },
        recording("clock", R.string.s_clock, Category.CITY, 1.06f),
        recording("fan", R.string.s_fan, Category.CITY, 1.01f),
        recording("air_conditioner", R.string.s_air_conditioner, Category.CITY, 1.01f),
        recording("washing_machine", R.string.s_washing_machine, Category.CITY, 1.05f),
        recording("hair_dryer", R.string.s_hair_dryer, Category.CITY, 1.01f),
        recording("fireplace", R.string.s_fireplace, Category.CITY, 1.02f),

        // Noise
        Sound("white_noise", R.string.s_white_noise, Category.NOISE, 0.2f) { ColoredNoise(it, NoiseColor.WHITE) },
        Sound("pink_noise", R.string.s_pink_noise, Category.NOISE, 0.28f) { ColoredNoise(it, NoiseColor.PINK) },
        Sound("brown_noise", R.string.s_brown_noise, Category.NOISE, 0.5f) { ColoredNoise(it, NoiseColor.BROWN) },
        Sound("deep_brown", R.string.s_deep_brown, Category.NOISE, 0.39f) { ColoredNoise(it, NoiseColor.DEEP) },
        Sound("green_noise", R.string.s_green_noise, Category.NOISE, 0.55f) { ColoredNoise(it, NoiseColor.GREEN) },
        Sound("heartbeat", R.string.s_heartbeat, Category.NOISE, 1.33f) { Heartbeat(it, womb = false) },
        Sound("womb", R.string.s_womb, Category.NOISE, 0.8f) { Heartbeat(it, womb = true) },
        Sound("shush", R.string.s_shush, Category.NOISE, 0.67f) { Shush(it) },

        // Meditation & music
        Sound("singing_bowl", R.string.s_singing_bowl, Category.MEDITATION, 0.6f) { SingingBowl(it) },
        Sound("wind_chimes", R.string.s_wind_chimes, Category.MEDITATION, 1.3f) { WindChimes(it) },
        Sound("om", R.string.s_om, Category.MEDITATION, 0.3f) { OmDrone(it) },
        Sound("pad", R.string.s_pad, Category.MEDITATION, 0.47f) { AmbientPad(it) },
        Sound("kalimba", R.string.s_kalimba, Category.MEDITATION, 2.04f) {
            Plucked(
                it,
                PluckSpec(
                    ratios = floatArrayOf(1f, 2f, 5.95f), amps = floatArrayOf(1f, 0.1f, 0.25f), decays = floatArrayOf(2.2f, 1f, 0.25f),
                    scale = PENTATONIC, intervalMin = 0.6f, intervalMax = 1.8f, harmonyChance = 0.2f, click = 0.08f,
                    reverb = 0.3f, echo = true, softness = 7000f,
                ),
            )
        },
        Sound("piano", R.string.s_piano, Category.MEDITATION, 1.62f) {
            Plucked(
                it,
                PluckSpec(
                    ratios = floatArrayOf(1f, 2.003f, 3.008f, 4.017f, 5.03f),
                    amps = floatArrayOf(1f, 0.4f, 0.18f, 0.08f, 0.04f),
                    decays = floatArrayOf(3.6f, 2.2f, 1.4f, 1f, 0.7f),
                    scale = floatArrayOf(50f, 55f, 57f, 59f, 62f, 64f, 66f, 67f, 69f, 71f, 74f, 76f),
                    intervalMin = 1f, intervalMax = 2.8f, harmonyChance = 0.3f, click = 0.03f, reverb = 0.35f, softness = 3200f,
                ),
            )
        },
        Sound("music_box", R.string.s_music_box, Category.MEDITATION, 1.55f) {
            Plucked(
                it,
                PluckSpec(
                    ratios = floatArrayOf(1f, 3f, 5.2f), amps = floatArrayOf(1f, 0.18f, 0.12f), decays = floatArrayOf(1.6f, 0.6f, 0.3f),
                    scale = floatArrayOf(72f, 74f, 76f, 79f, 81f, 84f, 86f, 88f, 91f),
                    intervalMin = 0.35f, intervalMax = 0.9f, harmonyChance = 0.15f, click = 0.05f, reverb = 0.3f, softness = 9000f,
                ),
            )
        },
        Sound("binaural_delta", R.string.s_binaural_delta, Category.MEDITATION, 0.2f) { Binaural(it, 140f, 2.5f) },
        Sound("binaural_theta", R.string.s_binaural_theta, Category.MEDITATION, 0.2f) { Binaural(it, 180f, 6f) },
        Sound("binaural_alpha", R.string.s_binaural_alpha, Category.MEDITATION, 0.2f) { Binaural(it, 200f, 10f) },
    )

    private val byId = all.associateBy { it.id }

    operator fun get(id: String): Sound? = byId[id]

    fun inCategory(category: Category): List<Sound> = all.filter { it.category == category }
}
