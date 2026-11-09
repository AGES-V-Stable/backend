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

Defina `JWT_SECRET` com uma chave própria antes de iniciar a aplicação. O projeto não
inclui mais uma chave JWT padrão. O PostgreSQL local usa `DB_SSL_MODE=disable` por
padrão; fora do perfil `lambda`, configure TLS ao conectar a um banco remoto.

## API na AWS Lambda

`Dockerfile.lambda` empacota a mesma aplicação Spring Boot com o [AWS Lambda Web
Adapter](https://github.com/aws/aws-lambda-web-adapter). Os controllers e as regras de
autorização continuam atendendo as mesmas rotas. A imagem local do `Dockerfile`
continua disponível para o Docker Compose.

Build da imagem para a arquitetura x86_64 da função:

```bash
docker build --platform linux/amd64 -f Dockerfile.lambda -t vstable-backend-lambda:local .
```

O Terraform agora define dois ambientes independentes em
[`terraform/environments/staging`](terraform/environments/staging/) e
[`terraform/environments/production`](terraform/environments/production/). Cada um
cria rede, RDS PostgreSQL, ECR, Lambda/API Gateway e hospedagem React privada em
S3 com CloudFront. O CORS da Lambda usa a URL CloudFront do próprio ambiente.
O frontend existente está em [AGES-V-Stable/frontend](https://github.com/AGES-V-Stable/frontend)
e usa `VITE_API_URL` no build para chamar a API do ambiente.

Veja o [guia de implantação Terraform](terraform/README.md) para aplicar o
bootstrap OIDC, provisionar os ambientes em estados separados, publicar a imagem
Lambda e configurar o workflow de deploy do frontend. O staging recebe merges
em `main`; produção recebe releases estáveis. O runner Terraform continua
precisando de acesso à rede privada do RDS para criar o usuário da aplicação.

O workflow [deploy.yml](.github/workflows/deploy.yml) publica o backend de forma
independente do frontend: `main` em staging e GitHub Releases estáveis em
produção. Cada ambiente tem uma Lambda privada para criar um super-admin
configurado em segredo do GitHub; staging também recebe dados sintéticos.
Configure os valores de ambiente e segredos conforme o
[guia Terraform](terraform/README.md#backend-github-actions).

Formato do `SecretString` (valores ilustrativos; não versionar credenciais):

```json
{
  "DB_USERNAME": "<usuario-do-banco>",
  "DB_PASSWORD": "<senha-do-banco>",
  "JWT_SECRET": "<chave-HS512-com-pelo-menos-64-bytes>",
  "AVENIA_API_KEY": "<chave-se-necessaria>",
  "AVENIA_PRIVATE_KEY": "<chave-privada-se-necessaria>",
  "AVENIA_EMAIL": "<email-se-necessario>",
  "AVENIA_PASSWORD": "<senha-se-necessaria>",
  "BLINDPAY_API_KEY": "<chave-se-necessaria>",
  "BLINDPAY_INSTANCE_ID": "<id-se-necessario>",
  "BLINDPAY_WEBHOOK_SECRET": "<segredo-se-necessario>"
}
```

O perfil `lambda` falha na inicialização se o ARN ou as três chaves obrigatórias
(`DB_USERNAME`, `DB_PASSWORD`, `JWT_SECRET`) estiverem ausentes. O Flyway aplica as
migrations no startup e o Hibernate valida o schema. A conexão JDBC do perfil
`lambda` exige TLS com validação do certificado e do hostname do RDS Proxy usando
o bundle global de CA do RDS incluído na imagem. Publique primeiro em staging e
teste login, JWT válido/inválido, CORS e o download de comprovante PDF pela URL do
API Gateway antes de encaminhar tráfego de produção.

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

## 4. Testes e análise de mutação (PITest)

Suíte padrão (unitários, slices web e migrações quando Docker está disponível):

```bash
./gradlew test
```

Análise de mutação com PITest (tarefa dedicada e independente de `test`, `check`, `build` e `bootJar`):

```bash
./gradlew pitest
```

* **Relatórios:** gerados em caminho fixo em `build/reports/pitest/index.html` e `build/reports/pitest/mutations.xml` (`timestampedReports = false`). Como o Gradle faz cache incremental das entradas e saídas da tarefa, use `./gradlew pitest --rerun-tasks` se quiser forçar nova execução sem mudanças no código.
* **Isolamento:** exclui a tag `blindpay-sandbox`, testes de integração de banco/Testcontainers (`*IntegrationTest` de persistência/serviço) e testes puros de infraestrutura/wiring (`*ContextTest`, `LoggingFormatTest`). Em runtime, as JVMs do PIT recebem `-Dspring.profiles.active=test`, segredo JWT exclusivamente de teste, endpoints loopback inválidos (`http://127.0.0.1:0`) para Avenia e BlindPay, `APP_CONFIG_SECRET_ARN` vazio e conciliação desabilitada; adicionalmente, `LambdaSecretsEnvironmentPostProcessor` fica fora do alvo de mutação e `avoidCallsTo` suprime mutações em linhas de chamada ao AWS SDK e loggers (não sendo um bloqueador de rede em runtime).
* **Linha de base e leitura do relatório:** não há `mutationThreshold` mínimo configurado nesta etapa inicial, e `repository.specification.*` permanece fora do escopo até haver testes unitários/isolados dedicados para essas Specifications. No `mutations.xml`, o atributo `partial="true"` reflete o modo padrão de cobertura parcial (`shouldReportCoverage`) do `XMLReportListener`, e status `TIMED_OUT` indica mutante detectado por loop/tempo excedido (a revisar na curadoria da baseline). No CI (`pr-to-trunk.yml` e `push-on-trunk.yml`), o PIT roda em step explícito após o build/testes e publica `pitest-report` como artefato.

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
