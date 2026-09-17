# 04 — Regras de Negócio

Regras do domínio. Cada uma tem dono no código e teste correspondente. Mudar uma delas
muda resultado científico — não são detalhes de implementação.

---

## RN-01 · Sinal sináptico deriva do neurotransmissor

O conectoma **não traz** se uma conexão é excitatória ou inibitória. Derivamos da
predição de neurotransmissor (Eckstein et al. 2024):

| Neurotransmissor | Sinal |
|---|---|
| Acetilcolina, Dopamina, Octopamina | **+1** excitatório |
| GABA, Glutamato | **−1** inibitório |

Glutamato é inibitório em *Drosophila* via receptor GluCl — ao contrário de vertebrados.
Erro clássico de quem porta modelos de mamífero.

> **Isto é regra nossa, não dado da fonte.** Versionar. Onde: `graph.py::assign_sign`.

**Validação parcial (17/09/2026, DT-2 em `docs/05-resultados.md`).** Cruzado
`top_nt` predito (Eckstein et al. 2024) contra `gt_data.csv`
(flyconnectome/drosophila_neurotransmitters — dado de literatura, não predição de
classificador) para os 168 tipos celulares do subcircuito v1: **13 tipos têm
ground truth independente, 12 confirmam a predição exatamente** (`DNa10`, `DNb05`,
`DNp05`, `DNp06`, `DNp10`, `DNp102`, `DNp103`, `DNp18`, `DNp20`, `DNp28`, `PS091`,
`PS093`, `cM15`). **1 diverge** (`DNp27` — ver RN-01a abaixo, sem impacto no
resultado por outro motivo). Cobertura pequena (13/168, ~8%) — não valida RN-01
como um todo, mas é a primeira checagem independente real desde que a regra foi
adotada. Reproduzível cruzando `nodes.parquet::cell_type` contra `gt_data.csv`.

### RN-01a · Serotonina não está coberta ⚠️ em aberto

Serotonina é **neuromodulador**, não neurotransmissor rápido — não tem sinal óbvio.
Hoje esses neurônios recebem sinal **0**, ou seja, suas conexões ficam mudas.

Medido no subcircuito v1: **4 neurônios** (1 motor, 3 descendentes: `DNg94`×2, `DNp27`),
todos com confiança baixa (0,35–0,79), somando **4 arestas silenciadas**. Todos estão na
fronteira motora, onde RN-04 já os torna folhas — logo o impacto atual é **nulo**.

Deixar como está na v1. **Reabrir obrigatoriamente** ao ampliar o escopo (2 saltos ou
cérebro inteiro), onde neuromoduladores aparecem em massa no meio do circuito e silenciá-los
deixa de ser inócuo.

**Achado (17/09/2026) — `DNp27` provavelmente é erro de classificador, não
neurônio serotonérgico de verdade.** Cruzando nossos 168 tipos celulares contra
`gt_data.csv` (flyconnectome/drosophila_neurotransmitters — dado de literatura,
não predição), 13 tipos batem, 12 confirmam exatamente nossa predição (Eckstein et
al. 2024). **1 diverge:** `DNp27` está com `top_nt=serotonina` no nosso dado
(confiança 0,35-0,79, já sinalizado como baixa), mas o ground truth (Cheong et
al. 2023) diz **acetilcolina** (excitatório). Não muda nada em código — `DNp27` é
descendente, RN-04 já o torna folha, o impacto de RN-01a continua nulo — mas
registra que a fonte provável do erro é falha real do classificador nessa
proteína específica, não ambiguidade genuína de sinal. `DNg94` (os outros 2
neurônios afetados por RN-01a) não tem entrada no ground truth, segue sem
checagem independente.

---

## RN-02 · Override de histamina nos fotorreceptores ⚠️

Os 273 fotorreceptores ocelares aparecem no dado como `serotonin`, confiança média 0,58.
É **artefato**: o classificador de Eckstein et al. tem apenas 6 classes e **histamina não
está entre elas**. Fotorreceptores de *Drosophila* são histaminérgicos e **inibitórios**.

