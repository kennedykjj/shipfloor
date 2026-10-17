# Decisões

Registro das decisões que formaram a shipfloor, com o motivo e o que foi descartado. Tomadas entre o dono e o Claude em 2026-10-15, antes do primeiro código. Desenho completo, com fluxos interativos: https://claude.ai/artifact/8D5VdReqza6nbwu3EMLH9u.

Decisão nova ou revisada entra aqui no mesmo PR que a aplica.

## Por que existe uma shipfloor

O Shipfloor V0 guarda o estado de cada card num GitHub Project v2. Toda leitura e escrita cruza a rede e disputa a cota GraphQL (5.000 pontos por hora, compartilhada com a fábrica, estourou em 2026-10-14 e parou o dispatcher). O dashboard enxerga uma cópia de até 3 minutos. Mover um card é uma escrita REST. Os agentes comentam na issue no meio do trabalho e a aprovação vira um comentário que o deploy precisa interpretar.

Objetivo do produto: uma squad de desenvolvimento para cada desenvolvedor, tanto para projetos pessoais e vibe-coded quanto para o desenvolvedor dentro de uma empresa, com velocidade e poder de decisão.

## Decididas

| # | Decisão | Por quê | Descartado |
|---|---|---|---|
| 1 | **Projeto novo, construído pela V0** | Escolha do dono: o antigo vira V0 e constrói o novo card a card até a fase 5. Nada do V0 é copiado como código; lógica, testes e design viram especificação. | Costura no repositório atual (recomendação inicial do Claude: ~8 arquivos concentravam o GitHub e o `board.sh` era uma costura estreita). |
| 2 | **Kotlin, Compose Multiplatform, Gradle em tudo** | Linguagem que o dono domina; uma só do daemon ao app e ao celular depois. Gradle porque o tooling do Compose (plugin, `jpackage`, Android/iOS) só existe em Gradle. | Maven (preferência do dono, sem suporte no Compose); Tauri (WebKitGTK frágil no Linux); Electron (pesado); Go e Rust (reaproveitamento zero, outra linguagem). |
| 3 | **Estado local: SQLite para cards e eventos, spec em Markdown** | O card vive na máquina; leitura e escrita instantâneas, sem cota. SQLite aguenta vários leitores e consulta cruzada entre projetos; a spec em arquivo fica legível e comparável em diff. | Arquivos JSON por card (concorrência e consulta por conta própria). |
| 4 | **Só o daemon escreve no banco** | Elimina concorrência no SQLite e garante que todo efeito vira evento. | CLI e app lendo/escrevendo direto. |
| 5 | **Importar e fechar, sem sincronização contínua** | Some o eco entre escrita e leitura, o polling e a cota. O tracker é tocado na importação, nos marcos opcionais ("comecei", "PR aberto") e no fechamento confirmado. O card guarda as referências externas com hash do texto; mudança externa vira aviso. | Sync bidirecional; GitHub como fonte da verdade; propriedade dividida contínua. |
| 6 | **Agentes falam com a fábrica por MCP** | O agente lê e pede; o daemon decide. A latência de iniciar a JVM a cada chamada deixa de importar, porque a CLI fica para o humano. | Agentes chamando a CLI (como o `board.sh` da V0). |
| 7 | **Todo evento tem ator** | Dono, agente (com papel), adaptador (com provedor) ou fábrica (com componente). É a trilha de auditoria que uma empresa pede. | — |
| 8 | **Aprovação é evento assinado pelo dono** | Nunca um comentário em issue (a V0 interpreta comentários em `deploy.py:460`). | — |
| 9 | **Modo de entrega por projeto** | Autônomo (a fábrica mergeia) para projeto pessoal; entrega no PR (para em revisão humana) para empresa com revisores obrigatórios. | Merge sempre pela fábrica. |
| 10 | **Um dono por fábrica, numa máquina** | "Uma squad por desenvolvedor" é um usuário só. Se a máquina for trocada, o card é reconstruído a partir de ticket, branch e PR. | Ferramenta de time; servidor. |
| 11 | **Linux primeiro** | Fedora/GNOME/Wayland é onde o dono usa. macOS e Windows na fase 8, com socket unix no Linux/macOS e TCP local com token no Windows (o JDK não tem named pipe). | — |
| 12 | **Assistente da fábrica com skins** | Personalidade sem rosto obrigatório, sobre o log de eventos. Modo silencioso desde o primeiro dia. O que ele diz vem do banco; o modelo só dá voz. Skins: ícone e badge, terminal no canto, pop-up, voz em casa; o gerente cartunesco é decidido na fase 7. | Mascote como interface principal; projeto "Jarvis" open source (descartado pelo dono). |
| 13 | **Toolchain JDK 25, bytecode Java 21** | A máquina do dono só tem o JRE 25 (pacote headless, sem `javac`); o foojay baixa o JDK do toolchain. Bytecode 21 roda em qualquer JVM 21+. | Exigir instalar o JDK no sistema. |
| 14 | **Compose em XWayland, janelas transparentes em software** | Resultado do spike #7 (`docs/spikes/2026-wayland.md`): Wayland nativo não abre; transparente em OpenGL cai para 1 fps. Bandeja via StatusNotifierItem, notificação via `org.freedesktop.Notifications`. | WLToolkit; `TrayIcon` do AWT. |
| 15 | **Projeto sem limite de WIP** | Escolha do dono. `limits` com `wip: 0` explícito, porque sem o bloco valem os padrões da V0 (refine 2, develop 4). | — |
| 16 | **JSON com kotlinx.serialization** | Uma biblioteca para o projeto todo (eventos, protocolo do socket, MCP). Serializa tipo selado (`Actor`) sem montar a árvore à mão, sem reflexão e no mesmo versionamento do Kotlin. O plugin do compilador entra no classpath por `build-logic/`; o módulo aplica `id("org.jetbrains.kotlin.plugin.serialization")` sem versão e declara `libs.kotlinx.serialization.json`. Decidido ao auditar #9 e #15, que escolhiam Jackson em versões diferentes. | Jackson 2.x (árvore montada à mão, duas versões em paralelo); Jackson 3. |
| 17 | **SQLite pelo SQLDelight** | Consultas tipadas geradas do SQL, migrações `.sqm` com `verifyMigrations` (pega migração já aplicada editada) e versão no `PRAGMA user_version`. Plugin aplicado só no `store`, versão fixa no catálogo. Decidido pelo dono interino ao revisar a spec do #12 (versão corrigida de 2.1.0 para 2.4.1, compatível com o Kotlin 2.4). | JDBC puro com `sqlite-jdbc` (mapeamento e migração à mão); Exposed (ORM, mais distante do SQL). |
| 18 | **Esperam o dono: Spec Review e Staged** | Decisão do dono em 2026-10-16 (#215). A decisão de liberar é Staged → Ready for Prod (`OWNER_ONLY` no `MoveRules`); um card em Ready for Prod já foi liberado e segue em entrega. Uma constante serve à esteira (#64) e às notificações (#70). | Spec Review + Ready for Prod (exigiria mudar a regra de movimento no `core`). |
| 19 | **Aviso pelo app residente na bandeja** | Decisão do dono em 2026-10-16. Segue a spec #70 ("o app avisa"); o pacote do #72 sobe app e serviço juntos. Limite aceito: com o app fechado não há aviso até reabrir. | Daemon dispara a notificação nativa; os dois ao mesmo tempo. |
| 20 | **Cartum (#77) adiado** | Decisão do dono em 2026-10-16: decidir depois da Virada e de um dia de uso das duas skins que existem (terminal no canto e pop-up). Nada depende do #77. | Não entra; entra como skin opcional. |
| 21 | **Portes macOS e Windows (#79, #80, #81) adiados** | Decisão do dono em 2026-10-16. Ninguém verifica esses sistemas aqui e a saída da fase 8 exige VM limpa de cada um. Seguem na fase 8: #78, #82, #83, #84. | Manter tudo como planejado; adiar a fase 8 inteira. |

## Propostas à espera do aceite do dono

- **Outbox reduzido aos marcos:** fechamento sempre com confirmação; marcos intermediários por projeto, sem confirmação. O agente rascunha, um script com a CLI do provedor escreve; MCP só para provedor sem CLI.
- **Mínimo do projeto em dois níveis:** bloqueia só o verificável (git, `factory.yml` com build e teste por camada, convenções); PRODUCT, ARCHITECTURE e DESIGN entram como score de prontidão, com rascunho oferecido pela fábrica.

## Em aberto

- Nome definitivo (`shipfloor` é provisório).
