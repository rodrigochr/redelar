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
    HALF_DIM("m7(b5)", third = 3, fifth = 6, sixth = 8, seventh = 10),
    SUS4("sus4", third = 5, fifth = 7, sixth = 9, seventh = 10),
    SUS2("sus2", third = 2, fifth = 7, sixth = 9, seventh = 10),
    AUG("aug", third = 4, fifth = 8, sixth = 9, seventh = 10),

    /** Sem acorde (N.C.): o baixo descansa. */
    NC("N.C.", third = 4, fifth = 7, sixth = 9, seventh = 10),
}

/**
 * Acorde relativo ao tom: [degree] = semitons acima da tônica. [suffix] é a cifra como
 * escrita (ex.: "m7(9)", com [quality] guardando só os intervalos que o baixo usa) e [bass]
 * é o baixo invertido (ex.: D/F#), também em semitons acima da tônica.
 */
data class Chord(
    val degree: Int,
    val quality: Quality,
    val suffix: String = quality.suffix,
    val bass: Int? = null,
    /** Trechos que modulam: grafar como se o tom fosse [spellShift] semitons acima (e menor, se [spellMinor]). */
    val spellShift: Int = 0,
    val spellMinor: Boolean? = null,
) {
    val isNoChord: Boolean get() = quality == Quality.NC

    fun rootPitchClass(key: Int): Int = Math.floorMod(key + degree, 12)

    /** Nota mais grave do acorde (o baixo invertido, se houver). */
    fun bassPitchClass(key: Int): Int = Math.floorMod(key + (bass ?: degree), 12)

    fun name(key: Int): String = NoteNames.name(rootPitchClass(key), NoteNames.prefersFlats(key)) + quality.suffix

    /** Nome com a grafia correta para o tom (ex.: F# em Ré maior, Bb em Fá maior). */
    fun spelled(key: Int, minor: Boolean): String {
        if (isNoChord) return suffix
        val local = key + spellShift
        val localMinor = spellMinor ?: minor
        val root = NoteNames.spell(rootPitchClass(key), local, localMinor) + suffix
        return if (bass == null) root else root + "/" + NoteNames.spell(Math.floorMod(key + bass, 12), local, localMinor)
    }
}

object NoteNames {
    private val SHARPS = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
    private val FLATS = listOf("C", "Db", "D", "Eb", "E", "F", "Gb", "G", "Ab", "A", "Bb", "B")
    private val SOLFEGE = listOf("Dó", "Dó#", "Ré", "Mib", "Mi", "Fá", "Fá#", "Sol", "Láb", "Lá", "Sib", "Si")
    private val MINOR_TONICS = listOf("C", "C#", "D", "Eb", "E", "F", "F#", "G", "G#", "A", "Bb", "B")
    private const val LETTERS = "CDEFGAB"
    private val NATURALS = intArrayOf(0, 2, 4, 5, 7, 9, 11)

    /** Letra (grau da escala) usada para cada distância em semitons da tônica: b2, b3, #4, b6, b7. */
    private val STEP_OF_DEGREE = intArrayOf(0, 1, 1, 2, 2, 3, 3, 4, 5, 5, 6, 6)
    private val FLAT_SIGNATURES = setOf(1, 3, 5, 8, 10)
    private val AWKWARD = setOf("E#", "B#", "Cb", "Fb")

    /** Nomes dos 12 tons como aparecem no seletor. */
    val KEYS: List<String> = listOf("C", "Db", "D", "Eb", "E", "F", "F#", "G", "Ab", "A", "Bb", "B")

    fun prefersFlats(key: Int): Boolean = Math.floorMod(key, 12) in setOf(0, 1, 3, 5, 8, 10)

    fun name(pitchClass: Int, flats: Boolean): String =
        (if (flats) FLATS else SHARPS)[Math.floorMod(pitchClass, 12)]

    fun keyLabel(key: Int): String = KEYS[Math.floorMod(key, 12)]

    /** Nome do tom de uma música (ex.: "Em", "F#", "Bbm"). */
    fun keyName(key: Int, minor: Boolean): String =
        if (minor) MINOR_TONICS[Math.floorMod(key, 12)] + "m" else KEYS[Math.floorMod(key, 12)]

