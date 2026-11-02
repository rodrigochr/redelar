package com.redelar.bluestempo.audio

import com.redelar.bluestempo.model.BassTone
import com.redelar.bluestempo.model.Instrument
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.DataInputStream
import java.io.InputStream
import kotlin.math.abs

/** Abre arquivos da pasta de samples (assets no Android, sistema de arquivos nos testes). */
fun interface SampleSource {
    fun open(name: String): InputStream?
}

@Serializable
internal data class SampleManifest(
    val drums: Map<String, List<DrumLayerSpec>> = emptyMap(),
    val bass: Map<String, List<BassRegionSpec>> = emptyMap(),
    val credits: List<String> = emptyList(),
)

@Serializable
internal data class DrumLayerSpec(val level: Float, val files: List<String>)

@Serializable
internal data class BassRegionSpec(val key: Int, val files: List<String>, val levels: List<Float>, val cents: Int = 0)

/** Uma camada de dinâmica de uma peça: [level] = volume do ataque relativo à camada mais forte. */
class DrumLayer(val level: Float, val hits: Array<FloatArray>)

/** Peça gravada com várias camadas de dinâmica e variações (round robin). */
class SampledDrum(val layers: List<DrumLayer>) {
    private var roundRobin = 0

    /**
     * Escolhe a camada cujo volume natural mais se aproxima (por cima) do volume desejado
     * [target] (0..1) e devolve o sample; o ganho de ajuste fino vai em [out].
     */
    fun pick(target: Float, out: FloatArray): FloatArray {
        var layer = layers.last()
        for (l in layers) {
            if (l.level >= target) {
                layer = l
                break
            }
        }
        out[0] = (target / layer.level).coerceIn(0.05f, 1.25f)
        roundRobin++
        return layer.hits[roundRobin % layer.hits.size]
    }
}

/**
 * Nota gravada do baixo: [key] = nota MIDI, [cents] = desvio de afinação medido na gravação
 * (corrigido na reprodução), camadas de dinâmica em ordem crescente.
 */
class BassRegion(val key: Int, val layers: Array<FloatArray>, val levels: FloatArray, val cents: Int = 0)

class SampledBassInstrument(val dataRate: Int, val regions: List<BassRegion>) {
    /** Região mais próxima da nota (transposição máxima de 1,5 semitom com amostragem a cada 3). */
    fun regionFor(midi: Int): BassRegion = regions.minBy { abs(it.key - midi) }
}

/** Instrumentos reais carregados dos arquivos gerados por tools/build_samples.py. */
class SampleLibrary(
    val drums: Map<Instrument, SampledDrum>,
    val basses: Map<BassTone, SampledBassInstrument>,
    val credits: List<String>,
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** Carrega a biblioteca convertendo a bateria para [engineRate]; null se não houver samples. */
        fun load(source: SampleSource, engineRate: Int): SampleLibrary? {
            val manifestText = source.open("manifest.json")?.use { it.readBytes().decodeToString() } ?: return null
            val manifest = json.decodeFromString(SampleManifest.serializer(), manifestText)

            val drums = mutableMapOf<Instrument, SampledDrum>()
            for ((name, layers) in manifest.drums) {
                val instrument = Instrument.entries.firstOrNull { it.name == name } ?: continue
                drums[instrument] = SampledDrum(
                    layers.map { spec ->
                        DrumLayer(
                            level = spec.level.coerceAtLeast(0.01f),
                            hits = spec.files.map { readSnd(source, it).resampled(engineRate) }.toTypedArray(),
                        )
                    },
                )
            }

            val basses = mutableMapOf<BassTone, SampledBassInstrument>()
            for ((name, regions) in manifest.bass) {
                val tone = BassTone.entries.firstOrNull { it.name == name } ?: continue
                var rate = 0
                val loaded = regions.map { spec ->
                    val sounds = spec.files.map { readSnd(source, it) }
                    rate = sounds.first().rate
                    BassRegion(spec.key, sounds.map { it.data }.toTypedArray(), spec.levels.toFloatArray(), spec.cents)
                }
                if (loaded.isNotEmpty()) basses[tone] = SampledBassInstrument(rate, loaded)
            }
            return SampleLibrary(drums, basses, manifest.credits)
        }

        internal class Snd(val rate: Int, val data: FloatArray) {
            fun resampled(target: Int): FloatArray = if (rate == target) data else resample(data, rate, target)
        }

        /** Lê o formato "BTS1": magia, taxa (int32 LE), quadros (int32 LE) e PCM 16 bits mono. */
        internal fun readSnd(source: SampleSource, name: String): Snd {
            val stream = source.open(name) ?: error("Sample ausente: $name")
            DataInputStream(stream.buffered()).use { input ->
                val magic = ByteArray(4)
                input.readFully(magic)
                require(magic.decodeToString() == "BTS1") { "Formato inválido: $name" }
                val rate = Integer.reverseBytes(input.readInt())
                val frames = Integer.reverseBytes(input.readInt())
                val bytes = ByteArray(frames * 2)
                input.readFully(bytes)
                val data = FloatArray(frames)
                for (i in 0 until frames) {
                    val lo = bytes[2 * i].toInt() and 0xFF
                    val hi = bytes[2 * i + 1].toInt()
                    data[i] = ((hi shl 8) or lo).toShort() / 32768f
                }
                return Snd(rate, data)
            }
        }

        /** Reamostragem por interpolação cúbica (Catmull-Rom). */
        internal fun resample(data: FloatArray, from: Int, to: Int): FloatArray {
            val ratio = from.toDouble() / to
            val out = FloatArray(((data.size - 1) / ratio).toInt().coerceAtLeast(1))
            for (i in out.indices) out[i] = cubicAt(data, i * ratio)
            return out
        }
    }
}

/** Interpolação Catmull-Rom em [data] na posição fracionária [pos]. */
internal fun cubicAt(data: FloatArray, pos: Double): Float {
    val i = pos.toInt()
    val t = (pos - i).toFloat()
    val last = data.size - 1
    val y0 = data[(i - 1).coerceIn(0, last)]
    val y1 = data[i.coerceIn(0, last)]
    val y2 = data[(i + 1).coerceIn(0, last)]
    val y3 = data[(i + 2).coerceIn(0, last)]
    val a = -0.5f * y0 + 1.5f * y1 - 1.5f * y2 + 0.5f * y3
    val b = y0 - 2.5f * y1 + 2f * y2 - 0.5f * y3
    val c = -0.5f * y0 + 0.5f * y2
    return ((a * t + b) * t + c) * t + y1
}
