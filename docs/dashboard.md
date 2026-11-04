# Dashboard de métricas (feature #44)

Endpoints consumidos pelo dashboard do frontend (`frontend/docs/dashboard.md`).

## Escopo e autorização

| Rotas | Quem | Empresa |
|---|---|---|
| `/v1/dashboard/*` | Usuário de empresa | Sempre a do token. Sem empresa: 403. `companyId` na query é ignorado. |
| `/v1/admin/dashboard/*` | `ROLE_ADMIN` | `companyId` opcional: sem ele, todas as empresas; inexistente: 404. |

Usuário de empresa em rota de admin e administrador em rota de empresa recebem 403; sem
autenticação, 401.

## Parâmetros comuns

- `startDate`, `endDate`: opcionais, `AAAA-MM-DD`, dias civis de America/Sao_Paulo, fim inclusivo.
  Data ausente não limita o período.
- 400 `{"code":"INVALID_DATE_RANGE"}` quando `startDate > endDate` ou a data está fora de
  2000-01-01 a 2100-12-31; 400 `INVALID_PARAMETER` para formato inválido.
- Dinheiro é string decimal com duas casas; datas e horas em ISO-8601.

## Endpoints

| Rota | Resposta |
|---|---|
| `GET …/summary` | `currency`, `startDate`, `endDate`, `amount` (nunca nulo; `"0.00"` sem economia), `referenceSpreadPercentage`, `averageEffectiveSpreadPercentage` (nulo sem registros), `incomplete`, `incompleteOperations` |
| `GET …/remittance-volume` | Série mensal em BRL: `currency`, `granularity` (`MONTH`), `startDate`, `endDate`, `total`, `points[{month: "AAAA-MM", amount}]`, `incompleteOperations` |
| `GET …/savings-history` | Mesmo formato, com a economia estimada |
| `GET …/settlements?page&size` | Página Spring (`content`, `number`, `size`, `totalElements`, `totalPages`), `size` de 1 a 50 (padrão 5). Item: `id`, `companyId`, `companyName`, `settledAt`, `createdAt`, `direction` (`PAYMENT`/`RECEIPT`), `counterparty`, `destination`, `amount`, `currency`, `amountBrl`, `exchangeRate`, `feeBrl`, `savings`, `status` |
| `GET /v1/dashboard/transaction-volume` | `transactedVolumeBrl` (#53); `endDate` opcional adicionado sem quebrar o contrato |
| `GET /v1/admin/dashboard/transaction-volume` | Mesmo DTO, consolidado ou da empresa escolhida |

Séries: sem nenhuma operação liquidada no período, `points` é vazio; caso contrário, um ponto
por mês do período, com `"0.00"` nos meses sem operação.

## Regras

- Métricas e séries contam só `SETTLED` e são agregadas no banco. A data de referência é
  `COALESCE(settled_at, created_at)`, porque o fluxo Avenia ainda não registra a liquidação.
  O volume transacionado (#53) mantém o filtro por `created_at`.
- Últimas liquidações: todos os status, ordenadas por `COALESCE(settled_at, created_at)` desc.
- Economia estimada por operação (`SavingsEstimator`, usada também no detalhe administrativo de
  `GET /api/admin/transferencias/{id}`):

  ```
  (settlementAmountBrl − serviceFeeBrl) × referência% − serviceFeeBrl
  ```

  A referência vem de `dashboard.savings.reference-spread-percentage`
  (`DASHBOARD_SAVINGS_REFERENCE_SPREAD_PERCENTAGE`, padrão 4.00). O valor pode ser negativo, é
  arredondado por operação (HALF_UP, duas casas) e é nulo para status diferente de `SETTLED` ou
  sem valor em BRL ou tarifa; essas operações entram em `incompleteOperations`.

## Decisões da entrega

- Câmbio atual (#55) ficou fora desta entrega.
- Filtros de moeda, tipo, status e beneficiário do Figma ficaram fora do escopo; existem período
  e empresa (administrador).
- O volume é exibido em BRL (o Figma mostra USD), porque somar moedas estrangeiras diferentes não
  tem significado sem conversão.
- O extrato em PDF (`statement.pdf`) é uma issue separada.
