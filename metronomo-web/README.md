# BluesTempo Web — metrônomo e levadas no navegador (iPhone, Android, computador)

Versão web do [BluesTempo](../metronomo-android/README.md): as mesmas 35 levadas de blues, rock e
jazz, a mesma setlist de 31 músicas com cifra compasso a compasso, os mesmos instrumentos reais (bateria, baixo elétrico, contrabaixo e percussão em domínio
público) e o mesmo motor de tempo do app Android, rodando no navegador com o Web Audio.

No iPhone, abra o endereço no Safari e use **Compartilhar → Adicionar à Tela de Início**: o
BluesTempo vira um ícone, abre em tela cheia e funciona offline depois do primeiro acesso.

## O que tem

- Metrônomo: 20–300 BPM, TAP, fórmulas de compasso, acentos por tempo, subdivisões com swing e
  5 sons de clique.
- Levadas com bateria e baixo reais, swing, viradas, humanização, 12 tons, 10 progressões
  (blues 12/8 compassos, quick change, menor, jazz blues…), 20 estilos de baixo e mixer.
- Músicas: 31 músicas com andamento, tom, levada e cifra compasso a compasso que acompanha a
  execução; transposição, andamento de estudo (50–100%), loop de uma parte e fim com acorde final.
- Treino: contagem inicial, treinador de andamento, compassos em silêncio e timer.
- Presets salvos no navegador.
- Mantém a tela ligada enquanto toca e toca mesmo com a chave de silencioso do iPhone ligada.

**Limitação do iPhone:** o som para quando a tela bloqueia ou quando você troca de app (o iOS
suspende o áudio de páginas web). Para tocar com a tela apagada, só um app nativo.

## Como funciona

- `src/data/music.json` é **gerado pelo app Android** (`WebDataExportTest`): levadas, progressões,
  linhas de baixo e constantes de mixagem vêm da mesma fonte, então as duas versões não divergem.
- `src/js/engine.js` é a porta do `Sequencer` do Android para o Web Audio. Os eventos são
  agendados no relógio do `AudioContext` (precisão de amostra) com um *worker* como despertador.
- `test/parity.mjs` roda o motor web e compara cada batida, clique e nota de baixo com a
  referência gerada pelo motor Kotlin (`ParityReferenceTest`) em 43 cenários (inclusive músicas
  com compassos irregulares, acordes invertidos, transposição e final): os 12.795 eventos caem no
  mesmo quadro de áudio.
- Os sons vêm dos samples do app Android, empacotados num único arquivo (`samples.bin`, 5 MB):
  PCM 16 bits em delta + gzip, sem perda nenhuma e sem depender de codec do navegador.

## Comandos

```bash
cd metronomo-web
npm ci
npm run build              # gera dist/ (site/PWA) e dist-artifact/ (página sem invólucro)
node test/parity.mjs       # paridade com o app Android
node test/smoke.mjs        # teste no navegador (Chromium/Playwright) + screenshots
npx serve dist             # ou qualquer servidor estático, para testar no celular
```

Se mudar levadas ou a mixagem no app Android, atualize os dados da versão web:

```bash
cd metronomo-android
BLUESTEMPO_UPDATE_WEB=1 ./gradlew testDebugUnitTest
```

## Publicação (GitHub Pages)

O workflow `.github/workflows/web.yml` compila, roda os testes e publica `dist/` no GitHub Pages
a cada push na branch principal. Na primeira vez, ative em **Settings → Pages → Source:
GitHub Actions**. O endereço fica `https://<usuário>.github.io/<repositório>/`.
