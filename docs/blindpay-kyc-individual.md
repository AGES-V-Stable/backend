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
- `POST /v1/blindpay/kyc/terms`: URL do aceite hospedado. **Não** considera o retorno do navegador como aceite; requer `tos.accept` assinado.
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
