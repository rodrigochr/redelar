package com.redelar.calmaria.audio

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream

/**
 * Writes 30 s of every synthesized sound (at its catalog gain) as WAV files in app/build/synth so their
 * loudness can be compared with the bundled recordings. Opt-in: -Pexport-synth
 */
class ExportSynthTest {
    @Test
    fun export() {
        assumeTrue(System.getProperty("calmaria.exportSynth") == "true")
        val sr = 48000
        val dir = File("build/synth").apply { mkdirs() }
        val buf = FloatArray(MAX_FRAMES * 2)
        for (sound in SoundCatalog.all.filter { it.asset == null }) {
            val gen = sound.create(sr)
            val frames = sr * 30
            val pcm = ShortArray(frames * 2)
            var done = 0
            while (done < frames) {
                val n = minOf(960, frames - done)
                gen.render(buf, n)
                for (i in 0 until n * 2) {
                    pcm[done * 2 + i] = (buf[i] * sound.gain * 32767f).coerceIn(-32768f, 32767f).toInt().toShort()
                }
                done += n
            }
            DataOutputStream(FileOutputStream(File(dir, "${sound.id}.wav"))).use { out ->
                fun le32(v: Int) = out.writeInt(Integer.reverseBytes(v))
                fun le16(v: Int) = out.writeShort(java.lang.Short.reverseBytes(v.toShort()).toInt())
                out.writeBytes("RIFF"); le32(36 + pcm.size * 2); out.writeBytes("WAVEfmt ")
                le32(16); le16(1); le16(2); le32(sr); le32(sr * 4); le16(4); le16(16)
                out.writeBytes("data"); le32(pcm.size * 2)
                val bytes = ByteArray(pcm.size * 2)
                for (i in pcm.indices) {
                    bytes[2 * i] = (pcm[i].toInt() and 0xff).toByte()
                    bytes[2 * i + 1] = (pcm[i].toInt() shr 8 and 0xff).toByte()
                }
                out.write(bytes)
            }
        }
    }
}
