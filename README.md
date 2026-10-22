# Desenvolvimento Local

Este projeto utiliza **PostgreSQL via Docker** e **Spring Boot executado localmente**. 

## Pré-requisitos

Antes de iniciar o projeto, certifique-se de ter instalado:

* [Docker](https://www.docker.com/)
* [Java](https://www.oracle.com/java/technologies/downloads/)
* IDE de sua preferência (recomendado [IntelliJ](https://www.jetbrains.com/idea/))

## 1. Subir o banco de dados

No diretório raiz do projeto, começe o Postgres:

```bash
docker compose up -d db
```

## 2. Rodar o Spring Boot

### Gradle

Linux/macOS:

```bash
./gradlew bootRun
```

Windows:

```powershell
.\gradlew.bat bootRun
```

## 3. Dados de teste (perfil dev)

Para popular o banco local, configure as variáveis de ambiente `SPRING_PROFILES_ACTIVE=dev`
e `DEV_SEED_PASSWORD` antes de iniciar a aplicação. Escolha uma senha local; ela será
armazenada com o mesmo encoder usado no cadastro. No IntelliJ, configure essas variáveis
na configuração de execução. Sem o perfil `dev`, o seeder não é executado.

O `DevelopmentDataSeeder`, ao lado de `BackendApplication`, cria uma empresa aprovada,
um usuário (`dev.user@example.test`), um administrador (`dev.admin@example.test`), uma
verificação KYC de exemplo, dois beneficiários (conta bancária e carteira) e três
transações em estados diferentes. Os dois logins usam a senha de `DEV_SEED_PASSWORD`.
Todos os dados são fictícios e a criação não chama Avenia ou BlindPay.

A presença de `dev.user@example.test` indica que a carga já foi realizada. Todos os
registros são gravados na mesma transação: uma falha desfaz a carga inteira e permite
uma nova tentativa. Reiniciar a aplicação preserva os dados e as senhas existentes,
sem duplicar registros. A senha só é obrigatória na primeira carga; sua ausência
nesse momento interrompe a inicialização com uma mensagem de configuração.

### Contrato de erros para o frontend

Respostas do `GlobalExceptionHandler` incluem `message` e um `code` estável, preservando os status HTTP existentes. Códigos: `INVALID_ARGUMENT`, `VALIDATION_ERROR`, `MALFORMED_PAYLOAD`, `MISSING_HEADER`, `INVALID_PARAMETER`, `CONFLICT`, `FORBIDDEN`, `NOT_FOUND`, `UNPROCESSABLE_ENTITY`, `PROVIDER_ERROR`, `ACCESS_DENIED` e `INTERNAL_ERROR`. Login inválido retorna 401 com `INVALID_CREDENTIALS`; conta bloqueada retorna 423 com `ACCOUNT_LOCKED`. Contratos especializados de detalhes/comprovantes e códigos da camada de segurança são preservados. Clientes antigos podem continuar consumindo somente `message`.

## Operational logging

The backend writes logs to stdout. Local runs use readable text, including structured
key/value fields and `requestId`. Docker Compose sets
`LOGGING_STRUCTURED_FORMAT_CONSOLE=logstash` for one JSON object per event. You can also
set that variable for a local run. The existing SLF4J/Logback stack handles both formats;
no extra logging dependency or database is used.

Levels default to `INFO`. Override `LOG_LEVEL_APP`, `LOG_LEVEL_SPRING`, or
`LOG_LEVEL_ROOT` when diagnosing a problem. SQL statements, JDBC bind parameters,
request-body logging, and Apache HTTP wire/header logging stay disabled. Payload-capable
web, security, Hibernate, and HTTP-client loggers remain at `INFO` or quieter even when
broader application/framework levels are set to `DEBUG`. Do not enable
payload/wire logging or add sensitive values to log statements.

### Following a request

Send an optional `X-Request-ID` containing a UUID. Missing or invalid values are
replaced with a generated UUID, and every response includes the selected ID. CORS
allows this request header and exposes the response header. IDs correlate HTTP,
provider, and business events, including parallel quote workers; they are not identity
or authorization evidence.

```bash
docker compose logs --follow backend
docker compose logs --no-log-prefix backend | rg 'YOUR-REQUEST-UUID'
```

The backend container uses Docker's `local` logging driver, rotating at 10 MB with at
most five files per container. This is bounded local storage, not durable audit history.
Use `docker compose up -d --build backend` to apply the logging configuration to an
existing container. No database migration is needed.

### Event fields and behavior

All application events use fixed English names. JSON includes timestamp, level,
logger, message, and `requestId` when running within a request. Readable output shows
the same application fields as key/value pairs.

| Event | Additional fields / outcomes |
| --- | --- |
| `http.request.completed` | `operation=http.request`, `method`, `route`, `status`, `durationMs`, `outcome=success/failure` |
| `provider.call.completed` | `provider`, `operation`, optional HTTP `status`, `durationMs`, `outcome=success/http_failure/communication_failure/timeout/failure` |
| `quote.aggregation.completed` | `operation=quote.aggregate`, `durationMs`, `timeoutCount`, `outcome=success/partial/failure` |
| `authentication.rejected` | `operation=authenticate`, fixed rejection classification in `outcome` |
| `application.error` | `exceptionType`, `stackFrames`, `outcome=unexpected_failure` |
| Business milestones below | `operation` equal to event name, `outcome` |

Provider operation names use a fixed HTTP verb and response DTO class, for example
`get.AveniaQuoteResponse` or `post.BlindPayQuoteResponse`; no URI or account identifier
is included. Each attempted client exchange emits one provider event. Local validation,
serialization, or signing that fails before an exchange starts is described by the
request/error events. A quote aggregation timeout does not cancel a running HTTP call:
`timeoutCount` records that deadline independently of any later provider completion.

HTTP completion events are unsampled: successes and redirects use `INFO`, 4xx use
`WARN`, and 5xx use `ERROR`. Routes are matched templates such as `/v1/companies/{id}`;
requests rejected before routing use `UNMATCHED`. Raw paths and query strings are
excluded. Expected errors are represented by completion events; unexpected failures
also produce one diagnostic event at their handling boundary.

Business events cover `onboarding.completed`, `beneficiary.creation.completed`,
`transfer.creation.completed`, `kyc.submission.completed`, `document.upload.started`,
`document.submission.completed`, `liveness.started`, and `liveness.submission.completed`.
Transactional success events emit after commit and are suppressed on rollback. Other
milestones emit when the service finishes; missing compliance records use `unavailable`.
KYC submission outcomes report the known state (`pending`, `under_review`, `approved`,
`rejected`), including idempotent calls that reuse an existing submission. A completed
transfer-creation operation does not imply settlement or payment completion.

Log fields exclude credentials, bearer tokens, headers, bodies, personal and financial
data, customer/company identifiers, and provider error text. Unexpected diagnostics
keep exception class names and code locations (up to eight causes and forty frames per
cause), excluding exception messages and suppressed exceptions. Do not pass original
exceptions or DTOs directly to loggers; their messages and `toString()` can contain
sensitive data. Request context is restored on servlet and worker threads after use.
