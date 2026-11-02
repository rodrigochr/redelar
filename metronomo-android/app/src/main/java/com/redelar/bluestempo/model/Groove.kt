package com.redelar.bluestempo.model

enum class Genre(val label: String) {
    BLUES("Blues"),
    ROCK("Rock"),
    JAZZ("Jazz"),
}

/** Unidade em que o swing é aplicado: pares de colcheias ou de semicolcheias. */
enum class SwingUnit(val pairLength: Double) {
    EIGHTH(1.0),
    SIXTEENTH(0.5),
}

/** Instrumentos usados nas viradas. */
enum class FillKit { STICKS, BRUSHES }

/** Uma linha da bateria: intensidade (0..1) de cada passo da grade. */
class Track(val instrument: Instrument, val velocities: FloatArray)

/**
 * Uma levada (groove) de bateria com sugestão de baixo e progressão.
 *
 * A grade tem [stepsPerBeat] passos por tempo: 2 = colcheias (com swing opcional),
 * 3 = tercinas (12/8, shuffle "de verdade"), 4 = semicolcheias.
 */
class Groove(
    val id: String,
    val name: String,
    val genre: Genre,
    val description: String,
    val meter: String,
    val beatsPerBar: Int,
    val stepsPerBeat: Int,
    val bars: Int,
    val defaultBpm: Int,
    val swingUnit: SwingUnit?,
    val defaultSwing: Float,
    val bassStyle: BassStyle,
    val bassTone: BassTone,
    val progression: ProgressionType,
    val fillKit: FillKit,
    val tracks: List<Track>,
    /** Ganho de compensação (instrumentos reais) para todas as levadas soarem com volume parecido. */
    val level: Float = 1f,
    /** Idem, para a versão sintetizada. */
    val synthLevel: Float = 1f,
    /**
     * Mixagem padrão da levada (o mixer volta a ela ao trocar de levada). Calibrada medindo a
     * intensidade das batidas de cada instrumento e aplicando o equilíbrio típico do estilo.
     */
    val mix: MixerSettings = MixerSettings(),
) {
    val stepsPerBar: Int get() = beatsPerBar * stepsPerBeat
    val totalSteps: Int get() = stepsPerBar * bars
    val swingEnabled: Boolean get() = swingUnit != null

    /** Figura que vale um tempo (semínima ou semínima pontuada nos compassos compostos). */
    val beatNote: String get() = if (stepsPerBeat == 3 && meter.endsWith("/8")) "♩." else "♩"

    init {
        require(tracks.isNotEmpty()) { "Levada $id sem trilhas" }
        tracks.forEach {
            require(it.velocities.size == totalSteps) {
                "Levada $id: trilha ${it.instrument} tem ${it.velocities.size} passos, esperado $totalSteps"
            }
        }
    }
}

/** Construtor compacto: cada caractere é um passo. X=forte x=normal o=fraco g=fantasma -=pausa. */
private class GrooveBuilder {
    val tracks = mutableListOf<Track>()

    fun track(instrument: Instrument, pattern: String) {
        tracks += Track(instrument, parseVelocities(pattern))
    }

    fun kick(p: String) = track(Instrument.KICK, p)
    fun snare(p: String) = track(Instrument.SNARE, p)
    fun rim(p: String) = track(Instrument.RIM, p)
    fun hat(p: String) = track(Instrument.HAT_CLOSED, p)
    fun hatOpen(p: String) = track(Instrument.HAT_OPEN, p)
    fun pedal(p: String) = track(Instrument.HAT_PEDAL, p)
    fun ride(p: String) = track(Instrument.RIDE, p)
    fun brush(p: String) = track(Instrument.BRUSH_TAP, p)
    fun sweep(p: String) = track(Instrument.BRUSH_SWEEP, p)
    fun tomHigh(p: String) = track(Instrument.TOM_HIGH, p)
    fun tomLow(p: String) = track(Instrument.TOM_LOW, p)
    fun cowbell(p: String) = track(Instrument.COWBELL, p)
    fun shaker(p: String) = track(Instrument.SHAKER, p)
    fun tambourine(p: String) = track(Instrument.TAMBOURINE, p)
}

internal fun parseVelocities(pattern: String): FloatArray =
    pattern.filter { !it.isWhitespace() && it != '|' }
        .map {
            when (it) {
                'X' -> 1f
                'x' -> 0.78f
                'o' -> 0.5f
                'g' -> 0.28f
                '-', '.' -> 0f
                else -> error("Caractere inválido no padrão: '$it'")
            }
        }
        .toFloatArray()

