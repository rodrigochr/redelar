package com.redelar.calmaria.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Every bundled recording must exist, be 48 kHz Ogg Vorbis, and be properly credited. */
class RecordingAssetsTest {
    private val assets = File("src/main/assets")

    private fun field(obj: String, name: String) =
        Regex("\"$name\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(obj)?.groupValues?.get(1).orEmpty()

    @Test
    fun recordingsAreOggVorbisAt48k() {
        val recordings = SoundCatalog.all.filter { it.asset != null }
        assertTrue("expected bundled recordings", recordings.size >= 30)
        for (sound in recordings) {
            val file = File(assets, sound.asset!!)
            assertTrue("missing ${file.path}", file.isFile)
            val head = file.readBytes().copyOf(256)
            assertEquals("${sound.id} is not Ogg", "OggS", String(head, 0, 4, Charsets.US_ASCII))
            val id = String(head, Charsets.ISO_8859_1).indexOf("\u0001vorbis")
            assertTrue("${sound.id} is not Vorbis", id > 0)
            val channels = head[id + 11].toInt()
            val rate = (head[id + 12].toInt() and 0xff) or ((head[id + 13].toInt() and 0xff) shl 8) or
                ((head[id + 14].toInt() and 0xff) shl 16) or ((head[id + 15].toInt() and 0xff) shl 24)
            assertEquals("${sound.id} sample rate", 48000, rate)
            assertTrue("${sound.id} channels", channels in 1..2)
        }
    }

    @Test
    fun everyRecordingIsCredited() {
        val json = File(assets, "sounds/credits.json").readText()
        val objects = Regex("\\{[^{}]*\\}").findAll(json).map { it.value }.toList()
        val byId = objects.associateBy { field(it, "id") }
        val recordings = SoundCatalog.all.filter { it.asset != null }.map { it.id }.toSet()
        assertEquals("credits must match the bundled recordings", recordings, byId.keys)
        for ((id, obj) in byId) {
            for (name in listOf("work", "author", "license", "license_url", "source", "source_url")) {
                assertTrue("$id has no $name", field(obj, name).isNotBlank())
            }
            assertTrue("$id has an unexpected license", field(obj, "license").matches(Regex("CC0|CC BY(-SA)? [34]\\.0|Public domain")))
        }
    }
}
