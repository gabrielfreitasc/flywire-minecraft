# 05 — Resultados (F0–F5)

Relato coerente do que foi feito e do que foi encontrado. Números e datas são reais,
reproduzíveis pelas ferramentas citadas — nada aqui é resumo por memória.

---

## Pergunta de pesquisa e resposta

`docs/00-visao-geral.md` formulou a pergunta da v1:

> O circuito ocelar real, alimentado por luz do Minecraft e ligado aos 92 neurônios
> descendentes, produz uma resposta de estabilização/orientação distinguível de ruído?

**Resposta: o acoplamento é real, a resposta específica não foi caracterizada.**

O experimento de lesão (critério de falsificação definido em `00-visao-geral.md`)
mostrou diferença estatisticamente significativa entre abelha com fotorreceptores
normais e cegos — Mann-Whitney U, **p = 0,0014** (N=20 trials, distância percorrida
31,26 ± 1,60 blocos normal vs. 27,56 ± 5,08 lesionado). Isso responde a metade da
pergunta: **existe sinal, não é teatro.**

A outra metade — se é especificamente "estabilização" ou "orientação" — não está
provada. O `MotorMapping` atual só controla magnitude de avanço (velocidade na
direção que a abelha já está olhando), não direção. RN-08 (curadoria de função por
tipo celular) está 13/46 tipos resolvida; nenhum dos tipos resolvidos dá controle de
yaw/lift. Então: sabemos que o circuito muda o comportamento; não sabemos ainda que
tipo de mudança comportamental é.

## Linha do tempo por fase

| Fase | Entregável central | Critério de saída |
|---|---|---|
| F0 | Subcircuito ocelar extraído e validado | ✅ 625 nós, 2.981 arestas, checksums registrados |
| F1 | Núcleo de simulação (LIF + engine) | ✅ RN-09 (Mann-Whitney p=0,0028 no grupo inibitório) |
| F2 | Decodificação motora + telemetria | ✅ Run de 10s gravado, consulta SQL funcional |
| F3 | Ponte TCP + scaffold do plugin | ✅ 1500 trocas / 0 falhas; validado em Docker real |
| F4 | Loop de controle real + experimento de lesão | ✅ Mann-Whitney p=0,0014 |
| F5 | Observabilidade (visualização, lesão/estímulo por comando) | ✅ Confirmado em servidor com jogador |

Detalhe de cada fase em `docs/03-roadmap-fases.md`.

## Achados que mudaram o rumo do projeto

Nenhum destes estava previsto no design original — todos vieram de testar e medir,
não de teoria:

1. **Fotorreceptores 100% inibitórios tornam o modelo matematicamente incapaz de
   disparar fora da camada sensorial sem corrente de base** (RN-09, F1). Não é
   questão de calibrar ganho — é impossibilidade do modelo em repouso absoluto.
   Corrigido com `BIAS_CURRENT` + `NOISE_STD` (o ruído quebra a sincronia artificial
   que o bias sozinho causaria).

2. **Agregar/tirar média de descendentes com respostas opostas cancela o sinal —
   aconteceu 3 vezes.** RN-09 (F1): 29 excitatórios vs. 63 inibitórios se cancelam
   na média. Primeiro experimento de lesão (F4): mesmo erro repetido com os 8 grupos
   por prefixo. RN-08 (F6): somar `locomotion_drive` (não responde à luz) em
   `phototaxis` (responde) diluiu o sinal validado — confirmado mesmo controlando o
   confundidor de local (dentro de casa vs. ao ar livre, ambos nulos). **Princípio
   geral registrado:** antes de combinar dois canais, perguntar se os dois respondem
   ao mesmo estímulo que está sendo testado.

3. **`setAI(false)` numa abelha voadora trava o movimento inteiro, não só a
   decisão** (F4). O plano original ("desabilitar IA antes de aplicar vetor motor")
   estava errado na direção oposta: o certo é manter IA ligada e sobrescrever
   velocidade a cada tick, que domina a decisão nativa sem precisar desligar nada.