**Regra:** todo neurônio com `super_class == "sensory"` e `cell_sub_class == "ocellar"`
recebe sinal **−1**, ignorando `top_nt`.

> Sem este override, toda a entrada sensorial do circuito entra com o sinal trocado.
> Como é o início da cadeia, o comportamento inteiro do mob sairia errado.
> Onde: `graph.py::apply_nt_overrides`. Teste obrigatório.

**Evidência de apoio, não prova direta (17/09/2026, DT-3 em `docs/05-resultados.md`).**
`gt_data.csv` (flyconnectome/drosophila_neurotransmitters) não tem entrada de
ground truth para `cell_sub_class == "ocellar"` especificamente — os fotorreceptores
do nosso subcircuito não têm `cell_type` nomeado, só a subclasse, então não há como
cruzar direto. Mas o dado confirma histamina em **R7 e R8** (fotorreceptores do
olho composto, `histamine=1`, fontes Davis et al. 2020 e Sarthy 1991) — mesma
classe geral de neurônio, biologia consistente com o override. **Isto não é
confirmação direta pros ocelos** — continua sendo inferência nossa por analogia
biológica (fotorreceptor → histaminérgico é regra geral em *Drosophila*, não
específica de ocelo), só que agora com um ponto de apoio a mais do que "nenhum
dado direto".

## RN-03 · Limiar de 5 sinapses por conexão

Conexões com menos de 5 sinapses são descartadas. Segue o artigo (que usa `>4`), cuja
justificativa é que as sinapses não foram revisadas manualmente e conexões fracas podem
ser ruído de classificador.

O próprio artigo admite que 5 é "razoável, mas arbitrário". Se o circuito ficar silencioso
na F1, **este é o primeiro parâmetro a questionar** — não os parâmetros do LIF.

Onde: `ingest.py`, constante `SYN_THRESHOLD`.

---

## RN-04 · Fronteira motora são os neurônios descendentes

Neurônios com `super_class == "descending"` são a saída do sistema. A expansão BFS **não
atravessa** essa fronteira: são folhas, mesmo tendo conexões a jusante no cérebro real.

Justificativa biológica: descendentes levam comando do cérebro para o cordão nervoso
ventral, que não está no conectoma. É a fronteira natural do dado.

Onde: `ingest.py::expand`, `motor.py`.

---

## RN-05 · Identidade interna é `nid`, não `root_id`

`root_id` é int64 e **muda a cada rodada de proofreading** do FlyWire. Congelado na
materialização 783 e remapeado para `nid` int32 sequencial, atribuído por `root_id`
ordenado (determinístico).

**Só `nid` circula** em NBT, protocolo da ponte e telemetria. `root_id` fica em
`nodes.parquet` para rastreabilidade e citação.

Onde: `ingest.py`. Nunca expor `root_id` acima de L1.

---

## RN-06 · Relógio: dt=1 ms, desacoplado do tick

Engine roda a dt=1 ms em thread própria. O jogo lê o último vetor motor disponível a
20 Hz. O vetor pode estar até 50 ms atrasado — **aceito e documentado**, não é bug.

Proibido: fazer o tick do jogo bloquear esperando o simulador.

Onde: `engine.py`, `server.py`. Ver `02-arquitetura.md`.

---

## RN-07 · Período refratário

Após disparar, o neurônio fica 2 ms sem poder disparar e seu potencial é reposto ao
repouso. Sem isso, um neurônio com entrada forte dispara a cada passo e satura a rede.

Onde: `neuron.py`.

---

## RN-08 · Mapeamento descendente → comportamento (curadoria parcial: 18/47 tipos)

Os 92 descendentes precisam virar canais motores com significado (forward/yaw/lift...).
**A semântica ainda não está definida** — exige curadoria por tipo celular (DNp, DNa,
DNg têm funções documentadas na literatura, mas a leitura específica não foi feita; não
é algo para inventar, ver `CONVENCOES.md`).

