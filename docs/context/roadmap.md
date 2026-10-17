# Roadmap e mapa de issues

Plano completo, interativo: https://claude.ai/artifact/MftqL6htDj2ngM4FsCVc9J. Os critérios de aceitação de cada issue estão no corpo dela no GitHub.

Cada fase deixa a fábrica usável e só termina quando o teste de saída passa. As fases 0 a 5 estão detalhadas para a V0 executar; as fases 6 a 9 são rascunho e devem ser refinadas pela própria shipfloor depois do marco 5. A V0 constrói a shipfloor até a fase 5; dali em diante a shipfloor constrói a si mesma.

## Regras de liberação

- Mover para Ready for Refinement só as issues da fase atual: spec escrita contra código que ainda não existe envelhece.
- Dependências são a relação Blocked by nativa do GitHub (122 relações). O dispatcher não começa um card com dependência aberta; texto no corpo da issue é ignorado.
- Issues com label `manual` não vão para Ready for Refinement: são feitas à mão (caminhos que a fábrica não toca: `.github/`, `.claude/`, `.shipfloor/`, `CLAUDE.md`, `build-logic/`, `gradle/wrapper/`).
- O último card de cada fase carrega o teste de saída na camada `e2e`.
- Labels: `fase-0` a `fase-9` em todas; `ui` e `cybersec` onde cabe.

## Fase 0 · Bootstrap

**Saída:** Um PR gerado pela V0 mergeado com CI verde; relatório do spike de Wayland.

| Issue | Item | Título | Camada | Bloqueada por | Estado |
|---|---|---|---|---|---|
| #1 | S0-01 | Criar o repositório shipfloor e o board | à mão | — | fechada |
| #2 | S0-02 | Esqueleto Gradle multi-módulo e convenções de build | à mão | #1 | fechada |
| #3 | S0-03 | CI no GitHub Actions | à mão | #2 | fechada |
| #4 | S0-04 | Contexto do projeto para os agentes | à mão | #1 | fechada |
| #5 | S0-05 | factory.yml, permissões e registro na V0 | à mão | #2, #3, #4 | fechada |
| #6 | S0-06 | CLI responde shipfloor --version | `cli` | #5 | fechada |
| #7 | S0-07 | Spike: Compose Desktop no Fedora com Wayland | à mão | #2 | fechada |

## Fase 1 · Núcleo

**Saída:** Pela CLI: criar card, movê-lo por todas as colunas, reiniciar o daemon e ver o histórico íntegro; dois clientes recebem o mesmo evento.

| Issue | Item | Título | Camada | Bloqueada por | Estado |
|---|---|---|---|---|---|
| #8 | S1-01 | Card e colunas | `core` | #6 | aberta |
| #9 | S1-02 | Evento e ator | `core` | #6 | aberta |
| #10 | S1-03 | Regras de movimento entre colunas | `core` | #8, #9 | aberta |
| #11 | S1-04 | Referência externa e spec | `core` | #8 | aberta |
| #12 | S1-05 | Banco SQLite e migrações | `store` | #9, #10, #11 | aberta |
| #13 | S1-06 | Card e evento na mesma transação | `store` | #12 | aberta |
| #14 | S1-07 | Spec em Markdown com hash | `store` | #12 | aberta |
| #15 | S1-08 | Daemon com socket unix e protocolo | `daemon` | #6 | aberta |
| #16 | S1-09 | Comandos de card no daemon | `daemon` | #10, #13, #15 | aberta |
| #17 | S1-10 | Stream de eventos | `daemon` | #16 | aberta |
| #18 | S1-11 | CLI de cards | `cli` | #16, #17 | aberta |

## Fase 2 · Runtime e pipelines

**Saída:** Com runtime falso, um card vai de Inbox a Staged só com as aprovações do dono, custo registrado, cada gate como evento. Nesta fase Staged = branch validada localmente; PR é da fase 3.

