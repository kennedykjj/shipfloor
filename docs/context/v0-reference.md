# O que olhar na V0

A shipfloor não copia código da V0, mas a V0 já resolveu (e errou) muita coisa. Antes de implementar um card, conferir aqui se existe especificação viva. Caminhos relativos a `~/git/shipfloor`.

| Assunto na shipfloor | Onde está na V0 | Para quê |
|---|---|---|
| Contrato de cada script, linhas de log, marcadores | `docs/contracts.md` | O que dispatcher, gates e deploy prometem uns aos outros |
| Colunas e movimentos do dono (S1-03) | `DESIGN.md` (movimentos M1 a M7), testes `scripts/test/py/dashboard_belt.py` | Quem pode mover o quê |
| Dispatcher, pool, locks, horário, step-up (S2-07, S2-09, S2-17) | `scripts/shipfloor_core/dispatch.py`, `cycle.py`, testes `dispatch_*.py` | Regras de elegibilidade, `stopped/`, `stepup/`, limites |
| Gates (S2-12 a S2-14) | `scripts/shipfloor_core/gate/` (`acceptance.py`, `run.py`, `scope.py`, `spec.py`), teste `gate_diff.py` | Vermelho antes/verde depois, `ran`, asserções, caminhos proibidos |
| Custo por transcrição (S2-15) | `scripts/dashboard_cost.py`, `templates/prices.yml` | Parsers de transcrição e tabela de preços; usar transcrições reais como fixtures |
| Runtimes (S2-04, S2-05) | `scripts/runtimes/`, `docs/configuration.md` (Runtime: Codex, opencode) | Como lançar `claude -p` e `codex exec` headless |
| Skills dos estágios (S2-18 a S2-20) | `skills/refine`, `skills/develop`, `skills/validate`, `skills/deploy`, `agents/` | Texto base para reescrever falando MCP |
| Spec e critérios | `templates/spec-template.md`, `templates/review-criteria.md` | Formato que os gates de spec conferem |
| Forge e entrega (fase 3) | `scripts/shipfloor_core/deploy.py`, testes `deploy_production.py` | staged-head, PR já mergeado, rerun de CI, revert, conflitos |
| Board e importação (fase 4) | `scripts/shipfloor_core/board.py`, `setup.py` | Labels, estado de issue, de quem a fábrica escuta comentários |
| Notificações (S6-08) | `docs/superpowers/specs/2026-10-11-notifications-when-the-factory-needs-the-design.md` (#35) | Eventos, canais, horário de silêncio, digest |
| Dashboard (fase 6) | `scripts/dashboard.html`, `DESIGN.md` | Especificação de comportamento das telas |

## Lições da V0 que viram regra aqui

- **#282:** card em Ready for Prod com PR mergeado fora da fábrica ficava preso para sempre. Na shipfloor é o S3-06.
- **Cota GraphQL (2026-10-14):** ler board em loop parou a fábrica. Na shipfloor o estado é local; na V0, ler pelo `board.tsv` e pela REST.
- **staged-head:** o merge exige que o head do PR seja o que o dono aprovou. Na shipfloor é o S3-04.
- **Comentários como prova de aprovação** (`deploy.py:460`): frágil e sujeito a injeção. Na shipfloor a aprovação é evento assinado.
- **Agente lendo comentário de qualquer pessoa** num repositório público é instrução de estranho. Na shipfloor o texto externo entra por uma porta só, marcado.
