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

### RN-01a · Serotonina não está coberta ⚠️ em aberto

Serotonina é **neuromodulador**, não neurotransmissor rápido — não tem sinal óbvio.
Hoje esses neurônios recebem sinal **0**, ou seja, suas conexões ficam mudas.

Medido no subcircuito v1: **4 neurônios** (1 motor, 3 descendentes: `DNg94`×2, `DNp27`),
todos com confiança baixa (0,35–0,79), somando **4 arestas silenciadas**. Todos estão na
fronteira motora, onde RN-04 já os torna folhas — logo o impacto atual é **nulo**.

Deixar como está na v1. **Reabrir obrigatoriamente** ao ampliar o escopo (2 saltos ou
cérebro inteiro), onde neuromoduladores aparecem em massa no meio do circuito e silenciá-los
deixa de ser inócuo.

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

---

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

## RN-08 · Mapeamento descendente → comportamento (curadoria parcial: 12/46 tipos)

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
bruto.** Cruzando com nossos 46 tipos: **12 batem** (27 de 92 neurônios, ~29%):

| Nosso tipo | Categoria publicada |
|---|---|
| DNa10, DNb05, DNb06, DNp05, DNp16, DNp18 | Fast Locomotion |
| DNp28 | Broad Locomotion |
| DNp06, DNp20 | Anterior Movements |
| DNg11, DNp10, DNp27 | Wing & Abdomen Movements |

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

**O que isso NÃO resolve ainda:** os outros 34 tipos (65 neurônios) seguem sem dado
publicado localizável — tentamos extrair o restante do PDF suplementar do Namiki 2018
(50MB, quase todo imagem/gráfico bruto de rastreamento) e não foi possível de forma
confiável (interpretar gráfico de densidade visualmente seria fabricar classificação,
não ler uma real). Também sem direção (yaw/lift) — só magnitude. Curadoria segue aberta
para esse restante; próxima fonte candidata: Schlegel et al. 2024 (whole-brain), não
tentada ainda. Ver `docs/adr/README.md` AD-14 e `FlyWire Citation Guidelines - Data.csv`
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
| RN-08 | `motor.py`, `MotorMapping.java` | `test_motor_groups_cover_all_descendants`, `test_motor_decode_range`, `test_published_behavior_groups_are_real_types` | **Crítico — 12/46 tipos com dado real (AD-14), 34 sem curadoria** |
| RN-09 | `engine.py` | `tools/calibration_check.py` (estatístico, manual — não roda no CI) | Alto — validado por teste estatístico, ver acima |
