package com.redelar.bluestempo.model

/**
 * Canais do mixer. Cada instrumento pertence a um canal com volume próprio.
 */
enum class Channel(val label: String) {
    KICK("Bumbo"),
    SNARE("Caixa"),
    CYMBALS("Pratos"),
    PERCUSSION("Percussão"),
    BASS("Baixo"),
    GUIDE_CLICK("Clique guia"),
}

/**
 * Peças de bateria/percussão sintetizadas pelo app.
 *
 * @param pan posição estéreo (-1 = esquerda, 1 = direita), na perspectiva da plateia.
 * @param chokeGroup peças do mesmo grupo (> 0) se abafam (ex.: chimbal fechado abafa o aberto).
 * @param replacedByFill se a peça é silenciada durante as viradas.
 */
enum class Instrument(
    val label: String,
    val channel: Channel,
    val pan: Float,
    val chokeGroup: Int = 0,
    val replacedByFill: Boolean = true,
) {
    KICK("Bumbo", Channel.KICK, 0f, replacedByFill = false),
    SNARE("Caixa", Channel.SNARE, 0.08f),
    RIM("Aro", Channel.SNARE, 0.08f),
    BRUSH_TAP("Vassourinha", Channel.SNARE, 0.08f),
    BRUSH_SWEEP("Varrida", Channel.SNARE, 0.08f),
    HAT_CLOSED("Chimbal", Channel.CYMBALS, -0.32f, chokeGroup = 1),
    HAT_OPEN("Chimbal aberto", Channel.CYMBALS, -0.32f, chokeGroup = 1),
    HAT_PEDAL("Chimbal (pé)", Channel.CYMBALS, -0.32f, chokeGroup = 1, replacedByFill = false),
    RIDE("Condução", Channel.CYMBALS, 0.38f),
    RIDE_BELL("Cúpula", Channel.CYMBALS, 0.38f),
    CRASH("Ataque", Channel.CYMBALS, -0.45f),
    TOM_HIGH("Tom", Channel.PERCUSSION, -0.15f),
    TOM_LOW("Surdo", Channel.PERCUSSION, 0.25f),
    COWBELL("Cowbell", Channel.PERCUSSION, 0.2f, replacedByFill = false),
    SHAKER("Shaker", Channel.PERCUSSION, -0.2f, replacedByFill = false),
    TAMBOURINE("Pandeirola", Channel.PERCUSSION, 0.3f, replacedByFill = false),
    CLAVE("Clave", Channel.PERCUSSION, 0.15f, replacedByFill = false),
}

/** Conjunto de sons para o clique do metrônomo. */
enum class ClickSound(val label: String) {
    CLASSIC("Clássico"),
    WOOD("Madeira"),
    STICKS("Baquetas"),
    COWBELL("Cowbell"),
    DIGITAL("Digital"),
}

/** Intensidade de cada tempo do compasso no modo metrônomo. */
enum class Accent(val label: String) {
    ACCENT("Forte"),
    NORMAL("Normal"),
    SOFT("Fraco"),
    MUTE("Mudo");

    /** Próximo nível ao tocar no tempo (forte → normal → fraco → mudo → forte). */
    fun next(): Accent = entries[(ordinal + 1) % entries.size]
}