**Implementado na F2, mecanismo provisório:** `motor.group_by_cell_type_prefix` agrupa
por prefixo alfabético do `cell_type` (não por função). No subcircuito v1 dá 8 grupos:
DNp (34), DNpe (21), DNg (16), DNge (10), DNb (4), DNbe (3), DNa (2), DNae (2).
`MotorDecoder.decode()` normaliza a taxa de disparo de cada grupo, numa janela
deslizante de `MOTOR_WINDOW_MS`, via `tanh(taxa_hz / MOTOR_RATE_SCALE)`.

**O que isso NÃO resolve:** os valores retornados são taxa (sempre ≥0, sem direção) —
o contrato L4→L5 de `docs/02-arquitetura.md` pressupõe canais com sinal (ex.: yaw
negativo = esquerda), que só existem depois que RN-08 agrupar por FUNÇÃO real, com
pares agonista/antagonista. Isso é a curadoria pendente.

> Esta é a regra mais frágil do sistema e a que mais afeta o resultado observável.
> Qualquer conclusão comportamental depende dela estar certa.

Onde: `motor.py`. Semântica a decidir antes da F4 (é quando o vetor motor passa a
mover a abelha de verdade).

**Atualização F4 (16/09/2026) — canal `phototaxis` adicionado.** O `ControlLoop` do
plugin Java inicialmente usava a MÉDIA dos 8 grupos por prefixo como magnitude de
avanço da abelha. O experimento de lesão (silenciar fotorreceptores vs. normal) deu
resultado nulo (p=0,37) — e a causa era o **mesmo erro já corrigido uma vez em RN-09**:
agregar os 92 descendentes junto cancela o sinal, porque 29 respondem excitatório
(desinibição) e 63 inibitório (direto) ao mesmo estímulo, em direções opostas.

Corrigido promovendo a topologia de sinal de `sim/tools/signal_topology.py` (script
manual da F1) para `sim/src/flywire_sim/topology.py` (módulo real do pacote, usado em
RUNTIME dentro do container) — `motor.py` agora expõe um canal extra:
`phototaxis = tanh((taxa_excitatória − taxa_inibitória) / MOTOR_RATE_SCALE)`. É o
único canal do vetor motor com direção real (sinal), porque vem de algo já validado
estatisticamente, não de curadoria — `MotorMapping.java` passou a usar ele em vez da
média. **✅ Confirmado: experimento de lesão re-rodado deu Mann-Whitney p=0,0014**
(F4 concluída).

**Atualização — curadoria real iniciada (AD-14, 16/09/2026).** Primeira vez que RN-08
usa dado de literatura de verdade, não só mecanismo. Namiki, Cande et al. 2018 (eLife,
DOI 10.7554/eLife.34275) caracterizou comportamento de disparo por tipo de neurônio
descendente via ativação optogenética. A Figura 6 desse paper categoriza 53 dos 58
tipos testados em 7 categorias (Fast/Slow/Broad Locomotion, Slow/Still, Anterior
Movements, Anterior Groom, Wing & Abdomen Movements) — **leitura direta dos rótulos da
figura (classificação dos próprios autores), não inferência nossa a partir de gráfico
bruto.** Cruzando com nossos 47 tipos (contagem corrigida em 16/09/2026 — ver nota
abaixo; o texto original desta seção dizia 46 por erro de contagem manual): **12 batem
direto** (27 de 92 neurônios,
~29%), **+1 via `hemibrain_type`** (Schlegel et al. 2024 — coluna já presente em
`Supplemental_file1_neuron_annotations.tsv`, identidade de tipo cruzada entre
conectomas FlyWire/Hemibrain, não behavior medido nesse root_id específico):

| Nosso tipo | Categoria publicada | Fonte |
|---|---|---|
| DNa10, DNb05, DNb06, DNp05, DNp16, DNp18 | Fast Locomotion | Namiki 2018, Fig. 6 (direto) |
| DNge070 | Fast Locomotion | `hemibrain_type`==DNb06 (Schlegel 2024) |
| DNp28 | Broad Locomotion | Namiki 2018, Fig. 6 (direto) |
| DNp06, DNp20 | Anterior Movements | Namiki 2018, Fig. 6 (direto) |
| DNg11, DNp10, DNp27 | Wing & Abdomen Movements | Namiki 2018, Fig. 6 (direto) |