4. **RN-08 (mapear descendente → comportamento) não é tradução automática, mesmo
   com dado publicado.** Namiki et al. 2018 deu 12 tipos com categoria real; um 13º
   veio de cruzar `hemibrain_type` (Schlegel et al. 2024). Mas "Anterior Movements"
   e "Wing & Abdomen Movements" são comportamento de mosca **andando** — não têm
   tradução validada pra voo de abelha, então ficam expostos só pra
   telemetria/exploração, não usados no controle motor.

5. **A IA nativa da abelha não é só ruído de fundo — mascarava um efeito real**
   (F6). Experimento dia/noite deu nulo em 3 rodadas com IA nativa ligada
   (p≥0,29). `setAI(false)` continuava fora de cogitação (achado 3 acima); a Mob
   Goal API do Paper (`Bukkit.getMobGoals()`) remove objetivos específicos
   (voltar pra colmeia, vagar, polinizar) sem travar física — testado isolado
   antes de integrar (mesma disciplina do achado 3). Com isso, o efeito apareceu
   e replicou em 2 locais (p=0,00184). Direção veio invertida do esperado (menos
   luz → mais distância) — não explicada, registrada como pergunta aberta, não
   como conclusão inventada.

## O que está validado (pode ser citado com confiança)

- **Extração e integridade do subcircuito** (F0): 625 nós, 2.981 arestas, validado
  aresta por aresta contra a fonte primária (AD-11), checksums em `manifest.json`.
- **RN-09** (F1): corrente de base + ruído necessários e suficientes para o circuito
  sair do silêncio; efeito do estímulo de luz mensurável no grupo inibitório
  (p=0,0028, `tools/calibration_check.py`).
- **Acoplamento luz → comportamento** (F4): Mann-Whitney p=0,0014, reproduzível via
  `/flywirebee lesion` + `sim/tools/lesion_analysis.py`.
- **Ponte assíncrona sem perda de frame** (F3): 1500 trocas / 0 falhas em 58s reais,
  ~26 Hz efetivo, RN-06 respeitada (rede nunca bloqueia o tick do jogo).
- **18 tipos de descendente com função publicada** (RN-08/AD-14+AD-15): Namiki et al.
  2018 + BANC connectome, tabela completa em `docs/04-regras-de-negocio.md`.
- **Resposta a dia/noite** (F6): Mann-Whitney p=0,00184, reproduzível via
  `/flywirebee goals off` + `control start` + `daynight` +
  `sim/tools/daynight_analysis.py` — **só aparece com `goals off`** (IA nativa
  mascara o efeito quando ligada, ver limitação abaixo). Direção do efeito
  (menos luz → mais distância) não está explicada.

## O que NÃO está provado (não afirmar isso)

- **Que o comportamento é "orientação" ou "estabilização" especificamente.** Só
  provamos que existe efeito mensurável na velocidade — a semântica direcional
  (RN-08 completa) não existe ainda.
- **Que o acoplamento é forte ou visualmente óbvio.** O tamanho do efeito é
  discreto (~12% de diferença de distância) — perceptível estatisticamente, não a
  olho nu. A abelha ainda parece "abelha normal fazendo coisa de abelha" na maior
  parte do tempo, porque a IA nativa continua controlando direção/pouso/polinização
  — só a velocidade responde ao circuito.
- **Que o modelo é biofisicamente realista.** LIF com peso = contagem de sinapse,
  sem canais iônicos, dendritos ou neuromodulação real (ver `00-visao-geral.md`).
- **Qualquer conclusão sobre *Drosophila* real.** O corpo é abelha do Minecraft, com
  física de jogo. O resultado é sobre o *acoplamento*, não sobre etologia de mosca.

## Dívida técnica e limitações conhecidas

