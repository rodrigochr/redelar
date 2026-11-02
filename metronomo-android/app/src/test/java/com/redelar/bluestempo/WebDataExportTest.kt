package com.redelar.bluestempo

import com.redelar.bluestempo.audio.Sequencer
import com.redelar.bluestempo.audio.SoundBank
import com.redelar.bluestempo.model.Accent
import com.redelar.bluestempo.model.BassStyle
import com.redelar.bluestempo.model.BassTone
import com.redelar.bluestempo.model.Channel
import com.redelar.bluestempo.model.ClickSound
import com.redelar.bluestempo.model.Degree
import com.redelar.bluestempo.model.Genre
import com.redelar.bluestempo.model.GrooveLibrary
import com.redelar.bluestempo.model.Instrument
import com.redelar.bluestempo.model.Meter
import com.redelar.bluestempo.model.ProgressionType
import com.redelar.bluestempo.model.Quality
import com.redelar.bluestempo.model.Subdivision
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * A versão web (pasta metronomo-web) usa exatamente as mesmas levadas, progressões, linhas de
 * baixo e constantes de mixagem do app Android. Este teste gera esses dados e falha se o arquivo
 * da versão web estiver desatualizado. Para atualizar: BLUESTEMPO_UPDATE_WEB=1 ./gradlew testDebugUnitTest
 */
class WebDataExportTest {

    private val target = File("../../metronomo-web/src/data/music.json")

    @Test
    fun `dados da versao web estao sincronizados`() {
        val json = Json { prettyPrint = true }.encodeToString(JsonElement.serializer(), export()) + "\n"
        if (System.getenv("BLUESTEMPO_UPDATE_WEB") == "1") {
            target.parentFile.mkdirs()
            target.writeText(json)
        }
        assertEquals(
            "metronomo-web/src/data/music.json desatualizado: rode com BLUESTEMPO_UPDATE_WEB=1",
            json,
            target.takeIf { it.exists() }?.readText(),
        )
    }

    private fun degree(d: Degree) = buildJsonObject {
        put("n", d.number)
        put("acc", d.accidental)
        put("oct", d.octave)
        put("chord", d.chordAware)
    }

    private fun floats(values: FloatArray): JsonArray =
        JsonArray(values.map { JsonPrimitive(Math.round(it * 100) / 100.0) })

