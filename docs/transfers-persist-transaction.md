# Guideline: persistir a transferência criada em `POST /transfers`

## 0. Contexto e o que já existe

`TransferService.create` (ver [`service/TransferService.java`](../src/main/java/ages/vstable/backend/service/TransferService.java)) hoje só orquestra a chamada à Avenia (`AveniaTransferService.createTransfer`) e devolve o status mapeado — **nenhuma linha é gravada no banco**. O schema já tem as tabelas certas para isso (`V1__init.sql`), mas **não existe nenhuma entity/repository JPA para elas** ainda (confirmado por busca: zero arquivos `*Transaction*` em `entity/`/`repository/` além do enum `TransactionStatus`).

Esta guideline cobre só o necessário para persistir o resultado de `POST /transfers`. Não cobre:
- `export_transactions` (cobrança/recebível — usado por um fluxo de "exportação" que essa card não implementa);
- atualização de status depois da criação (webhook/polling da Avenia) — ver seção 6.

## 1. Tabelas envolvidas (`V1__init.sql`)

```
base_transactions
├─ id, company_id, creator_user_id
├─ status (transaction_status_enum, default PROCESSING)
├─ foreign_currency (varchar 3), foreign_amount (numeric 15,2)
├─ settlement_amount_brl (numeric 15,2), service_fee_brl (numeric 10,2, default 0)
├─ effective_spread_percentage (numeric 5,4), exchange_rate (numeric 12,6)
├─ avenia_ticket_id (uuid, UNIQUE), blockchain_transaction_hash (varchar 120)
└─ created_at, updated_at

import_transactions          -- é esta tabela que POST /transfers usa (paga um beneficiário)
├─ transaction_id (PK, FK -> base_transactions.id, ON DELETE CASCADE)
├─ beneficiary_id (FK -> beneficiaries.id, ON DELETE RESTRICT)
└─ transfer_method (transfer_method_enum: TED/PIX/ACCOUNT_BALANCE/BLOCKCHAIN)
```

`import_transactions` bate exatamente com o que `CreateTransferRequest` já carrega: `beneficiaryId` + `paymentMethod` (`TransferMethod`, que já mapeia 1:1 para `transfer_method_enum`). Não precisa de migration nova — os campos já existem.

## 2. Entities e repository a criar

Seguindo o padrão do projeto (nenhuma entity hoje usa `@Inheritance`/tabela-por-classe — ver `BeneficiaryEntity`, `AveniaKycVerificationEntity`, todas simples), a opção mínima viável é **duas entities planas + dois repositories**, sem mapear herança JPA entre elas. O service grava a base primeiro (pra obter o `id` gerado) e depois a linha de import referenciando esse `id`.

- `entity/BaseTransactionEntity.java` — mapeia `base_transactions`. Campos: `id` (`@GeneratedValue`, a tabela já tem `DEFAULT uuid_generate_v4()` mas o Java deve gerar também pra poder referenciar o id antes do flush, como já é feito em outras entities do projeto), `company` (`@ManyToOne` -> `CompanyEntity`, `company_id`), `creatorUserId` (`UUID`, sem relação obrigatória — a FK permite `ON DELETE SET NULL`, então não precisa ser `@ManyToOne` obrigatório; pode ser coluna simples), `status` (`@Enumerated(EnumType.STRING)` + `@JdbcTypeCode(SqlTypes.NAMED_ENUM)`, igual ao padrão de `ComplianceStatus` em `AveniaKycVerificationEntity`), `foreignCurrency`, `foreignAmount`, `settlementAmountBrl`, `serviceFeeBrl`, `effectiveSpreadPercentage`, `exchangeRate`, `aveniaTicketId` (`UUID`), `blockchainTransactionHash`, `createdAt`/`updatedAt`.
- `entity/ImportTransactionEntity.java` — mapeia `import_transactions`. `transactionId` como `@Id` (é FK e PK ao mesmo tempo — use `@MapsId` ou simplesmente um `@OneToOne`/`@Id @Column(name = "transaction_id")` com o UUID copiado do `BaseTransactionEntity` salvo), `beneficiary` (`@ManyToOne` -> `BeneficiaryEntity`), `transferMethod` (`@Enumerated(EnumType.STRING)` + `@JdbcTypeCode(SqlTypes.NAMED_ENUM)`, tipo `TransferMethod`).
- `repository/BaseTransactionRepository.java` e `repository/ImportTransactionRepository.java` — `JpaRepository<X, UUID>` simples, sem query customizada por enquanto.

## 3. Onde persistir no `TransferService`

No fim de `create(...)`, depois de `aveniaTransferService.createTransfer(...)` retornar com sucesso (a exceção já propaga antes de chegar aqui, então nunca se persiste uma transferência que falhou na Avenia):

```java
AveniaTransferResult result = aveniaTransferService.createTransfer(quoteRequest, ticketRequest);
TransactionStatus status = mapAveniaTicketStatus(result.ticket().status());

BaseTransactionEntity baseTransaction = buildBaseTransaction(beneficiary, request, result, status, currentUserId);
baseTransaction = baseTransactionRepository.save(baseTransaction);

ImportTransactionEntity importTransaction = new ImportTransactionEntity();
importTransaction.setTransactionId(baseTransaction.getId());
importTransaction.setBeneficiary(beneficiary);
importTransaction.setTransferMethod(request.getPaymentMethod());
importTransactionRepository.save(importTransaction);

return new CreateTransferResponse(status);
```

Marcar o método `create(...)` com `@Transactional` (o projeto já usa isso em outros services com múltiplos saves) para garantir que as duas linhas sejam gravadas atomicamente — se o segundo save falhar, o primeiro também deve reverter.

