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

| Fluxo | Dados recebidos | Decisão |
| --- | --- | --- |
| Cotação | origem e destino | usa as moedas informadas |
| Cotação | somente destino | infere origem BRL, pois o único saldo disponível no domínio é `availableBalanceBrl` |
| Cotação | somente origem | retorna 422; não há beneficiário para determinar o destino |
| Cotação | nenhuma moeda | retorna 422; não há contexto para determinar o destino |
| Criação | origem ausente | infere BRL pelo saldo de liquidação da empresa |
| Criação | destino ausente | usa a moeda cadastrada no beneficiário; PIX é BRL |
| Criação | destino ausente e beneficiário sem moeda inferível | retorna 422 antes da Avenia |

Não existe fallback para USD. Na criação, uma origem diferente de BRL também é
rejeitada, pois a persistência e o saldo da empresa só representam liquidação
em BRL.

O endpoint de cotação não recebe beneficiário nem métodos de entrada e saída.
O mapeamento legado `INTERNAL` usado para exibição precisa ser confirmado no
sandbox para cada combinação. A tabela pública de combinações da Avenia não é
suficiente para escolher entre, por exemplo, ACH e WIRE sem contexto do
beneficiário. Nenhuma nova escolha arbitrária foi adicionada nesta revisão.

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

1. valida request, empresa, beneficiário e vínculo da subconta;
2. resolve moedas e métodos;
3. solicita uma cotação nova com `IMMEDIATE` na subconta do usuário;
4. valida token, valores, moedas, métodos, taxas e fee settlement;
5. substitui qualquer token/subconta do ticket pelo token recém-gerado e pela
   subconta da cotação;
6. cria e valida o ticket;
7. persiste o snapshot local;
8. retorna somente o status público.

Os status documentados com hífen (`ON-HOLD` e `PARTIAL-FAILED`) e as variantes
legadas com sublinhado são mapeados. Status nulo não é tratado como concluído.

Há um risco residual inevitável no desenho atual: o ticket externo pode ser
criado com sucesso e a transação local falhar ao persistir. Uma solução robusta
exige reconciliação/outbox e está fora do escopo desta task; a recuperação
depende de uma consulta segura ao histórico do provedor.

Transferências SWIFT permanecem bloqueadas antes da cotação porque o ticket
exige uma invoice previamente processada e esse documento não faz parte do
contrato HTTP atual.

## Roteiro manual para o sandbox

Executar somente com autorização explícita, dados sintéticos e credenciais
fornecidas por variável de ambiente.

| Teste | Endpoint Avenia | Condição | Resultado esperado |
| --- | --- | --- | --- |
| Cotação SOURCE | `GET /v2/account/quote/fixed-rate` | combinação válida, `inputAmount`, `feeSettlement=IMMEDIATE` e `subAccountId` | resposta confirma `IMMEDIATE`, métodos/moedas e taxas coerentes |
| Cotação DESTINATION | mesmo endpoint | combinação válida e somente `outputAmount` | Avenia calcula `inputAmount`; nenhum dos valores é nulo |
| Métodos de exibição | mesmo endpoint | combinação usada por `POST /v1/transfers/quote` | confirmar se `INTERNAL` é aceito para as moedas desejadas; se não, ampliar o contrato HTTP antes de escolher outra rota |
| Taxas | mesmo endpoint | operação com taxa | `appliedFees` e `markupAmount` conferem com o custo debitado; `inputAmount` não deve ser somado novamente à taxa |
| Beneficiário | endpoints `/v2/account/beneficiaries/...` | subconta individual e destino sintético | ID externo retornado na mesma subconta |
| Isolamento | cotação/beneficiário/ticket | repetir com duas subcontas de teste | identificadores de uma subconta não são aceitos na outra |
| Ticket | `POST /v2/account/tickets/` | cotação nova e referência com 68 caracteres | ticket usa o token novo e retorna ID/status válidos |
| Limite da referência | mesmo endpoint | 69 caracteres em chamada controlada, se necessário | rejeição de validação; o backend já bloqueia antes da rede |
| Estados | consulta/webhook disponível no sandbox | ticket em espera ou falha parcial | `ON-HOLD`/`PARTIAL-FAILED` são mapeados sem marcar liquidação |

Os testes automatizados usam mocks e servidor HTTP local; não criam
beneficiários, cotações financeiras ou tickets reais.