| Issue | Item | Título | Camada | Bloqueada por | Estado |
|---|---|---|---|---|---|
| #19 | S2-01 | Políticas do projeto | `core` | #11 | aberta |
| #20 | S2-02 | Registro de projetos | `daemon` | #18, #19 | aberta |
| #21 | S2-03 | Interface Runtime e fake | `ports` | #18 | aberta |
| #22 | S2-04 | Runtime claude | `adapters` | #21 | aberta |
| #23 | S2-05 | Runtime codex | `adapters` | #21 | aberta |
| #24 | S2-06 | Worktree por card | `daemon` | #20 | aberta |
| #25 | S2-07 | Pool, slots e locks | `daemon` | #18 | aberta |
| #26 | S2-08 | Executor de gates | `daemon` | #18 | aberta |
| #27 | S2-09 | Ciclo do dispatcher | `daemon` | #21, #24, #25 | aberta |
| #28 | S2-10 | MCP: ferramentas de leitura | `daemon` | #20 | aberta |
| #29 | S2-11 | MCP: ferramentas de pedido | `daemon` | #28 | aberta |
| #30 | S2-12 | Gates de escopo e caminhos proibidos | `daemon` | #26 | aberta |
| #31 | S2-13 | Gates de build e teste por camada | `daemon` | #26 | aberta |
| #32 | S2-14 | Gates de spec: plano de teste e peso | `daemon` | #26, #14 | aberta |
| #33 | S2-15 | Custo por transcrição | `daemon` | #22, #23 | aberta |
| #34 | S2-16 | Limites de custo | `daemon` | #33, #27 | aberta |
| #35 | S2-17 | Horário de trabalho e step-up de modelo | `daemon` | #27 | aberta |
| #36 | S2-18 | Skill de refine via MCP | `daemon` | #29, #32 | aberta |
| #37 | S2-19 | Skill de develop via MCP | `daemon` | #29, #31 | aberta |
| #38 | S2-20 | Skill de validate via MCP | `daemon` | #29, #31 | aberta |
| #39 | S2-21 | Pipeline de refine | `daemon` | #27, #36 | aberta |
| #40 | S2-22 | Pipeline de develop | `daemon` | #27, #37, #35 | aberta |
| #41 | S2-23 | Pipeline de validate | `daemon` | #27, #38 | aberta |
| #42 | S2-24 | Aprovar e pedir mudança pela CLI | `cli` | #39, #40, #41, #34 | aberta |

## Fase 3 · Forge e entrega

**Saída:** O mesmo card chega a Done nos dois modos de entrega; apagar o banco e reconstruir pelo PR recupera o estágio.

| Issue | Item | Título | Camada | Bloqueada por | Estado |
|---|---|---|---|---|---|
| #43 | S3-01 | Interface Forge e fake | `ports` | #42 | aberta |
| #44 | S3-02 | Forge GitHub via gh | `adapters` | #43 | aberta |
| #45 | S3-03 | Validate abre o PR e espera o CI | `daemon` | #44 | aberta |
| #46 | S3-04 | Entrega autônoma | `daemon` | #45 | aberta |
| #47 | S3-05 | Entrega no PR | `daemon` | #45, #19 | aberta |
| #48 | S3-06 | PR mergeado fora da fábrica | `daemon` | #46 | aberta |
| #49 | S3-07 | Conflito com a main e rodadas de correção | `daemon` | #46 | aberta |
| #50 | S3-08 | CI vermelho na main e revert | `daemon` | #46 | aberta |
| #51 | S3-09 | Reconstruir card a partir de branch e PR | `cli` | #47, #48 | aberta |

## Fase 4 · Importar e fechar

**Saída:** Importar, trabalhar e fechar uma issue com confirmação; zero chamadas ao tracker entre importação e fechamento.

| Issue | Item | Título | Camada | Bloqueada por | Estado |
|---|---|---|---|---|---|
| #52 | S4-01 | Interface Tracker e fake | `ports` | #43 | aberta |
| #53 | S4-02 | Tracker GitHub Issues via gh | `adapters` | #52 | aberta |
| #54 | S4-03 | Comando importar | `daemon` | #53 | aberta |
| #55 | S4-04 | Marcos opcionais e aviso de texto alterado | `daemon` | #54, #19 | aberta |
| #56 | S4-05 | Rascunho e confirmação do fechamento | `daemon` | #54, #51 | aberta |
| #57 | S4-06 | Porta única de texto externo | `daemon` | #56, #28 | aberta |

## Fase 5 · Autossuficiência

**Saída:** Dez cards consecutivos do próprio repositório entregues pela shipfloor com a V0 parada; só então a V0 é arquivada.

