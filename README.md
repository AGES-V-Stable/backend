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

Os convites de membros exigem duas variáveis, sem valor padrão, que no desenvolvimento local
vão no `.env` (não versionado; o Docker Compose o repassa ao container) ou na configuração
de execução do IDE: `INVITATION_ENCRYPTION_KEY`
(chave AES de 32 bytes em Base64, por exemplo `openssl rand -base64 32`; cifra a cópia
recuperável do token e nunca vai para o banco nem para o Git) e `INVITATION_FRONTEND_ORIGIN`
(origem pública do frontend do ambiente, usada para montar o link do convite).
`INVITATION_KEY_VERSION` (padrão `v1`) identifica a chave em uso e deve mudar quando ela for trocada.
O aceite do convite registra a versão dos termos vigente: `TERMS_VERSION` (até 50 caracteres), com os
endereços `TERMS_URL` e `PRIVACY_URL` (URLs http(s) absolutas). Fora da Lambda há padrões só para
desenvolvimento (`v1`, `/termos-de-uso` e `/politica-de-privacidade` na origem do frontend); no perfil
`lambda` as três variáveis são obrigatórias e a aplicação não sobe sem elas, para que um valor provisório
nunca seja tratado como publicação oficial. Trocar `TERMS_VERSION` faz aceites enviados com a versão
anterior responderem 409 `TERMS_VERSION_MISMATCH`.

`INVITATION_RATE_LIMIT_TRUSTED_PROXY_HOPS` informa quantos proxies confiáveis acrescentam ao
`X-Forwarded-For` antes da aplicação, para identificar o cliente no limite de tentativas dos convites.
O cabeçalho só é lido quando a conexão chega pelo loopback (o Lambda Web Adapter); de qualquer outra
origem ele é ignorado. Use `0` (padrão local) para ignorá-lo, `1` para navegador → API Gateway regional
ou HTTP API, e `2` para CloudFront (ou endpoint edge-optimized) → API Gateway. No perfil `lambda` a
variável é obrigatória. Confirme o valor em staging antes da produção: de uma mesma máquina, 31
requisições a `/v1/company-invitations/resolve` com `X-Forwarded-For` forjado e diferente em cada uma
precisam terminar em 429.

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

O workflow [deploy.yml](.github/workflows/deploy.yml) publica `main` em staging e
releases estáveis em produção. Configure `MIGRATION_LAMBDA_FUNCTION_NAME`,
`SEED_LAMBDA_FUNCTION_NAME` e `BACKEND_LAMBDA_FUNCTION_NAME` com os outputs do
Terraform no GitHub Environment correspondente. As funções internas usam a
mesma imagem Spring da API, mas não têm API Gateway. O Lambda Web Adapter envia
invocações diretas para `POST /events`; o perfil Spring seleciona `migration` ou
`seed`. O workflow atualiza e invoca a migração de forma síncrona antes da API.
Em pushes para staging, ele também invoca o seed após a migração e antes da API.
Qualquer falha interrompe o deploy.

No GitHub Environment `staging`, configure `INITIAL_ADMIN_NAME` e
`INITIAL_ADMIN_EMAIL` como variables e `INITIAL_ADMIN_PASSWORD` como secret
(mínimo de 12 caracteres). O seed cria apenas o primeiro administrador com
acesso `SUPER_ADMIN`; invocações posteriores retornam `already_exists` sem
alterar a conta. O perfil `dev` continua responsável apenas pelos dados
sintéticos locais e não é usado no deploy.
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
(`DB_USERNAME`, `DB_PASSWORD`, `JWT_SECRET`) estiverem ausentes. O Flyway não roda
automaticamente nesse perfil: a função de migração executa as migrations quando
é invocada. O Hibernate valida o schema na API e no seed; a função de migração
desabilita essa validação para poder atualizar um schema ainda antigo. A conexão
JDBC do perfil `lambda` exige TLS com validação do certificado e do hostname do RDS usando
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

Acesso à empresa e convites usam códigos próprios, que o frontend deve tratar por `code` e nunca pela mensagem. 403: `COMPANY_ACCESS_DENIED` (sem vínculo com a empresa, outra empresa ou administrador em rota de empresa), `COMPANY_ACCESS_REVOKED` (vínculo removido), `PERMISSION_DENIED` (o perfil não tem a permissão) e `OWNER_REQUIRED` (ação só do proprietário). Convites: 404 `INVITATION_NOT_FOUND`, 410 `INVITATION_REVOKED` e 409 `INVITATION_ALREADY_USED`, `MEMBER_ALREADY_EXISTS`, `INVITATION_ALREADY_EXISTS` (o corpo traz `details.invitationId` do convite pendente), `USER_IN_OTHER_COMPANY`, `EMAIL_RESERVED` e `INVITATION_NOT_REGENERABLE`. Gerar novo link e revogar exigem a versão do convite em `If-Match`, a mesma do `ETag` e entre aspas: ausente retorna 428 `VERSION_REQUIRED` e desatualizada, 412 `RESOURCE_VERSION_MISMATCH`. Entre duas gerações de link passam no mínimo 60 segundos, e são no máximo cinco por hora contando a criação; acima disso a resposta é 429 `INVITATION_RATE_LIMITED` com `Retry-After` em segundos. Página ou tamanho fora do permitido na lista de membros retorna 400 `INVALID_PARAMETER`.

