package com.redelar.bluestempo.audio

import com.redelar.bluestempo.data.AppJson
import com.redelar.bluestempo.model.AppSettings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Gera a referência de eventos (cada batida, clique e nota de baixo, com o quadro exato) do
 * motor Kotlin para os cenários de metronomo-web/test/parity-scenarios.json. O teste da versão
 * web compara o motor JavaScript com esta referência. Para atualizar:
 * BLUESTEMPO_UPDATE_WEB=1 ./gradlew testDebugUnitTest
 */
class ParityReferenceTest {

    private val scenariosFile = File("../../metronomo-web/test/parity-scenarios.json")
    private val referenceFile = File("../../metronomo-web/test/parity-reference.json")

    @Test
    fun `referencia de paridade da versao web esta atualizada`() {
        val rate = 48000
        val bank = SoundBank(rate, SampleLibrary.load(RealInstrumentsTest.source, rate))
        val scenarios = Json.parseToJsonElement(scenariosFile.readText()).jsonArray
        val result = JsonObject(scenarios.associate { element ->
            val scenario = element.jsonObject
            val name = scenario["name"]!!.jsonPrimitive.content
            val seconds = scenario["seconds"]!!.jsonPrimitive.double
            val settings = AppJson.decodeFromString(AppSettings.serializer(), scenario["settings"].toString()).normalized()
            val events = mutableListOf<JsonElement>()
            val seq = Sequencer(rate, bank)
            seq.listener = { e ->
                val (kind, id) = when (e) {
                    is FiredEvent.Drum -> "drum" to JsonPrimitive(e.instrument.name)
                    is FiredEvent.Click -> "click" to JsonPrimitive(e.sound.name + e.level)
                    is FiredEvent.Bass -> "bass" to JsonPrimitive(e.midi)
                }
                if (e.frame < seconds * rate) events.add(JsonArray(listOf(JsonPrimitive(e.frame), JsonPrimitive(kind), id)))
            }
            seq.reset(settings)
            val l = FloatArray(512)
            val r = FloatArray(512)
            while (seq.framesRendered < seconds * rate + rate) seq.render(l, r, 512)
            name to buildJsonObject {
                put("rate", rate)
                put("events", JsonArray(events))
            }
        })
        val text = Json.encodeToString(JsonElement.serializer(), result) + "\n"
        if (System.getenv("BLUESTEMPO_UPDATE_WEB") == "1") referenceFile.writeText(text)
        assertEquals(
            "metronomo-web/test/parity-reference.json desatualizado: rode com BLUESTEMPO_UPDATE_WEB=1",
            text,
            referenceFile.takeIf { it.exists() }?.readText(),
        )
    }
}