private fun groove(
    id: String,
    name: String,
    genre: Genre,
    description: String,
    bpm: Int,
    bass: BassStyle,
    progression: ProgressionType,
    meter: String = "4/4",
    beats: Int = 4,
    steps: Int = 2,
    bars: Int = 1,
    swingUnit: SwingUnit? = if (steps == 2) SwingUnit.EIGHTH else if (steps == 4) SwingUnit.SIXTEENTH else null,
    swing: Float = 0.5f,
    tone: BassTone = BassTone.ELECTRIC,
    fillKit: FillKit = FillKit.STICKS,
    level: Float = 1f,
    synthLevel: Float = 1f,
    mix: MixerSettings = MixerSettings(),
    build: GrooveBuilder.() -> Unit,
): Groove {
    val builder = GrooveBuilder().apply(build)
    return Groove(
        id = id, name = name, genre = genre, description = description, meter = meter,
        beatsPerBar = beats, stepsPerBeat = steps, bars = bars, defaultBpm = bpm,
        swingUnit = swingUnit, defaultSwing = swing, bassStyle = bass, bassTone = tone,
        progression = progression, fillKit = fillKit, tracks = builder.tracks, level = level, synthLevel = synthLevel, mix = mix,
    )
}

/** Volumes padrão (0..1) de bumbo, caixa, pratos, percussão e baixo; o clique guia começa desligado. */
private fun mix(kick: Float, snare: Float, cymbals: Float, percussion: Float, bass: Float) =
    MixerSettings(kick, snare, cymbals, percussion, bass, guideClick = 0f)

/** Biblioteca de levadas. Os padrões são escritos um grupo (tempo) por bloco. */
object GrooveLibrary {

