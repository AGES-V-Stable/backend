# Transferências pela melhor oferta (Avenia × BlindPay) e sandbox BlindPay

## Fluxo

1. `POST /v1/quotes`: cota na Avenia e na BlindPay em paralelo e devolve só a melhor oferta válida
   (`offer.offerId`, `offer.provider`, `offer.expiresAt`).
   - Valor de origem fixo (`amountSide=SOURCE`): vence quem entrega mais ao destinatário.
   - Valor de destino fixo (`TARGET`): vence o menor valor de origem (taxas já incluídas).
   - Só entram ofertas com valor fixo igual ao pedido, com prazo de validade ainda vigente e de um provedor
     com rota de execução: a Avenia só cota BRL via PIX para carteira cripto; a BlindPay só cota payout para
     conta bancária, com origem em BRL (pix/ted) ou em token.
   - A vencedora fica com `provider_quotes.status = SELECTED`. Só ela pode ser confirmada.
2. `POST /v1/quotes/offers/{offerId}/confirm` com `{"sourceAmount": ..., "targetAmount": ...}`. Os valores
   precisam ser exatamente os exibidos na oferta.
   - **Avenia**: recota na subconta do usuário; se o preço piorou, responde 409 e não cria ticket. O
     `quoteToken` da Avenia vale 15 s, por isso a oferta exibida tem uma janela própria de confirmação
     (`QUOTE_AVENIA_CONFIRMATION_WINDOW`, padrão 60 s); a cotação nova da execução protege o preço.
   - **BlindPay, origem BRL**: cria o payin e devolve `fundingInstructions` (código PIX) com status
     `AWAITING_FUNDS`. O payout só é enviado depois que a BlindPay confirma o payin (`completed`).
   - **BlindPay, origem em token**: envia o payout direto do saldo da carteira gerenciada da empresa.
3. `GET /v1/transfer-executions/{executionId}`: acompanha o estado.
4. `POST /v1/transfer-executions/{executionId}/requote/confirm`: obrigatório quando o status é
   `REQUOTE_REQUIRED`, ou seja, a cotação de payout expirou durante o financiamento e a nova entrega menos
   do que o confirmado. O corpo traz os valores de `requote`.

| Status | Significado |
|---|---|
| `PENDING_SUBMISSION` | Registrada; chamada ao provedor sem resultado conhecido (a conciliação repete) |
| `AWAITING_FUNDS` | Payin criado; aguardando o pagamento PIX |
| `FUNDS_RECEIVED` | Stablecoins creditadas na carteira gerenciada |
| `REQUOTE_REQUIRED` | Preço do payout piorou; precisa de nova confirmação |
| `PAYOUT_SUBMITTING` / `PAYOUT_PROCESSING` | Payout em envio / aceito pela BlindPay |
| `SUBMITTED` | Ticket criado na Avenia |
| `COMPLETED` / `FAILED` / `REFUNDED` | Terminais; `COMPLETED` só após `completed` da BlindPay |
| `RECONCILIATION_REQUIRED` | Resultado incerto que não pode ser repetido com segurança; exige ação manual |

## Garantias contra execução duplicada

- `transfer_executions.provider_quote_id` é único: confirmar a mesma oferta de novo, mesmo em requisições
  concorrentes, devolve a execução existente.
- Toda escrita na BlindPay usa a chave `transfer-execution:{id}:{etapa}` (`payin`, `payout-quote:{n}`,
  `payout:{n}`). Repetir uma chamada sem resposta devolve o payin/payout original.
- As transições usam bloqueio de linha e só partem do estado esperado; webhook e conciliação concorrentes
  não enviam o mesmo payout duas vezes.
- O payout nunca usa mais stablecoin do que o payin creditou nem entrega menos do que o confirmado sem nova
  confirmação.
- Payout sem resposta e depois recusado vai para `RECONCILIATION_REQUIRED`, nunca para uma recotação
  automática, porque o primeiro pode ter sido executado. Na Avenia, falha incerta depois do pedido de ticket
  também vai para conciliação manual: a Avenia não aceita chave de idempotência.

## Webhooks e conciliação

Cadastre na BlindPay um endpoint apontando para `POST /v1/webhooks/blindpay` com os eventos `payin.new`,
`payin.update`, `payin.complete`, `payout.new`, `payout.update` e `payout.complete`, e configure
`BLINDPAY_WEBHOOK_SECRET` com o `whsec_...` dele. O corpo do evento só identifica o payin/payout; o estado
é sempre consultado com `GET` na BlindPay.

Sem webhook (por exemplo, rodando localmente), a conciliação periódica (`transfers.reconciliation.*`, a cada
30 s por padrão) consulta e avança as execuções paradas.

## Sandbox BlindPay

### Pré-requisitos na instância de desenvolvimento

1. Cliente business com KYB aprovado e termos aceitos (`re_...`).
2. Conta bancária desse cliente com status `approved` (`ba_...`), por exemplo `wire` em USD.
3. Docker em execução: o teste usa Postgres via Testcontainers.