**13/47 tipos, 29/92 neurônios (~31%).** Um segundo candidato (`DNpe011`) foi
descartado por ambiguidade: só 1 dos 3 neurônios desse tipo bate com `DNp16` no
`hemibrain_type`, os outros batem com `PS227` (sem categoria conhecida) — não dá
pra afirmar o tipo inteiro sem inventar.

**Fast+Broad Locomotion viram o canal `locomotion_drive`** — mas **não** entra em
`MotorMapping.java` (ver abaixo, testado e revertido). Anterior Movements e Wing &
Abdomen Movements também têm dado real, mas o ensaio de Namiki testa **mosca andando**
(perna dianteira, extensão de asa em contexto de canto de corte) — sem tradução
validada pra voo de abelha. Os 4 canais ficam expostos em `motor.py::decode()` para
visualização/exploração (F5: `/flywirebee mute|stimulate`), fora do cálculo de
velocidade.

**Tentativa de usar `locomotion_drive` na velocidade — testada e revertida
(16/09/2026).** `MotorMapping.java` passou a somar `phototaxis` + `locomotion_drive`.
Experimento de lesão re-rodado deu nulo: p=0,43 (N=20). Hipótese alternativa
levantada pelo usuário — abelha tinha spawnado **dentro de casa**, luz quase
constante, podia estar mascarando o efeito de luz independente do canal novo.
Testado: refeito ao ar livre (céu visível), **ainda nulo** (p=0,27, N=20). Isso
descarta o confundidor de local e aponta pra causa real: `locomotion_drive` vem de
neurônios que não têm relação com o caminho de luz (escolhidos só por categoria
comportamental publicada, não por topologia de sinal RN-09) — somar esse canal
dilui o sinal que `phototaxis` carregava sozinho. **Mesma armadilha de "agregar
cancela o efeito" que já apareceu em RN-09 (F1) e no primeiro experimento de lesão
(F4)** — terceira vez. Não ajustamos peso até achar p&lt;0,05 de novo (seria
manipular o resultado); revertido pra `phototaxis` sozinho, que continua validado.

**O que isso NÃO resolvia até aqui:** os outros 34 tipos (63 neurônios) seguiam sem dado
publicado localizável — tentamos extrair o restante do PDF suplementar do Namiki 2018
(50MB, quase todo imagem/gráfico bruto de rastreamento) e não foi possível de forma
confiável (interpretar gráfico de densidade visualmente seria fabricar classificação,
não ler uma real). Também sem direção (yaw/lift) — só magnitude.

---

**Atualização — segunda fonte via BANC connectome (AD-15, 16/09/2026).** Bates, Phelps,
Kim, Yang et al. 2026, *"Distributed control circuits across a brain-and-cord
connectome"*, Nature (DOI: `10.1038/s41586-026-10735-w`; preprint bioRxiv DOI:
`10.1101/2025.07.31.667571`). Conectoma novo (BANC) que une cérebro + cordão nervoso
ventral da fêmea de *Drosophila*, com nomenclatura de tipo compatível com FlyWire/FAFB
v783 (o `cell_type` do BANC **é** o nome batido contra FAFB para neurônios de cérebro/DN
— ver Supplementary Data 1/3). Dados baixados do Harvard Dataverse (DOI:
`10.7910/DVN/7WTH1N`) — arquivos `supplemental_data_6.txt`, `supplemental_data_9.txt` e
`supplemental_data_3.txt` (cross-referência FAFB v783), 16/09/2026.

O BANC traz **duas fontes distintas, com peso epistêmico diferente** — não podem ser
misturadas na mesma tabela sem marcar qual é qual:

