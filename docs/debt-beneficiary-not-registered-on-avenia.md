# [Resolvido] Registro de beneficiário na Avenia

> Resolvido na correção da task #29. O cadastro local agora só é persistido depois
> que a Avenia retorna um identificador válido para a mesma subconta do usuário.

## Contexto

Durante a implementação de `POST /v1/transfers` (cotação e criação de transferências), foi identificado que o cadastro de beneficiários (`POST /v1/beneficiaries`) grava os dados só localmente e nunca chama a Avenia.

## Problema

`BeneficiaryEntity` tem as colunas `avenia_id` e `avenia_wallet_id`, claramente destinadas a guardar o identificador do beneficiário do lado da Avenia — mas nenhum ponto do código (`BeneficiaryController`, `BeneficiaryService`, `BeneficiaryCreateRequest`) as preenche. Confirmado por busca no código: zero ocorrências de `aveniaId`/`aveniaWalletId` fora da própria entidade e do DTO de resposta.

Resultado: **todo beneficiário cadastrado hoje tem `aveniaId`/`aveniaWalletId` nulos.**

## Impacto

`POST /v1/transfers` (recém-implementado) monta o ticket da Avenia usando esses campos para identificar o destino do pagamento (`beneficiaryBrlBankAccountId`, `beneficiarySwiftBankAccountId`, `beneficiaryWalletId`, conforme o método de recebimento). Com eles nulos, a chamada à Avenia deve ser rejeitada — não há como a Avenia rotear um pagamento para um beneficiário que não existe do lado dela.

Na prática, isso bloqueia o teste ponta a ponta de `POST /v1/transfers` contra o sandbox real com qualquer beneficiário cadastrado pelo fluxo atual.

## Solução implementada

Ao criar um beneficiário (`BeneficiaryService.create`), o backend resolve a
subconta Avenia do usuário autenticado e registra o destino antes de persistir
localmente. O identificador retornado é gravado em `aveniaId` (contas
bancárias/PIX) ou `aveniaWalletId` (carteira cripto).

Contratos suportados:

- PIX ou conta BRL: `/v2/account/beneficiaries/bank-accounts/brl/`;
- USD ACH/WIRE: `/v2/account/beneficiaries/bank-accounts/usd/`;
- EUR SEPA: `/v2/account/beneficiaries/bank-accounts/eur/`;
- conta internacional SWIFT: `/v2/account/beneficiaries/bank-accounts/swift/`;
- carteira cripto: `/v2/account/beneficiaries/wallets/`.

Beneficiários legados sem identificador externo ficam bloqueados na criação
de transferências com erro 422 e precisam ser recadastrados. Não há
sincronização automática porque não é possível provar a qual subconta Avenia
um cadastro antigo deveria pertencer.

Transferências SWIFT continuam bloqueadas antes da cotação: a Avenia exige
uma invoice enviada e processada, e o contrato atual de transferência não possui
um fluxo para associar esse documento ao ticket.

## Critérios de aceite

- Um beneficiário criado via `POST /v1/beneficiaries` tem `aveniaId` (ou `aveniaWalletId`, conforme o método de recebimento) preenchido após a criação.
- `POST /v1/transfers` para um beneficiário criado por esse fluxo completa com sucesso contra o sandbox real (não só nos testes com mock).
- Falha ao registrar na Avenia impede a criação local do beneficiário (evita ids nulos silenciosos de novo).

## Referências

- `entity/BeneficiaryEntity.java` (colunas `avenia_id`, `avenia_wallet_id`)
- `service/TransferService.java` (`buildAveniaTicketRequest`, onde esses ids são consumidos)
- `external/avenia/AveniaApi.Beneficiary` (constantes do contrato de registro de beneficiário na Avenia, ainda sem client)
- `docs/transfers-quote-and-creation.md` (guideline da card de transferências)
