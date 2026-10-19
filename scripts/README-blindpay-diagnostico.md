# Diagnostico BlindPay (Windows PowerShell)

O script `scripts/blindpay-diagnostico.ps1` cria um log com timestamp na sua area de trabalho, em `BlindPay-Diagnostico`. O arquivo resume resultados de Java, Gradle, status de compliance e uma cotacao opcional.

Execute no PowerShell na raiz do backend:

```powershell
powershell -NoProfile -File .\scripts\blindpay-diagnostico.ps1
```

Se sua maquina bloquear scripts baixados, evite desabilitar Smart App Control ou politicas corporativas. Use um terminal previamente autorizado e as rotinas Gradle diretamente.

O menu oferece testes, build, consulta autenticada de status ou POST /v1/quotes com um JSON que voce fornece. Para testes de API, o backend ja deve estar aberto em http://localhost:8080 (altere com `-ApiBase`). O JWT e solicitado no terminal e nunca escrito intencionalmente no log. O script nao cria clientes, nao submete KYB, nao envia documentos e nao executa transacoes ou transferencias. Para um fluxo de compliance completo real, uma conta de teste aprovada e configuracao de ambiente sao necessarias.

**Seguranca:** o script guarda apenas uma selecao de mensagens do Gradle e metadados/resumos HTTP, evitando payloads ou respostas integrais. Ainda assim, revise qualquer log antes de compartilha-lo e nao encaminhe arquivos com dados sensiveis.
