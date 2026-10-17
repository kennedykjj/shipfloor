# Product

<!-- impeccable:product-schema 1 -->

Shipfloor é a fábrica de software local; esta é a segunda versão (a primeira, o Shipfloor V0, está arquivada em `kennedykjj/shipfloor-legacy`). Desenho, fluxos e decisões: https://claude.ai/artifact/8D5VdReqza6nbwu3EMLH9u. Plano de execução: https://claude.ai/artifact/MftqL6htDj2ngM4FsCVc9J.

## Platform

desktop (Linux primeiro; macOS e Windows na fase 8)

## Stack

Kotlin na JVM, Gradle multi-módulo. Daemon local com SQLite (SQLDelight), CLI (Clikt), servidor MCP para os agentes, app desktop em Compose Multiplatform. Detalhes em ARCHITECTURE.md.

## Users

- O desenvolvedor sozinho com muitos projetos: projetos pessoais e vibe-coded, até dez numa máquina, sem configurar board nenhum.
- O desenvolvedor dentro de uma empresa: Jira, revisores obrigatórios, convenções da empresa, trilha de auditoria. A fábrica produz; ele decide e assina.

Um dono por fábrica, numa máquina. Não é ferramenta de time: colaboração acontece nas ferramentas da empresa, onde a shipfloor importa e fecha.

## Product Purpose

Uma squad de desenvolvimento para cada desenvolvedor. Agentes fazem o trabalho (refinar, implementar, validar, entregar); gates determinísticos seguram a qualidade; o dono aparece só onde a decisão é dele: aprovar a spec e liberar a entrega.

## Positioning

- A fábrica é dona do próprio estado. O card vive na máquina; GitHub, Jira e afins são tocados só na importação, em marcos opcionais e no fechamento confirmado.
- Gates são scripts, não a palavra do agente. O que move um card é um gate verde, nunca o relato de um agente.
- Custo medido das transcrições de cada run, não do que o agente diz.
- Toda ação é um evento com ator. A aprovação do dono é um evento assinado, não um comentário.

## Operating Context

Um daemon por máquina, sempre ligado, é o único escritor do estado. App, CLI e agentes são clientes: falam com ele por socket local (app e CLI) ou por MCP (agentes). A fábrica trabalha também com o app fechado e fora do horário do dono.

## Capabilities and Constraints

- Colunas, em ordem: Inbox, Ready for Refinement, Refinement, Spec Review, Ready for Dev, Coding, Validation, Staged, Ready for Prod, Done. Decisões do dono: Spec Review (aprovar a spec) e Staged (liberar para Ready for Prod); um card em Ready for Prod já foi liberado e segue em entrega.
- Modo de entrega por projeto: autônomo (a fábrica mergeia) ou entrega no PR (para em revisão humana).
- Importar e fechar, sem sincronização contínua com o tracker externo.
- Texto externo (issue, ticket, comentário) entra por uma porta só, marcado como não confiável.
- Nada sai da máquina sem evento assinado pelo dono ou política explícita do projeto que dispense a assinatura.
- Custo é estimativa a partir de uma tabela de preços, sempre rotulada como estimativa.

## What it is not

- Não é um board de time nem um substituto do Jira.
- Não é um agente que decide sozinho o que vai para produção.
- Não sincroniza continuamente com ferramentas externas.

## Brand Commitments

Nome: Shipfloor (o sfv2 foi o nome de trabalho). Herda do V0 a metáfora do chão de fábrica, o ícone (a fita de chão amarela que leva um card do Inbox ao Done) e o DESIGN.md. Um assistente da fábrica (personalidade sem rosto, com skins) entra na fase 7, sempre com modo silencioso.