    fun solfege(key: Int): String = SOLFEGE[Math.floorMod(key, 12)]

    /**
     * Grafia de [pitchClass] como grau do tom [key]: a letra segue a escala (F# em Ré maior,
     * Bb como bVII em Dó, G# como sensível em Lá menor). Quando isso daria dobrados ou E#/B#/Cb/Fb,
     * usa sustenidos ou bemóis conforme a armadura do tom.
     */
    fun spell(pitchClass: Int, key: Int, minor: Boolean): String {
        val pc = Math.floorMod(pitchClass, 12)
        val k = Math.floorMod(key, 12)
        val tonic = if (minor) MINOR_TONICS[k] else KEYS[k]
        val letter = (LETTERS.indexOf(tonic[0]) + STEP_OF_DEGREE[Math.floorMod(pc - k, 12)]) % 7
        val accidental = Math.floorMod(pc - NATURALS[letter] + 6, 12) - 6
        if (accidental in -1..1) {
            val name = LETTERS[letter] + when (accidental) {
                1 -> "#"
                -1 -> "b"
                else -> ""
            }
            if (name !in AWKWARD) return name
        }
        val signature = Math.floorMod(k + if (minor) 3 else 0, 12)
        return name(pc, signature in FLAT_SIGNATURES)
    }
}

/**
 * Acorde que soa no tempo [beat] de um compasso de [beats] tempos com os acordes [chords]:
 * os acordes dividem o compasso em partes iguais, arredondando para cima (2 acordes em 3 tempos
 * = 2 + 1). Para marcar durações diferentes, repita o acorde (ex.: G G G C = 3 + 1).
 */
fun chordInBar(chords: List<Chord>, beat: Int, beats: Int): Chord {
    val k = chords.size
    if (k == 1) return chords[0]
    var j = k - 1
    while (j > 0 && beat < (j * beats + k - 1) / k) j--
    return chords[j]
}

/**
 * Para cada compasso, quantos compassos seguidos o mesmo acorde já vinha soando
 * (0 = o acorde acabou de mudar). Usado para alternar padrões de baixo de 2 compassos.
 */
fun computeBarsSinceChange(bars: List<List<Chord>>): IntArray = IntArray(bars.size) { index ->
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

/** Sequência de compassos com acordes que a linha de baixo e as viradas seguem. */
interface Progression {
    val bars: List<List<Chord>>
    val barsSinceChange: IntArray

    val length: Int get() = bars.size

    /** Tempos do compasso [bar] quando ele foge da fórmula da levada (null = o da levada). */
    fun beatsAt(bar: Int): Int? = null

    /** Acorde que soa no tempo [beat] do compasso [barIndex] (com [beatsPerBar] tempos). */
    fun chordAt(barIndex: Int, beat: Int, beatsPerBar: Int): Chord =
        chordInBar(bars[Math.floorMod(barIndex, length)], beat, beatsPerBar)

    /** Virada no fim do compasso [bar] (anuncia a próxima parte). */
    fun fillAt(bar: Int): Boolean = length > 1 && bar == length - 1

    /** Prato de ataque no começo do compasso [bar]. */
    fun crashAt(bar: Int): Boolean = bar == 0

    /** Volta ao início no fim (false = a música termina). */
    val loops: Boolean get() = true

    /**
     * Compasso por onde entrar ao trocar para esta progressão durante a execução, vindo de
     * [previous] cujo último compasso tocado foi [previousBar] (0 = começar do início).
     */
    fun entryBar(previous: Progression, previousBar: Int): Int = 0

    /** Posição do compasso [bar] na cifra completa (mostrada na interface). */
    fun displayBar(bar: Int): Int = Math.floorMod(bar, length)

    fun chordName(chord: Chord, key: Int): String = chord.name(key)
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
enum class ProgressionType(
    val label: String,
    val description: String,
    override val bars: List<List<Chord>>,
) : Progression {
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

    override val barsSinceChange: IntArray by lazy { computeBarsSinceChange(bars) }
}