1. **`Supplementary Data 9`** — revisão de literatura curada pelos autores do BANC
   (comportamento medido experimentalmente por outros papers, mesmo nível de evidência
   que Namiki 2018/AD-14). **5 tipos novos, mesmo padrão de confiança do AD-14:**

   | Nosso tipo | Função publicada | Fonte |
   |---|---|---|
   | DNa03, DNae003 | steering | Feng et al. 2024, DOI 10.1101/2024.06.27.601106 |
   | DNg75 (=cDN1) | walking | Sapkal et al. 2024, DOI 10.1038/s41586-024-07854-7 |
   | DNg79 | landing | Liessem et al. 2025, DOI 10.1016/j.cub.2022.12.005, 10.1101/2025.12.13.693955 |
   | DNp22 (=DNOVS1) | **ocellar** | Suver et al. 2016, DOI 10.1523/jneurosci.2277-16.2016 |

   `DNp22`/DNOVS1 é achado notável: é um descendente com função publicada
   **especificamente ocelar** (Suver et al. 2016 estudou resposta de voo a estímulo
   ocelar) — o único caso onde a fonte de curadoria bate exatamente com o estímulo do
   nosso subcircuito, não só com locomoção genérica.

   **Novo total com literatura: 18/47 tipos, 39/92 neurônios (~42%)** — sobe de
   13/47 (29/92) do AD-14.

2. **`Supplementary Data 6`** — cluster comportamental por **conectividade** (PCA-UMAP
   sobre o padrão de influência até efetores, Fig. 3/Extended Data Fig. 6 do paper),
   não comportamento medido. **Mesmo nível epistêmico do canal `phototaxis`** (RN-09):
   inferência de topologia/conectividade, não leitura de ensaio comportamental. Cobre
   **33 dos 34 tipos** que ainda não tinham nada — só `DNp40` segue sem qualquer entrada
   no BANC. Categorias observadas: `flight steering 1`, `flight steering 2`,
   `head orienting`, `postural control`, `flight power`, `walking`, `threat response`,
   `probing`. **Não promover isso a comportamento "sabido"** sem dizer explicitamente
   que é cluster de conectividade — mesma armadilha que já gerou 3 diluições de
   sinal neste projeto (ver seção "Armadilhas conhecidas" do `CONVENCOES.md`).

   **Verificado (16/09/2026): o paper NÃO explica o que distingue `flight steering 1`
   de `flight steering 2`.** bioRxiv bloqueou download direto do PDF (HTTP 429, 4
   tentativas); o texto completo foi checado via PMC (`PMC13518251`) especificamente
   atrás dessa distinção. Os dois nomes aparecem só como rótulos de cluster na Fig. 3d
   e Extended Data Fig. 8a (links de Neuroglancer para "flight steering 1"/"2"), sem
   nenhuma frase no corpo do texto ou legenda que diga se é eixo (yaw vs. roll), lado,
   ou população de motoneurônio-alvo diferente. **A distinção existe só no espaço de
   conectividade (UMAP), não foi caracterizada pelos autores em texto — não dá pra
   assumir que 1/2 = agonista/antagonista.** Se isso for necessário, a única forma de
   descobrir é inspecionar conectividade individual (`Supplementary Data 7`, efetores)
   dos neurônios de cada cluster — não tentado ainda.

**Conflito Namiki×BANC — resolvido em 16/09/2026, decisão registrada tipo a tipo:**

| Tipo | Namiki 2018 (antigo) | BANC (`Supplementary Data 9`) | Decisão | Por quê |
|---|---|---|---|---|
| DNp05 | fast_locomotion | escape_takeoff (**putativo**, Cheong & Eichler 2023) | **Mantém fast_locomotion** | BANC marca a própria fonte como putativa; Namiki é comportamento medido direto (optogenética). Medido bate contra putativo, medido vence. |
| DNb05, DNb06 | fast_locomotion | steering (Yang et al. 2024) | **Muda para steering** | Ambas as fontes são medidas, não putativas — nesse empate, prevalece a mais específica e mais recente sobre o eixo que RN-08 precisa (direção, não magnitude). |
| DNge070 | fast_locomotion (via `hemibrain_type`==DNb06) | — | **Muda para steering** | Segue DNb06, de quem herdou a classificação por identidade cruzada — não tem citação própria, então segue o que DNb06 virou. |
| DNp06 | anterior_movements | escape_takeoff (Kim et al. 2023) | **Muda para escape_takeoff** | Namiki mede mosca **andando** (não traduz pra voo, por isso já ficava fora do cálculo de velocidade); BANC é medido e mais relevante ao domínio de voo. |
| DNp20 | anterior_movements | flight (Suver et al. 2016) | **Muda para flight** | Mesmo motivo do DNp06 — e Suver et al. 2016 é o mesmo paper que deu `DNp22`=ocellar (ambos rotulados `DNOVS1` em `other_names`), reforçando relevância pro nosso circuito. |
| DNp10 | wing_abdomen_movements | landing (Ache et al. 2019) | **Muda para landing** | Namiki mede mosca andando; landing é comportamento de voo real, mais útil pro nosso contexto. |
| DNp27 | wing_abdomen_movements | **neuromodulatory** (Meiselman et al. 2022, imuno; Chen et al. 2015, imuno) | **Muda para neuromodulatory, fora de qualquer categoria motora** | "Neuromodulatory" não é nem magnitude nem direção — é liberação de neuromodulador. Mantê-lo como categoria motora seria fabricar semântica. Consistente com o alerta de RN-01a sobre neuromoduladores. |