    val all: List<Groove> = listOf(
        // ------------------------------------------------------------------ BLUES
        groove(
            "chicago_shuffle", "Shuffle de Chicago", Genre.BLUES,
            "O shuffle clássico: chimbal \"longa-curta\" em tercinas, caixa no 2 e 4 e baixo 1-5-6. " +
                "Base de incontáveis jams de blues.",
            level = 2.22f, synthLevel = 1.11f, mix = mix(0.6f, 0.9f, 0.75f, 0.85f, 0.6f),
            bpm = 116, bass = BassStyle.SHUFFLE_156, progression = ProgressionType.BLUES_12, swing = 0.67f,
        ) {
            hat("Xo xo Xo xo")
            snare("-- X- -- Xg")
            kick("x- -- x- --")
        },
        groove(
            "texas_shuffle", "Shuffle Texano (double shuffle)", Genre.BLUES,
            "As duas mãos tocam o shuffle: chimbal e caixa em tercinas, caixa acentuada no 2 e 4 " +
                "e bumbo nos quatro tempos. Pegada Texas, à la Stevie Ray Vaughan.",
            level = 1.74f, synthLevel = 0.98f, mix = mix(0.55f, 0.9f, 0.85f, 0.85f, 0.65f),
            bpm = 126, bass = BassStyle.BOOGIE, progression = ProgressionType.BLUES_12, swing = 0.67f,
        ) {
            hat("Xo Xo Xo Xo")
            snare("go Xg go Xg")
            kick("x- x- x- x-")
        },
        groove(
            "boogie_woogie", "Boogie-Woogie", Genre.BLUES,
            "Swing acelerado com o famoso baixo de boogie 1-3-5-6-b7-6-5-3 em colcheias. " +
                "Raiz do piano boogie e do rock'n'roll.",
            level = 1.87f, synthLevel = 0.93f, mix = mix(0.5f, 0.85f, 0.9f, 0.8f, 0.7f),
            bpm = 150, bass = BassStyle.BOOGIE, progression = ProgressionType.BLUES_12, swing = 0.65f,
        ) {
            ride("Xo xo Xo xo")
            pedal("-- x- -- x-")
            snare("-g X- -g X-")
            kick("x- o- x- o-")
        },
        groove(
            "hooker_boogie", "Boogie de um acorde", Genre.BLUES,
            "Boogie hipnótico em um acorde só, com bumbo nos quatro tempos — no espírito de " +
                "John Lee Hooker e Canned Heat.",
            level = 1.66f, synthLevel = 0.91f, mix = mix(0.55f, 0.9f, 0.65f, 0.85f, 0.65f),
            bpm = 168, bass = BassStyle.SHUFFLE_156, progression = ProgressionType.VAMP_DOM, swing = 0.64f,
        ) {
            hat("Xx Xx Xx Xx")
            snare("-- X- -- X-")
            kick("x- x- x- x-")
        },
        groove(
            "slow_blues", "Slow Blues 12/8", Genre.BLUES,
            "Blues lento em 12/8: chimbal em todas as tercinas e caixa pesada no 2 e 4. " +
                "O andamento conta as semínimas pontuadas.",
            level = 2.29f, synthLevel = 1.51f, mix = mix(0.6f, 0.9f, 0.7f, 0.85f, 0.55f),
            bpm = 58, bass = BassStyle.SLOW_BLUES, progression = ProgressionType.BLUES_12,
            meter = "12/8", steps = 3,
        ) {
            hat("Xoo xoo Xoo xoo")
            snare("--- X-- --- X--")
            kick("x-- --- x-- --o")
        },
        groove(
            "jump_blues", "Jump Blues", Genre.BLUES,
            "Swing de big band aplicado ao blues: condução \"spang-a-lang\", backbeat forte e " +
                "walking bass. Estilo Louis Jordan e T-Bone Walker.",
            level = 2.17f, synthLevel = 1.55f, mix = mix(0.15f, 0.4f, 0.9f, 0.4f, 0.3f),
            bpm = 172, bass = BassStyle.WALKING, progression = ProgressionType.BLUES_12, swing = 0.66f,
            tone = BassTone.UPRIGHT,
        ) {
            ride("x- Xo x- Xo")
            pedal("-- x- -- x-")
            snare("-- X- -- X-")
            kick("o- o- o- o-")
        },
        groove(
            "train_beat", "Train Beat (trem)", Genre.BLUES,
            "A caixa imita o trem: semicolcheias contínuas com acento no 2 e 4, sobre baixo " +
                "caminhando em semínimas. Som de Memphis e Chicago dos anos 50.",
            level = 1.93f, synthLevel = 0.85f, mix = mix(0.55f, 0.9f, 0.75f, 0.85f, 0.7f),
            bpm = 140, bass = BassStyle.BOOGIE_WALK, progression = ProgressionType.BLUES_12,
            steps = 4, swing = 0.55f,
        ) {
            snare("ogog Xgog ogog Xgog")
            kick("x--- ---- x--- ----")
        },
        groove(
            "swamp_blues", "Swamp Blues (Louisiana)", Genre.BLUES,
            "Groove preguiçoso da Louisiana: aro da caixa e pandeirola no contratempo, swing leve. " +
                "Clima de Slim Harpo e Lazy Lester.",
            level = 2.95f, synthLevel = 2.39f, mix = mix(0.45f, 0.9f, 0.6f, 0.45f, 0.5f),
            bpm = 96, bass = BassStyle.SHUFFLE_156, progression = ProgressionType.BLUES_12, swing = 0.58f,
        ) {
            hat("Xo xo Xo xo")
            rim("-- X- -- X-")
            tambourine("-- x- -- x-")
            kick("x- -o x- --")
        },
        groove(
            "bo_diddley", "Bo Diddley Beat", Genre.BLUES,
            "A batida \"jungle\" 3-2 nos tons com maracas em semicolcheias, sobre um acorde só.",
            level = 2.04f, synthLevel = 1.12f, mix = mix(0.45f, 0.75f, 0.65f, 0.9f, 0.6f),
            bpm = 112, bass = BassStyle.BO_DIDDLEY, progression = ProgressionType.VAMP_DOM, steps = 4,
        ) {
            tomLow("x--x --x- --x- x---")
            shaker("Xoxo xoxo Xoxo xoxo")
            kick("x--- ---- x--- ----")
        },
        groove(
            "blues_rock", "Blues Rock (colcheias retas)", Genre.BLUES,
            "Colcheias retas e pesadas com o baixo martelando a tônica. Blues com pegada de rock " +
                "britânico dos anos 60 e 70.",
            level = 2.04f, synthLevel = 1.1f, mix = mix(0.65f, 0.9f, 0.55f, 0.85f, 0.55f),
            bpm = 120, bass = BassStyle.ROCK_EIGHTHS, progression = ProgressionType.BLUES_12,
        ) {
            hat("Xo Xo Xo Xo")
            snare("-- X- -- X-")
            kick("x- -- xx --")
        },
        groove(
            "minor_blues", "Blues Menor (16ths)", Genre.BLUES,
            "Groove reto em semicolcheias para blues em tom menor, no clima de \"The Thrill Is Gone\".",
            level = 2.34f, synthLevel = 1.22f, mix = mix(0.6f, 0.9f, 0.55f, 0.85f, 0.6f),
            bpm = 88, bass = BassStyle.MINOR_GROOVE, progression = ProgressionType.BLUES_MINOR, steps = 4,
        ) {
            hat("Xoxo Xoxo Xoxo Xoxo")
            snare("---- X--g ---- X---")
            kick("x--- --x- x--- ----")
        },

        // ------------------------------------------------------------------ ROCK
        groove(
            "rock_basic", "Rock Básico", Genre.ROCK,
            "A levada fundamental do rock: chimbal em colcheias, caixa no 2 e 4, bumbo no 1 e 3.",
            level = 2.19f, synthLevel = 1.11f, mix = mix(0.7f, 0.9f, 0.55f, 0.85f, 0.5f),
            bpm = 110, bass = BassStyle.ROCK_EIGHTHS, progression = ProgressionType.ROCK_I_IV_V,
        ) {
            hat("Xo Xo Xo Xo")
            snare("-- X- -- X-")
            kick("x- -- x- --")
        },
        groove(
            "rock_drive", "Rock com Bumbo Sincopado", Genre.ROCK,
            "Bumbo no \"e\" do 3 e do 4, empurrando a música para frente.",
            level = 2.02f, synthLevel = 1.08f, mix = mix(0.55f, 0.9f, 0.55f, 0.85f, 0.5f),
            bpm = 120, bass = BassStyle.ROCK_EIGHTHS, progression = ProgressionType.ROCK_I_IV_V,
        ) {
            hat("Xo Xo Xo Xo")
            snare("-- X- -- X-")
            kick("x- -- xx -x")
        },
        groove(
            "rock_16", "Rock em Semicolcheias", Genre.ROCK,
            "Chimbal em semicolcheias para rock e pop-rock de andamento médio.",
            level = 2.01f, synthLevel = 1.08f, mix = mix(0.65f, 0.9f, 0.5f, 0.85f, 0.5f),
            bpm = 92, bass = BassStyle.ROCK_EIGHTHS, progression = ProgressionType.POP_I_V_VI_IV, steps = 4,
        ) {
            hat("Xoxo Xoxo Xoxo Xoxo")
            snare("---- X--- ---- X---")
            kick("x--- --x- x-x- ----")
        },
        groove(
            "hard_rock", "Hard Rock (condução)", Genre.ROCK,
            "Mão direita no prato de condução e bumbo antecipando o 3. Peso e energia de arena.",
            level = 1.97f, synthLevel = 1.0f, mix = mix(0.6f, 0.9f, 0.6f, 0.85f, 0.5f),
            bpm = 132, bass = BassStyle.ROCK_EIGHTHS, progression = ProgressionType.ROCK_I_IV_V,
        ) {
            ride("Xo Xo Xo Xo")
            snare("-- X- -- X-")
            kick("x- -x x- --")
        },
        groove(
            "half_time", "Meio-Tempo (half-time)", Genre.ROCK,
            "Caixa só no 3: o andamento parece a metade. Ótimo para refrões pesados.",
            level = 2.06f, synthLevel = 1.26f, mix = mix(0.7f, 0.9f, 0.65f, 0.85f, 0.55f),
            bpm = 140, bass = BassStyle.ROOTS, progression = ProgressionType.POP_I_V_VI_IV,
        ) {
            hat("Xo Xo Xo Xo")
            snare("-- -- X- --")
            kick("x- -x -- --")
        },
        groove(
            "rock_and_roll", "Rock'n'Roll (Chuck Berry)", Genre.ROCK,
            "Blues de 12 compassos acelerado, backbeat forte e o riff 1-5-6 em colcheias quase retas.",
            level = 1.91f, synthLevel = 0.94f, mix = mix(0.65f, 0.9f, 0.6f, 0.85f, 0.6f),
            bpm = 164, bass = BassStyle.SHUFFLE_156, progression = ProgressionType.BLUES_12, swing = 0.56f,
        ) {
            hat("Xx Xx Xx Xx")
            snare("-- X- -- X-")
            kick("x- -- x- --")
        },
        groove(
            "rockabilly", "Rockabilly", Genre.ROCK,
            "Caixa em shuffle com acento no 2 e 4 e contrabaixo caminhando. Sun Records e Stray Cats.",
            level = 1.99f, synthLevel = 0.85f, mix = mix(0.55f, 0.9f, 0.7f, 0.85f, 0.45f),
            bpm = 180, bass = BassStyle.BOOGIE_WALK, progression = ProgressionType.BLUES_12, swing = 0.62f,
            tone = BassTone.UPRIGHT,
        ) {
            snare("go Xo go Xo")
            pedal("-- x- -- x-")
            kick("x- -- x- --")
        },
        groove(
            "purdie_shuffle", "Shuffle Meio-Tempo (Purdie)", Genre.ROCK,
            "Shuffle em meio-tempo com notas fantasma na caixa entre as batidas do chimbal. " +
                "Popularizado por Bernard Purdie.",
            level = 2.52f, synthLevel = 1.52f, mix = mix(0.55f, 0.9f, 0.7f, 0.85f, 0.55f),
            bpm = 90, bass = BassStyle.SHUFFLE_156, progression = ProgressionType.BLUES_12, steps = 3,
        ) {
            hat("X-x x-x X-x x-x")
            snare("-g- -g- Xg- -g-")
            kick("x-- --x --- --o")
        },
        groove(
            "ballad_68", "Balada 6/8", Genre.ROCK,
            "Balada em 6/8 (dois tempos de semínima pontuada) com chimbal nas seis colcheias.",
            level = 2.57f, synthLevel = 1.49f, mix = mix(0.55f, 0.9f, 0.65f, 0.85f, 0.5f),
            bpm = 58, bass = BassStyle.ROOTS, progression = ProgressionType.POP_I_V_VI_IV,
            meter = "6/8", beats = 2, steps = 3,
        ) {
            hat("Xoo xoo")
            snare("--- X--")
            kick("x-- --o")
        },
        groove(
            "punk", "Punk (polca)", Genre.ROCK,
            "Bumbo nos tempos e caixa em todos os contratempos: a polca rápida do punk.",
            level = 1.81f, synthLevel = 0.87f, mix = mix(0.6f, 0.9f, 0.55f, 0.85f, 0.5f),
            bpm = 190, bass = BassStyle.ROCK_EIGHTHS, progression = ProgressionType.ROCK_I_IV_V,
        ) {
            hat("Xo Xo Xo Xo")
            snare("-x -x -x -x")
            kick("x- x- x- x-")
        },

        // ------------------------------------------------------------------ JAZZ
        groove(
            "swing_medium", "Swing Médio", Genre.JAZZ,
            "Condução \"spang-a-lang\" no prato, chimbal no 2 e 4, bumbo \"feathering\" e walking " +
                "bass num jazz blues.",
            level = 3.16f, synthLevel = 2.27f, mix = mix(0.2f, 0.45f, 0.9f, 0.45f, 0.3f),
            bpm = 140, bass = BassStyle.WALKING, progression = ProgressionType.JAZZ_BLUES, swing = 0.66f,
            tone = BassTone.UPRIGHT, bars = 2,
        ) {
            ride("x- Xo x- Xo | x- Xo x- Xo")
            pedal("-- x- -- x- | -- x- -- x-")
            kick("g- g- g- g- | g- g- g- g-")
            snare("-- -- -- -g | -- -g -- --")
        },
        groove(
            "bebop", "Bebop (rápido)", Genre.JAZZ,
            "Andamento alto com swing mais reto, comping leve de caixa e \"bombas\" de bumbo.",
            level = 2.52f, synthLevel = 1.64f, mix = mix(0.3f, 0.45f, 0.9f, 0.45f, 0.35f),
            bpm = 240, bass = BassStyle.WALKING, progression = ProgressionType.JAZZ_BLUES, swing = 0.58f,
            tone = BassTone.UPRIGHT, bars = 2,
        ) {
            ride("x- Xo x- Xo | x- Xo x- Xo")
            pedal("-- x- -- x- | -- x- -- x-")
            snare("-- -g -- -- | -- -- -o --")
            kick("-- -- -- -- | -- -- -- -o")
        },
        groove(
            "kc_shuffle", "Shuffle Kansas City", Genre.JAZZ,
            "O shuffle das big bands de Kansas City: bumbo nos quatro, caixa em shuffle e walking bass.",
            level = 2.14f, synthLevel = 1.72f, mix = mix(0.2f, 0.35f, 0.9f, 0.35f, 0.3f),
            bpm = 150, bass = BassStyle.WALKING, progression = ProgressionType.BLUES_12, swing = 0.67f,
            tone = BassTone.UPRIGHT,
        ) {
            ride("Xo xo Xo xo")
            pedal("-- x- -- x-")
            snare("-g Xg -g Xg")
            kick("x- x- x- x-")
        },
        groove(
            "brush_swing", "Swing com Vassourinhas", Genre.JAZZ,
            "A condução de swing tocada com vassourinhas na caixa, para trios e combos acústicos.",
            level = 1.95f, synthLevel = 1.86f, mix = mix(0.4f, 0.9f, 1f, 0.85f, 0.7f),
            bpm = 132, bass = BassStyle.WALKING, progression = ProgressionType.JAZZ_BLUES, swing = 0.66f,
            tone = BassTone.UPRIGHT, fillKit = FillKit.BRUSHES,
        ) {
            brush("x- Xo x- Xo")
            sweep("x- -- x- --")
            pedal("-- x- -- x-")
            kick("g- g- g- g-")
        },
        groove(
            "jazz_ballad", "Balada com Vassourinhas", Genre.JAZZ,
            "Vassourinhas varrendo a pele em cada tempo e toques no 2 e 4, com baixo em mínimas.",
            level = 2.29f, synthLevel = 2.63f, mix = mix(0.5f, 0.9f, 1f, 0.85f, 0.8f),
            bpm = 64, bass = BassStyle.TWO_FEEL, progression = ProgressionType.II_V_I, swing = 0.66f,
            tone = BassTone.UPRIGHT, fillKit = FillKit.BRUSHES,
        ) {
            sweep("x- x- x- x-")
            brush("-- Xo -- Xo")
            pedal("-- x- -- x-")
            kick("g- -- g- --")
        },
        groove(
            "two_feel", "Two-Feel (em dois)", Genre.JAZZ,
            "Baixo em mínimas no 1 e 3 com condução swing — usado na exposição do tema antes do walking.",
            level = 3.24f, synthLevel = 2.38f, mix = mix(0.2f, 0.75f, 0.9f, 0.7f, 0.35f),
            bpm = 120, bass = BassStyle.TWO_FEEL, progression = ProgressionType.JAZZ_BLUES, swing = 0.66f,
            tone = BassTone.UPRIGHT,
        ) {
            ride("x- Xo x- Xo")
            pedal("-- x- -- x-")
            kick("o- -- o- --")
        },
        groove(
            "jazz_waltz", "Valsa Jazz 3/4", Genre.JAZZ,
            "Swing em três tempos com walking bass, para valsas de jazz.",
            level = 2.88f, synthLevel = 2.26f, mix = mix(0.2f, 0.4f, 0.9f, 0.4f, 0.3f),
            bpm = 150, bass = BassStyle.WALKING, progression = ProgressionType.JAZZ_BLUES, swing = 0.66f,
            tone = BassTone.UPRIGHT, meter = "3/4", beats = 3,
        ) {
            ride("x- Xo x-")
            pedal("-- x- x-")
            kick("o- -- --")
            snare("-- -- -g")
        },
        groove(
            "bossa_nova", "Bossa Nova", Genre.JAZZ,
            "O aro faz a clave da bossa em dois compassos sobre o chimbal em colcheias e o bumbo " +
                "imitando o surdo.",
            level = 3.23f, synthLevel = 2.11f, mix = mix(0.25f, 0.9f, 0.55f, 0.85f, 0.4f),
            bpm = 132, bass = BassStyle.BOSSA, progression = ProgressionType.II_V_I, swingUnit = null,
            tone = BassTone.UPRIGHT, bars = 2,
        ) {
            hat("Xo xo Xo xo | Xo xo Xo xo")
            rim("x- -x -- x- | -- x- -x --")
            kick("x- -o x- -o | x- -o x- -o")
        },
        groove(
            "afro_68", "Afro 6/8", Genre.JAZZ,
            "O sino em 12/8 (padrão bembé) sobre um vamp menor — o clima de \"Afro Blue\".",
            level = 2.14f, synthLevel = 2.07f, mix = mix(0.5f, 0.75f, 0.9f, 0.9f, 0.65f),
            bpm = 100, bass = BassStyle.AFRO, progression = ProgressionType.VAMP_MINOR,
            tone = BassTone.UPRIGHT, meter = "12/8", steps = 3,
        ) {
            cowbell("x-x -xx -x- x-x")
            shaker("xgg xgg xgg xgg")
            pedal("--- x-- --- x--")
            kick("x-- o-- x-- o--")
        },
    )

    private val byId: Map<String, Groove> = all.associateBy { it.id }

    val default: Groove get() = all.first()

    fun byId(id: String): Groove = byId[id] ?: default

    fun byGenre(genre: Genre): List<Groove> = all.filter { it.genre == genre }
}