### Mapeamento de campos (`buildBaseTransaction`)

| Coluna | Origem |
|---|---|
| `company` | `beneficiary.getCompany()` (o beneficiário já carrega a empresa) |
| `creatorUserId` | `currentUserId` (já chega em `create(request, currentUserId)`) |
| `status` | `mapAveniaTicketStatus(result.ticket().status())` (já existe) |
| `foreignCurrency`/`foreignAmount` | moeda/valor de **destino** da cotação (`result.quote().outputCurrency()`/`outputAmount()`) — é o valor "estrangeiro" do ponto de vista da empresa (BRL é a moeda local) |
| `settlementAmountBrl` | valor total debitado em BRL — hoje só faz sentido quando `sourceCurrency == BRL` (ver ressalva na seção 5) |
| `serviceFeeBrl` | taxa cobrada, já calculada em `toTransferQuoteResponse`/`totalFeeAmount` — reaproveitar essa lógica em vez de duplicar |
| `effectiveSpreadPercentage` | `feePercentage` já calculado (mesma lógica de `toTransferQuoteResponse`) |
| `exchangeRate` | `result.quote().basePrice()` |
| `aveniaTicketId` | `result.ticket().id()` |
| `blockchainTransactionHash` | não disponível no momento da criação (ver seção 6) — deixar `null` |

## 4. Erros e casos de borda

- Se `beneficiaryRepository.findById` falhar (já trata — `NotFoundException`) ou a resolução de moeda/subconta falhar (`UnprocessableEntityException`), nada deve ser persistido — como esses checks já ocorrem antes da chamada à Avenia, está coberto automaticamente.
- Se `aveniaTransferService.createTransfer` lançar `AveniaIntegrationException`, a exceção deve continuar propagando sem persistir nada — não adicionar `try/catch` para "salvar como FAILED"; isso não foi pedido e infla escopo (a Avenia nunca confirmou a criação do ticket).
- `avenia_ticket_id` é `UNIQUE` no banco — não deveria colidir na prática (cada chamada gera um ticket novo), mas vale não ignorar uma eventual `DataIntegrityViolationException` silenciosamente; deixar propagar como 500 é aceitável por ora.

## 5. Decisão de design em aberto — `settlementAmountBrl` quando a origem não é BRL

Hoje `TransferService` sempre resolve `sourceCurrency` como BRL por padrão, mas o request permite informar `sourceCurrency` diferente de BRL explicitamente. O schema assume que `settlement_amount_brl` é **o valor debitado do saldo em BRL da empresa** — não faz sentido preencher esse campo se a origem não for BRL. Duas opções:
1. **Mínimo viável**: preencher `settlementAmountBrl` só quando `sourceCurrency == BRL`; deixar `null` caso contrário.
2. Rejeitar (422) transferências com `sourceCurrency != BRL` nesta primeira versão, já que a empresa só tem saldo registrado em BRL (`CompanyEntity.availableBalanceBrl`) — evita gravar um estado ambíguo.

Recomendo a opção 2, mas é uma decisão de produto/arquitetura — não implementar sem validar com o time.

## 6. Fora de escopo desta guideline (mas relevante)

- **Atualização de status depois da criação**: o ticket criado pode mudar de status na Avenia (ex.: `UNPAID` → `PAID`) depois do `POST /transfers` retornar. Esta guideline só persiste o snapshot inicial; manter a linha local sincronizada exige um mecanismo novo (polling em `GET /v2/account/tickets/{id}` ou webhook da Avenia) — não implementado em nenhum lugar do projeto hoje.
- **`blockchain_transaction_hash`**: só existe depois que a transferência liquida on-chain; não vem no retorno síncrono de criação do ticket. Fica nulo até o mecanismo acima existir.
- **Consulta/listagem de transações** (`GET /v1/transfers` ou similar): não pedida nesta card; as entities/repositories acima só cobrem escrita.

## 7. Testes necessários

- `TransferServiceTest`: para cada tipo de beneficiário (PIX/cripto/conta bancária) já coberto, adicionar asserção de que `baseTransactionRepository.save`/`importTransactionRepository.save` são chamados com os campos corretos (via `ArgumentCaptor`); um teste garantindo que nada é persistido quando a Avenia falha (`verify(..., never()).save(any())`); um teste do mapeamento de `settlementAmountBrl` conforme a decisão da seção 5.
- Se as entities usarem enums nativos do Postgres (`@JdbcTypeCode(SqlTypes.NAMED_ENUM)`), considerar um teste de integração com Testcontainers cobrindo o save real (os enums `transaction_status_enum`/`transfer_method_enum` só validam o `columnDefinition` contra um banco real).

## 8. Checklist de PR

- [ ] `BaseTransactionEntity`, `ImportTransactionEntity` criadas em `entity/`, sem `dto/external/avenia` vazando para dentro delas.
- [ ] `BaseTransactionRepository`, `ImportTransactionRepository` em `repository/`.
- [ ] `TransferService.create` persiste as duas linhas dentro de uma transação, só depois de a Avenia confirmar o ticket.
- [ ] Decisão da seção 5 tomada e documentada (aceitar apenas BRL como origem, ou preencher `settlementAmountBrl` condicionalmente).
- [ ] Testes cobrindo persistência (unitários) e, se possível, um teste de integração com Testcontainers validando os enums nativos.
- [ ] Nenhuma migration nova necessária — confirmar que os tipos dos campos da entity batem com os da tabela existente (`V1__init.sql`).