Em desenvolvimento, a BlindPay só aceita redes de teste (`base_sepolia`, `sepolia`, `arbitrum_sepolia`,
`polygon_amoy`, ...) e o token `USDB`. Payins e payouts completam sozinhos em segundos. O código PIX
devolvido é um placeholder e não é pagável.

### Variáveis de ambiente

| Variável | Obrigatória | Padrão |
|---|---|---|
| `BLINDPAY_API_KEY` | sim | — |
| `BLINDPAY_INSTANCE_ID` | sim (instância de **desenvolvimento**) | — |
| `BLINDPAY_SANDBOX_CUSTOMER_ID` | sim | — |
| `BLINDPAY_SANDBOX_BANK_ACCOUNT_ID` | sim | — |
| `BLINDPAY_SANDBOX_PAYMENT_RAIL` | não | `wire` |
| `BLINDPAY_SANDBOX_TARGET_CURRENCY` | não | `USD` |
| `BLINDPAY_SANDBOX_NETWORK` | não (precisa ser rede de teste) | `base_sepolia` |
| `BLINDPAY_SANDBOX_TOKEN` | não (precisa ser `USDB`) | `USDB` |

Nunca versione esses valores; use variáveis do terminal ou do CI.

### Executar

```bash
export BLINDPAY_API_KEY=...            # PowerShell: $env:BLINDPAY_API_KEY="..."
export BLINDPAY_INSTANCE_ID=in_...
export BLINDPAY_SANDBOX_CUSTOMER_ID=re_...
export BLINDPAY_SANDBOX_BANK_ACCOUNT_ID=ba_...
./gradlew blindpaySandboxTest
```

`./gradlew test` não executa esses testes (tag `blindpay-sandbox`). Sem `BLINDPAY_SANDBOX_CUSTOMER_ID` ou sem
Docker, eles são ignorados.

### Cenários cobertos (`BlindPaySandboxTransferTest`)

A BlindPay força o resultado em desenvolvimento pelo `request_amount`: `66600` falha, `77700` é reembolsado e
qualquer outro valor completa.

| Cenário | Pedido | Resultado esperado |
|---|---|---|
| Sucesso | origem BRL 100,00 | `COMPLETED`, com payout `po_...` |
| Falha no payin | origem BRL 666,00 | `FAILED`, sem payout |
| Reembolso do payin | origem BRL 777,00 | `REFUNDED`, sem payout |
| Falha no payout | destino 666,00 | `FAILED` depois dos fundos recebidos |
| Reembolso do payout | destino 777,00 | `REFUNDED` |
| Expiração da oferta | oferta com prazo vencido | 422 na confirmação |
| Expiração durante o financiamento | cotação de payout vencida | recotação; payout só com valor ≥ confirmado ou após nova confirmação |
| Duplicidade | confirmar duas vezes e repetir o payin com a mesma chave | mesma execução e mesmo `pi_...` |

### Teste manual via API

```bash
# 1. Cotação (beneficiário com conta bancária vinculada à BlindPay)
curl -X POST localhost:8080/v1/quotes -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{
  "beneficiaryId":"<uuid>","direction":"PAYOUT","sourceCurrency":"BRL","targetCurrency":"USD",
  "sourcePaymentMethod":"pix","targetPaymentMethod":"wire","amount":100.00,"amountSide":"SOURCE",
  "token":"USDB","blockchainNetwork":"base_sepolia","coverFees":false,"description":"Teste sandbox"}'

# 2. Confirmação com os valores exibidos
curl -X POST localhost:8080/v1/quotes/offers/<offerId>/confirm -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d '{"sourceAmount":100.00,"targetAmount":<offer.targetAmount>}'

# 3. Acompanhamento (a conciliação avança sem webhook em até ~1 min)
curl localhost:8080/v1/transfer-executions/<executionId> -H "Authorization: Bearer $TOKEN"
```

## Migrations

A antiga `V4__add_blindpay_compliance.sql` da branch colidia com `V4__expand_beneficiary_country_codes.sql`
da `main`. Ela virou `V6__add_blindpay_compliance.sql`, idempotente (`IF NOT EXISTS`), e as execuções estão
em `V7__add_transfer_executions.sql`.

- Bancos da `main` (até V5): aplicam V6 e V7 normalmente.
- Bancos locais que chegaram a aplicar a V4 antiga da branch têm um histórico Flyway incompatível
  (V4 com outra descrição e outro checksum). Recrie o banco local (`docker compose down -v`) ou remova a
  linha `version = '4'` de `flyway_schema_history` e rode a aplicação: V4, V5, V6 e V7 são aplicadas, e a V6
  não falha porque os objetos já existem.

## Limitações conhecidas

- Status de tickets da Avenia depois da criação (por exemplo, `UNPAID` → `PAID`) continua sem atualização
  automática, como antes desta mudança.
- `RECONCILIATION_REQUIRED` ainda não tem tela nem endpoint administrativo; a conferência é manual no
  painel do provedor.
- `total_fee` das ofertas BlindPay com origem BRL soma a taxa do payin (BRL) e a do payout (stablecoin), como
  já fazia a cotação agregada.
