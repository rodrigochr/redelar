# HR-V Monitor

App Android para ler, por um ELM327, os dados do motor que importam para o projeto do
piggyback no HR-V 1.5 turbo. Mostra os valores ao vivo, grava a sessão e resume cada
aceleração forte ("puxada").

## O que ele lê

| Dado | PID | Para quê |
|---|---|---|
| Rotação | 0x0C | Referência de todas as leituras |
| λ medido (sonda 1, banda larga) | 0x24 ou 0x34 | Ver se a mistura empobrece sob turbo |
| λ comandado | 0x44 | Comparar o que a ECU pediu com o que aconteceu |
| Pressão do turbo | 0x70, 0x87 ou 0x0B | Sensor de boost; o 0x0B usa a escala do 0x4F |
| Wastegate | 0x72 | Posição real e comandada |
| Avanço de ignição | 0x0E | Quedas bruscas indicam detonação |
| Pressão do rail | 0x23 ou 0x59 | Limite da bomba de alta pressão |
| Ar de admissão (0x0F ou 0x68, até 2 sensores), motor (0x05 ou 0x67), trims, etanol, carga, velocidade, MAF, pedal | vários | Contexto |

O app pergunta à ECU quais PIDs ela suporta e só pede esses. Ele tenta 6 PIDs por pedido,
que é bem mais rápido. Se o pedido travar o adaptador (comum em clones do ELM327) ou voltar
vazio, cai para 3 e depois para 1 PID por pedido. PIDs que a ECU diz suportar mas nunca
respondem saem da leitura.

Os pedidos vão só para a central do motor. O app liga os cabeçalhos, vê quem responde ao
0100 e passa a endereçar a central (7E8 → 7E0 em CAN 11 bits; 18DAF110 → DA10F1 em 29 bits,
como no HR-V 2024), com o filtro de recepção (`ATCRA`) explícito. Se o adaptador não aceitar,
manda o pedido para todos mas só ouve a central. Se nem isso der, liga os cabeçalhos (`ATH1`)
e separa ele mesmo os quadros da central, remontando as mensagens de vários quadros. Sem
nada disso, câmbio e outros módulos respondem junto e clones do ELM327 embaralham as
respostas; nesse último caso o app pede um PID por vez.

## Puxadas

Uma puxada começa quando a pressão do turbo fica acima de 0,25 bar por 0,8 s e termina
quando fica abaixo por 0,7 s. O resumo usa só leituras recentes (até 2 s); λ, rail e avanço
são avaliados no miolo da puxada, depois do primeiro segundo e antes de a borboleta fechar.
Para cada uma, o app guarda rotação, pressão máxima e alvo, λ medido e pedido, faixa de
avanço, pressão do rail, wastegate e temperaturas do ar, e avisa se:

- o λ medido ficar mais de 0,05 acima do λ pedido pela central (0x44), com mais de 0,3 bar,
  em duas leituras seguidas. O 1.5 turbo do HR-V roda em λ 1,00 com boa parte do turbo e só
  enriquece perto do limite, então um limite fixo de λ daria alarme falso;
- a pressão do rail cair mais de 15% depois do pico, ainda com o pé no fundo;
- o avanço cair mais de 3° entre leituras com até 0,35 s de distância, com a rotação subindo;
- o MAP do PID 0x0B ficar no limite da escala;
- a puxada tiver menos de 8 leituras (alertas pouco confiáveis).

## Como usar

1. Pareie o ELM327 nas configurações de Bluetooth do celular.
2. Abra o app, toque em **Conectar** e escolha o adaptador. Para ELM327 Wi-Fi, conecte o
   celular à rede dele antes e escolha a opção Wi-Fi.
3. **Salvar log** grava um CSV e um resumo em `Downloads/HRV Monitor` e abre o
   compartilhamento.
4. **Copiar relatório** copia o resumo (PIDs suportados, puxadas e o registro da
   comunicação com o adaptador) para colar numa conversa.

Não mexa no celular dirigindo: deixe gravando e confira depois.

## Compilar

Não usa Gradle. Com o Android SDK (`platforms;android-35` e `build-tools;35.0.1`):

```
./build.sh   # gera build/hrv-monitor.apk
./test.sh    # roda a lógica contra um ELM327 simulado na JVM (inclui um clone que trava)
```

O `debug.keystore` fica no repositório de propósito, para que cada versão nova instale
por cima da anterior sem precisar desinstalar.
