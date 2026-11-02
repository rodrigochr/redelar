package com.redelar.bluestempo.model

/** Qualidade do acorde; define os intervalos usados pelas linhas de baixo. */
enum class Quality(
    val suffix: String,
    val third: Int,
    val fifth: Int,
    val sixth: Int,
    val seventh: Int,
) {
    DOM7("7", third = 4, fifth = 7, sixth = 9, seventh = 10),
    MIN7("m7", third = 3, fifth = 7, sixth = 9, seventh = 10),
    MAJ7("7M", third = 4, fifth = 7, sixth = 9, seventh = 11),
    MAJ("", third = 4, fifth = 7, sixth = 9, seventh = 10),
    MIN("m", third = 3, fifth = 7, sixth = 8, seventh = 10),
    DIM7("°", third = 3, fifth = 6, sixth = 9, seventh = 9),
}

/** Acorde relativo ao tom: [degree] = semitons acima da tônica. */
data class Chord(val degree: Int, val quality: Quality) {
    fun rootPitchClass(key: Int): Int = Math.floorMod(key + degree, 12)
    fun name(key: Int): String = NoteNames.name(rootPitchClass(key), NoteNames.prefersFlats(key)) + quality.suffix
}

object NoteNames {
    private val SHARPS = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
    private val FLATS = listOf("C", "Db", "D", "Eb", "E", "F", "Gb", "G", "Ab", "A", "Bb", "B")
    private val SOLFEGE = listOf("Dó", "Dó#", "Ré", "Mib", "Mi", "Fá", "Fá#", "Sol", "Láb", "Lá", "Sib", "Si")

    /** Nomes dos 12 tons como aparecem no seletor. */
    val KEYS: List<String> = listOf("C", "Db", "D", "Eb", "E", "F", "F#", "G", "Ab", "A", "Bb", "B")

    fun prefersFlats(key: Int): Boolean = Math.floorMod(key, 12) in setOf(0, 1, 3, 5, 8, 10)

    fun name(pitchClass: Int, flats: Boolean): String =
        (if (flats) FLATS else SHARPS)[Math.floorMod(pitchClass, 12)]

    fun keyLabel(key: Int): String = KEYS[Math.floorMod(key, 12)]

    fun solfege(key: Int): String = SOLFEGE[Math.floorMod(key, 12)]
}

private val I7 = Chord(0, Quality.DOM7)
private val IV7 = Chord(5, Quality.DOM7)
private val V7 = Chord(7, Quality.DOM7)
private val Im7 = Chord(0, Quality.MIN7)
private val IVm7 = Chord(5, Quality.MIN7)
private val bVI7 = Chord(8, Quality.DOM7)
private val Vm7 = Chord(7, Quality.MIN7)
private val sharpIVdim = Chord(6, Quality.DIM7)
private val VI7 = Chord(9, Quality.DOM7)
private val IIm7 = Chord(2, Quality.MIN7)
private val Imaj7 = Chord(0, Quality.MAJ7)
private val I = Chord(0, Quality.MAJ)
private val IV = Chord(5, Quality.MAJ)
private val V = Chord(7, Quality.MAJ)
private val VIm = Chord(9, Quality.MIN)

private fun bar(vararg chords: Chord): List<Chord> = chords.toList()

/**
 * Progressões harmônicas. Cada compasso tem 1 ou 2 acordes; com 2 acordes, o primeiro
 * ocupa a primeira metade do compasso (arredondada para cima).
 */
enum class ProgressionType(val label: String, val description: String, val bars: List<List<Chord>>) {
    BLUES_12(
        "Blues 12 compassos",
        "I7 nos 4 primeiros, IV7 no 5º e 6º, V7–IV7 no 9º e 10º e turnaround no 12º.",
        listOf(
            bar(I7), bar(I7), bar(I7), bar(I7),
            bar(IV7), bar(IV7), bar(I7), bar(I7),
            bar(V7), bar(IV7), bar(I7), bar(V7),
        ),
    ),
    BLUES_12_QUICK(
        "Blues 12 – quick change",
        "Igual ao tradicional, mas vai para o IV7 já no 2º compasso.",
        listOf(
            bar(I7), bar(IV7), bar(I7), bar(I7),
            bar(IV7), bar(IV7), bar(I7), bar(I7),
            bar(V7), bar(IV7), bar(I7), bar(V7),
        ),
    ),
    BLUES_8(
        "Blues 8 compassos",
        "Forma curta de 8 compassos (estilo \"Key to the Highway\").",
        listOf(
            bar(I7), bar(V7), bar(IV7), bar(IV7),
            bar(I7), bar(V7), bar(I7), bar(V7),
        ),
    ),
    BLUES_MINOR(
        "Blues menor 12",
        "Blues em tom menor: im7, ivm7 e bVI7–V7 no final.",
        listOf(
            bar(Im7), bar(Im7), bar(Im7), bar(Im7),
            bar(IVm7), bar(IVm7), bar(Im7), bar(Im7),
            bar(bVI7), bar(V7), bar(Im7), bar(V7),
        ),
    ),
    JAZZ_BLUES(
        "Jazz blues",
        "Blues com ii–V, VI7 e diminuto de passagem, como nas jams de jazz.",
        listOf(
            bar(I7), bar(IV7), bar(I7), bar(Vm7, I7),
            bar(IV7), bar(sharpIVdim), bar(I7), bar(VI7),
            bar(IIm7), bar(V7), bar(I7, VI7), bar(IIm7, V7),
        ),
    ),
    II_V_I(
        "ii–V–I maior",
        "A cadência mais comum do jazz.",
        listOf(bar(IIm7), bar(V7), bar(Imaj7), bar(Imaj7)),
    ),
    ROCK_I_IV_V(
        "Rock I–IV–V–IV",
        "Sequência clássica de rock com acordes maiores.",
        listOf(bar(I), bar(IV), bar(V), bar(IV)),
    ),
    POP_I_V_VI_IV(
        "I–V–vi–IV",
        "A progressão de baladas pop/rock.",
        listOf(bar(I), bar(V), bar(VIm), bar(IV)),
    ),
    VAMP_DOM(
        "Vamp em I7 (um acorde)",
        "Um acorde só, estilo boogie de John Lee Hooker.",
        List(8) { bar(I7) },
    ),
    VAMP_MINOR(
        "Vamp menor (im7)",
        "Um acorde menor, ideal para Afro 6/8 e grooves modais.",
        List(8) { bar(Im7) },
    );

    val length: Int get() = bars.size

    /** Acorde que soa no tempo [beat] do compasso [barIndex] (com [beatsPerBar] tempos). */
    fun chordAt(barIndex: Int, beat: Int, beatsPerBar: Int): Chord {
        val chords = bars[Math.floorMod(barIndex, bars.size)]
        if (chords.size == 1) return chords[0]
        val firstHalf = (beatsPerBar + 1) / 2
        return if (beat < firstHalf) chords[0] else chords[1]
    }

    /**
     * Para cada compasso, quantos compassos seguidos o mesmo acorde já vinha soando
     * (0 = o acorde acabou de mudar). Usado para alternar padrões de baixo de 2 compassos.
     */
    val barsSinceChange: IntArray by lazy {
        IntArray(bars.size) { index ->
            var count = 0
            var previous = index - 1
            val current = bars[index].first()
            while (previous >= 0) {
                val prevBar = bars[previous]
                if (prevBar.size == 1 && prevBar[0] == current) {
                    count++
                    previous--
                } else {
                    break
                }
            }
            count
        }
    }
}