Consulta e aceite pelo link (`POST /v1/company-invitations/resolve`, `/accept-new-user` e `/accept`, com o token sempre no corpo): 404 `INVITATION_INVALID` (token malformado ou inexistente), 410 `INVITATION_SUPERSEDED` (link substituído por regeneração) e `INVITATION_REVOKED`, 409 `INVITATION_ALREADY_USED`, `USER_ALREADY_EXISTS`, `USER_IN_OTHER_COMPANY`, `MEMBER_ALREADY_EXISTS` e `TERMS_VERSION_MISMATCH`, 403 `INVITATION_IDENTITY_MISMATCH` (a conta autenticada não é a do e-mail convidado) e 422 `PASSWORD_TOO_WEAK`, `PASSWORD_CONFIRMATION_MISMATCH` e `TERMS_NOT_ACCEPTED`. As três rotas somam no máximo 30 tentativas por minuto por IP em cada instância da aplicação; acima disso a resposta é 429 `RATE_LIMITED` com `Retry-After`. O contador fica na memória: na Lambda, cada ambiente de execução conta separadamente e recomeça no cold start, então **não há limite global garantido** sem uma regra compartilhada na borda (por exemplo, AWS WAF rate-based, disponível para REST API e não para HTTP API).

## 4. Testes e análise de mutação (PITest)

Suíte padrão (unitários, slices web e migrações quando Docker está disponível):

```bash
./gradlew test
```

Análise de mutação com PITest (tarefa dedicada e independente de `test`, `check`, `build` e `bootJar`):

```bash
./gradlew pitest
```

* **Relatórios e Sumário via Terminal/CI:** gerados em caminho fixo em `build/reports/pitest/index.html` e `build/reports/pitest/mutations.xml` (`timestampedReports = false`), além de imprimir automaticamente no terminal (`PITEST MUTATION SUMMARY`) e no `GITHUB_STEP_SUMMARY` do GitHub Actions o placar global, o detalhamento por pacote e a lista de classes com mutantes sobreviventes. Como o Gradle faz cache incremental das entradas e saídas da tarefa, use `./gradlew pitest --rerun-tasks` se quiser forçar nova execução sem mudanças no código.
* **Isolamento:** exclui a tag `blindpay-sandbox`, testes de integração de banco/Testcontainers (`*IntegrationTest` de persistência, serviço, controlador e segurança), o teste de seeder de desenvolvimento (`DevelopmentDataSeederTest`) e testes puros de infraestrutura/wiring (`*ContextTest`, `LoggingFormatTest`, `BackendApplicationTests`). Em runtime, as JVMs do PIT recebem `-Dspring.profiles.active=test`, segredos exclusivamente de teste (`JWT_SECRET`, `TWO_FACTOR_ENCRYPTION_KEY`, `INVITATION_ENCRYPTION_KEY`, `INVITATION_FRONTEND_ORIGIN`), endpoints loopback inválidos (`http://127.0.0.1:0`) para Avenia, BlindPay, Gemini e DeepSeek, `APP_CONFIG_SECRET_ARN` vazio e conciliação desabilitada; adicionalmente, classes puramente de bootstrap/seed/wiring (`BackendApplication`, `DevelopmentDataSeeder`, `SyntheticDataSeeder`, `CorsConfig`, `QuoteConfiguration`, `LambdaSecretsEnvironmentPostProcessor`, `InternalJobSecurityConfiguration`) ficam fora do alvo de mutação e `avoidCallsTo` suprime mutações em linhas de chamada ao AWS SDK e loggers (`Logger`, `LoggingEventBuilder`, `OperationalLog`).
* **Linha de base, controle de regressão e plano de contingência:** `repository.specification.*` permanece fora do escopo até haver testes unitários isolados dedicados para essas Specifications. No `mutations.xml`, o atributo `partial="true"` reflete o modo padrão de cobertura parcial (`shouldReportCoverage`) do `XMLReportListener`, e status `TIMED_OUT` indica mutante detectado por loop/tempo excedido. Para manter o feedback rápido em Pull Requests (`pr-to-trunk.yml`), a análise de mutação roda apenas na branch principal (`push-on-trunk.yml`) em um job paralelo dedicado (`mutation-tests`) com `continue-on-error: true` e publica `pitest-report` como artefato, garantindo visibilidade contínua em staging/trunk sem impactar o tempo de revisão dos PRs.

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
