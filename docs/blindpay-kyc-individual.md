# KYC individual da BlindPay (piloto Standard BR)

Fluxo independente do KYB empresarial e da verificação KYC da Avenia. A implementação reutiliza o BlindPayGateway, valida a assinatura Svix nos webhooks e registra apenas IDs/status em `blindpay_individual_kyc`, sem guardar selfie, CPF ou documentos no banco.

## Habilitação (por ambiente)

Desligado por padrão para evitar solicitações acidentais ao provedor:

- `BLINDPAY_INDIVIDUAL_KYC_ENABLED=true` — habilite somente no ambiente de desenvolvimento com credenciais da **instância development** da BlindPay.
- `BLINDPAY_API_KEY`, `BLINDPAY_INSTANCE_ID`, `BLINDPAY_WEBHOOK_SECRET` — credenciais do ambiente. Nunca commitar.
- `BLINDPAY_INDIVIDUAL_KYC_REDIRECT_URL` — URL pública de volta ao frontend, ex. `https://dev.exemplo.com/kyc/blindpay`. Se vazio, o usuário deve retornar à aplicação manualmente.
- Configure no dashboard o webhook existente `POST /v1/webhooks/blindpay` para `tos.accept`, `customer.new` e `customer.update`.

## Endpoints autenticados (ROLE_USER)

- `GET /v1/blindpay/kyc`: status individual (termsAccepted, submitted, kycStatus, amlStatus).
- `POST /v1/blindpay/kyc/terms`: URL do aceite hospedado. **Não** considera o retorno do navegador como aceite; requer `tos.accept` assinado. Enquanto o aceite não for confirmado, uma nova chamada gera outro link com nova chave de sessão (apenas o link mais recente confirma o aceite); depois do aceite ou do envio responde 409.
- `POST /v1/blindpay/kyc`: multipart com `profile` JSON, `idFront` (JPEG/PNG), `idBack` (se RG/CNH), `selfie` (JPEG/PNG) e `proofAddress` (opcional no Standard: PDF/JPEG/PNG). Cada arquivo: no máximo 3 MB. Requisição total até 14 MB.

O e-mail vem do usuário autenticado, nunca do formulário. O `external_id` é isolado como `user:{UUID}` para não colidir com o `external_id` empresarial do KYB. O país de residência e emissão é BR, pois a documentação exige `id_doc_country=BR` para pessoa física brasileira.

## Limitações antes da homologação

- Apenas KYC Standard para residentes no Brasil. Não aceitar outros países por este endpoint: países de alto risco exigem Enhanced e proibidos precisam ser bloqueados.
- O usuário deve inserir os dados individuais completos novamente; no fluxo Avenia eles não são preservados no banco da aplicação.
- O upload é encaminhado server-side com validação inicial da assinatura de arquivo. Não há análise de fraude, detecção de foto de tela nem OCR local.
- Reenvio após perda de resposta de createCustomer deve passar por investigação manual. A integração evita duplicação intencional com idempotency key, mas uma falha após uploads precisa de reconciliação operacional; não fazer retry automático com novas URLs.
- Rejeições exigem novo cadastro de cliente conforme orientação da BlindPay, nunca edição do cliente rejeitado. RFI individual ainda precisa de UI dedicada.
- Status de KYC aprovado não é, sozinho, autorização para pagamentos ou alteração do status KYB da empresa.

## Referência

- https://blindpay.com/docs/learn/customers
- https://blindpay.com/docs/kb/kyc
- https://blindpay.com/docs/learn/terms-of-service

Execute `./gradlew test` e `./gradlew build` antes do merge. Para teste de aceite real, utilize somente a instância development da BlindPay e verifique a entrega dos webhooks.

## Aplicação focada em KYC (pessoa física)

A empresa opera nos dois provedores com o **KYC individual do proprietário**:

- **Avenia**: subconta INDIVIDUAL do dono, vinculada em `provider_accounts` (`CompanyFinancialAccountService`).
- **BlindPay**: sem KYB, a primeira cotação vincula o cliente individual aprovado do dono (`BlindPayIndividualAccountService`). Exige `kyc_status` approved/approved_rfi, `aml_status=clear` e termos aceitos. Um cliente não pode ser vinculado a duas empresas. Os webhooks atualizam o KYC individual e a conta vinculada.

### Compliance

- Cadastro próprio exige aceite da versão vigente dos termos da V-Stable (`GET /v1/onboarding/terms`, público). Versão e instante ficam em `users.terms_version` e `users.terms_accepted_at` (V20).
- Termos da Avenia: `GET/POST /v1/onboarding/{kycId}/compliance/avenia-terms` usam `GET/POST /v2/terms/{subAccountId}`. O token de aceite nunca sai do backend; a evidência vai em `clientMetadata`. A finalização do KYC Avenia recusa com `AVENIA_TERMS_REQUIRED` sem o aceite.

Para desenvolver enquanto o endpoint de termos estiver indisponível no sandbox,
defina `AVENIA_TERMS_MOCK_ENABLED=true` no `.env` do backend e reinicie a aplicação.
O padrão é `false`; o mock só funciona com o host `api.sandbox.avenia.io`.
Ele simula leitura e aceite da versão 1 por subconta, em memória (reiniciar apaga
os aceites). Os documentos são identificados como simulações e seus links apontam
para o guia de integração, não para documentos legais reais. Nenhum aceite é enviado
à Avenia. Os demais endpoints continuam reais, incluindo o envio de KYC; o mock não
remove uma eventual exigência de termos feita pelo próprio provedor.
- KYC BlindPay recusado: `POST /v1/blindpay/kyc/restart` permite novo cadastro (novo cliente e novo aceite). O cliente anterior fica em `superseded_customer_id` e seus webhooks são ignorados.
- RFI individual: `GET/POST /v1/blindpay/kyc/rfi` e `POST /v1/blindpay/kyc/rfi/documents`, com a mesma validação do KYB (`BlindPayRfiSupport`).

### Concorrência nas cotações

O orquestrador já escolhe a melhor oferta entre os provedores elegíveis, mas hoje eles atendem destinos diferentes:

- Avenia: BRL (PIX) → token em carteira cripto.
- BlindPay: → conta bancária.

A rota em que os dois podem concorrer com KYC individual é **BRL (PIX) → USDC/USDT em carteira cripto**. Na BlindPay ela exige payin para a carteira gerenciada e depois transfer (`/transfer-quotes`, `/transfers`) para o endereço do beneficiário. A BlindPay só entrega payin direto em carteira do próprio cliente. Pagamentos bancários em USD/EUR pela Avenia exigem KYC USD/EUR adicional, partem de saldo em token e não existem no sandbox.

### Limitações

- Um vínculo BlindPay já criado nunca é substituído. Se o cliente individual vinculado for recusado depois, a empresa precisa de intervenção operacional.
- `ip_address` não é enviado à BlindPay: atrás do proxy da AWS o IP do cliente não é confiável sem configuração dedicada.
