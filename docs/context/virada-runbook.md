# Virada: a shipfloor assume o próprio repositório (#62)

Passo a passo do dono para trocar a V0 pela shipfloor neste repositório, com o caminho de volta. Nada daqui roda sozinho: cada passo é do dono (#62 tem a label `manual`).

## Antes de começar

- `./gradlew build` verde na `main`, e nenhum run da V0 em andamento: `ls ~/.shipfloor/projects/shipfloor/locks` vazio e nenhum card em Coding, Validation ou Ready for Prod.
- `shipfloor doctor` sem FAIL (java, git, gh, claude, socket, banco, serviço, permissões, ambiente do serviço).
- `~/.config/shipfloor/service.env` com `SHIPFLOOR_DISPATCHER_ENABLED=true` (o valor é `true`, não `1`: qualquer outro texto mantém o dispatcher desligado), o `PATH` com git, gh, java e claude e, se precisar, `JAVA_HOME`. Modo 0600. Sem `SHIPFLOOR_COST_UNLIMITED`: o teto de custo vem do `factory.yml`.
- `prices.yml` e os limites de custo no `.shipfloor/factory.yml` preenchidos; sem preço o dispatcher não sobe.

## Passos

1. **Pausar a V0** para o projeto: `touch ~/.shipfloor/projects/shipfloor/PAUSE`. O `board.tsv` fica parado a partir daqui.
2. **Instalar e subir o serviço**: `shipfloor service install` (cria a unit do systemd do usuário e liga o serviço; se aparecer o aviso de `linger`, `loginctl enable-linger` faz o serviço subir no boot).
3. **Registrar o projeto no daemon**: hoje só existe o comando de socket `project.add` (sem comando na CLI); com a prova do dono (`owner.token`, modo 0600, ao lado do socket) envie `{"cmd":"project.add","actor":"owner","path":"<repositório>","proof":"<token>"}`. O daemon lê o `factory.yml` da `main` do repositório.
4. **Importar o board da V0**: `shipfloor card import-board ~/.shipfloor/projects/shipfloor/board.tsv`. Cards Inbox, Ready for Refinement, Technical Refinement e Done entram na coluna do mesmo nome; Ready for Dev, Coding, Validation, Staged e Ready for Prod entram em Spec Review (a shipfloor não tem o worktree, a branch nem o head aprovado deles) com `v0_column=<nome original>` no evento. As issues abertas fora do board entram no Inbox. A importação não duplica: pode rodar de novo.
5. **Conferir**: `shipfloor card list` bate com o board; `shipfloor doctor` sem FAIL; o log do serviço aparece em `journalctl --user -t shipfloor`.
6. **Promover os cards** de Spec Review que quiser reexecutar (aprovar em `shipfloor card approve <id>`) e acompanhar o primeiro ciclo completo antes de largar.
7. **Dez cards seguidos** entregues pela shipfloor com a V0 pausada, registrando custo e lead time (`report.csv`) para comparar com a V0.
8. **Arquivar a V0** para este repositório só depois dos dez cards e da comparação.

## Caminho de volta

- `shipfloor service uninstall` para o serviço e remove a unit.
- `rm ~/.shipfloor/projects/shipfloor/PAUSE` religa a V0. O `board.tsv` volta a ser atualizado no próximo ciclo; cards movidos só pela shipfloor precisam ser movidos no board à mão.
- Nada da importação apaga a V0: o board e o estado dela ficam intactos até o passo 8.

## Cuidados

- Nunca deixe os dois operando o mesmo projeto: a V0 e a shipfloor movendo o mesmo card repetem o G16 e o G22 (`docs/context/gargalos-da-v0.md`).
- O token do dono (`owner.token`) e o `service.env` nunca vão para o repositório nem para o log.
