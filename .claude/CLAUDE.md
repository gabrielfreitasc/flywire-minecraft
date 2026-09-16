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
- **Agregar/tirar média de todos os 92 descendentes cancela o sinal de luz.** 29
  respondem excitatório (desinibição de 2 saltos), 63 inibitório (direto) — direções
  opostas. Já causou dois resultados nulos falsos (RN-09 na F1, experimento de lesão na
  F4) antes de separar por `flywire_sim.topology.group_outputs_by_predicted_sign`.
- **`String.format`/`printf` com `%f` usa o locale padrão da JVM.** Em servidor pt_BR,
  vírgula é separador decimal — corrompe qualquer CSV silenciosamente (vírgula decimal
  colide com vírgula de coluna). Sempre `Locale.ROOT` em código que escreve arquivo.

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
