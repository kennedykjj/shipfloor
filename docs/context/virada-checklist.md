# Checklist da Virada (#62)

Roteiro para o dono executar quando decidir a hora. Nada aqui foi executado. Cada comando traz a situação da fonte:

- **lido**: confirmado no código de `main` em 2026-10-16.
- **a confirmar**: inferido ou não encontrado; testar antes de confiar.

## 0. Antes de começar

1. A V0 só pode ter terminado os cards que já tinha. Conferir pelo board (REST, nunca `gh project item-list`; ver `factory-operations.md`) que nenhum card está em Refinement, Coding ou Validation e que `~/.shipfloor/projects/shipfloor/locks/` está vazio. (**lido**)
2. `main` verde e sem PR aberto da fábrica.
3. Guardar o estado: `cp ~/.shipfloor/projects/shipfloor/board.tsv ~/board-v0-antes-da-virada.tsv`. O `board.tsv` só se atualiza com o dispatcher rodando; se o projeto está pausado, ele pode estar velho, então gere o TSV de novo pela REST. (**lido**)

## 1. Parar a V0 neste projeto

`touch ~/.shipfloor/projects/shipfloor/PAUSE` faz o dispatcher da V0 pular o projeto inteiro. (**lido**, `factory-operations.md`)

## 2. Subir a shipfloor

1. `./gradlew :daemon:installDist :cli:installDist` gera `daemon/build/install/shipfloor-daemon/bin/shipfloor-daemon` e o `shipfloor` da CLI. (**lido**: plugin `application` nos dois módulos)
2. `daemon/build/install/shipfloor-daemon/bin/shipfloor-daemon service install` instala e inicia a unidade `shipfloor.service` do systemd do usuário. Tem que ser pelo script de partida, porque ele define `-Dshipfloor.launcher`. (**lido**)
3. Se o comando avisar sobre linger: `loginctl enable-linger $USER`, para o serviço subir sem sessão aberta. (**lido**)
4. `shipfloor doctor` confere java, git, gh, claude, socket, banco e serviço. Tudo OK antes de seguir. (**lido**)

## 3. Registrar o projeto e importar o board

1. `shipfloor project add ~/git/shipfloor`: o daemon lê o `factory.yml` de `main`. (**lido**) O `.shipfloor/factory.yml` de hoje é o da V0 (`board:`, `forbidden:`); **a confirmar** se o daemon aceita esse arquivo como está.
2. `cut -f1,2 board.tsv > /tmp/board-import.tsv` e `shipfloor card import-board /tmp/board-import.tsv --project shipfloor`. O formato é `issue<TAB>coluna`. (**lido**) **A confirmar**: o nome das colunas da V0 (`Ready for Dev`, `Ready for Prod`) contra as da shipfloor.
3. Rodar a importação uma segunda vez: não pode duplicar (critério 2 do #60). Depois `shipfloor card list` e comparar a contagem por coluna com o board. (**a confirmar**: sintaxe exata do `list`)

## 4. Primeiro card

1. Escolher um card leve da fila e movê-lo: `shipfloor card move <n> "Ready for Refinement"` (movimento só do dono). (**a confirmar**: sintaxe do `move`)
2. Acompanhar com `shipfloor events` e `systemctl --user status shipfloor`. O dispatcher só liga se a tabela de preços carregou; se ficar parado, é isso. (**lido**: `dispatcherAllowed` em `Main.kt`)

## 5. Contar os dez cards (critério 3 do #62)

Manter a lista num comentário do #62: número do card, hora de entrada em Inbox e de Done, PR. Só conta card entregue sem a V0 ligada.

## 6. Se a shipfloor travar num card

A V0 e a shipfloor **não podem rodar juntas no mesmo projeto**: duas fábricas no mesmo board abrem worktrees e PRs duplicados (**inferência**, não testada). Ordem segura:

1. `systemctl --user stop shipfloor`
2. mover o card para a coluna da V0 e `rm ~/.shipfloor/projects/shipfloor/PAUSE`
3. deixar a V0 terminar esse card, `touch .../PAUSE` de novo e `systemctl --user start shipfloor`

## 7. Fechar a Virada

1. Comparar custo e lead time por card nos dois (critério 4). **A confirmar**: o relatório do #61 não aparece como comando da CLI (não achei comando `report` no código da CLI).
2. Arquivar a V0 para este repositório (critério 5): remover o projeto da config da V0 e, no fim, `.shipfloor/` deixa de ser lido. Alterar `.shipfloor/` é à mão (CLAUDE.md).
3. Fechar o #62 e mover o item para Done no board (card manual fica parado na coluna; ver `factory-operations.md`).