Critério geral aplicado: **medido bate putativo**; entre duas fontes igualmente medidas,
prevalece a mais específica sobre o eixo que falta (direção) e/ou mais relevante ao
domínio de voo (não andar). Nenhuma junção silenciosa — cada linha tem justificativa
própria.

**`PUBLISHED_DN_BEHAVIOR` atualizado em `motor.py`** com as 5 categorias novas (AD-15,
`Supplementary Data 9`) e as 7 reclassificações acima. Novo total: **18/47 tipos,
39/92 neurônios (~42%)** — `anterior_movements` deixou de existir (ficou vazia após as
duas reclassificações); `wing_abdomen_movements` ficou só com `DNg11`.

**Canais de conectividade BANC (`Supplementary Data 6`) — adicionados como telemetria,
NÃO usados no cálculo de velocidade.** 27 dos 34 tipos que não tinham nada (50
neurônios) ganharam cluster de conectividade (prefixo `conn_` em `motor.py::decode()`
pra deixar claro que é evidência mais fraca — UMAP de conectividade, não comportamento
medido): `conn_flight_steering_1`, `conn_flight_steering_2`, `conn_head_orienting`,
`conn_flight_power`, `conn_walking`, `conn_postural_control`, `conn_threat_response`,
`conn_probing`. **Ficam de fora de `MotorMapping.java`/`locomotion_drive`** até serem
validados por experimento de lesão individual — mesmo risco de diluição que já
aconteceu 3 vezes se forem agregados sem validar primeiro.

`DNpe027` (2 neurônios) ficou **de fora dos dois** — os 3 neurônios do tipo se dividem
entre `postural control` e `walking` no BANC, sem consenso por tipo; classificar
qualquer um dos dois seria inventar. `DNp40` (1 neurônio) segue **sem nenhum dado**,
nem literatura nem conectividade — não aparece em BANC.

Ver `docs/adr/README.md` AD-14, AD-15 e `FlyWire Citation Guidelines - Data.csv`
(citação oficial por coluna de dado, fornecida pelo usuário).

---

## RN-09 · Corrente tônica de base + ruído

**Descoberto na F1, ao investigar por que o critério de saída não era atingido.**

Os 273 fotorreceptores ocelares têm sinal **100% inibitório** (RN-02, sem exceção).
Com `V_REST=0` e `V_THRESHOLD>0`, corrente puramente inibitória nunca cruza um limiar
positivo — não é questão de calibrar `SYNAPTIC_GAIN`, é **impossibilidade matemática**
do modelo em repouso absoluto: confirmado por varredura, nenhum neurônio além da
camada sensorial dispara para nenhum ganho entre 0,001 e 1,0 sem corrente de base.

**Regra:** toda corrente injetada no LIF tem dois termos adicionais além do
estímulo e da corrente recorrente — `BIAS_CURRENT` (tônico, igual para todo
neurônio) e ruído gaussiano i.i.d. por neurônio a cada passo (`NOISE_STD`,
`NOISE_SEED` fixa a semente). `BIAS_CURRENT` representa bombardeio sináptico
espontâneo não modelado (técnica padrão em redes LIF, cf. Brunel 2000).

