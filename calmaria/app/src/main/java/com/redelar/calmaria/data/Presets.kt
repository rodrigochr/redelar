package com.redelar.calmaria.data

import androidx.annotation.StringRes
import com.redelar.calmaria.R

/** One scene of a journey. [minutes] = 0 means it plays until stopped. */
class JourneyStage(val minutes: Int, val items: List<MixItem>)

class Journey(
    val id: String,
    @param:StringRes val title: Int,
    @param:StringRes val description: Int,
    /** Card gradient, top-left to bottom-right, as ARGB. */
    val colors: Pair<Long, Long>,
    val stages: List<JourneyStage>,
) {
    /** Minutes until the last stage is reached. */
    val leadMinutes: Int get() = stages.dropLast(1).sumOf { it.minutes }

    /** Every sound used anywhere in the journey, in order of first appearance. */
    val soundIds: List<String> get() = stages.flatMap { s -> s.items.map { it.soundId } }.distinct()
}

class WakeScene(val id: String, @param:StringRes val title: Int, val items: List<MixItem>)

private fun mix(vararg pairs: Pair<String, Float>) = pairs.map { MixItem(it.first, it.second) }

object Presets {
    val journeys: List<Journey> = listOf(
        Journey(
            "storm_cabin", R.string.j_storm_cabin, R.string.j_storm_cabin_desc, 0xFF3B2F6BL to 0xFFB0577AL,
            listOf(
                JourneyStage(10, mix("fireplace" to 0.7f, "rain_roof" to 0.5f)),
                JourneyStage(25, mix("fireplace" to 0.55f, "rain_roof" to 0.75f, "thunder" to 0.6f)),
                JourneyStage(0, mix("fireplace" to 0.3f, "rain_roof" to 0.5f, "rain_light" to 0.4f)),
            ),
        ),
        Journey(
            "forest_night", R.string.j_forest_night, R.string.j_forest_night_desc, 0xFF1F4D4AL to 0xFF2B2A6BL,
            listOf(
                JourneyStage(8, mix("forest_birds" to 0.6f, "wind_trees" to 0.4f, "stream" to 0.35f)),
                JourneyStage(20, mix("crickets" to 0.55f, "owl" to 0.45f, "stream" to 0.3f, "campfire" to 0.3f)),
                JourneyStage(0, mix("crickets" to 0.45f, "wind_trees" to 0.25f, "stream" to 0.25f)),
            ),
        ),
        Journey(
            "beach", R.string.j_beach, R.string.j_beach_desc, 0xFF1C4E80L to 0xFF5AA0C8L,
            listOf(
                JourneyStage(15, mix("ocean" to 0.75f, "wind" to 0.25f)),
                JourneyStage(0, mix("shore" to 0.7f, "crickets" to 0.2f)),
            ),
        ),
        Journey(
            "night_train", R.string.j_night_train, R.string.j_night_train_desc, 0xFF2D2F55L to 0xFF6A5ACDL,
            listOf(
                JourneyStage(20, mix("train" to 0.75f, "rain_window" to 0.5f)),
                JourneyStage(0, mix("train" to 0.6f, "rain_window" to 0.35f, "brown_noise" to 0.3f)),
            ),
        ),
        Journey(
            "rainy_cafe", R.string.j_rainy_cafe, R.string.j_rainy_cafe_desc, 0xFF5B3A29L to 0xFFC98B5CL,
            listOf(
                JourneyStage(20, mix("cafe" to 0.55f, "rain_window" to 0.6f, "piano" to 0.45f)),
                JourneyStage(0, mix("rain_window" to 0.55f, "piano" to 0.35f, "fireplace" to 0.3f)),
            ),
        ),
        Journey(
            "temple", R.string.j_temple, R.string.j_temple_desc, 0xFF6B4E9BL to 0xFFE8A87CL,
            listOf(
                JourneyStage(15, mix("singing_bowl" to 0.6f, "wind_chimes" to 0.4f, "stream" to 0.4f, "morning_birds" to 0.25f)),
                JourneyStage(0, mix("singing_bowl" to 0.45f, "om" to 0.35f, "stream" to 0.3f)),
            ),
        ),
        Journey(
            "camping", R.string.j_camping, R.string.j_camping_desc, 0xFF233D2BL to 0xFFB3622EL,
            listOf(
                JourneyStage(15, mix("campfire" to 0.6f, "frogs" to 0.4f, "crickets" to 0.4f)),
                JourneyStage(20, mix("rain_tent" to 0.6f, "crickets" to 0.3f, "thunder" to 0.3f)),
                JourneyStage(0, mix("rain_tent" to 0.45f, "crickets" to 0.2f)),
            ),
        ),
        Journey(
            "flight", R.string.j_flight, R.string.j_flight_desc, 0xFF0F2A4AL to 0xFF4A6FA5L,
            listOf(JourneyStage(0, mix("airplane" to 0.7f, "brown_noise" to 0.3f))),
        ),
        Journey(
            "deep_sleep", R.string.j_deep_sleep, R.string.j_deep_sleep_desc, 0xFF14163AL to 0xFF46378CL,
            listOf(
                JourneyStage(30, mix("rain_medium" to 0.6f, "brown_noise" to 0.45f, "binaural_delta" to 0.25f)),
                JourneyStage(0, mix("rain_light" to 0.45f, "deep_brown" to 0.5f, "binaural_delta" to 0.2f)),
            ),
        ),
        Journey(
            "baby", R.string.j_baby, R.string.j_baby_desc, 0xFFB06C9EL to 0xFFF2B5A0L,
            listOf(
                JourneyStage(15, mix("womb" to 0.6f, "shush" to 0.4f, "music_box" to 0.3f)),
                JourneyStage(0, mix("womb" to 0.55f, "white_noise" to 0.3f)),
            ),
        ),
    )

    val wakeScenes: List<WakeScene> = listOf(
        WakeScene("wake_birds", R.string.wake_birds, mix("morning_birds" to 0.75f, "stream" to 0.35f)),
        WakeScene("wake_chimes", R.string.wake_chimes, mix("wind_chimes" to 0.75f, "forest_birds" to 0.35f)),
        WakeScene("wake_waves", R.string.wake_waves, mix("shore" to 0.65f, "morning_birds" to 0.4f)),
        WakeScene("wake_bowl", R.string.wake_bowl, mix("singing_bowl" to 0.8f, "wind_chimes" to 0.25f)),
        WakeScene("wake_piano", R.string.wake_piano, mix("piano" to 0.75f, "morning_birds" to 0.3f)),
    )

    fun journey(id: String?): Journey? = journeys.firstOrNull { it.id == id }
}
