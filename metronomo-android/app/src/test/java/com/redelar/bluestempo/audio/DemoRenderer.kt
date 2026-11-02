package com.redelar.bluestempo.audio

import com.redelar.bluestempo.model.AppSettings
import com.redelar.bluestempo.model.GrooveLibrary
import com.redelar.bluestempo.model.PlayMode
import org.junit.Test
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream

/**
 * Gera arquivos WAV de demonstração de cada levada (para ouvir/analisar fora do celular).
 * Só roda quando a variável de ambiente BLUESTEMPO_DEMO_DIR aponta para uma pasta.
 */
class DemoRenderer {

    @Test
    fun renderDemos() {
        val dir = System.getenv("BLUESTEMPO_DEMO_DIR")?.takeIf { it.isNotBlank() }?.let(::File) ?: return
        dir.mkdirs()
        val sr = 44100
        val bank = SoundBank(sr)
        raw = System.getenv("BLUESTEMPO_DEMO_RAW") == "1"
        GrooveLibrary.all.forEach { g ->
            val s = AppSettings(
                mode = PlayMode.GROOVE, grooveId = g.id, bpm = g.defaultBpm, swing = g.defaultSwing,
                bassStyle = g.bassStyle, bassTone = g.bassTone, progression = g.progression, key = 9,
            )
            val bars = if (g.progression.length >= 12) 12 else 8
            writeWav(File(dir, "${g.id}.wav"), render(bank, s, bars * g.beatsPerBar * 60.0 / g.defaultBpm + 1.0), sr)
        }
        val samplesDir = File(dir, "samples").apply { mkdirs() }
        com.redelar.bluestempo.model.Instrument.entries.forEach { inst ->
            val mono = bank.drum(inst)
            writeWav(File(samplesDir, "${inst.name}.wav"), FloatArray(mono.size * 2) { mono[it / 2] }, sr)
        }
        com.redelar.bluestempo.model.ClickSound.entries.forEach { cs ->
            for (level in 0..2) {
                val mono = bank.click(cs, level)
                writeWav(File(samplesDir, "click_${cs.name}_$level.wav"), FloatArray(mono.size * 2) { mono[it / 2] }, sr)
            }
        }
        val drumsOff = com.redelar.bluestempo.model.MixerSettings(kick = 0f, snare = 0f, cymbals = 0f, percussion = 0f)
        com.redelar.bluestempo.model.BassTone.entries.forEach { tone ->
            val s = AppSettings(mode = PlayMode.GROOVE, grooveId = "boogie_woogie", bpm = 120, bassTone = tone,
                bassStyle = com.redelar.bluestempo.model.BassStyle.BOOGIE, mixer = drumsOff, key = 9)
            writeWav(File(dir, "bass_only_${tone.name}.wav"), render(bank, s, 8.0), sr)
        }
        val click = AppSettings(mode = PlayMode.CLICK, bpm = 100)
        writeWav(File(dir, "click.wav"), render(bank, click, 5.0), sr)
    }

    private var raw = false

    private fun render(bank: SoundBank, s: AppSettings, seconds: Double): FloatArray {
        val seq = Sequencer(bank.sampleRate, bank)
        seq.limiterEnabled = !raw
        seq.reset(s)
        val chunk = 512
        val frames = (seconds * bank.sampleRate).toInt() / chunk * chunk
        val out = FloatArray(frames * 2)
        val l = FloatArray(chunk)
        val r = FloatArray(chunk)
        var pos = 0
        while (pos < frames) {
            seq.render(l, r, chunk)
            for (i in 0 until chunk) {
                out[2 * (pos + i)] = l[i]
                out[2 * (pos + i) + 1] = r[i]
            }
            pos += chunk
        }
        return out
    }

    private fun writeWav(file: File, stereo: FloatArray, sr: Int) {
        DataOutputStream(FileOutputStream(file).buffered()).use { out ->
            fun le32(v: Int) = out.writeInt(Integer.reverseBytes(v))
            fun le16(v: Int) = out.writeShort(java.lang.Short.reverseBytes(v.toShort()).toInt())
            val dataBytes = stereo.size * 2
            out.writeBytes("RIFF"); le32(36 + dataBytes); out.writeBytes("WAVE")
            out.writeBytes("fmt "); le32(16); le16(1); le16(2); le32(sr); le32(sr * 4); le16(4); le16(16)
            out.writeBytes("data"); le32(dataBytes)
            for (x in stereo) le16((x.coerceIn(-1f, 1f) * 32767).toInt())
        }
    }
}
