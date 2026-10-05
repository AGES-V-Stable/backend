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

## 3. Integração com o frontend

- **CORS:** a origem do frontend precisa estar em `CORS_ALLOWED_ORIGINS` (lista separada por vírgula; aceita padrões como `http://localhost:*` ou `https://*.vstable.com`). O padrão libera `http://localhost:*` e `http://127.0.0.1:*`. A API expõe o header `Authorization`, onde o login devolve o JWT.
- **Erros de autenticação** seguem o mesmo formato JSON dos demais erros: `{"message": "...", "code": "UNAUTHENTICATED" | "TOKEN_INVALID" | "TOKEN_EXPIRED" | "ACCESS_DENIED"}`.

### Criar um administrador

Administradores ficam na tabela `administrators` e entram pelo mesmo `POST /v1/auth/login`. Não existe endpoint público para criá-los. Gere um hash BCrypt da senha e insira o registro diretamente:

```sql
INSERT INTO administrators (full_name, email, password_hash, password_salt, access_level)
VALUES ('Admin V-Stable', 'admin@vstable.com', '<hash bcrypt>', '-', 'SUPER_ADMIN');
```

O hash pode ser gerado com `htpasswd -bnBC 10 "" 'SuaSenha@123' | tr -d ':\n'`. O onboarding recusa e-mails de administradores, então cada e-mail de login pertence a uma única conta.
