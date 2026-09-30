# [Débito técnico] Beneficiário nunca é registrado na Avenia

## Contexto

Durante a implementação de `POST /v1/transfers` (cotação e criação de transferências), foi identificado que o cadastro de beneficiários (`POST /v1/beneficiaries`) grava os dados só localmente e nunca chama a Avenia.

## Problema

`BeneficiaryEntity` tem as colunas `avenia_id` e `avenia_wallet_id`, claramente destinadas a guardar o identificador do beneficiário do lado da Avenia — mas nenhum ponto do código (`BeneficiaryController`, `BeneficiaryService`, `BeneficiaryCreateRequest`) as preenche. Confirmado por busca no código: zero ocorrências de `aveniaId`/`aveniaWalletId` fora da própria entidade e do DTO de resposta.

Resultado: **todo beneficiário cadastrado hoje tem `aveniaId`/`aveniaWalletId` nulos.**

## Impacto

`POST /v1/transfers` (recém-implementado) monta o ticket da Avenia usando esses campos para identificar o destino do pagamento (`beneficiaryBrlBankAccountId`, `beneficiarySwiftBankAccountId`, `beneficiaryWalletId`, conforme o método de recebimento). Com eles nulos, a chamada à Avenia deve ser rejeitada — não há como a Avenia rotear um pagamento para um beneficiário que não existe do lado dela.

Na prática, isso bloqueia o teste ponta a ponta de `POST /v1/transfers` contra o sandbox real com qualquer beneficiário cadastrado pelo fluxo atual.

## Proposta de solução

Ao criar um beneficiário (`BeneficiaryService.create`), registrar o beneficiário correspondente na Avenia antes de persistir localmente, e gravar o id retornado em `aveniaId` (contas bancárias/PIX) ou `aveniaWalletId` (carteira cripto). Provavelmente via `POST /v2/account/beneficiaries/bank-accounts/{...}/` (ver `AveniaApi.Beneficiary`, que já documenta os campos desse contrato, mas ainda não tem um client/service implementado).

Escopo sugerido:
1. Endpoint(s) Avenia de registro de beneficiário (bancário e wallet) na camada `external/avenia` (`AveniaClient`/`AveniaGateway`), análogo ao que já existe para quote/ticket.
2. `BeneficiaryService.create` chama esse registro e preenche `aveniaId`/`aveniaWalletId` antes de salvar.
3. Decidir o que fazer com beneficiários já cadastrados sem esses ids (migração de dados vs. bloquear transferência para eles até re-cadastro).

## Critérios de aceite

- Um beneficiário criado via `POST /v1/beneficiaries` tem `aveniaId` (ou `aveniaWalletId`, conforme o método de recebimento) preenchido após a criação.
- `POST /v1/transfers` para um beneficiário criado por esse fluxo completa com sucesso contra o sandbox real (não só nos testes com mock).
- Falha ao registrar na Avenia impede a criação local do beneficiário (evita ids nulos silenciosos de novo).

## Referências

- `entity/BeneficiaryEntity.java` (colunas `avenia_id`, `avenia_wallet_id`)
- `service/TransferService.java` (`buildAveniaTicketRequest`, onde esses ids são consumidos)
- `external/avenia/AveniaApi.Beneficiary` (constantes do contrato de registro de beneficiário na Avenia, ainda sem client)
- `docs/transfers-quote-and-creation.md` (guideline da card de transferências)
