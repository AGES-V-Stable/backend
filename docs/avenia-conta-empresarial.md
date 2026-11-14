# Conta financeira empresarial na Avenia (backend#76)

## Regra

Operações financeiras da Avenia (cotação, transferência, confirmação de oferta e cadastro de beneficiário) usam a conta **da empresa**, resolvida pelo vínculo ativo do usuário autenticado. O usuário é apenas o ator: autoria (`creator_user_id`, `requested_by`), 2FA e permissões continuam sendo dele.

| Antes | Agora |
|---|---|
| Subconta do último KYC individual do usuário (`avenia_kyc_verifications.avenia_sub_account_id`), criada sob demanda como `INDIVIDUAL` | Associação da empresa em `provider_accounts` (`provider = 'AVENIA'`) |
| Cada membro operava uma subconta diferente | OWNER e OPERATOR da mesma empresa usam a mesma subconta |

A resolução fica em `CompanyFinancialAccountService.requireAveniaSubAccountId(companyId)`. Ela só devolve a subconta quando:

1. existe a linha `provider_accounts` da empresa com `provider = 'AVENIA'`;
2. `status = 'ACTIVE'` (valor exato);
3. `external_customer_id` está preenchido;
4. `external_customer_id` **não** é subconta de nenhum KYC individual (`avenia_kyc_verifications.avenia_sub_account_id`).

Qualquer outra situação responde `422 UNPROCESSABLE_CONTENT` ("A empresa não possui conta financeira empresarial válida na Avenia; a operação foi bloqueada"), sem chamar a Avenia. Não há fallback para a conta principal da API key, para o KYC individual de um membro nem para outra empresa.

Defesa em profundidade contra a conta principal: `AveniaTransferService`, `AveniaBeneficiaryService` e o próprio `AveniaClient` recusam cotação, ticket e cadastro de beneficiário sem `subAccountId`, antes de qualquer requisição HTTP.

Ordem do bloqueio, sem efeito colateral:

- `POST /v1/transfers`: autenticação → permissão → 2FA validado (não consumido) → beneficiário da empresa → conta empresarial → Avenia → persistência → consumo do TOTP. Sem conta, nada é persistido e o TOTP não é consumido.
- `POST /v1/quotes/offers/{id}/confirm` (oferta Avenia): a conta é verificada **antes** de registrar a execução e de marcar a oferta como `CONFIRMED`. Sem conta, nada é gravado e a oferta continua confirmável.
- `POST /v1/companies/{id}/beneficiaries`: KYB → validação → conta empresarial → Avenia → persistência.
- `POST /v1/quotes`: a falha da Avenia só invalida a oferta desse provedor, registrada como `FAILED` em `provider_quotes`, que é um registro local de cotação. Nenhuma operação financeira é criada.

O índice `provider_accounts_provider_external_customer_key` (V6) impede que duas empresas apontem para a mesma subconta. Nenhuma migration foi necessária.

## O que **não** foi feito, e por quê

- **Provisionamento automático da subconta `COMPANY`.** A integração atual só modela `accountType = INDIVIDUAL` (`AveniaApi.SubAccount.AccountType`, `AveniaClient.criarSubconta`) e nenhum fluxo de KYB empresarial da Avenia foi validado contra o sandbox. **Dependência externa:** para implementar, é preciso o contrato oficial da Avenia (endpoint de criação de subconta empresarial, campos obrigatórios, fluxo de KYB/representante legal e status), validado no sandbox, mais uma decisão financeira/jurídica sobre titularidade. Sem isso, não se inventam campos de provedor nem se simula o provisionamento. Quando houver, o provisionamento deve gravar a linha `provider_accounts` (`AVENIA`), único ponto lido por `CompanyFinancialAccountService`.
- **Conversão de associações existentes.** A subconta `INDIVIDUAL` criada para o KYC de um representante não vira conta da empresa. A titularidade financeira é diferente, e a Avenia não documenta essa conversão.
- **Fluxo de KYC individual.** `ComplianceDocumentService`, `ComplianceLivenessService` e `ComplianceKycService` continuam criando a subconta `INDIVIDUAL` do representante que faz o próprio KYC (identidade). Inclusão ou remoção de membros não chama esse fluxo nem nenhum provedor.

## Impacto nas empresas existentes

Até a conciliação, toda empresa sem associação `AVENIA` ativa tem as operações da Avenia bloqueadas com 422. A cotação agregada (`POST /v1/quotes`) continua oferecendo BlindPay quando ela for elegível, porque a falha da Avenia só invalida a oferta desse provedor. Saldo, histórico e detalhes de transferências não dependem da conta Avenia e continuam disponíveis.

### Beneficiários existentes

Todo beneficiário cadastrado antes desta mudança foi registrado na Avenia dentro da subconta de KYC individual de quem o criou. Essa subconta está em `beneficiaries.avenia_sub_account_id`, e os identificadores externos (`avenia_id` / `avenia_wallet_id`) só existem dentro dela. `TransferService.validateBeneficiarySubAccount` exige que a subconta do beneficiário seja a mesma da operação, que agora é a da empresa.

