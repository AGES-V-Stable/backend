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

### Painel administrativo

Acima dos logs, o painel usa Recharts para exibir ERROR, WARN e INFO ao longo do
tempo, quatro cartões (total e severidades) e os dez módulos com mais eventos
ERROR. O ranking conta severidade ERROR, não o campo `failed`: uma requisição 4xx
pode ser WARN e uma operação malsucedida sem ser ERROR.

`GET /v1/admin/observability/events/dashboard` aceita os mesmos filtros da listagem
e retorna `{summary, events}`. `summary` contém `from`, `to`, `interval`,
`generatedAt`, totais, `series` com `{from,to,error,warn,info}` e
`errorsByModule`. As consultas rodam em uma transação read-only REPEATABLE READ
com timeout de 5 segundos: agregados e logs veem o mesmo snapshot de registros
confirmados. As agregações ocorrem no PostgreSQL, sem carregar todos os eventos
na aplicação, e independem de `page` e `size`. O endpoint exige ADMIN.

Todos os filtros (inclusive ação, resultado e correlação) valem para o painel e a
listagem. O período padrão da tela é 24 horas; há opções de 1 hora, 7 dias e
intervalo personalizado, limitado a 90 dias e à retenção configurada. Atualizar
avança os períodos relativos e preserva os limites de períodos personalizados.
A tela informa o período efetivamente consultado, o fuso IANA do navegador e o
horário da última resposta bem-sucedida. Datas são enviadas e processadas em UTC.

As séries têm limites UTC e vêm ordenadas, com zero nos intervalos vazios:
minuto até 1 hora; hora até 2 dias; dia nos demais casos (máximo 91 pontos).
Intervalos usam início inclusivo e fim exclusivo, inclusive na listagem, para
evitar dupla contagem ao selecionar pontos adjacentes. As pontas podem ser
parciais. Os campos de início e fim definem o intervalo da consulta. Os botões
Ver ERROR, Ver WARN e Ver INFO exibem a série correspondente no gráfico e filtram
a tabela pelo mesmo intervalo e severidade. Selecionar um ponto preenche seus
limites e aplica sua severidade. Clicar novamente no botão ativo restaura todas
as séries e os filtros anteriores; editar o intervalo e clicar reaplica a consulta.
Falhas de consulta exibem erro e retentativa; períodos vazios exibem totais zero.
Requisições obsoletas são canceladas e suas respostas ignoradas.

Para conferir manualmente em ambiente de desenvolvimento:

1. Entre como administrador e abra `/admin/observability`. Selecione última hora.
2. Execute uma operação local já instrumentada, como criar uma empresa de teste,
   e uma tentativa com conflito (mesmo CNPJ). Volte e clique em **Atualizar**:
   os eventos de sucesso aparecem em INFO e o HTTP 409 aparece em WARN.
3. Para conferir ERROR sem provocar falhas em provedores, reproduza uma falha de
   interface em desenvolvimento ou envie uma categoria válida ao endpoint
   autenticado de browser-events descrito acima; o módulo e a origem browser
   devem aparecer nos logs e nos agregados. Não use dados de produção.
4. Confira o total da tabela contra o cartão; mude a página e verifique que os
   totais e as séries permanecem iguais. A soma das três séries deve corresponder
   ao total. Filtre módulo/severidade e selecione um ponto para conferir seus logs.
5. Confira um período sem eventos, um intervalo inválido e uma falha de consulta
   (backend indisponível). Refaça filtros rapidamente para validar a atualização.

Os testes integrados verificam o painel com eventos reais de criação, conflito
HTTP e ingestão do navegador, além de limites de buckets, zeros, retenção,
granularidade, filtros, paginação e permissões. Dados artificiais de fronteira
são exclusivos dos testes.

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
