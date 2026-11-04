package com.redelar.calmaria.data

import android.content.Context
import org.json.JSONArray

/** Attribution for one bundled recording (required by CC BY / CC BY-SA, courteous for CC0). */
data class Credit(
    val soundId: String,
    val work: String,
    val author: String,
    val license: String,
    val licenseUrl: String,
    val source: String,
    val sourceUrl: String,
    val changes: String,
)

object Credits {
    const val ASSET = "sounds/credits.json"

    fun load(context: Context): List<Credit> = runCatching {
        val arr = JSONArray(context.assets.open(ASSET).bufferedReader().use { it.readText() })
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Credit(
                soundId = o.getString("id"),
                work = o.optString("work"),
                author = o.optString("author"),
                license = o.optString("license"),
                licenseUrl = o.optString("license_url"),
                source = o.optString("source"),
                sourceUrl = o.optString("source_url"),
                changes = o.optString("changes"),
            )
        }
    }.getOrDefault(emptyList())
}
