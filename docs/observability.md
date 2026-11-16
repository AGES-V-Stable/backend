# Observability

Acesse **Observability** no menu administrativo (`/admin/observability`). Somente contas
com `ROLE_ADMIN` consultam eventos e detalhes. A autorização é conferida no backend;
alterar o token ou o menu local não concede acesso.

O Flyway aplica `V21__add_observability_events.sql` na inicialização. Não há serviço,
dependência ou credencial adicional. A implementação reutiliza SLF4J/Logback,
PostgreSQL/JPA, JWT, `X-Request-ID`, tabela e drawer existentes.

## Eventos coletados

* Requisições HTTP: método, template da rota, status real e duração. Falhas são
  atribuídas ao módulo da rota. Corpos, queries e caminhos brutos são descartados.
* Integrações Avenia/BlindPay, agregação de cotações e atualização do câmbio:
  resultado conhecido, duração e contexto técnico permitido.
* Onboarding, criação de beneficiários e transferências, etapas de KYC,
  documentos e liveness, já instrumentados em `OperationalLog`.
* Criação, alteração e exclusão de empresas; alteração de perfil e revogação de
  membros; criação, regeneração e revogação de convites.
* Mudanças efetivas de status das transações na conciliação Avenia e execução
  BlindPay; falhas dos jobs de conciliação.
* Rejeições de autenticação e erros inesperados (somente tipo da exceção na aba).
* Falhas de rede, JSON inválido e erros de interface/renderização do navegador.
  Falhas de upload direto ao storage também são relatadas como erro de interface
  do onboarding, sem enviar URL assinada, nome, conteúdo ou headers do arquivo.

Marcos de negócio em transações são emitidos **depois do commit** e suprimidos no
rollback. Concluir a criação de uma transferência não implica sua liquidação.
Eventos HTTP registram o resultado da requisição, inclusive falhas anteriores a um
marco de negócio. Uma operação pode gerar vários eventos: os indicadores contam
eventos, não operações únicas.

Cada evento possui UUID, instante UTC, severidade, módulo, ação, ID interno do ator
quando autenticado, entidade quando disponível, resultado, duração quando disponível,
mensagem técnica fixa, origem, correlação e metadados permitidos. Jobs sem usuário
não inventam uma identidade. Nunca se usa o e-mail como identidade no evento.

Para instrumentar novos serviços, use `OperationalLog.milestone` com nome e resultado
fixos e, quando disponível, tipo da entidade e UUID interno. Não passe DTOs, mensagens
de exceção, respostas de provedores, nomes ou valores financeiros. O armazenamento
projeta uma lista permitida de campos; não persiste mensagens livres nem stack traces.

## Correlação e navegador

Uma interação do usuário inicia um UUID, compartilhado pelas requisições iniciadas
por ela. O contexto expira após 30 segundos; novas interações iniciam outro UUID.
`X-Request-ID` acompanha a requisição e a resposta; o backend valida o UUID e associa
o ID do usuário carregado da conta autenticada. O contexto acompanha os workers de
cotações e os callbacks de commit. IDs de correlação são contexto de diagnóstico,
nunca evidência de identidade. Nos detalhes, **Ver eventos desta correlação** aplica
esse filtro ao período selecionado.

`POST /v1/observability/browser-events` exige autenticação e perfil USER/ADMIN.
Aceita apenas UUID do evento, UUID da correlação e enums de categoria/módulo. Campos
extras não são armazenados. Severidade, identidade e horário são definidos no servidor.
Os relatos ficam identificados como `source=browser`, `trusted=false`.

O endpoint limita o corpo real a **1024 bytes**, inclusive sem Content-Length, antes
da desserialização; são **20 tentativas/minuto por conta por instância**, com mapa
limitado a 5000 contas e janelas expiradas descartadas. Responde 413 para excesso de
bytes, 429 com `Retry-After` para frequência e 400 para contrato inválido. É um limite
local: múltiplas instâncias/Lambda não compartilham o contador; uma regra na borda
é necessária se o ambiente exigir limite global.

O frontend limita o envio a 10/minuto, deduplica a mesma exceção/categoria e usa
transporte sem recursão. A unicidade `(actor_id, client_event_id)` elimina reenvios no
banco. Falhas HTTP já registradas pelo backend não geram um segundo relato do browser.
Mensagens, stacks, URLs, headers, payloads e valores de formulários não são enviados
no corpo da telemetria. O bearer token é usado somente para autenticar o transporte.

## Consulta e configuração

* `GET /v1/admin/observability/events`: filtros `from`/`to` (ISO UTC), `severity`,
  `module`, `action`, `outcome`, `correlationId`; `page` a partir de zero, `size` 1–100,
  `order=asc|desc`. Padrão: últimos 7 dias, 25 itens, mais recentes primeiro.
  Máximo de 90 dias por consulta e página 1000. Ordenação estável por data e UUID.
* A resposta inclui total, quantidade de ERROR e falhas agrupadas por módulo,
  calculadas sobre **os mesmos filtros**, sem carregar o histórico inteiro em memória.
* `GET /v1/admin/observability/events/{id}` retorna os detalhes de um evento retido.
* `OBSERVABILITY_RETENTION_DAYS`: padrão **30**, permitido 1–365. Eventos vencidos
  deixam de aparecer imediatamente; a limpeza física roda a cada minuto em lotes de
  até 5000. Índices cobrem data, severidade, módulo, ação, resultado e correlação.
* `OBSERVABILITY_ENABLED`: padrão `true`; `false` desativa coleta e limpeza, mantendo
  o histórico consultável. Os testes gerais desativam a coleta para isolar contextos
  Spring com bancos diferentes; o teste integrado de observabilidade a habilita.
* Preserve o logger `ages.vstable.backend.logging.OperationalLog` e
  `ages.vstable.backend.logging.RequestLoggingFilter` em INFO para coletar também
  sucessos. A persistência consome os eventos estruturados desses dois loggers;
  elevar seu nível remove os eventos inferiores desse armazenamento e do stdout.

A gravação é **best effort**: fila de 2048 eventos, lotes de até 100 a cada 200 ms,
worker dedicado e transações independentes. Falha de banco ou fila cheia descarta
eventos, sem interromper operações de negócio; perdas geram aviso técnico com
`droppedEvents` quando uma gravação falha. Não é um livro de auditoria sem perdas.
Queda abrupta pode perder a fila. Em Lambda, a suspensão pode adiar a gravação até
outra invocação; retenção física depende de tempo ativo. Para requisitos de auditoria
durável ou coleta garantida durante suspensão, é necessário um destino externo
durável/outbox. Os logs stdout existentes continuam disponíveis.

As próprias consultas/ingestões de observabilidade não são persistidas, evitando
recursão e crescimento provocado pelo uso da aba. Não se importam logs históricos.

## Validação

`ObservabilityIntegrationTest` usa PostgreSQL efêmero e JWT real para criar empresa,
verificar commit, persistência, filtros, detalhes, conflito, deduplicação e retenção,
sem chamadas externas. Testes de segurança cobrem acesso anônimo, USER e ADMIN,
validação, frequência e tamanho; testes unitários cobrem projeção segura e falhas do
armazenamento. O frontend testa transporte/correlação, deduplicação, navegação por
perfil, listagem, indicadores, filtros, paginação, detalhes e estados de erro/vazio.

```powershell
# backend (Java 25 e Docker)
.\gradlew.bat test
.\gradlew.bat build
# frontend (Node 24)
npm run audit
npm run format:check
npm run lint
npm run test:coverage
npm run build
```
