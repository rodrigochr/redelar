# BluesTempo — Metrônomo & Levadas (Android)

Metrônomo completo para Android com **levadas de bateria e baixo** de blues, rock e jazz —
com atenção especial ao blues: shuffle de Chicago, double shuffle texano, boogie-woogie,
boogie de um acorde, slow blues 12/8, jump blues, train beat, swamp blues, Bo Diddley e mais.

A bateria, o baixo elétrico, o contrabaixo e a percussão são **instrumentos reais gravados**
(bibliotecas em domínio público, veja abaixo), com várias camadas de dinâmica e variações por
peça. O tempo é gerado com **precisão de amostra**: o andamento não "escorrega" nem acumula
erro, mesmo depois de horas (há teste automatizado para isso). Uma versão sintetizada de todos
os sons continua disponível como opção.

## Funcionalidades

**Metrônomo**
- 20 a 300 BPM, botões −/+ (segure para repetir), slider e **TAP** (toque o andamento).
- Qualquer fórmula de compasso (1 a 16 tempos, figura /2, /4, /8, /16) com atalhos
  (2/4, 3/4, 4/4, 5/4, 6/4, 5/8, 6/8, 7/8, 9/8, 12/8). Compassos compostos e irregulares
  já vêm com agrupamento de acentos (ex.: 7/8 = 2+2+3).
- **Acentos por tempo**: forte, normal, fraco ou mudo (toque em cada tempo).
- **Subdivisões**: colcheias, tercinas, semicolcheias, shuffle, pontuada, galope,
  galope invertido, quintinas e sextinas, com **swing ajustável** e volume próprio.
- 5 sons de clique: clássico, madeira, baquetas, cowbell e digital.

**Levadas (bateria + baixo)**
- 30 levadas: 11 de blues, 10 de rock e 9 de jazz (tabela abaixo), com grade visual que
  mostra cada peça e o passo que está tocando.
- **Swing ajustável** (reto → shuffle → pontuado) nas levadas em colcheias/semicolcheias.
- **Linha de baixo** que segue a harmonia: boogie 1-3-5-6-b7, shuffle 1-5-6, boogie em
  semínimas, slow blues 12/8, walking bass de jazz (gerado nota a nota, com aproximações
  cromáticas), "em dois", bossa, Bo Diddley, afro 6/8 e outros. Timbre elétrico ou acústico
  e região grave/normal/aguda (a aguda ajuda em alto-falante de celular).
- **Progressões**: blues de 12 compassos, quick change, 8 compassos, blues menor, jazz
  blues, ii–V–I, I–IV–V–IV, I–V–vi–IV e vamps de um acorde, em **qualquer tom**. A tela
  mostra o acorde atual e o próximo, e a grade da progressão acende o compasso atual.
- **Viradas** no fim da progressão e prato de ataque na volta; **humanização** de dinâmica.
- **Mixer**: bumbo, caixa, pratos, percussão, baixo e clique guia por cima da levada.
- Todas as levadas têm volume equalizado (≈ −16 LUFS com instrumentos reais) para trocar sem sustos.

**Treino**
- Contagem inicial de 1, 2 ou 4 compassos.
- **Treinador de andamento**: sobe/desce X BPM a cada N compassos até o alvo.
- **Compassos em silêncio**: toca N compassos e silencia M — treina o tempo interno.
- **Timer de estudo** (5 a 60 min).

**Outros**
- **Presets/setlist**: salve andamento, levada, tom, progressão, mixer e treinos por música.
- Toca **em segundo plano** e com a tela apagada (notificação com botão Parar).
- Por padrão **toca junto com outros apps** (para acompanhar gravações); pode pausá-los.
- Para sozinho em ligações e ao desconectar o fone.
- Mantém a tela ligada enquanto toca; opção de piscar a tela no tempo 1.

## Instrumentos reais

Todos os samples vêm de bibliotecas **CC0 (domínio público)** — podem ser usados, modificados e
redistribuídos sem restrições:

