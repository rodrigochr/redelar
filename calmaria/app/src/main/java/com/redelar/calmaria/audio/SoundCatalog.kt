package com.redelar.calmaria.audio

import androidx.annotation.StringRes
import com.redelar.calmaria.R
import com.redelar.calmaria.audio.sounds.AmbientPad
import com.redelar.calmaria.audio.sounds.Binaural
import com.redelar.calmaria.audio.sounds.BirdKind
import com.redelar.calmaria.audio.sounds.Birds
import com.redelar.calmaria.audio.sounds.Cafe
import com.redelar.calmaria.audio.sounds.Cicadas
import com.redelar.calmaria.audio.sounds.Clock
import com.redelar.calmaria.audio.sounds.ColoredNoise
import com.redelar.calmaria.audio.sounds.Crickets
import com.redelar.calmaria.audio.sounds.DropLayer
import com.redelar.calmaria.audio.sounds.Fire
import com.redelar.calmaria.audio.sounds.FlowSpec
import com.redelar.calmaria.audio.sounds.FlowingWater
import com.redelar.calmaria.audio.sounds.Frogs
import com.redelar.calmaria.audio.sounds.Heartbeat
import com.redelar.calmaria.audio.sounds.Keyboard
import com.redelar.calmaria.audio.sounds.Machine
import com.redelar.calmaria.audio.sounds.MachineSpec
import com.redelar.calmaria.audio.sounds.NoiseColor
import com.redelar.calmaria.audio.sounds.Ocean
import com.redelar.calmaria.audio.sounds.OceanSpec
import com.redelar.calmaria.audio.sounds.OmDrone
import com.redelar.calmaria.audio.sounds.Owl
import com.redelar.calmaria.audio.sounds.PluckSpec
import com.redelar.calmaria.audio.sounds.Plucked
import com.redelar.calmaria.audio.sounds.Rain
import com.redelar.calmaria.audio.sounds.RainSpec
import com.redelar.calmaria.audio.sounds.Shush
import com.redelar.calmaria.audio.sounds.SingingBowl
import com.redelar.calmaria.audio.sounds.Thunder
import com.redelar.calmaria.audio.sounds.Train
import com.redelar.calmaria.audio.sounds.Traffic
import com.redelar.calmaria.audio.sounds.WashingMachine
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
 * A sound in the library. [gain] levels every sound to a similar loudness at full volume
 * (see `SoundLevelsTest`), [create] builds a fresh generator for the given sample rate.
 */
class Sound(
    val id: String,
    @param:StringRes val title: Int,
    val category: Category,
    val gain: Float,
    val create: (sampleRate: Int) -> SoundGenerator,
)

private val RAIN_HEAVY = RainSpec(
    bed = 1.1f, bedHp = 180f, bedLp = 6500f, rumble = 0.6f,
    layers = listOf(
        DropLayer(650f, 0.4f, 1000f, 6000f, 0.003f, 0.01f, noise = 0.65f, noiseDecay = 0.0015f, bright = 0.75f),
        DropLayer(120f, 0.4f, 350f, 1300f, 0.008f, 0.025f, noise = 0.5f, bright = 0.4f),
        DropLayer(4f, 0.2f, 600f, 1600f, 0.04f, 0.08f, noise = 0.1f, glide = 1.3f),
    ),
    intensityMin = 0.85f, intensityMax = 1.2f,
)

private val GENTLE_TREES = WindSpec(0.2f, 0.7f, 3f, 8f, 500f, 1100f, q = 0.8f, tone = 0.5f, body = 0.2f, rustle = 0.7f)

private val PENTATONIC = floatArrayOf(60f, 62f, 64f, 67f, 69f, 72f, 74f, 76f, 79f, 81f)