**Por que precisa dos dois, não só bias:** com bias sozinho e toda a rede
partindo do mesmo `V_REST`, a trajetória de carga é **idêntica** para todo
neurônio — a rede inteira sincroniza e dispara no mesmo passo só pelo bias,
mascarando completamente o circuito real. O ruído quebra essa sincronia
artificial.

**Calibrado em `bias=0,045 · noise=0,05 · gain=0,01`** (`AMP` de estímulo = 2,0).
Reproduzível com `python tools/calibration_check.py` (a partir de `sim/`).
Primeira tentativa de validação (comparação pareada, 30 sementes, somando
atividade de **todos** os 92 descendentes) deu p=0,44 — sem efeito detectável.
**A causa não era falta de sinal, era o desenho do teste:** a topologia (BFS de
sinal a partir dos fotorreceptores, produto de sinal ao longo do caminho mais
curto) mostra que dos 92 descendentes, **29 têm caminho líquido excitatório**
(desinibição de 2 saltos: fotorreceptor inibe um interneurônio inibitório, que
por sua vez inibe menos o descendente) e **63 têm caminho líquido inibitório**
direto. Somar os dois grupos cancela o efeito na média.

Refeito o teste **separando os dois grupos previstos pela topologia**:

| Grupo (previsto) | N descendentes | Δ atividade (estím. − base) | p |
|---|---|---|---|
| Inibitório (caminho de sinal líquido −1) | 63 | −3,03 ± 5,00 | **0,0028** ✅ |
| Excitatório/desinibição (caminho líquido +1) | 29 | +0,80 ± 3,71 | 0,25 (tendência na direção certa, não significativo ainda) |

**Conclusão: o critério de saída da F1 é atingido** — estimular os fotorreceptores tem
efeito estatisticamente significativo e biologicamente consistente sobre a
atividade dos descendentes (grupo inibitório, p=0,0028). O grupo de desinibição
mostra a direção esperada mas precisa de mais sementes/calibração para
significância — registrado como acompanhamento, não bloqueia a fase.

**Isto não foi forçado a "funcionar":** o efeito nulo do primeiro teste era real
(erro de desenho experimental, não de simulação); a correção veio de entender a
topologia, não de mexer em parâmetro até aparecer número bonito. Ver
`CONVENCOES.md`.

Onde: `engine.py::Engine.step` (bias+ruído), constantes em `config.py`.
Análise de topologia de sinal em `sim/tools/signal_topology.py`; validação
estatística em `sim/tools/calibration_check.py` — reproduz os números acima.

---

## Tabela de rastreio

| Regra | Módulo | Teste | Risco se errada |
|---|---|---|---|
| RN-01 | `graph.py` | `test_sign_assignment` | Alto |
| RN-01a | `graph.py` | — | Nulo na v1, **alto** na v2 |
| RN-02 | `graph.py` | `test_photoreceptor_override` | **Crítico** |
| RN-03 | `ingest.py` | `test_threshold` | Médio |
| RN-04 | `ingest.py` | `test_motor_boundary` | Médio |
| RN-05 | `ingest.py` | `test_nid_stability` | Alto |
| RN-06 | `engine.py`, `server.py`, `ControlLoop.java` | `test_bridge_request_response_no_frame_loss`, `test_bridge_history_stays_bounded_by_window` (`test_server.py`); validado em produção — 1500 trocas/0 falhas em servidor real (F4) | Médio |
| RN-07 | `neuron.py` | `test_refractory` | Alto |
| RN-08 | `motor.py`, `MotorMapping.java` | `test_motor_groups_cover_all_descendants`, `test_motor_decode_range`, `test_published_behavior_groups_are_real_types`, `test_connectivity_cluster_groups_are_real_types` | **Crítico — 18/47 tipos com literatura real (AD-14+AD-15), 27/34 restantes com cluster de conectividade BANC (telemetria só, não behavior medido), 1 tipo (DNpe027) ambíguo, 1 tipo (DNp40) sem nenhum dado. `MotorMapping.java` continua só com `phototaxis`.** |
| RN-09 | `engine.py` | `tools/calibration_check.py` (estatístico, manual — não roda no CI) | Alto — validado por teste estatístico, ver acima |
