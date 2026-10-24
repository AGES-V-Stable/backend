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
o truststore da JVM. Publique primeiro em staging e teste login, JWT válido/inválido,
CORS e o download de comprovante PDF pela URL
do API Gateway antes de encaminhar tráfego de produção.

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