    private fun export() = buildJsonObject {
        put("generatedFrom", "metronomo-android (WebDataExportTest)")
        put("constants", buildJsonObject {
            put("leadIn", Sequencer.LEAD_IN_SECONDS)
            put("grooveHeadroom", Sequencer.GROOVE_HEADROOM)
            put("clickHeadroom", Sequencer.CLICK_HEADROOM)
            put("sampledBassLevel", Sequencer.SAMPLED_BASS_LEVEL)
            put("limitKnee", Sequencer.LIMIT_KNEE)
            put("bassLowRoot", Sequencer.BASS_LOW_ROOT)
            put("minBassNote", Sequencer.MIN_BASS_NOTE)
            put("maxBassNote", Sequencer.MAX_BASS_NOTE)
        })
        put("channels", buildJsonArray {
            Channel.entries.forEach { c -> add(buildJsonObject { put("id", c.name); put("label", c.label) }) }
        })
        put("instruments", buildJsonObject {
            Instrument.entries.forEach { i ->
                put(i.name, buildJsonObject {
                    put("label", i.label)
                    put("channel", i.channel.name)
                    put("pan", i.pan)
                    put("choke", i.chokeGroup)
                    put("fill", i.replacedByFill)
                    put("trim", SoundBank.REAL_TRIM[i.ordinal])
                })
            }
        })
        put("qualities", buildJsonObject {
            Quality.entries.forEach { q ->
                put(q.name, buildJsonObject {
                    put("suffix", q.suffix); put("third", q.third); put("fifth", q.fifth)
                    put("sixth", q.sixth); put("seventh", q.seventh)
                })
            }
        })
        putJsonArray("progressions") {
            ProgressionType.entries.forEach { p ->
                add(buildJsonObject {
                    put("id", p.name)
                    put("label", p.label)
                    put("description", p.description)
                    put("bars", JsonArray(p.bars.map { bar ->
                        JsonArray(bar.map { c -> buildJsonObject { put("degree", c.degree); put("quality", c.quality.name) } })
                    }))
                    put("barsSinceChange", JsonArray(p.barsSinceChange.map { JsonPrimitive(it) }))
                })
            }
        }
        putJsonArray("bassStyles") {
            BassStyle.entries.forEach { b ->
                add(buildJsonObject {
                    put("id", b.name)
                    put("label", b.label)
                    put("algorithm", b.algorithm.name)
                    val p = b.pattern
                    if (p != null) {
                        put("stepsPerBeat", p.stepsPerBeat)
                        put("gate", p.gate)
                        put("variations", JsonArray(p.variations.map { v ->
                            buildJsonObject {
                                put("length", v.length)
                                put("notes", JsonArray((0 until v.length).map { step ->
                                    v.noteAt(step)?.let { n ->
                                        buildJsonObject { put("len", n.lengthSteps); put("degree", degree(n.degree)) }
                                    } ?: JsonNull
                                }))
                            }
                        }))
                    }
                })
            }
        }
        put("walkTemplates", JsonArray(Sequencer.WALK_TEMPLATES.map { t -> JsonArray(t.map { degree(it) }) }))
        putJsonArray("genres") { Genre.entries.forEach { g -> add(buildJsonObject { put("id", g.name); put("label", g.label) }) } }
        putJsonArray("bassTones") { BassTone.entries.forEach { t -> add(buildJsonObject { put("id", t.name); put("label", t.label) }) } }
        putJsonArray("clickSounds") { ClickSound.entries.forEach { c -> add(buildJsonObject { put("id", c.name); put("label", c.label) }) } }
        putJsonArray("accents") { Accent.entries.forEach { a -> add(buildJsonObject { put("id", a.name); put("label", a.label) }) } }
        putJsonArray("meters") { Meter.COMMON.forEach { m -> add(buildJsonObject { put("beats", m.beats); put("unit", m.unit) }) } }
        putJsonArray("subdivisions") {
            Subdivision.entries.forEach { s ->
                add(buildJsonObject {
                    put("id", s.name)
                    put("label", s.label)
                    put("positions", JsonArray(s.positions.map { JsonPrimitive(it) }))
                    put("swingPair", s.swingPair?.let { JsonPrimitive(it) } ?: JsonNull)
                })
            }
        }
        putJsonArray("grooves") {
            GrooveLibrary.all.forEach { g ->
                add(buildJsonObject {
                    put("id", g.id)
                    put("name", g.name)
                    put("genre", g.genre.name)
                    put("description", g.description)
                    put("meter", g.meter)
                    put("beats", g.beatsPerBar)
                    put("steps", g.stepsPerBeat)
                    put("bars", g.bars)
                    put("bpm", g.defaultBpm)
                    put("swingUnit", g.swingUnit?.let { JsonPrimitive(it.pairLength) } ?: JsonNull)
                    put("swing", g.defaultSwing)
                    put("bass", g.bassStyle.name)
                    put("tone", g.bassTone.name)
                    put("progression", g.progression.name)
                    put("fillKit", g.fillKit.name)
                    put("level", g.level)
                    put("mix", buildJsonObject {
                        put("kick", g.mix.kick); put("snare", g.mix.snare); put("cymbals", g.mix.cymbals)
                        put("percussion", g.mix.percussion); put("bass", g.mix.bass); put("guideClick", g.mix.guideClick)
                    })
                    put("beatNote", g.beatNote)
                    put("tracks", JsonArray(g.tracks.map { t ->
                        buildJsonObject { put("instrument", t.instrument.name); put("v", floats(t.velocities)) }
                    }))
                })
            }
        }
    }
}
