package com.redelar.bluestempo.model

/**
 * Subdivisões do tempo no modo metrônomo, como frações (0..1) do tempo.
 * [swingPair] indica a unidade em que o controle de swing atua (null = sem swing).
 */
enum class Subdivision(val label: String, val positions: DoubleArray, val swingPair: Double? = null) {
    NONE("Só o tempo", doubleArrayOf(0.0)),
    EIGHTHS("Colcheias", doubleArrayOf(0.0, 0.5), swingPair = 1.0),
    TRIPLETS("Tercinas", doubleArrayOf(0.0, 1.0 / 3, 2.0 / 3)),
    SIXTEENTHS("Semicolcheias", doubleArrayOf(0.0, 0.25, 0.5, 0.75), swingPair = 0.5),
    SHUFFLE("Shuffle", doubleArrayOf(0.0, 2.0 / 3)),
    DOTTED("Pontuada", doubleArrayOf(0.0, 0.75)),
    GALLOP("Galope", doubleArrayOf(0.0, 0.5, 0.75)),
    REVERSE_GALLOP("Galope invertido", doubleArrayOf(0.0, 0.25, 0.5)),
    QUINTUPLETS("Quintinas", DoubleArray(5) { it / 5.0 }),
    SEXTUPLETS("Sextinas", DoubleArray(6) { it / 6.0 }),
}

/** Fórmulas de compasso oferecidas como atalho. */
data class Meter(val beats: Int, val unit: Int) {
    val label: String get() = "$beats/$unit"

    companion object {
        val COMMON = listOf(
            Meter(2, 4), Meter(3, 4), Meter(4, 4), Meter(5, 4), Meter(6, 4),
            Meter(5, 8), Meter(6, 8), Meter(7, 8), Meter(9, 8), Meter(12, 8),
        )
        val UNITS = listOf(2, 4, 8, 16)
        const val MIN_BEATS = 1
        const val MAX_BEATS = 16
    }
}

/** Acentuação padrão de cada fórmula (agrupa colcheias em 3 nos compassos compostos). */
fun defaultAccents(beats: Int, unit: Int): List<Accent> {
    val groups: List<Int> = when {
        unit == 8 && beats == 5 -> listOf(3, 2)
        unit == 8 && beats == 7 -> listOf(2, 2, 3)
        unit == 8 && beats % 3 == 0 && beats > 3 -> List(beats / 3) { 3 }
        else -> return List(beats) { if (it == 0) Accent.ACCENT else Accent.NORMAL }
    }
    val result = MutableList(beats) { Accent.SOFT }
    var index = 0
    groups.forEachIndexed { g, size ->
        result[index] = if (g == 0) Accent.ACCENT else Accent.NORMAL
        index += size
    }
    return result
}

/** Nome italiano do andamento, só como referência visual. */
fun tempoMarking(bpm: Int): String = when {
    bpm < 40 -> "Grave"
    bpm < 55 -> "Largo"
    bpm < 66 -> "Larghetto"
    bpm < 76 -> "Adagio"
    bpm < 92 -> "Andante"
    bpm < 108 -> "Moderato"
    bpm < 120 -> "Allegretto"
    bpm < 156 -> "Allegro"
    bpm < 176 -> "Vivace"
    bpm < 200 -> "Presto"
    else -> "Prestissimo"
}
