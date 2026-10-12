# Task #29 — decisões finais e validação da Avenia

Este documento registra as decisões verificáveis adotadas na integração de
transferências. Ele não substitui a validação no sandbox e não contém
credenciais nem dados financeiros reais.

## Contratos consultados

- [Issue #29](https://github.com/AGES-V-Stable/backend/issues/29)
- [Avenia — Quotes and Tickets](https://integration-guide.avenia.io/docs/Operations/quotesAndTickets/)
- [Avenia — Fee settlement](https://integration-guide.avenia.io/docs/Operations/feeSettlement/)
- [Avenia — Available combinations](https://integration-guide.avenia.io/docs/Operations/combinations/)

## Resolução de moedas

O contrato HTTP mantém `sourceCurrency` e `destinationCurrency` opcionais, mas
uma chamada à Avenia só é feita depois de ambas serem resolvidas.

Cotação e criação usam a mesma regra (`TransferService.resolveRoute`):

| Dados recebidos | Decisão |
| --- | --- |
| origem ausente | infere BRL pelo saldo de liquidação da empresa |
| origem diferente de BRL | retorna 422; persistência e saldo só representam liquidação em BRL |
| destino informado | precisa coincidir com a moeda do beneficiário; PIX é BRL |
| destino ausente | usa a moeda cadastrada no beneficiário; PIX é BRL |
| destino ausente e beneficiário sem moeda inferível | retorna 422 antes da Avenia |

Não existe fallback para USD.

## Métodos de pagamento

Segundo a [tabela de combinações](https://integration-guide.avenia.io/docs/Operations/combinations/):

- BRL entra e sai somente via `PIX` (ou `REFUND`); não existe `BRL/INTERNAL`.
- `INTERNAL` é saldo em token na conta Avenia: BRLA, USDC, USDT e EURC.
- Com entrada `BRL/PIX`, as saídas documentadas são tokens (on-chain ou
  INTERNAL) e `SWIFT`. Não existem `BRL/PIX -> BRL/PIX`, `BRL/PIX -> USD/ACH|WIRE`
  nem `BRL/PIX -> EUR/SEPA`; esses destinos são pagos a partir de token INTERNAL.
- Redes por token (tabela *Supported Assets*): BRLA e USDC em BASE, CELO,
  ETHEREUM, GNOSIS, MOONBEAM e POLYGON; USDT em CELO, ETHEREUM, MOONBEAM, POLYGON
  e TRON; EURC somente INTERNAL.

Mapeamento aplicado, sempre antes de qualquer chamada à Avenia (inclusive o
provisionamento da subconta):

| `paymentMethod` | Entrada | Situação |
| --- | --- | --- |
| `PIX` | `BRL/PIX` | suportado |
| `ACCOUNT_BALANCE` | — | 422: exigiria token INTERNAL; o domínio só registra saldo em BRL |
| `TED` | — | 422: não é método de entrada da Avenia |
| `BLOCKCHAIN` | — | 422: exige dados de assinatura fora do contrato |

| Beneficiário | Saída | Situação com entrada `BRL/PIX` |
| --- | --- | --- |
| `CRYPTO_WALLET` com BRLA/USDC/USDT em rede suportada | rede (ex.: `POLYGON`) | suportado |
| `CRYPTO_WALLET` com token/rede fora da tabela | — | 422 |
| `PIX_KEY` / `BANK_ACCOUNT` pix | `PIX` | 422: combinação inexistente |
| `BANK_ACCOUNT` ach/wire | `ACH`/`WIRE` | 422: exige token INTERNAL |
| `BANK_ACCOUNT` sepa | `SEPA` | 422: exige token INTERNAL |
| `BANK_ACCOUNT` swift | `SWIFT` | 422: exige invoice processada |

BRL nunca é convertido implicitamente em BRLA, e o saldo local em BRL não é
tratado como saldo disponível na Avenia.

### Dependência em aberto

Pagar beneficiários PIX, ACH, WIRE e SEPA exige `ACCOUNT_BALANCE` com origem
em token INTERNAL (por exemplo `BRLA/INTERNAL -> BRL/PIX` ou
`USDC/INTERNAL -> USD/WIRE`). Para habilitar isso com segurança, o time precisa
decidir:

1. como representar e consultar o saldo em token da subconta
   (`GET /v2/account/balances`), em vez de usar `availableBalanceBrl`;
2. como persistir uma transação cuja origem não é BRL
   (`settlement_amount_brl` hoje recebe `inputAmount`);
3. como o saldo em token é abastecido (depósito `BRL/PIX -> BRLA/INTERNAL`),
   fluxo fora do escopo da task #29.

Até lá, essas rotas são rejeitadas com 422 explicativo, e não com uma cotação
que não poderia ser confirmada.

## Cotação de exibição

`POST /v1/transfers/quote` aceita `beneficiaryId` e `paymentMethod`, opcionais
no contrato e com os mesmos tipos de `POST /v1/transfers`. Sem eles, a resposta
é 422, pois não há como determinar os métodos da operação. Com eles, a cotação:

- busca o beneficiário restrito à empresa do usuário (404 para outra empresa);
- valida o vínculo com a subconta do usuário;
- monta a requisição à Avenia pela mesma `resolveRoute` da criação.

A requisição de cotação da exibição é idêntica à da confirmação para os mesmos
dados (coberto por teste). A confirmação continua gerando uma cotação nova, e
os valores podem mudar.

## Semântica financeira

Na Avenia, `inputAmount` é o valor aportado/pago e `outputAmount` é o valor
recebido. O modo de liquidação da taxa pode depender da configuração da conta;
por isso toda cotação deste fluxo solicita explicitamente
`feeSettlement=IMMEDIATE` e rejeita uma resposta que não confirme esse modo.

Com `IMMEDIATE`, a taxa é descontada do valor do ticket. Assim:

- `source.amount` e `total.amount` são o `inputAmount`;
- somar a taxa novamente a `inputAmount` causaria dupla contabilização;
- `appliedFees` é a fonte primária da taxa; `markupAmount` só é fallback quando
  não há taxas detalhadas;
- só se somam taxas na moeda de entrada;
- `fee.percentage = fee.amount / inputAmount * 100`;
- a persistência usa o mesmo `inputAmount`, taxa, percentual e câmbio da
  cotação que originou o ticket.

Resposta com taxa incompleta, negativa, maior que o valor de entrada ou em
outra moeda é rejeitada como resposta inválida do provedor. Não é feita
conversão implícita entre moedas.

## `externalId`

Segundo o contrato da Avenia, o campo é opcional, livre, não precisa ser único
e aceita no máximo 68 caracteres. A descrição pública da transferência é
normalizada e reutilizada como referência de conciliação. O limite é validado
no DTO HTTP e novamente na fronteira da integração para proteger chamadas
internas.

Nenhum `quoteToken`, `quoteId`, `ticketId`, identificador de beneficiário da
Avenia ou identificador de subconta é devolvido no contrato público.

## Ordem e consistência da criação

1. valida request, empresa e beneficiário;
2. resolve moedas e métodos e rejeita combinações não documentadas;
3. resolve a subconta e valida o vínculo do beneficiário com ela;
4. solicita uma cotação nova com `IMMEDIATE` na subconta do usuário;
5. valida token, valores, moedas, métodos, taxas e fee settlement;
6. substitui qualquer token/subconta do ticket pelo token recém-gerado e pela
   subconta da cotação;
7. cria e valida o ticket;
8. persiste o snapshot local;
9. retorna somente o status público.

Os status documentados com hífen (`ON-HOLD` e `PARTIAL-FAILED`) e as variantes
legadas com sublinhado são mapeados. Status nulo não é tratado como concluído.

Há um risco residual inevitável no desenho atual: o ticket externo pode ser
criado com sucesso e a transação local falhar ao persistir. Uma solução robusta
exige reconciliação/outbox e está fora do escopo desta task; a recuperação
depende de uma consulta segura ao histórico do provedor.

Transferências SWIFT permanecem bloqueadas antes da cotação (inclusive a de exibição) porque o ticket
exige uma invoice previamente processada e esse documento não faz parte do
contrato HTTP atual.

## Roteiro manual para o sandbox

Executar somente com autorização explícita, dados sintéticos e credenciais
fornecidas por variável de ambiente.

| Teste | Endpoint Avenia | Condição | Resultado esperado |
| --- | --- | --- | --- |
| Cotação SOURCE | `GET /v2/account/quote/fixed-rate` | combinação válida, `inputAmount`, `feeSettlement=IMMEDIATE` e `subAccountId` | resposta confirma `IMMEDIATE`, métodos/moedas e taxas coerentes |
| Cotação DESTINATION | mesmo endpoint | combinação válida e somente `outputAmount` | Avenia calcula `inputAmount`; nenhum dos valores é nulo |
| Rota suportada | mesmo endpoint | `BRL/PIX -> USDC/POLYGON` com `subAccountId` | quote válida, mesmos métodos/moedas ecoados; ticket com `ticketBrlPixInput` + `ticketBlockchainOutput` |
| Taxas | mesmo endpoint | operação com taxa | `appliedFees` e `markupAmount` conferem com o custo debitado; `inputAmount` não deve ser somado novamente à taxa |
| Beneficiário | endpoints `/v2/account/beneficiaries/...` | subconta individual e destino sintético | ID externo retornado na mesma subconta |
| Isolamento | cotação/beneficiário/ticket | repetir com duas subcontas de teste | identificadores de uma subconta não são aceitos na outra |
| Ticket | `POST /v2/account/tickets/` | cotação nova e referência com 68 caracteres | ticket usa o token novo e retorna ID/status válidos |
| Limite da referência | mesmo endpoint | 69 caracteres em chamada controlada, se necessário | rejeição de validação; o backend já bloqueia antes da rede |
| Estados | consulta/webhook disponível no sandbox | ticket em espera ou falha parcial | `ON-HOLD`/`PARTIAL-FAILED` são mapeados sem marcar liquidação |

Os testes automatizados usam mocks e servidor HTTP local; não criam
beneficiários, cotações financeiras ou tickets reais.
