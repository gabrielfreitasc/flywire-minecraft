# Convenções — FlyWire on Minecraft

> Espelho de `CONVENCOES.md` (fonte no repositório) — atualize os dois juntos.

Leia antes de escrever qualquer código neste repositório.

## Contexto em uma frase

Simulação do conectoma real da *Drosophila* (FlyWire 783) encarnada numa abelha do
Minecraft. Subcircuito ocelar: 625 neurônios, 2.981 conexões.

## Leitura obrigatória antes de mexer

| Vai mexer em | Leia antes |
|---|---|
| Qualquer coisa | `docs/04-regras-de-negocio.md` |
| `ingest.py`, `graph.py` | `docs/01-camada-de-dados.md` |
| `engine.py`, `server.py` | `docs/02-arquitetura.md` (seção do relógio) |
| Plugin Java | `docs/02-arquitetura.md` (contratos) + `plugin/README.md` (estado atual, achados de F4) |
| `motor.py`, `MotorMapping.java` | RN-08 e RN-09 em `docs/04-regras-de-negocio.md` — agregar descendentes sem separar por sinal cancela o efeito |

## Regras duras

1. **`data/raw/` é imutável.** Nunca editar, limpar ou sobrescrever. Transformação lê
   daqui e escreve em `interim/` ou `processed/`.
2. **Nunca contornar a validação do ingest.** Se os totais não batem com o artigo, o dado
   está errado — não o check. Números exigidos em `data/raw/README.md`.
3. **`root_id` não sobe de L1.** Acima da camada de dados só existe `nid` (RN-05).
4. **Regra de negócio muda com ADR.** Alterar RN-01…RN-09 exige registro em `docs/adr/`.
   Não mude um limiar "só para testar" e deixe commitado.
5. **O tick do jogo nunca bloqueia esperando o simulador** (RN-06).
6. **Não afirmar resultado sem o teste de lesão.** Ver `docs/00-visao-geral.md`.

## Estilo

- Python 3.11, type hints obrigatórios em fronteiras de módulo, `ruff` + `pytest`.
- Sem classe onde função pura resolve. O engine é estado; o resto não deveria ser.
- Nome de variável em inglês; comentário e documentação em português.
- Toda constante científica (limiar, dt, refratário) vive em `config.py`, nunca inline.

## Armadilhas conhecidas

- **Glutamato é inibitório em *Drosophila*** (GluCl). Não portar intuição de mamífero.
- **Fotorreceptores vêm rotulados como serotonina.** É artefato; ver RN-02.
- **Parâmetros do LIF de Shiu et al. foram ajustados para 139k neurônios.** Num
  subcircuito de 625 a rede pode ficar silenciosa sem corrente de base — ver RN-09.
- **`setAI(false)` na abelha CONGELA o movimento, não só a decisão.** Testado (F4):
  `setVelocity()` a cada tick com IA desligada produziu deslocamento ZERO em 5s reais.
  O certo é o oposto do que parece intuitivo: manter a IA **ligada** e sobrescrever a
  velocidade a cada tick — isso domina a decisão nativa sem precisar desligar nada. Ver
  `plugin/README.md`, seção "Risco investigado".
- **Somar/tirar média de canais com fontes diferentes dilui ou cancela o efeito que
  você quer medir — já aconteceu 3 vezes.** (1) RN-09/F1: agregar os 92 descendentes
  cancela sinal de luz (29 excitatório, 63 inibitório, direções opostas). (2) F4,
  primeiro experimento de lesão: mesmo erro repetido com a média dos 8 grupos por
  prefixo. (3) RN-08/F6: somar `locomotion_drive` (não responde à luz) em
  `phototaxis` (responde) deu nulo de novo (p=0,43), confirmado que não era
  confundidor de local (testado dentro de casa E ao ar livre, ambos nulos). **Antes
  de combinar dois canais motores, pergunte: os dois respondem ao MESMO estímulo que
  você está testando? Se não, a combinação vai diluir, não somar.** Ver RN-08/RN-09
  em `docs/04-regras-de-negocio.md`.
- **`String.format`/`printf` com `%f` usa o locale padrão da JVM.** Em servidor pt_BR,
  vírgula é separador decimal — corrompe qualquer CSV silenciosamente (vírgula decimal
  colide com vírgula de coluna). Sempre `Locale.ROOT` em código que escreve arquivo.
