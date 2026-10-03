# AGENTS.md

Instruções para agentes que alteram o backend V-Stable. Use este arquivo como mapa; confirme detalhes no código e nos arquivos de configuração antes de mudar comportamento.

## Projeto e arquitetura

- Stack: Java 25, Gradle Wrapper, Spring Boot, PostgreSQL, Spring Data JPA, Spring Security, JWT e Flyway. O CI usa Temurin 25.
- Código de produção em `src/main/java/ages/vstable/backend/`: `controller/` adapta HTTP; `service/` concentra regras e casos de uso; `repository/` acessa persistência; `entity/` mapeia o domínio persistido; `dto/` define contratos de entrada e saída; `external/` integra Avenia e BlindPay; `configuration/security/` configura autenticação e autorização; `exception/` traduz erros para respostas HTTP.
- Mantenha essas fronteiras. Controller deve permanecer fino; regra de negócio pertence a service; acesso a dados pertence a repository. Não exponha entities como contratos HTTP nem acople regras de negócio diretamente a uma integração externa.
- Antes de mudar um endpoint, entidade, regra ou integração, leia seus consumidores e testes. Preserve contratos existentes, salvo quando a tarefa pedir explicitamente uma mudança compatível com o frontend e demais consumidores.

## Regras de implementação e segurança

- Prefira soluções simples e coesas. Aplique Clean Code, GRASP, KISS e YAGNI na prática: nomes claros, responsabilidades bem atribuídas e nenhuma abstração especulativa.
- Valide entradas na fronteira HTTP usando os padrões existentes. Trate dados recebidos de clientes e provedores externos como não confiáveis; converta erros externos em respostas previsíveis sem vazar detalhes internos.
- Preserve os controles de autenticação e autorização em `configuration/security/`. Mudanças em rotas públicas, JWT, CORS ou permissões precisam de testes que cubram acesso permitido e negado.
- Nunca registre nem exponha credenciais, tokens, dados pessoais, documentos de compliance ou dados financeiros. Não adicione segredos a código, testes, logs ou arquivos versionados; use propriedades por ambiente.
- `application.yaml` mantém `ddl-auto: validate` e Flyway habilitado. Mudanças de schema devem ser feitas em uma nova migration versionada em `src/main/resources/db/migration/`; não edite migrations que já possam ter sido aplicadas.
- Isole chamadas à Avenia e BlindPay em `external/`. Testes não devem chamar serviços reais nem depender de credenciais reais. Não adicione dependências ou novas camadas sem necessidade demonstrável.
- Siga a formatação do código Java próximo. Atualmente o Gradle não configura um formatter ou linter Java como gate; não afirme que essa validação é executada e evite reformatar arquivos fora do escopo.

## Testes

- Toda mudança que altera comportamento deve incluir ou atualizar teste da funcionalidade no mesmo PR. Não é obrigatório escrever o teste antes da implementação; é obrigatório entregar a mudança comportamental com teste.
- Use JUnit e os padrões já presentes: testes unitários para regras isoladas; testes de controller para contratos HTTP; integração com Testcontainers quando for necessário validar persistência ou configuração real do PostgreSQL.
- Estruture testes em Arrange, Act, Assert (Given, When, Then). Mantenha-os rápidos, independentes, repetíveis, auto-validáveis e abrangentes; use nomes que expressem cenário e resultado.
- Não faça chamadas reais a Avenia, BlindPay ou outros serviços externos. Evite sleeps, dependência de ordem entre testes e mocks de detalhes internos sem relevância para o contrato.
- A meta de qualidade do projeto é cobertura global mínima de 80% para linhas e branches. A configuração atual do JaCoCo gera relatórios, mas não aplica um limite mínimo ao build; não afirme que o CI bloqueia cobertura abaixo de 80% até que essa regra seja configurada no Gradle.

## Validação

Para alterações Java, execute os testes e o build localmente quando possível:

```bash
./gradlew test
./gradlew build
```

Os testes de integração que usam Testcontainers podem exigir Docker. O CI executa `./gradlew build sonar --no-daemon` em pull requests para `main` e em pushes para `main`; a análise Sonar pode depender dos secrets configurados no GitHub. Não invente resultados: se uma verificação não puder ser executada, informe qual e por quê.