| Issue | Item | Título | Camada | Bloqueada por | Estado |
|---|---|---|---|---|---|
| #58 | S5-01 | Serviço systemd do usuário | `daemon` | #15 | aberta |
| #59 | S5-02 | shipfloor doctor | `cli` | #58 | aberta |
| #60 | S5-03 | Importar os cards da V0 | `cli` | #54 | aberta |
| #61 | S5-04 | Relatório de lead time e custo por card | `daemon` | #33 | aberta |
| #62 | S5-05 | Virada: a shipfloor assume o próprio repositório | à mão | #58, #59, #60, #61 | aberta |

## Fase 6 · App desktop (rascunho)

**Saída:** Um dia de operação sem abrir a CLI para nenhuma ação do dono.

| Issue | Item | Título | Camada | Bloqueada por | Estado |
|---|---|---|---|---|---|
| #63 | S6-01 | Casca do app e conexão ao daemon | `app` | #62 | aberta |
| #64 | S6-02 | Esteira | `app` | #63 | aberta |
| #65 | S6-03 | Movimentos do dono na esteira | `app` | #64 | aberta |
| #66 | S6-04 | Visão do card | `app` | #63 | aberta |
| #67 | S6-05 | Aprovar e pedir mudança pelo app | `app` | #66 | aberta |
| #68 | S6-06 | Feed e custo | `app` | #63 | aberta |
| #69 | S6-07 | Configurações | `app` | #63 | aberta |
| #70 | S6-08 | Bandeja, pop-up e notificação no Linux | `app` | #63 | aberta |
| #71 | S6-09 | Tela de fechamento | `app` | #66 | aberta |
| #72 | S6-10 | Pacotes deb e rpm | à mão | #64 | aberta |

## Fase 7 · Assistente (rascunho)

**Saída:** Skins trocáveis sem reiniciar; nenhuma frase do assistente contradiz o banco.

| Issue | Item | Título | Camada | Bloqueada por | Estado |
|---|---|---|---|---|---|
| #73 | S7-01 | Estados do assistente derivados de eventos | `core` | #17 | aberta |
| #74 | S7-02 | Texto com personalidade | `daemon` | #73 | aberta |
| #75 | S7-03 | Skin terminal no canto | `app` | #74 | aberta |
| #76 | S7-04 | Skin pop-up animado | `app` | #74 | aberta |
| #77 | S7-05 | Decisão sobre o cartum | à mão | #75, #76 | adiada (decisão 20) |

## Fase 8 · Portes e onboarding (rascunho)

**Saída:** Em VM limpa de cada sistema: instalar, registrar e entregar um card, sem admin no Windows.

| Issue | Item | Título | Camada | Bloqueada por | Estado |
|---|---|---|---|---|---|
| #78 | S8-01 | Transporte TCP local com token | `daemon` | #62 | aberta |
| #79 | S8-02 | Serviço no macOS e no Windows | `daemon` | #78 | adiada (decisão 20/21) |
| #80 | S8-03 | CI e pacotes para macOS e Windows | à mão | #79 | adiada (decisão 20/21) |
| #81 | S8-04 | Atualizador automático | `app` | #80 | adiada (decisão 20/21) |
| #82 | S8-05 | shipfloor init com prontidão do projeto | `cli` | #62 | aberta |
| #83 | S8-06 | Rascunho assistido dos documentos do projeto | `daemon` | #82 | aberta |
| #84 | S8-07 | CLI nativa com GraalVM | `cli` | #80 | aberta |

## Fase 9 · Empresa (rascunho)

**Saída:** Fluxo corporativo real de ponta a ponta num ticket sem dado sensível.

| Issue | Item | Título | Camada | Bloqueada por | Estado |
|---|---|---|---|---|---|
| #85 | S9-01 | Tracker Jira | `adapters` | #52 | aberta |
| #86 | S9-02 | Gate de DoR na importação | `daemon` | #85 | aberta |
| #87 | S9-03 | Runtime via Bedrock, Vertex e proxy | `adapters` | #21 | aberta |
| #88 | S9-04 | Outbox com confirmação por padrão | `daemon` | #85 | aberta |
| #89 | S9-05 | Exportar a trilha de auditoria | `cli` | #17 | aberta |
| #90 | S9-06 | Convenções corporativas | `daemon` | #85 | aberta |
