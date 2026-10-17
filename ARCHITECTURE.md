# Arquitetura

Como a shipfloor está dividida, quem pode depender de quem e onde cada tipo de mudança entra. Uma mudança que altera uma fronteira descrita aqui atualiza este documento no mesmo PR.

## Visão geral

```
 App (Compose)    CLI (dono)              Agentes (claude, codex)
      │ socket        │ socket                    │ MCP
      └───────────────┴──────────┬──────────────────┘
                                 ▼
                    Daemon (único escritor)
         dispatcher · pool · gates · custo · políticas · MCP
                 │                 │                 │
             Tracker             Forge            Runtime        ← interfaces (ports)
                 │                 │                 │
      SQLite + specs .md     GitHub (PR, CI)   claude, codex      ← store e adapters
                 │
   importar e fechar (gh, acli) ── só no início, nos marcos e no fim
```

## Módulos e dependências permitidas

| Módulo | Responsabilidade | Pode depender de |
|---|---|---|
| `core` | Modelo de domínio: card, colunas, regras de movimento, evento e ator, referência externa, políticas. Funções puras, **sem IO**. | nada |
| `store` | SQLite (SQLDelight), migrações, specs em Markdown. Card e evento na mesma transação. | `core` |
| `ports` | Interfaces `Tracker`, `Forge`, `Runtime` e os fakes de cada uma (para os testes dos outros módulos). | `core` |
| `adapters` | Implementações reais das ports: `gh`, `acli`, `claude`, `codex`. Todo processo externo com timeout. | `core`, `ports` |
| `daemon` | Composição: dispatcher, pool, locks, gates, custo, políticas, API no socket, servidor MCP. Único que escreve no banco. | `core`, `store`, `ports`, `adapters` |
| `cli` | Cliente fino do socket. Não lê o banco. | `core` |
| `app` | Compose Desktop. Cliente do socket. Não lê o banco. | `core` |
| `e2e` | Só testes: critérios de saída de cada fase, caixa-preta contra daemon e CLI. | `core`, `store`, `ports`, `adapters`, `daemon`, `cli` |

A tabela é verificada pelo build (`build-logic/src/main/kotlin/shipfloor.module-boundaries.gradle.kts`): uma dependência fora dela falha a configuração. Mudar a tabela é decisão de arquitetura, feita à mão junto com este documento.

## Regras que valem em todo o código

1. **Só o daemon escreve no banco.** CLI, app e agentes pedem; o daemon aplica as regras do `core`, grava e responde.
2. **Toda mudança de estado gera um evento**, gravado na mesma transação da mudança. Eventos nunca são alterados nem apagados.
3. **Todo evento tem ator**: dono, agente (com papel), adaptador (com provedor) ou fábrica (com componente).
4. **A aprovação do dono é um evento assinado** (ator dono). Nunca um comentário em issue ou PR.
5. **O agente não toca o banco nem a ferramenta externa.** Lê e pede pelo MCP; o daemon decide.
6. **Texto externo é não confiável.** Entra só pela importação, marcado, e chega ao agente envolvido como dado, nunca como instrução.
7. **Nada sai da máquina sem evento assinado** ou política do projeto que dispense a assinatura.
8. **Gates são código determinístico com teste.** O relato de um agente não move um card.
9. **Toda integração externa fica atrás de uma port e tem um fake.** A suíte roda sem rede e sem token.
10. **Todo processo externo (`gh`, `claude`, `git`) tem timeout** e a falha vira erro claro, sem estado meio gravado.

## Onde cada mudança entra

| Mudança | Módulo |
|---|---|
| Nova regra de negócio (coluna, movimento, política, cálculo) | `core` |
| Nova tabela, coluna ou consulta | `store` (com migração nova; migração aplicada nunca é editada) |
| Nova integração externa | interface e fake em `ports`, implementação em `adapters` |
| Novo comando do dono | protocolo em `core`, tratamento no `daemon`, comando na `cli` |
| Nova ferramenta para agentes | `daemon` (servidor MCP) |
| Novo gate | `daemon` |
| Tela | `app` |
| Critério de saída de fase | `e2e` |

## Build

- Kotlin na JVM, compilado com toolchain JDK 25 (baixado pelo Gradle se faltar) para bytecode Java 21.
- Gradle multi-módulo com version catalog (`gradle/libs.versions.toml`) e convenções em `build-logic/`.
- Testes: JUnit 5 e AssertJ. A convenção imprime `PASSED` para cada teste, que é o que o gate verde da fábrica procura.
- `./gradlew build` roda tudo. Um módulo: `./gradlew :core:test`.

## Caminhos que a fábrica não altera

`.shipfloor/`, `.github/`, `.claude/`, `CLAUDE.md`, `build-logic/`, `gradle/wrapper/`. Mudanças nesses caminhos são feitas à mão pelo dono.