| Som | Biblioteca | O que foi usado |
|---|---|---|
| Bateria (baquetas) | [Virtuosity Drums](https://github.com/sfzinstruments/virtuosity_drums) — Versilian Studios & Karoryfer Samples | Bumbo (4 camadas), caixa (8 de 36 camadas), cross-stick, chimbal fechado/aberto/pé, condução, cúpula, ataque e 2 tons; microfones do bumbo, da caixa e overhead mixados em mono |
| Vassourinhas | [Big Rusty Drums](https://github.com/sfzinstruments/karoryfer.big-rusty-drums) — Karoryfer Samples | Toque e varrida de vassourinha na caixa |
| Baixo elétrico | [Black And Blue Basses](https://github.com/sfzinstruments/karoryfer.black-and-blue-basses) ("darkblack") — Karoryfer Samples | Notas a cada 3 semitons, 2 dinâmicas |
| Contrabaixo | [Meatbass](https://github.com/sfzinstruments/karoryfer.meatbass) — Karoryfer Samples | Pizzicato de um Otto Rubner de 1958, notas a cada 3 semitons, 2 dinâmicas |
| Percussão | [VCSL](https://github.com/sgossner/VCSL) — Versilian Community Sample Library | Cowbell, shaker, pandeirola e claves |

O script [`tools/build_samples.py`](tools/build_samples.py) baixa somente os arquivos necessários,
mixa os microfones, recorta, mede a afinação de cada nota de baixo (corrige o desvio fino em
cents) e gera `app/src/main/assets/samples/` (formato próprio "BTS1": PCM 16 bits mono, lido em
Kotlin puro). Para regenerar: `python3 tools/build_samples.py` (precisa de git, ffmpeg e numpy).

## Levadas incluídas

| Gênero | Levada | Compasso | BPM sugerido | Baixo | Progressão padrão |
|---|---|---|---|---|---|
| Blues | Shuffle de Chicago | 4/4 | 116 | Shuffle 1-5-6 | Blues 12 compassos |
| Blues | Shuffle Texano (double shuffle) | 4/4 | 126 | Boogie 1-3-5-6-b7 | Blues 12 compassos |
| Blues | Boogie-Woogie | 4/4 | 150 | Boogie 1-3-5-6-b7 | Blues 12 compassos |
| Blues | Boogie de um acorde | 4/4 | 168 | Shuffle 1-5-6 | Vamp em I7 (um acorde) |
| Blues | Slow Blues 12/8 | 12/8 | 58 | Slow blues 12/8 | Blues 12 compassos |
| Blues | Jump Blues | 4/4 | 172 | Walking bass (jazz) | Blues 12 compassos |
| Blues | Train Beat (trem) | 4/4 | 140 | Boogie em semínimas | Blues 12 compassos |
| Blues | Swamp Blues (Louisiana) | 4/4 | 96 | Shuffle 1-5-6 | Blues 12 compassos |
| Blues | Bo Diddley Beat | 4/4 | 112 | Bo Diddley | Vamp em I7 (um acorde) |
| Blues | Blues Rock (colcheias retas) | 4/4 | 120 | Tônica em colcheias | Blues 12 compassos |
| Blues | Blues Menor (16ths) | 4/4 | 88 | Groove menor (16ths) | Blues menor 12 |
| Rock | Rock Básico | 4/4 | 110 | Tônica em colcheias | Rock I–IV–V–IV |
| Rock | Rock com Bumbo Sincopado | 4/4 | 120 | Tônica em colcheias | Rock I–IV–V–IV |
| Rock | Rock em Semicolcheias | 4/4 | 92 | Tônica em colcheias | I–V–vi–IV |
| Rock | Hard Rock (condução) | 4/4 | 132 | Tônica em colcheias | Rock I–IV–V–IV |
| Rock | Meio-Tempo (half-time) | 4/4 | 140 | Tônica nos tempos | I–V–vi–IV |
| Rock | Rock'n'Roll (Chuck Berry) | 4/4 | 164 | Shuffle 1-5-6 | Blues 12 compassos |
| Rock | Rockabilly | 4/4 | 180 | Boogie em semínimas | Blues 12 compassos |
| Rock | Shuffle Meio-Tempo (Purdie) | 4/4 | 90 | Shuffle 1-5-6 | Blues 12 compassos |
| Rock | Balada 6/8 | 6/8 | 58 | Tônica nos tempos | I–V–vi–IV |
| Rock | Punk (polca) | 4/4 | 190 | Tônica em colcheias | Rock I–IV–V–IV |
| Jazz | Swing Médio | 4/4 | 140 | Walking bass (jazz) | Jazz blues |
| Jazz | Bebop (rápido) | 4/4 | 240 | Walking bass (jazz) | Jazz blues |
| Jazz | Shuffle Kansas City | 4/4 | 150 | Walking bass (jazz) | Blues 12 compassos |
| Jazz | Swing com Vassourinhas | 4/4 | 132 | Walking bass (jazz) | Jazz blues |
| Jazz | Balada com Vassourinhas | 4/4 | 64 | Em dois (mínimas) | ii–V–I maior |
| Jazz | Two-Feel (em dois) | 4/4 | 120 | Em dois (mínimas) | Jazz blues |
| Jazz | Valsa Jazz 3/4 | 3/4 | 150 | Walking bass (jazz) | Jazz blues |
| Jazz | Bossa Nova | 4/4 | 132 | Bossa nova | ii–V–I maior |
| Jazz | Afro 6/8 | 12/8 | 100 | Afro 6/8 | Vamp menor (im7) |

## Instalar

Baixe o APK (artefato **BluesTempo-debug-apk** do GitHub Actions, aba *Actions* do
repositório) e abra no celular. Android 8.0 (API 26) ou superior. Talvez seja preciso
permitir "instalar apps de fontes desconhecidas".

## Compilar

Requisitos: JDK 17+ e Android SDK (API 35). Pelo Android Studio, basta abrir a pasta
`metronomo-android`. Pela linha de comando:

```bash
cd metronomo-android
./gradlew assembleDebug        # APK em app/build/outputs/apk/debug/
./gradlew testDebugUnitTest    # testes (motor de áudio, instrumentos reais, teoria musical e o app com Robolectric)
./gradlew assembleRelease      # APK minificado (precisa configurar a assinatura)
```

Para ouvir as levadas no computador, gere WAVs de demonstração:

```bash
BLUESTEMPO_DEMO_DIR=/tmp/demos ./gradlew testDebugUnitTest --tests '*DemoRenderer*'
```

## Arquitetura

```
app/src/main/java/com/redelar/bluestempo/
├── model/      Teoria musical e dados: levadas (DSL), progressões, baixo, configurações
├── audio/      Motor de áudio
│   ├── SampleLibrary.kt  carrega os instrumentos reais (camadas de dinâmica, notas de baixo)
│   ├── SoundBank.kt   escolhe a camada gravada certa; síntese de reserva e cliques
│   ├── Voices.kt      reprodutor polifônico, baixo por samples (transposição cúbica) e baixo sintetizado
│   ├── Sequencer.kt   agenda os eventos tempo a tempo, swing, viradas, baixo e treinos
│   └── AudioEngine.kt thread de áudio + AudioTrack (float estéreo) e sincronia visual
├── service/    Foco de áudio, ligações, fone desconectado e serviço em primeiro plano
├── data/       Persistência (SharedPreferences + kotlinx.serialization)
└── ui/         Jetpack Compose (Material 3): painel de transporte e 4 abas
```

- O **Sequencer** é Kotlin puro (sem Android) e gera os eventos de cada tempo no momento em
  que ele começa; por isso mudanças de andamento valem imediatamente e o tempo é exato.
- O **swing** é uma deformação do tempo dentro de cada par de colcheias (ou semicolcheias):
  50% = reto, 66,7% = tercina (shuffle), 75% = pontuado.
- A posição mostrada na tela vem do **timestamp de apresentação** do AudioTrack, então o
  visual acompanha o som que realmente sai do alto-falante.

### Criando uma levada

As levadas ficam em `model/Groove.kt`, escritas com um caractere por passo
(`X` forte, `x` normal, `o` fraco, `g` nota fantasma, `-` pausa):

```kotlin
groove(
    "chicago_shuffle", "Shuffle de Chicago", Genre.BLUES, "Descrição…",
    bpm = 116, bass = BassStyle.SHUFFLE_156, progression = ProgressionType.BLUES_12, swing = 0.67f,
) {
    hat("Xo xo Xo xo")      // 4 tempos × 2 passos (colcheias com swing)
    snare("-- X- -- Xg")
    kick("x- -- x- --")
}
```

Linhas de baixo usam graus relativos ao acorde (`R 3 5 6 7 6 5 3`; `~` prolonga,
`.` é pausa, `b`/`#` alteram, `,`/`'` mudam a oitava) em `model/Bass.kt`.