- **`Block.getLightFromSky()` (nosso `dorsal_light`) NÃO varia com hora do dia — só com
  exposição ao céu.** Confirmado na Minecraft Wiki (F6, 16/09/2026): o skylight bruto por
  bloco fica travado em 15 ao ar livre em qualquer hora, dia ou noite. Quem escurece à
  noite é o "internal sky light" (meio-dia=15, meia-noite=4), calculado à parte a partir
  do skylight bruto + hora do mundo — e é isso que `Block.getLightLevel()` (nosso `light`)
  já incorpora. **Contra-intuitivo, ao contrário do que os nomes sugerem:** `light` é o
  canal sensível a dia/noite; `dorsal_light` é sensível a teto/céu aberto (indoor vs.
  outdoor), não a hora do dia. Não trocar um pelo outro esperando pegar dia/noite — usar
  `dorsal_light` só como filtro de "a abelha está mesmo ao ar livre?" antes de comparar
  dia vs. noite via `light`. Ver F6 em `docs/03-roadmap-fases.md` e `plugin/README.md`.
- **Nunca comparar rodadas de experimento feitas em origens diferentes.** Medido (F6,
  16/09/2026): duas rodadas na MESMA condição (abelha cega), a 70 blocos uma da outra,
  diferiram 9 blocos de trajetória (p&lt;0,001) — só terreno. Isso é maior que o efeito
  de lesão da F4 (3,7 blocos). Uma comparação normal × cega entre rodadas pareceu dar
  efeito forte (~10 blocos) e era quase toda local. **Toda condição comparada tem que
  ser sorteada trial a trial dentro da MESMA rodada, mesma origem** — como
  `LesionExperiment` e `DayNightExperiment` já fazem internamente. A origem é a posição
  da **abelha** no comando, não a do jogador.
- **A IA nativa da abelha é uma fonte de ruído maior do que parecia.** Medido (F6,
  17/09/2026): removendo os objetivos de IA que competem com locomoção via Mob Goal
  API do Paper (`Bukkit.getMobGoals().removeGoal(...)`, não `setAI(false)` — que
  continua travando física, ver acima), o desvio-padrão da trajetória caiu de
  ~0,6–8,2 blocos pra **0,22** entre trials da mesma condição. A API funciona sem
  travar (testado isolado antes de integrar: 96% do deslocamento esperado, igual ao
  modo com IA ligada). `BEE_GO_TO_HIVE`/`BEE_LOCATE_HIVE`/`BEE_ENTER_HIVE` (voltar pra
  colmeia à noite) eram candidatos a confundidor de dia/noite; `BEE_WANDER`/
  `BEE_GO_TO_KNOWN_FLOWER`/`BEE_POLLINATE` competem com locomoção em geral. **Sem
  volta pela API pública** — abelha precisa ser respawnada pra ter os goals padrão de
  volta. Ver `CompetingGoals.java`, `/flywirebee goals off`, `docs/03-roadmap-fases.md`
  (F6). **Confirmado (17/09/2026, réplica em 2 locais):** com IA ligada, dia/noite deu
  nulo em 3 rodadas independentes (p≥0,29); com `goals off`, efeito real e replicado
  (p&lt;0,002 nos dois testes) — a IA nativa estava mesmo mascarando o sinal.
- **A resposta à luz não é monotônica — tem um pico em luz baixa, não é "mais luz =
  mais movimento".** Medido (F6, dose-resposta, 17/09/2026, N=32, `goals off`, mesma
  origem exata): luz 0,25 deu distância MAIOR que luz 1,0 (p=0,005), luz 0,5 voltou
  ao mesmo patamar de 1,0, luz 0 caiu abaixo de todos (p=0,0002). Kruskal-Wallis
  p=0,00003. Não afirmar "mais luz → mais phototaxis" sem checar — o achado
  "invertido" do dia/noite (light=0,25 vs 1,0) não era ruído nem confundidor, era essa
  curva. Mecanismo não explicado — hipótese candidata (não testada): as duas vias de
  sinal da topologia (29 excitatórios/desinibição vs. 63 inibitórios, RN-09) podem ter
  sensibilidade à intensidade de luz diferente uma da outra. Ver
  `docs/03-roadmap-fases.md` F6.
