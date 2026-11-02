# Guideline — Cotação e criação de transferências (Avenia)

Guia de implementação para a card "cotação e criação de transferências". Cobre onde cada peça entra na arquitetura existente, os contratos exatos a expor, as decisões de design que ficaram em aberto na card e o que validar antes de abrir o PR. Não substitui `AGENTS.md` — leia-o primeiro se ainda não conhece as convenções do projeto.

## 0. O que já existe (não reimplementar)

A PR `feat/25-Arquitetura` já trouxe toda a camada de baixo nível de integração com a Avenia para cotação/ticket. **Reaproveite, não duplique**:

- `external/avenia/AveniaClient` (implementa `AveniaGateway`) — sabe assinar e chamar `createQuote`/`createTicket` na Avenia.
- `external/avenia/AveniaTransferService` — já implementa a regra mais importante da card: `createTransfer(quoteRequest, ticketRequest)` gera uma **cotação nova** na Avenia e só então cria o ticket com o `quoteToken` fresco. Isso é exatamente a regra "a cotação para criar a transferência nunca reaproveita a cotação de exibição".
- `external/avenia/dto/AveniaQuoteRequest` / `AveniaQuoteResponse` / `AveniaTicketRequest` / `AveniaTicketResponse` / `AveniaTransferResult` — DTOs internos da integração. **Nunca vazam para fora do pacote `external/avenia`** — é assim que a regra "frontend não conhece quoteId/quoteToken/ticketId" já fica garantida estruturalmente, desde que a nova camada não exponha esses tipos.

O que falta é inteiramente a camada voltada para o frontend: não existe `TransferController`, nem DTOs de request/response no formato da card, nem a lógica de `amountType`/resolução de moeda/montagem do breakdown de valores.

## 1. Onde cada arquivo entra (respeitando os limites do AGENTS.md)

```
controller/TransferController.java          # HTTP fino: valida, delega, monta status code
dto/transfer/TransferQuoteRequest.java       # POST /transfers/quote — request
dto/transfer/TransferQuoteResponse.java      # POST /transfers/quote — response
dto/transfer/CreateTransferRequest.java      # POST /transfers — request
dto/transfer/CreateTransferResponse.java     # POST /transfers — response
dto/transfer/MoneyAmount.java                # { amount, currency } reaproveitado em source/destination/fee/total
dto/transfer/ExchangeRateInfo.java           # { fromCurrency, toCurrency, rate }
service/TransferService.java                 # regra de negócio: resolve moeda, chama AveniaTransferService, monta o DTO de saída
entity/enums/TransferAmountType.java         # SOURCE, DESTINATION
```

`TransferService` é o único ponto que deve conhecer tanto o mundo "frontend" (DTOs em `dto/transfer`) quanto o mundo "Avenia" (`AveniaTransferService`, DTOs em `external/avenia/dto`). O `TransferController` não deve importar nada de `external/avenia`.

## 2. Contratos HTTP

### `POST /transfers/quote`

Request (`TransferQuoteRequest`, moedas opcionais — `sourceCurrency`/`destinationCurrency` podem ser `null`):

```java
public record TransferQuoteRequest(
    @NotNull @DecimalMin(value = "0.01") BigDecimal amount,
    @NotNull TransferAmountType amountType,     // SOURCE | DESTINATION
    String sourceCurrency,                      // opcional
    String destinationCurrency                  // opcional
) {}
```

Response (`TransferQuoteResponse`) — mapeia 1:1 para o JSON da card, usando `MoneyAmount`/`ExchangeRateInfo` como sub-records para não repetir `{amount, currency}` em cada campo:

```java
public record TransferQuoteResponse(
    MoneyAmount source,
    MoneyAmount destination,
    ExchangeRateInfo exchangeRate,
    FeeInfo fee,          // { percentage, amount, currency }
    MoneyAmount total
) {}
```

`TransferService.quote(...)`:
1. Resolve `sourceCurrency`/`destinationCurrency` quando ausentes (ver seção 4 — **decisão em aberto**).
2. Monta um `AveniaQuoteRequest` com `inputAmount`/`outputAmount` de acordo com `amountType` (só um dos dois é preenchido — o record já valida isso no construtor compacto).
3. Chama `aveniaTransferService.createQuote(...)`.
4. Traduz o `AveniaQuoteResponse` (que tem `inputAmount`, `outputAmount`, `markupAmount`, `appliedFees`, `basePrice`, `pairName`) para `TransferQuoteResponse` — **sem devolver `quoteToken`**.
5. **Não persiste nada.** Cada chamada de exibição gera e descarta uma cotação na Avenia; isso já é a regra de negócio ("cada consulta gera uma nova cotação").

### `POST /transfers`

Request (`CreateTransferRequest`) — igual ao de quote, mais `paymentMethod`, `beneficiaryId`, `description`:

```java
public record CreateTransferRequest(
    @NotNull @DecimalMin(value = "0.01") BigDecimal amount,
    @NotNull TransferAmountType amountType,
    String sourceCurrency,
    String destinationCurrency,
    @NotNull TransferMethod paymentMethod,       // reaproveitar o enum transfer_method_enum (TED/PIX/ACCOUNT_BALANCE/BLOCKCHAIN) já existente no schema
    @NotNull UUID beneficiaryId,
    String description
) {}
```

Response — só o necessário pro frontend seguir o fluxo, conforme a card:

```java
public record CreateTransferResponse(TransactionStatus status) {}
```

`TransferService.create(...)`:
1. Busca o `BeneficiaryEntity` por `beneficiaryId` (404 via `NotFoundException` se não existir — ver `GlobalExceptionHandler`).
2. Resolve moedas ausentes (mesma lógica do quote — extrair para um método privado/compartilhado, não duplicar).
3. Monta `AveniaQuoteRequest` + `AveniaTicketRequest` (usar `beneficiaryId` para preencher os campos de destino do ticket — ver `AveniaTicketRequest.SwiftOutput` e as demais variantes por método de pagamento).
4. Chama `aveniaTransferService.createTransfer(quoteRequest, ticketRequest)` — **não** chamar `createQuote` e `createTicket` manualmente; é exatamente para isso que esse método existe.
5. Mapeia o `status` do `AveniaTicketResponse` para o `CreateTransferResponse`.
6. **Se a cotação falhar, não criar o ticket** — já é o comportamento de `AveniaTransferService.createTransfer` (a exceção sobe antes de chegar em `createTicket`), então isso vem de graça se você não capturar e engolir a exceção no meio do caminho.

## 3. Erros

`AveniaIntegrationException` já é tratada pelo `GlobalExceptionHandler` (422 para rejeição de negócio 4xx da Avenia, 503 para falha retryable, 502 para o resto — ver `handleAveniaIntegration`). **Não crie um handler novo** para isso.

O que falta mapear:
- Moeda não determinável (nem informada, nem resolvível a partir do beneficiário/empresa) → `UnprocessableEntityException` (já vira 422, já tem handler).
- `beneficiaryId` inexistente → `NotFoundException` (já vira 404, já tem handler).
- `amount`/`amountType` inválidos → deixe o Bean Validation (`@NotNull`, `@DecimalMin`) cuidar disso; já vira 400 via `MethodArgumentNotValidException`, que já tem handler.

## 4. Decisão em aberto — resolução de moeda quando não informada

A card diz "o backend deverá identificá-la a partir dos demais dados da operação", mas **nenhuma entidade atual guarda moeda explicitamente**:

- `CompanyEntity` só tem `availableBalanceBrl` — sugere BRL como moeda de origem padrão, mas não é um campo de moeda de verdade.
- `BeneficiaryEntity` não tem coluna de moeda. O mais próximo é inferir pelo `receivingMethod`/dados preenchidos: `PIX_KEY` preenchido → BRL; `blockchainNetwork`/`walletAddress` preenchido → stablecoin (qual? `BRLA`? `USDC`?); conta bancária internacional → moeda do `country`.

Isso precisa ser decidido com o time **antes** de implementar — não dá pra inferir com segurança só olhando o schema atual. Alternativas, da mais simples à mais completa:

1. **Mínimo viável**: `sourceCurrency` sempre default BRL (moeda de operação da empresa); `destinationCurrency` obrigatório quando não há uma forma confiável de inferir do beneficiário — retornar 422 pedindo pra informar.
2. **Completo**: adicionar uma coluna de moeda em `beneficiaries` (nova migration) e usar isso como fonte de verdade — mais correto, mas é uma mudança de schema que extrapola o escopo da card conforme descrita.

Recomendo a opção 1 para esta card e abrir um follow-up separado se o time quiser a coluna de moeda no beneficiário.

## 5. Testes

Seguir o padrão do projeto (AAA, sem chamada real à Avenia):

- `TransferServiceTest`: mocka `AveniaTransferService` (não `AveniaGateway`/`AveniaClient` diretamente — a fronteira do seu teste é o serviço que você está escrevendo). Cobrir: mapeamento de `amountType` SOURCE/DESTINATION, resolução de moeda (padrão e erro quando não resolvível), tradução da resposta da Avenia pros campos separados (`source`/`destination`/`exchangeRate`/`fee`/`total`), propagação de `AveniaIntegrationException` sem capturar, `beneficiaryId` inexistente → `NotFoundException`.
- `TransferControllerTest`: `@WebMvcTest`, valida contrato HTTP (400 pra payload inválido, 404, 422, 201/200 no caminho feliz) — mockar `TransferService`.
- Nenhum teste deve importar `AveniaClient`/`AveniaRequestSigner` diretamente; isso já está coberto pelos testes existentes de `external/avenia`.

## 6. Checklist antes do PR

```bash
./gradlew test
./gradlew build
```

Confirmar manualmente (grep) que nenhum tipo de `external/avenia/dto` (`AveniaQuoteResponse`, `AveniaTicketResponse`, etc.) aparece em `controller/TransferController.java` ou em qualquer DTO de `dto/transfer/` — é a forma mais simples de garantir que nenhum identificador da Avenia vazou pro contrato público.
