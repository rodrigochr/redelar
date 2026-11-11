package com.redelar.bluestempo.model

/** Grau de confiança na transcrição (andamento, tom e acordes) de uma música. */
enum class Confidence(val label: String) {
    HIGH("Alta"),
    MEDIUM("Média"),
    LOW("Baixa"),
}

/** Onde a música está: na setlist (numerada), nos extras ou fora da setlist (guardada). */
enum class SongGroup(val label: String) {
    SETLIST("Setlist"),
    EXTRA("Extras"),
    OTHER("Fora da setlist"),
}

/** Um compasso da cifra: acordes (divididos em partes iguais) e, se diferente da levada, quantos tempos. */
class SongBar(val chords: List<Chord>, val beats: Int? = null)

/** Seção nomeada da música (Intro, Verso, Refrão…). */
class SongSection(val name: String, val bars: List<SongBar>)

/** Uma passagem da forma: a seção [section] tocada a partir do compasso [start] da música. */
class SongPart(val index: Int, val section: SongSection, val start: Int) {
    val name: String get() = section.name
    val length: Int get() = section.bars.size
    val end: Int get() = start + length
}

/**
 * Música da setlist: andamento, tom, levada sugerida e a cifra compasso a compasso.
 * Os acordes ficam relativos ao tom ([Chord.degree]), então trocar o tom transpõe a música.
 */
class Song(
    val id: String,
    val number: Int,
    val title: String,
    val artist: String,
    /** Gravação usada como referência. */
    val reference: String,
    val key: Int,
    val minor: Boolean,
    val bpm: Int,
    val grooveId: String,
    /** Estilo de baixo (null = o sugerido pela levada). */
    val bassStyle: BassStyle?,
    /** Swing (null = o padrão da levada). */
    val swing: Float?,
    val sections: List<SongSection>,
    val form: List<SongSection>,
    val notes: String,
    val confidence: Confidence,
    val group: SongGroup = SongGroup.SETLIST,
) {
    /** Número mostrado: "9" na setlist, "E2" nos extras, nada para as que saíram. */
    val label: String
        get() = when (group) {
            SongGroup.SETLIST -> "$number"
            SongGroup.EXTRA -> "E$number"
            SongGroup.OTHER -> ""
        }

    val parts: List<SongPart>
    val bars: List<SongBar>

    init {
        require(form.isNotEmpty()) { "Música $id sem forma" }
        var start = 0
        parts = form.mapIndexed { i, section -> SongPart(i, section, start).also { start += section.bars.size } }
        bars = form.flatMap { it.bars }
        require(bars.isNotEmpty()) { "Música $id sem compassos" }
    }

    val length: Int get() = bars.size

    val groove: Groove get() = GrooveLibrary.byId(grooveId)

    val keyName: String get() = NoteNames.keyName(key, minor)

    /** Compasso [bar] começa uma nova parte da forma (com nome diferente da anterior)? */
    internal val phraseStart: BooleanArray = BooleanArray(bars.size).also { starts ->
        parts.forEachIndexed { i, part ->
            if (i == 0 || parts[i - 1].section !== part.section) starts[part.start] = true
        }
    }

    fun partAt(bar: Int): SongPart = parts.last { it.start <= Math.floorMod(bar, length) }

    /** Tempos totais de uma execução (para estimar a duração). */
    fun totalBeats(grooveBeats: Int): Int = bars.sumOf { it.beats ?: grooveBeats }

    private val wholeLoop by lazy { SongProgression(this, 0, length, loops = true) }
    private val wholeOnce by lazy { SongProgression(this, 0, length, loops = false) }
    private val partLoops by lazy { parts.map { SongProgression(this, it.start, it.end, loops = true) } }

    /** Progressão tocada: a música inteira (repetindo ou não) ou só a parte [part] em loop. */
    fun progression(part: Int, repeat: Boolean): SongProgression = when {
        part in parts.indices -> partLoops[part]
        repeat -> wholeLoop
        else -> wholeOnce
    }
}

/** Trecho [from, until) de uma música tocado pelo sequenciador. */
class SongProgression(
    val song: Song,
    val from: Int,
    val until: Int,
    override val loops: Boolean,
) : Progression {
    private val songBars = song.bars.subList(from, until)
    override val bars: List<List<Chord>> = songBars.map { it.chords }
    override val barsSinceChange: IntArray = computeBarsSinceChange(bars)

    override fun beatsAt(bar: Int): Int? = songBars[Math.floorMod(bar, length)].beats

    private fun startsPhrase(bar: Int): Boolean = bar == 0 || song.phraseStart[from + bar]

    override fun fillAt(bar: Int): Boolean = length > 1 && (bar == length - 1 || startsPhrase(bar + 1))

    override fun crashAt(bar: Int): Boolean = startsPhrase(bar)

    override fun displayBar(bar: Int): Int = from + Math.floorMod(bar, length)

    /** Ao sair do loop de uma parte para a música inteira, segue do compasso seguinte. */
    override fun entryBar(previous: Progression, previousBar: Int): Int {
        if (previous !is SongProgression || previous.song !== song) return 0
        val next = previous.displayBar(previousBar) + 1
        return when {
            next in from until until -> next - from
            next >= until && !loops -> length
            else -> 0
        }
    }

    override fun chordName(chord: Chord, key: Int): String = chord.spelled(key, song.minor)
}

