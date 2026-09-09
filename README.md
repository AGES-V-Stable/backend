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

## Consulta da situação cadastral

```http
GET /api/empresas/{id}/situacao-cadastral
```

`id` deve ser o UUID da empresa. A resposta `200 OK` reflete os dados atuais da
empresa e inclui os documentos de compliance associados:

```json
{
	"id": "b7b3c4e0-2d9b-4f9f-8c9b-5f4f7f4f4f4f",
	"razaoSocial": "Empresa Teste LTDA",
	"nomeFantasia": "Empresa Teste",
	"cnpj": "00.000.000/0001-00",
	"statusKyb": "APROVADO",
	"statusAml": "EM_ANALISE",
	"statusGeral": "EM_ANALISE",
	"documentos": [
		{
			"id": "f7b3c4e0-2d9b-4f9f-8c9b-5f4f7f4f4f4f",
			"tipoDocumento": "CONTRATO_SOCIAL",
			"nomeArquivo": "contrato_social.pdf",
			"tamanhoArquivoBytes": 204800,
			"status": "EM_ANALISE",
			"enviadoEm": "2026-09-07T12:00:00Z"
		}
	]
}
```

O `statusGeral` é calculado a partir dos estados KYB e AML:

* se qualquer um estiver `REJEITADO`, o resultado será `REJEITADO`;
* caso contrário, se qualquer um estiver `EM_ANALISE`, o resultado será
  `EM_ANALISE`;
* o resultado será `APROVADO` somente quando ambos estiverem `APROVADO`;
* nas combinações restantes, o resultado será `PENDENTE`.

A empresa e seus documentos são lidos em uma única transação somente leitura,
com um snapshot consistente do banco no início da consulta.

Erros possíveis:

* `400 Bad Request`: o UUID informado na rota é inválido.
* `404 Not Found`: não existe empresa para o UUID informado.
* `422 Unprocessable Content`: o cadastro possui situação cadastral inválida,
  como status KYB, AML ou de documento nulo.

Os erros usam o seguinte formato:

```json
{
  "status": 400,
  "error": "Bad Request",
  "message": "Parâmetro 'id' inválido"
}
```

Para `404`, `status` e `error` serão `404` e `Not Found`; para `422`, serão
`422` e `Unprocessable Content`. A mensagem identifica a empresa quando houver
um UUID válido.
