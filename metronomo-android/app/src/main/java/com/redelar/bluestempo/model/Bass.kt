package com.redelar.bluestempo.model

/** Timbre do baixo sintetizado. */
enum class BassTone(val label: String) {
    ELECTRIC("Elétrico"),
    UPRIGHT("Acústico"),
}

/**
 * Grau de uma nota da linha de baixo, relativo à fundamental do acorde.
 *
 * Sintaxe dos tokens: `R` ou `1`..`8`, com prefixo opcional `b`/`#` e sufixos
 * `,` (oitava abaixo) ou `'` (oitava acima). Sem acidente, 3/5/6/7 seguem a
 * qualidade do acorde (ex.: `3` vira terça menor num acorde m7); com acidente
 * o intervalo é fixo (ex.: `b7` é sempre sétima menor).
 */
data class Degree(val number: Int, val accidental: Int, val octave: Int, val chordAware: Boolean) {

    fun semitones(quality: Quality): Int {
        val base = if (chordAware) {
            when (number) {
                1 -> 0
                2 -> 2
                3 -> quality.third
                4 -> 5
                5 -> quality.fifth
                6 -> quality.sixth
                7 -> quality.seventh
                else -> 12
            }
        } else {
            MAJOR_SCALE[number - 1] + accidental
        }
        return base + 12 * octave
    }

    companion object {
        private val MAJOR_SCALE = intArrayOf(0, 2, 4, 5, 7, 9, 11, 12)

        fun parse(token: String): Degree {
            var text = token
            var accidental = 0
            var hasAccidental = false
            if (text.startsWith("b") || text.startsWith("#")) {
                accidental = if (text[0] == 'b') -1 else 1
                hasAccidental = true
                text = text.substring(1)
            }
            var octave = 0
            while (text.endsWith(",") || text.endsWith("'")) {
                octave += if (text.last() == ',') -1 else 1
                text = text.dropLast(1)
            }
            val number = when (text) {
                "R" -> 1
                else -> text.toIntOrNull() ?: error("Token de baixo inválido: $token")
            }
            require(number in 1..8) { "Grau fora do intervalo: $token" }
            return Degree(number, accidental, octave, chordAware = !hasAccidental)
        }
    }
}

/** Nota de um padrão de baixo: começa em [step] e dura [lengthSteps] passos. */
data class BassNoteSpec(val step: Int, val lengthSteps: Int, val degree: Degree)

/**
 * Padrão de baixo escrito em uma grade de [stepsPerBeat] passos por tempo.
 * Pode ter várias variações (compassos), alternadas enquanto o acorde se repete.
 * Tokens: grau (ver [Degree]), `~` prolonga a nota anterior e `.` é pausa.
 */
class BassPattern(val stepsPerBeat: Int, val gate: Float, vararg variations: String) {

    val variations: List<Variation> = variations.map { parseVariation(it) }

    class Variation(val length: Int, private val notes: Array<BassNoteSpec?>) {
        /** Nota que começa no passo [step] (o padrão se repete se o compasso for maior). */
        fun noteAt(step: Int): BassNoteSpec? = notes[Math.floorMod(step, length)]
    }

    private fun parseVariation(text: String): Variation {
        val tokens = text.trim().split(Regex("\\s+"))
        val notes = arrayOfNulls<BassNoteSpec>(tokens.size)
        tokens.forEachIndexed { index, token ->
            if (token == "~" || token == ".") return@forEachIndexed
            var length = 1
            while (index + length < tokens.size && tokens[index + length] == "~") length++
            notes[index] = BassNoteSpec(index, length, Degree.parse(token))
        }
        return Variation(tokens.size, notes)
    }
}

enum class BassAlgorithm { PATTERN, WALKING, TWO_FEEL }

/** Estilos de linha de baixo disponíveis para acompanhar as levadas. */
enum class BassStyle(
    val label: String,
    val algorithm: BassAlgorithm,
    val pattern: BassPattern? = null,
) {
    ROOTS("Tônica nos tempos", BassAlgorithm.PATTERN, BassPattern(1, 0.9f, "R")),
    ROCK_EIGHTHS("Tônica em colcheias", BassAlgorithm.PATTERN, BassPattern(2, 0.78f, "R R R R R R R R")),
    SHUFFLE_156("Shuffle 1-5-6", BassAlgorithm.PATTERN, BassPattern(2, 0.82f, "R R 5 5 6 6 5 5")),
    BOOGIE("Boogie 1-3-5-6-b7", BassAlgorithm.PATTERN, BassPattern(2, 0.86f, "R 3 5 6 7 6 5 3")),
    BOOGIE_WALK(
        "Boogie em semínimas",
        BassAlgorithm.PATTERN,
        BassPattern(2, 0.88f, "R ~ 3 ~ 5 ~ 6 ~", "8 ~ 6 ~ 5 ~ 3 ~"),
    ),
    SLOW_BLUES("Slow blues 12/8", BassAlgorithm.PATTERN, BassPattern(3, 0.9f, "R ~ 3 5 ~ 6 7 ~ 6 5 ~ 3")),
    MINOR_GROOVE(
        "Groove menor (16ths)",
        BassAlgorithm.PATTERN,
        BassPattern(4, 0.85f, "R ~ ~ ~ ~ ~ R ~ 5 ~ ~ ~ 7 ~ 8 ~"),
    ),
    BO_DIDDLEY(
        "Bo Diddley",
        BassAlgorithm.PATTERN,
        BassPattern(4, 0.8f, "R ~ ~ R ~ ~ R ~ ~ . 5, ~ R ~ ~ ."),
    ),
    BOSSA("Bossa nova", BassAlgorithm.PATTERN, BassPattern(2, 0.92f, "R ~ ~ 5, ~ ~ ~ ~")),
    AFRO("Afro 6/8", BassAlgorithm.PATTERN, BassPattern(3, 0.92f, "R ~ ~ ~ ~ ~ 5, ~ ~ ~ ~ ~")),
    BALLAD("Balada (tônica e oitava)", BassAlgorithm.PATTERN, BassPattern(2, 0.9f, "R ~ ~ R 8 ~ 5 ~")),
    SAMBA("Samba (surdo)", BassAlgorithm.PATTERN, BassPattern(4, 0.85f, "R ~ ~ R ~ ~ ~ ~ 5, ~ ~ 5, ~ ~ ~ ~")),
    COUNTRY("Country (tônica-quinta)", BassAlgorithm.PATTERN, BassPattern(1, 0.8f, "R 5, R 5,")),
    GALLOP("Galope (colcheia + 2 semis)", BassAlgorithm.PATTERN, BassPattern(4, 0.8f, "R ~ R R")),
    SYNCOPATED("Sincopado 3+3+2", BassAlgorithm.PATTERN, BassPattern(4, 0.85f, "R ~ ~ R ~ ~ R ~")),
    MINOR_RIFF("Riff menor (1-5-7-8)", BassAlgorithm.PATTERN, BassPattern(2, 0.85f, "R 5 7 8 7 5 3 7,")),
    ARPEGGIO("Arpejo 12/8 (1-5-8-5)", BassAlgorithm.PATTERN, BassPattern(3, 0.92f, "R ~ ~ 5 ~ ~ 8 ~ ~ 5 ~ ~")),
    TRESILLO("Tresillo (latin)", BassAlgorithm.PATTERN, BassPattern(2, 0.85f, "R ~ ~ 5 ~ ~ 8 ~")),
    WALKING("Walking bass (jazz)", BassAlgorithm.WALKING),
    TWO_FEEL("Em dois (mínimas)", BassAlgorithm.TWO_FEEL),
}
