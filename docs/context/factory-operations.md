# Operar a V0 neste repositório

O que se aprendeu ao ligar o Shipfloor V0 a esta fábrica. Vale para qualquer sessão que mexa na fábrica, no board ou no build daqui.

## Onde as coisas estão

| O quê | Onde |
|---|---|
| Repositório | https://github.com/kennedykjj/shipfloor (clone local: `~/git/shipfloor`) |
| Board | Project v2 #4 do usuário: https://github.com/users/kennedykjj/projects/4 |
| Casa do projeto na V0 | `~/.shipfloor/projects/shipfloor/` (board.tsv, logs, locks, worktrees em `wt/`) |
| Logs de cada run | `~/.shipfloor/projects/shipfloor/logs/<data>-<n>-<estágio>.log` e `.events.jsonl` |
| Log do dispatcher | `~/.shipfloor/logs/dispatcher.log` (todos os projetos) |
| Config da máquina | `~/.shipfloor/config.yml` (pool 11; refine 2 por vez na máquina) |
| Código da V0 | `~/git/shipfloor` |

## factory.yml: armadilhas já resolvidas

- **Os comandos rodam do diretório da camada** (`gate/run.py` usa `cwd=<path da camada>`). Por isso `../gradlew`, nunca `./gradlew`.
- **`--rerun`, não `--rerun-tasks`.** O gate verde procura `PASSED`; uma tarefa de teste em cache ou up-to-date não imprime nada. `--rerun` reexecuta só a tarefa de teste; `--rerun-tasks` recompilava o projeto inteiro.
- **Não pôr `FAILED` em `assertions`.** Casa com `BUILD FAILED` de erro de compilação e deixaria o gate vermelho passar sem asserção falhando. O padrão embutido da V0 já reconhece `AssertionFailedError`.
- **`testLogging` com `PASSED`** vive em `build-logic/` (proibido aos agentes). Sem isso o gate verde nunca passa.
- **Validar antes de mergear:** `FACTORY_CONFIG=$PWD/.shipfloor/factory.yml bash ~/.shipfloor/factory/shipfloor-config.sh validate`.
- **A V0 lê o factory.yml de `origin/main`.** Mudança só vale depois do merge.

## Board

- **Ler o board pelo `board.tsv` ou pela REST, nunca `gh project item-list`**: o item-list custa ~200 pontos GraphQL por chamada e a cota é compartilhada com a fábrica.

  ```sh
  F=$(gh api users/kennedykjj/projectsV2/4/fields --jq '.[] | select(.name=="Status") | .id')
  gh api "users/kennedykjj/projectsV2/4/items?fields=$F&per_page=100" --paginate \
    --jq '.[] | "\(.content.number)\t\([.fields[] | select(.name=="Status") | .value.name.raw // .value.name // ""] | join(""))"'
  ```
- O `board.tsv` só é atualizado quando o dispatcher roda; com o projeto pausado ele fica velho.
- **Card fechado à mão fica parado na coluna.** A V0 só move para Done o que sai de Ready for Prod. Ao fechar uma issue manual, mover o item para Done no board.
- As colunas foram criadas com as mesmas opções do `shipfloor setup-board`; rodar o setup-board de novo preserva o status de cada card.

## Pausa

`~/.shipfloor/projects/shipfloor/PAUSE` faz o dispatcher pular o projeto inteiro (sem ler o board). Em 2026-10-15 o projeto ficou pausado das 20:33 às 21:46: as issues #2 e #4 foram para Ready for Refinement antes de existirem as permissões em `.claude/settings.json`, os dois refines pararam no primeiro passo e alguém pausou. Antes de `shipfloor resume shipfloor`, conferir pelo board que só os cards certos estão nas colunas da fábrica.

## Merge e GitHub

- `main` protegida: exige o check `build` com branch atualizada (administradores podem passar por cima). PRs foram mergeados com `--rebase`, para manter um commit por issue.
- **O token do `gh` não tem o escopo `workflow`**: o GitHub recusa push por HTTPS que toque `.github/workflows/`. Fazer esse push por SSH (`git push git@github.com:kennedykjj/shipfloor.git <branch>`) ou rodar `gh auth refresh -h github.com -s workflow`.
- Commits e PRs em português; código em inglês.

## Rodar janelas (app, spikes)

- O JDK do sistema é headless: o `run` do Compose precisa do `javaHome` do toolchain (o spike usa o JetBrains Runtime via foojay).
- A sessão pode herdar um `XAUTHORITY` velho. O certo está em `systemctl --user show-environment`.
- Comandos que abrem janela precisam rodar fora do sandbox do Claude Code.

## Estado em 2026-10-15, fim do dia

- Fase 0 fechada (#1 a #7). O #6 foi implementado à mão para a validação da V0 começar em cards reais.
- #8 (Card e colunas) e #9 (Evento e ator) em refine pela V0; #15 (Daemon com socket) na fila, esperando um dos 2 slots de refine da máquina.
- Em aberto com o dono: subir o refine da máquina para 3 (`shipfloor pool stage refine 3`), que vale para todos os projetos.
