# Cronômetro de Treino (projeto Android)

App Android mínimo: um WebView em tela cheia com a página offline do cronômetro
(`app/src/main/assets/index.html`, fontes em `assets/fonts`). Tela sempre ligada,
sem internet, única permissão: vibrar. Configurações e treinos ficam salvos no aparelho.

## Gerar o APK no seu computador

1. Instale o Android Studio (gratuito) e abra esta pasta (`File > Open`).
2. Espere o "Gradle sync" terminar (ele baixa o que precisa sozinho).
3. Menu `Build > Build Bundle(s) / APK(s) > Build APK(s)`.
4. Quando terminar, clique em `locate` na notificação. O arquivo é `app/build/outputs/apk/debug/app-debug.apk`.
5. Passe o arquivo para o celular (cabo, Drive, e-mail), abra e instale.
   Se o Android pedir, permita "instalar apps desconhecidos" para o app que abriu o arquivo.

Pela linha de comando, com Gradle 8.7+ e o Android SDK instalados: `gradle assembleDebug`.

## Gerar o APK sem instalar nada (GitHub)

1. Crie um repositório seu no GitHub (pode ser privado) e envie o conteúdo desta pasta, incluindo `.github/workflows/build-apk.yml`.
2. Na aba `Actions`, escolha "Gerar APK" e clique em `Run workflow`.
3. Quando terminar, baixe o arquivo `cronometro-treino-apk` (um .zip com o `app-debug.apk` dentro) e instale como acima.

Cada build no GitHub usa uma chave nova. Para atualizar um app já instalado por essa via, desinstale antes (os dados do app são apagados).

## Observações

- Atualizar o app depois: gere o APK de novo com a mesma chave (o Android Studio usa sempre a mesma chave de debug no seu computador) e instale por cima. Os dados são mantidos.
- Os dados do app no celular são separados dos da versão publicada no Claude (conta). Treinos criados lá não aparecem aqui.
- O app mantém a tela ligada enquanto está aberto. Se você bloquear a tela ou trocar de app, o tempo continua contando, mas os sons podem não tocar até você voltar.
- O botão voltar pergunta se quer encerrar o treino; na configuração, ele só manda o app para segundo plano.