/** Leitura da cifra escrita nas músicas (ver [SongBook]). */
object ChordParser {
    private val CHORD = Regex("^([A-G])([#b]?)([^/]*)(?:/([A-G])([#b]?))?$")
    private val LETTER_PC = mapOf('C' to 0, 'D' to 2, 'E' to 4, 'F' to 5, 'G' to 7, 'A' to 9, 'B' to 11)

    private fun pitch(letter: String, accidental: String): Int =
        Math.floorMod(LETTER_PC.getValue(letter[0]) + if (accidental == "#") 1 else if (accidental == "b") -1 else 0, 12)

    /** Tom escrito como "E", "F#", "Bbm"… → (tônica, menor?). */
    fun parseKey(text: String): Pair<Int, Boolean> {
        val m = Regex("^([A-G])([#b]?)(m?)$").matchEntire(text.trim()) ?: error("Tom inválido: $text")
        return pitch(m.groupValues[1], m.groupValues[2]) to (m.groupValues[3] == "m")
    }

    /** Padroniza a cifra no estilo brasileiro (7M para sétima maior). */
    fun normalizeSuffix(raw: String): String = raw
        .replace("maj7", "7M").replace("Maj7", "7M").replace("M7", "7M")
        .replace("maj9", "7M(9)").replace("min", "m")
        .replace("dim7", "°").replace("dim", "°")

    /** Qualidade (intervalos para o baixo) de uma cifra já padronizada. */
    fun qualityOf(suffix: String): Quality {
        val s = suffix
        return when {
            // Na cifra brasileira, "A9" é A com nona adicionada (o dominante com nona é "A7(9)").
            s.isEmpty() || s == "5" || s == "9" || s.startsWith("add") || s.startsWith("(") || s.startsWith("6") -> Quality.MAJ
            s.startsWith("m7(b5)") || s.startsWith("m7b5") || s.startsWith("ø") -> Quality.HALF_DIM
            s.startsWith("°") || s.startsWith("dim") -> Quality.DIM7
            s.startsWith("aug") || s.startsWith("+") || s.startsWith("5+") -> Quality.AUG
            s.startsWith("7M") || s.startsWith("9M") -> Quality.MAJ7
            s.startsWith("m") -> when {
                s == "m" || s.startsWith("m(") || s.startsWith("madd") -> Quality.MIN
                else -> Quality.MIN7
            }
            s.startsWith("sus2") || s == "2" -> Quality.SUS2
            s.startsWith("sus") || s.startsWith("7sus") || s == "4" || s.startsWith("7(4)") -> Quality.SUS4
            s.startsWith("7") || s.startsWith("9") || s.startsWith("11") || s.startsWith("13") -> Quality.DOM7
            else -> error("Cifra desconhecida: $suffix")
        }
    }

    /** Lê um acorde ("Em7", "D/F#", "N.C.") relativo ao tom [key]. */
    fun parseChord(text: String, key: Int, spellShift: Int = 0, spellMinor: Boolean? = null): Chord {
        if (text == "N.C." || text == "NC") return Chord(0, Quality.NC)
        val m = CHORD.matchEntire(text) ?: error("Acorde inválido: $text")
        val root = pitch(m.groupValues[1], m.groupValues[2])
        val suffix = normalizeSuffix(m.groupValues[3])
        val bass = m.groupValues[4].takeIf { it.isNotEmpty() }?.let { pitch(it, m.groupValues[5]) }
        return Chord(
            degree = Math.floorMod(root - key, 12),
            quality = qualityOf(suffix),
            suffix = suffix,
            bass = bass?.takeIf { it != root }?.let { Math.floorMod(it - key, 12) },
            spellShift = spellShift,
            spellMinor = spellMinor,
        )
    }

    /**
     * Compassos separados por `|`; acordes do compasso separados por espaço (dividem o compasso
     * em partes iguais). `%` repete o compasso anterior, `*4` no fim repete o compasso 4 vezes e
     * `[2]` no começo dá ao compasso 2 tempos (compasso irregular). `{Bb}` no início do trecho
     * indica que ele está em outro tom (modulação): os acordes são escritos com a grafia desse tom.
     */
    fun parseBars(text: String, key: Int): List<SongBar> {
        val result = mutableListOf<SongBar>()
        var body = text.trim()
        var shift = 0
        var localMinor: Boolean? = null
        Regex("^\\{([^}]+)}\\s*").find(body)?.let { m ->
            val (tonic, minor) = parseKey(m.groupValues[1])
            shift = Math.floorMod(tonic - key, 12)
            localMinor = minor
            body = body.substring(m.range.last + 1)
        }
        for (raw in body.split('|')) {
            var tokens = raw.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (tokens.isEmpty()) continue
            var repeat = 1
            var beats: Int? = null
            tokens.last().let { t -> if (t.matches(Regex("\\*\\d+"))) { repeat = t.drop(1).toInt(); tokens = tokens.dropLast(1) } }
            tokens.first().let { t ->
                if (t.matches(Regex("\\[\\d+]"))) { beats = t.drop(1).dropLast(1).toInt(); tokens = tokens.drop(1) }
            }
            require(tokens.isNotEmpty()) { "Compasso vazio em: $text" }
            val bar = if (tokens == listOf("%")) {
                result.lastOrNull()?.let { SongBar(it.chords, beats ?: it.beats) } ?: error("% sem compasso anterior")
            } else {
                SongBar(tokens.map { parseChord(it, key, shift, localMinor) }, beats)
            }
            repeat(repeat) { result += bar }
        }
        return result
    }
}
