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