- **Deriva de IA nativa entre comandos manuais invalida origem, mesmo com um comando
  de teleporte dedicado.** Medido (F6, 17/09/2026): mesmo criando `/flywirebee goto
  <x> <y> <z>` pra reposicionar a abelha antes de um experimento, o tempo real de
  digitar o próximo comando já bastava pra IA nativa mover a abelha nas rodadas
  seguintes (24 e 38 blocos de erro, mesmo com `goto` rodado antes). **Só resolveu de
  verdade** quando o próprio comando do experimento passou a aceitar `x y z` e
  teleportar no mesmo instante de execução, sem intervalo nenhum. Regra geral: se um
  comando depende de posição exata, teleportar tem que acontecer DENTRO do mesmo
  comando que começa a medir, nunca num comando manual anterior.
- **Ruído de IA nativa não tem direção previsível — pode mascarar OU inflar um
  efeito, dependendo do experimento.** Medido (F6, 17/09/2026): no dia/noite, IA
  nativa ligada MASCARAVA o efeito (nulo com ela, p=0,00184 sem). Na repetição da
  lesão da F4 com `goals off`, foi o OPOSTO — o efeito ficou MENOR (4,4% vs. 12%
  da F4 original), não maior; a F4 tinha um outlier específico inflando a
  diferença, e ruído baixo revelou o efeito real mais modesto (porém mais
  confiável: Welch e Mann-Whitney concordaram, contra Welch só marginal na F4).
  **Não assumir a direção do viés a partir de outro experimento — medir de novo
  em cada caso.**
- **`Entity#setVelocity()` move a abelha, mas NÃO gira o corpo visual dela.**
  Medido (F6, 18-19/09/2026, achado do usuário): depois de implementar guinada
  real (`yaw_steering` rotacionando a direção de avanço), o resultado
  ESTATÍSTICO deu limpo (giro suave e contínuo, log confirma), mas a checagem
  VISUAL pareceu contradizer tudo — abelha "andando de ré", virando
  "esporadicamente". Causa: girar o corpo de um mob é responsabilidade da IA
  nativa/pathfinding (que ajusta yaw enquanto persegue um objetivo próprio),
  não do `setVelocity()`. Sobrescrever velocidade por código (a técnica que já
  domina a decisão nativa, ver achado do `setAI(false)` acima) não faz o corpo
  virar sozinho — os dois ficam dessincronizados. Corrigido com
  `bee.setRotation(yaw, pitch)` a cada tick, calculando yaw a partir do vetor
  de velocidade comandado. **Se o resultado medido bate com a estatística mas
  parece visualmente errado, suspeitar de corpo/velocidade dessincronizados
  antes de duvidar da estatística.** Ver `ControlLoop.java`,
  `docs/04-regras-de-negocio.md` RN-08.
- **Folha de árvore reduz luz mas NÃO bloqueia chuva no Minecraft.**
  Achado do usuário (F7, 23/09/2026): "buscar abrigo" (hygro) usava
  `Block#getLightFromSky()` como proxy de "tem teto aqui?" — folha reduz
  esse valor (dá sombra), então a abelha parava embaixo de árvore achando
  que tinha se abrigado. Mecânica real do jogo: chuva atravessa folhas
  ("goteja" através de copas desde uma atualização); existe até um
  heightmap dedicado, `MOTION_BLOCKING_NO_LEAVES`, cujo propósito é
  calcular exposição à chuva EXCLUINDO folhas (é o que o próprio jogo usa
  pra decidir onde mobs em chamas se apagam, por exemplo). **Luz e chuva
  são sinais diferentes** — não usar um como proxy do outro. Corrigido
  usando `World#getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES)`
  diretamente, a mesma métrica que o motor do jogo usa. Ver
  `ShelterSensor.java`, `docs/03-roadmap-fases.md` F7 (bug 9).

## Papéis no projeto

| Papel | Responsabilidade | Fronteira |
|---|---|---|
| **Curador de dados** | L0–L1: proveniência, validação, extração | Não opina sobre dinâmica |
| **Modelador** | L2–L3: grafo, LIF, parâmetros | Não toca no jogo |
| **Integrador** | L4–L5: contrato motor, ponte | Traduz, não interpreta |
| **Encarnador** | L6: plugin, sensores, atuadores | Não muda a simulação para "ficar bonito" |

O último ponto é o que mais importa: se o comportamento não emerge, **não se ajusta a
simulação para produzir comportamento**. Ajusta-se a hipótese, ou registra-se o resultado
negativo.
