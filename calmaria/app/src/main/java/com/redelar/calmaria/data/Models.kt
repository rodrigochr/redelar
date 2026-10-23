package com.redelar.calmaria.data

import org.json.JSONArray
import org.json.JSONObject

/** One sound in a mix with its slider position (0..1). */
data class MixItem(val soundId: String, val volume: Float)

data class Favorite(val id: String, val name: String, val items: List<MixItem>)

data class Settings(
    /** Never request audio focus, so other apps can play at the same time. */
    val mixWithOthers: Boolean = true,
    val pauseOnCalls: Boolean = true,
    val pauseOnNoisy: Boolean = true,
    val fadeOutTimer: Boolean = true,
)

data class AlarmConfig(
    val enabled: Boolean = false,
    val hour: Int = 7,
    val minute: Int = 0,
    /** Bit 0 = Monday … bit 6 = Sunday. 0 means ring once. */
    val days: Int = 0,
    /** A wake scene id from [Presets.wakeScenes] or `fav:<favorite id>`. */
    val sound: String = Presets.wakeScenes.first().id,
    val gradual: Boolean = true,
)

fun List<MixItem>.toJson(): JSONArray = JSONArray().also { arr ->
    forEach { arr.put(JSONObject().put("id", it.soundId).put("v", it.volume.toDouble())) }
}

fun JSONArray.toMixItems(): List<MixItem> = (0 until length()).mapNotNull { i ->
    val o = optJSONObject(i) ?: return@mapNotNull null
    MixItem(o.optString("id"), o.optDouble("v", 0.7).toFloat())
}

/** Two mixes are "the same" for the favorite heart if they use the same sounds at similar volumes. */
fun sameMix(a: List<MixItem>, b: List<MixItem>): Boolean {
    if (a.size != b.size) return false
    val bv = b.associate { it.soundId to it.volume }
    return a.all { item -> bv[item.soundId]?.let { kotlin.math.abs(it - item.volume) < 0.02f } == true }
}