| Beneficiário | Rota que usava | Depois da #76 | Por quê |
|---|---|---|---|
| `CRYPTO_WALLET` (único destino com rota Avenia: BRL/PIX → token) | Avenia | **Deixa de funcionar** na Avenia | Sem conta empresarial: 422 na resolução. Com conta empresarial: 422 "cadastrado em outra subconta Avenia", porque a carteira foi registrada na subconta individual |
| `BANK_ACCOUNT` | BlindPay (a Avenia não oferece BRL/PIX → conta bancária neste fluxo) | **Continua funcionando** | O registro BlindPay fica em `beneficiary_provider_accounts`, que não depende de subconta Avenia |
| `PIX_KEY` | nenhuma (Avenia recusa; a BlindPay só aceita `BANK_ACCOUNT`) | sem mudança | Já não havia rota executável antes da #76 |
| Qualquer tipo com `avenia_sub_account_id` nulo (anterior à V5) | — | sem mudança | Já era recusado antes ("vínculo confiável") |

Listar, consultar, ver histórico e baixar comprovante continuam funcionando para todos, porque não dependem da conta Avenia. O histórico fica preservado: `import_transactions.beneficiary_id` tem `ON DELETE RESTRICT`, e nada é apagado.

**Cadastro de beneficiários novos.** `POST /v1/companies/{id}/beneficiaries` registra todo beneficiário na Avenia antes de persistir (regra da task #29). Enquanto a empresa não tiver conta Avenia ativa, **nenhum** beneficiário novo pode ser cadastrado, nem mesmo `BANK_ACCOUNT`, que só seria usado pela BlindPay. Antes, a pré-condição era o KYC individual de quem cadastrava. Desacoplar o cadastro da Avenia para destinos só-BlindPay é decisão de produto e está fora desta task.

**Como preservar ou migrar com segurança** (depois que a empresa tiver conta ativa):

1. **Recomendado: recadastrar o mesmo registro local na conta da empresa.** Registra o destino na Avenia com a subconta empresarial e atualiza `avenia_id`/`avenia_wallet_id`/`avenia_sub_account_id` da mesma linha. Isso preserva o `id`, o histórico e o vínculo BlindPay. Exige uma operação controlada que chame a Avenia. Não foi implementada, porque seria um contrato novo e precisaria de validação no sandbox.
2. **Alternativa disponível hoje:** cadastrar de novo pelo endpoint atual. Cria um novo registro, e o antigo fica para o histórico.
3. **Proibido:** só trocar `avenia_sub_account_id` para a subconta da empresa sem novo cadastro na Avenia. Os ids externos continuariam pertencendo à subconta individual, e o ticket seria recusado ou executado na conta errada.

## Conciliação (manual, após decisão do produto)

### 1. Diagnóstico

```sql
-- Empresas sem associação Avenia ativa
SELECT c.id, c.legal_name
FROM companies c
WHERE NOT EXISTS (
    SELECT 1 FROM provider_accounts p
    WHERE p.company_id = c.id AND p.provider = 'AVENIA' AND p.status = 'ACTIVE');

-- Beneficiários fora da conta Avenia da empresa, por tipo
-- (CRYPTO_WALLET precisa de recadastro; BANK_ACCOUNT segue pela BlindPay; PIX_KEY não tinha rota)
SELECT b.company_id, b.receiving_method, count(*) AS beneficiarios
FROM beneficiaries b
WHERE NOT EXISTS (
    SELECT 1 FROM provider_accounts p
    WHERE p.company_id = b.company_id AND p.provider = 'AVENIA' AND p.status = 'ACTIVE'
      AND p.external_customer_id = b.avenia_sub_account_id)
GROUP BY b.company_id, b.receiving_method
ORDER BY b.company_id, b.receiving_method;
```

### 2. Associar a conta empresarial confirmada

Só associe uma subconta confirmada como **conta da empresa** na Avenia: titular com o CNPJ da empresa, KYB aprovado no provedor e decisão registrada pelo responsável financeiro/jurídico. Nunca use o `avenia_sub_account_id` de um KYC individual; o backend recusa esse caso.

```sql
BEGIN;
INSERT INTO provider_accounts (company_id, provider, external_customer_id, status)
VALUES ('<company_id>', 'AVENIA', '<subconta_empresarial_confirmada>', 'ACTIVE');
-- conferir 1 linha inserida antes de confirmar
COMMIT;
```

Para suspender a operação sem apagar o histórico, troque o `status` para qualquer valor diferente de `ACTIVE` (por exemplo `SUSPENDED`).

### 3. Depois da associação

- Recadastre os beneficiários `CRYPTO_WALLET` listados no passo 1, conforme "Beneficiários existentes". O novo cadastro já vai para a conta da empresa.
- Tickets criados antes da mudança continuam nas subcontas individuais. O backend não acompanha o status de tickets da Avenia (ver `docs/transfers-persist-transaction.md`), então a conciliação desses saldos e tickets é manual e fica fora do sistema.
