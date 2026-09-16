# 00 — Visão Geral

## O problema

O conectoma da *Drosophila* é um grafo estático: 139.255 neurônios, 54,5 milhões de
sinapses. Ele diz **quem conecta com quem**, não **o que acontece**. A pergunta do
projeto é a segunda: dado o diagrama de fiação real de um cérebro, que comportamento
emerge quando ele é ligado a um corpo?

O Minecraft entra como **corpo e ambiente**: um mob nativo (abelha) fornece sensores
(luz, proximidade, dano) e atuadores (movimento, rotação, voo) já existentes e com
física consistente. É um laboratório barato para embodiment.

## O que este projeto é

Um sistema de três partes acopladas por contratos explícitos:

1. Um **grafo validado** extraído da fonte científica, com proveniência rastreável.
2. Um **simulador** que integra esse grafo no tempo e produz disparos.
3. Uma **encarnação** que traduz disparos em comportamento observável de um mob.

## O que este projeto não é

- **Não é um modelo biofísico realista.** Usamos integrate-and-fire com pesos =
  contagem de sinapses. Não há canais iônicos, dendritos, plasticidade ou neuromodulação.
- **Não é uma mosca.** O corpo é uma abelha do Minecraft, com física de jogo. Qualquer
  conclusão comportamental é sobre o *acoplamento*, não sobre etologia de *Drosophila*.
- **Não é um mod de gameplay.** A abelha não fica "mais inteligente" — fica *dirigida por
  um circuito real*, o que é diferente e frequentemente pior em termos de jogo.
- **Não produz dado novo de neurociência.** Consome o conectoma; não o corrige.

Essas fronteiras existem para evitar a armadilha mais comum em projetos assim:
confundir "a simulação fez algo interessante" com "descobrimos algo sobre o cérebro".

## Pergunta de pesquisa da v1

> O circuito ocelar real, alimentado por luz do Minecraft e ligado aos 92 neurônios
> descendentes, produz uma resposta de estabilização/orientação distinguível de ruído?

O circuito ocelar biológico serve para controle de voo e orientação em relação ao
horizonte. Se o acoplamento estiver correto, esperamos ver a abelha reagir a mudanças
de luminosidade de forma **não-aleatória e reprodutível**. Esse é o critério de sucesso.

## Critério de falsificação

Um experimento de **lesão** define se há sinal ou teatro: silenciar os 273 fotorreceptores
deve mudar o comportamento de forma mensurável. Se o comportamento for idêntico com e sem
entrada sensorial, a simulação não está acoplada — está apenas gerando movimento.

Este teste roda na Fase 4 e é obrigatório antes de qualquer afirmação sobre resultados.