| Item | Descrição | Onde tratado |
|---|---|---|
| DT-1 | Dados vêm de espelho GitHub, não da fonte primária (parcialmente fechado, AD-11) | `docs/01-camada-de-dados.md` |
| DT-2 | Regra de sinal (RN-01) herdada de Shiu et al., não validada por nós | `docs/04-regras-de-negocio.md` |
| DT-3 | Override de histamina (RN-02) é hipótese nossa, não dado direto | `docs/04-regras-de-negocio.md` |
| RN-08 parcial | 29 de 47 tipos de descendente sem função publicada localizável (27 têm cluster de conectividade, evidência mais fraca) | `docs/04-regras-de-negocio.md` |
| N pequeno | Experimentos de lesão e dia/noite validados com N=20; mais trials fortaleceriam a conclusão | `plugin/README.md` |
| Ruído de IA nativa | **Não é só ruído simétrico — mascara efeito real.** Confirmado na F6: dia/noite deu nulo em 3 rodadas com IA nativa ligada (p≥0,29) e virou significativo (p=0,00184) removendo os goals que competem via Mob Goal API (`/flywirebee goals off`). Experimentos anteriores (F4, lesão) tiveram esse ruído mas ainda assim deram significância — não invalida os resultados já publicados, mas sugere que o tamanho de efeito real pode ser maior do que o medido com IA ligada. | `plugin/README.md`, `docs/03-roadmap-fases.md` F6 |
| Direção do efeito dia/noite não explicada | Menos luz produziu MAIS distância, não menos — hipótese de resposta não-monotônica levantada, não testada | `docs/03-roadmap-fases.md` F6 |

## Trabalho futuro (não iniciado)

- **RN-08 completa** — curadoria de direção (yaw/lift), não só magnitude. Exigiria
  achar pares agonista/antagonista publicados, o que não apareceu na pesquisa até
  agora.
- **Multi-sensor** (chuva, toque) — toque exige extrair um circuito mecanossensorial
  inteiro, diferente do ocelar. Ver `docs/03-roadmap-fases.md`, seção "Fora de escopo".
  **Dia/noite saiu desta lista:** feito na F6, com um giro de dois atos. Primeiro deu
  nulo (a premissa original — "`dorsal_light` é quase de graça" — também estava
  tecnicamente errada, `dorsal_light` não varia com a hora). Investigando por que, a
  hipótese de que a IA nativa mascarava o efeito se confirmou: com `/flywirebee
  goals off`, o efeito aparece e replica (p=0,00184). Ver F6 em
  `docs/03-roadmap-fases.md`.
- **Explicar a direção do efeito dia/noite** (novo, da F6) — menos luz produziu MAIS
  distância, o oposto do esperado. Candidato: resposta não-monotônica à luz (luz
  zero, medida na F4, deu a MENOR distância das três condições já vistas). Só um
  teste de dose-resposta (luz 0 / 0,25 / 0,5 / 1,0 sorteada trial a trial, `goals
  off`, origem fixa) decide.
- **Repetir a lesão da F4 com `goals off`** — se a IA nativa mascarava o efeito
  dia/noite, pode estar diluindo também o tamanho do efeito de lesão medido (p=0,0014
  já é significativo, mas o tamanho pode estar subestimado).
- **Anatomia real** (posição 3D, agrupamento por neurópilo) — exige o arquivo de
  9,5GB da Zenodo (AD-11), já baixado mas não processado.
- **Circuito de 2 saltos** (10.578 neurônios) — v2, muda a escala do problema
  inteiro.

## Como reproduzir os números citados aqui

```
cd sim
python -m pytest tests/            # 20/20 testes
python tools/calibration_check.py  # RN-09: p=0,0028
```
Experimento de lesão exige servidor Minecraft + plugin rodando (ver
`mc-server/README.md`, `plugin/README.md`):
```
/flywirebee control start
/flywirebee lesion
```
depois, em `sim/`:
```
python tools/lesion_analysis.py
```
