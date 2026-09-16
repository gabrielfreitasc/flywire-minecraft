# Convenções — FlyWire on Minecraft

> Para que o Claude Code carregue estas convenções automaticamente, copie este
> arquivo para `.claude/CLAUDE.md` (não pode ser criado remotamente por restrição
> de segurança da ponte com o seu computador).

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
| Plugin Java | `docs/02-arquitetura.md` (contratos) |

## Regras duras

1. **`data/raw/` é imutável.** Nunca editar, limpar ou sobrescrever. Transformação lê
   daqui e escreve em `interim/` ou `processed/`.
2. **Nunca contornar a validação do ingest.** Se os totais não batem com o artigo, o dado
   está errado — não o check. Números exigidos em `data/raw/README.md`.
3. **`root_id` não sobe de L1.** Acima da camada de dados só existe `nid` (RN-05).
4. **Regra de negócio muda com ADR.** Alterar RN-01…RN-08 exige registro em `docs/adr/`.
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
  subcircuito de 625 a rede pode ficar silenciosa. Esperado; ajustar ganho de entrada.
- **A abelha tem IA nativa** que compete pelo controle. Desabilitar antes de aplicar
  o vetor motor.

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