object SoundCatalog {
    val all: List<Sound> = listOf(
        // Rain & water
        Sound("rain_light", R.string.s_rain_light, Category.RAIN, 1.25f) {
            Rain(
                it,
                RainSpec(
                    bed = 0.45f, bedHp = 600f, bedLp = 9000f,
                    layers = listOf(
                        DropLayer(70f, 0.35f, 1500f, 6000f, 0.003f, 0.012f, noise = 0.6f, noiseDecay = 0.0015f, bright = 0.8f),
                        DropLayer(1.2f, 0.25f, 700f, 1800f, 0.04f, 0.09f, noise = 0.1f, glide = 1.4f),
                    ),
                    intensityMin = 0.8f,
                ),
            )
        },
        Sound("rain_medium", R.string.s_rain_medium, Category.RAIN, 0.64f) {
            Rain(
                it,
                RainSpec(
                    bed = 0.8f, bedHp = 350f, bedLp = 7500f, rumble = 0.25f,
                    layers = listOf(
                        DropLayer(260f, 0.4f, 1200f, 6000f, 0.003f, 0.01f, noise = 0.6f, noiseDecay = 0.0015f, bright = 0.75f),
                        DropLayer(40f, 0.35f, 500f, 1500f, 0.008f, 0.02f, noise = 0.45f, bright = 0.45f),
                        DropLayer(2.5f, 0.22f, 700f, 1800f, 0.04f, 0.09f, noise = 0.1f, glide = 1.4f),
                    ),
                ),
            )
        },
        Sound("rain_heavy", R.string.s_rain_heavy, Category.RAIN, 0.41f) { Rain(it, RAIN_HEAVY) },
        Sound("rain_thunder", R.string.s_rain_thunder, Category.RAIN, 0.48f) {
            Layered(it, listOf(Rain(it, RAIN_HEAVY) to 0.8f, Thunder(it) to 1.3f))
        },
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
        Sound("rain_roof", R.string.s_rain_roof, Category.RAIN, 0.84f) {
            Rain(
                it,
                RainSpec(
                    bed = 0.6f, bedHp = 200f, bedLp = 2600f, rumble = 0.35f,
                    layers = listOf(
                        DropLayer(200f, 0.45f, 1200f, 3800f, 0.004f, 0.012f, noise = 0.5f, bright = 0.55f),
                        DropLayer(1.2f, 0.35f, 500f, 1200f, 0.06f, 0.15f, noise = 0.05f, glide = 1.35f, width = 0.6f),
                    ),
                    reverb = 0.12f,
                ),
            )
        },
        Sound("rain_window", R.string.s_rain_window, Category.RAIN, 1.09f) {
            Rain(
                it,
                RainSpec(
                    bed = 0.55f, bedHp = 150f, bedLp = 1700f, rumble = 0.2f,
                    layers = listOf(
                        DropLayer(28f, 0.35f, 2500f, 5200f, 0.006f, 0.02f, noise = 0.3f, noiseDecay = 0.0015f, bright = 0.5f, width = 0.9f),
                        DropLayer(6f, 0.25f, 900f, 2000f, 0.01f, 0.03f, noise = 0.4f, bright = 0.3f),
                    ),
                    reverb = 0.1f,
                ),
            )
        },
        Sound("rain_leaves", R.string.s_rain_leaves, Category.RAIN, 1.04f) {
            Rain(
                it,
                RainSpec(
                    bed = 0.55f, bedHp = 700f, bedLp = 10000f,
                    layers = listOf(
                        DropLayer(240f, 0.4f, 600f, 2600f, 0.006f, 0.018f, noise = 0.55f, bright = 0.7f),
                        DropLayer(7f, 0.5f, 300f, 800f, 0.02f, 0.05f, noise = 0.4f, noiseDecay = 0.003f, bright = 0.35f),
                    ),
                ),
            )
        },
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
        Sound("water_drops", R.string.s_water_drops, Category.RAIN, 1.47f) {
            Rain(
                it,
                RainSpec(
                    bed = 0.06f, bedHp = 800f, bedLp = 3000f,
                    layers = listOf(
                        DropLayer(2.6f, 0.7f, 650f, 2200f, 0.05f, 0.15f, noise = 0.04f, noiseDecay = 0.001f, bright = 0.9f, glide = 1.9f, width = 0.8f, skew = 1.2f),
                        DropLayer(0.8f, 0.4f, 1500f, 3200f, 0.03f, 0.08f, noise = 0.05f, glide = 1.5f),
                    ),
                    reverb = 0.38f,
                    intensityMin = 0.7f, intensityMax = 1.2f,
                ),
            )
        },
        Sound("ocean", R.string.s_ocean, Category.RAIN, 0.43f) {
            Ocean(it, OceanSpec(6f, 11f, 1.8f, 3.5f, 2f, 4f, body = 1f, fizz = 0.25f, low = 0.5f, floor = 0.18f))
        },
        Sound("shore", R.string.s_shore, Category.RAIN, 0.5f) {
            Ocean(it, OceanSpec(3f, 6f, 0.7f, 1.4f, 1f, 2.2f, body = 0.55f, fizz = 0.35f, low = 0.15f, floor = 0.12f, brightness = 3500f))
        },
        Sound("stream", R.string.s_stream, Category.RAIN, 1.43f) {
            FlowingWater(it, FlowSpec(0.18f, 700f, 1900f, 0.2f, 3500f, 0.05f, 90f, 0.3f, 280f, 1500f, 0.008f, 0.04f, 1.6f, 2.6f, reverb = 0.08f))
        },
        Sound("river", R.string.s_river, Category.RAIN, 0.59f) {
            FlowingWater(it, FlowSpec(0.2f, 400f, 1200f, 0.55f, 2200f, 0.3f, 45f, 0.22f, 150f, 800f, 0.015f, 0.05f, 1.4f, 2f))
        },
        Sound("waterfall", R.string.s_waterfall, Category.RAIN, 0.33f) {
            FlowingWater(it, FlowSpec(0.3f, 1500f, 4000f, 0.9f, 5500f, 0.6f, 220f, 0.08f, 300f, 2200f, 0.006f, 0.02f, 1.4f, 2.2f))
        },
        Sound("underwater", R.string.s_underwater, Category.RAIN, 0.67f) {
            FlowingWater(
                it,
                FlowSpec(0f, 200f, 400f, 0.25f, 500f, 0.9f, 0.25f, 0.5f, 150f, 600f, 0.03f, 0.09f, 1.4f, 1.9f, bursts = true, muffle = 1000f, reverb = 0.2f),
            )
        },

        // Nature & forest
        Sound("wind", R.string.s_wind, Category.NATURE, 1.07f) {
            Wind(it, WindSpec(0.25f, 1f, 3f, 8f, 300f, 800f, q = 0.9f, tone = 1f, body = 0.45f))
        },
        Sound("wind_trees", R.string.s_wind_trees, Category.NATURE, 0.8f) {
            Wind(it, WindSpec(0.3f, 1f, 2f, 6f, 500f, 1200f, q = 0.8f, tone = 0.6f, body = 0.25f, rustle = 0.9f))
        },
        Sound("howling_wind", R.string.s_howling_wind, Category.NATURE, 1.02f) {
            Wind(it, WindSpec(0.35f, 1f, 2.5f, 7f, 300f, 700f, q = 1f, tone = 0.9f, body = 0.5f, whistle = 0.5f))
        },
        Sound("blizzard", R.string.s_blizzard, Category.NATURE, 0.57f) {
            Wind(it, WindSpec(0.5f, 1f, 1.5f, 5f, 400f, 1500f, q = 0.8f, tone = 0.9f, body = 0.6f, whistle = 0.25f, hiss = 0.35f))
        },
        Sound("thunder", R.string.s_thunder, Category.NATURE, 3.4f) { Thunder(it, 10f, 30f, 0.08f) },
        Sound("forest_birds", R.string.s_forest_birds, Category.NATURE, 0.6f) {
            Birds(it, listOf(BirdKind.CHIRPS, BirdKind.WHISTLE, BirdKind.TRILL, BirdKind.WARBLE, BirdKind.CHIRPS), 3f, 9f, 0.05f, 0.25f)
        },
        Sound("morning_birds", R.string.s_morning_birds, Category.NATURE, 0.52f) {
            Birds(
                it,
                listOf(BirdKind.WARBLE, BirdKind.TRILL, BirdKind.CHIRPS, BirdKind.WARBLE, BirdKind.WHISTLE, BirdKind.CHIRPS, BirdKind.TRILL),
                1.5f, 6f, 0.04f, 0.2f,
            )
        },
        Sound("doves", R.string.s_doves, Category.NATURE, 0.87f) {
            Birds(it, listOf(BirdKind.DOVE, BirdKind.DOVE, BirdKind.CHIRPS), 5f, 12f, 0.05f, 0.25f)
        },
        Sound("owl", R.string.s_owl, Category.NATURE, 0.97f) { Owl(it) },
        Sound("crickets", R.string.s_crickets, Category.NATURE, 2.24f) { Crickets(it) },
        Sound("cicadas", R.string.s_cicadas, Category.NATURE, 2.74f) { Cicadas(it) },
        Sound("frogs", R.string.s_frogs, Category.NATURE, 1.4f) { Frogs(it) },
        Sound("campfire", R.string.s_campfire, Category.NATURE, 1.21f) { Fire(it, 0.55f, 0.08f, 6f, 0.35f) },
        Sound("night_forest", R.string.s_night_forest, Category.NATURE, 0.94f) {
            Layered(it, listOf(Crickets(it, 5) to 0.7f, Owl(it) to 0.6f, Wind(it, GENTLE_TREES) to 0.35f, Frogs(it, 3) to 0.3f))
        },

        // City & home
        Sound("city", R.string.s_city, Category.CITY, 0.77f) { Traffic(it) },
        Sound("train", R.string.s_train, Category.CITY, 0.81f) { Train(it) },
        Sound("airplane", R.string.s_airplane, Category.CITY, 0.5f) {
            Machine(it, MachineSpec(pink = 0.9f, brown = 0.5f, hp = 30f, lp = 600f, humFreq = 96f, hum = 0.025f, wanderMin = 0.96f, wanderMax = 1.04f))
        },
        Sound("car", R.string.s_car, Category.CITY, 0.58f) {
            Machine(it, MachineSpec(pink = 0.5f, brown = 0.9f, hp = 25f, lp = 420f, wanderMin = 0.85f, wanderMax = 1.1f, bumpRate = 0.25f))
        },
        Sound("cafe", R.string.s_cafe, Category.CITY, 2.71f) { Cafe(it) },
        Sound("keyboard", R.string.s_keyboard, Category.CITY, 2.83f) { Keyboard(it) },
        Sound("clock", R.string.s_clock, Category.CITY, 1.67f) { Clock(it) },
        Sound("fan", R.string.s_fan, Category.CITY, 0.62f) {
            Machine(it, MachineSpec(pink = 0.8f, brown = 0.25f, hp = 60f, lp = 1100f, humFreq = 118f, hum = 0.015f, amRate = 13f, amDepth = 0.06f))
        },
        Sound("air_conditioner", R.string.s_air_conditioner, Category.CITY, 0.7f) {
            Machine(it, MachineSpec(white = 0.05f, pink = 0.7f, brown = 0.2f, hp = 120f, lp = 2400f, humFreq = 60f, hum = 0.03f, humHarmonics = 4))
        },
        Sound("washing_machine", R.string.s_washing_machine, Category.CITY, 1.09f) { WashingMachine(it) },
        Sound("hair_dryer", R.string.s_hair_dryer, Category.CITY, 0.8f) {
            Machine(it, MachineSpec(white = 0.35f, pink = 0.45f, hp = 900f, lp = 6500f, humFreq = 290f, hum = 0.03f, humHarmonics = 5, wanderMin = 0.98f, wanderMax = 1.02f))
        },
        Sound("fireplace", R.string.s_fireplace, Category.CITY, 1.3f) { Fire(it, 0.35f, 0.05f, 9f, 0.8f, 0.12f) },

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
