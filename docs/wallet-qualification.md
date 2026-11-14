# Wallet beneficiary qualification

`POST /v1/companies/{companyId}/beneficiaries` accepts and persists these fields
for `receivingMethod: "CRYPTO_WALLET"`, and forwards them to Avenia when registering
the wallet:

| Field | Rule |
| --- | --- |
| `isSelfCustody` | Required boolean. `true` means the recipient controls the private keys. |
| `counterpartyName` | Required recipient name, at most 100 characters, without control characters. |
| `counterpartyCountry` | Required two-letter country code; normalized to uppercase. |
| `custodianName` | Required when `isSelfCustody` is `false`; omit when it is `true`. Same name limits. |
| `isSameEconomicGroup` | Optional qualification for foreign wallets; omit for Brazil. Avenia validates its applicability to the account. |

These fields are included in beneficiary responses. Bank and PIX registrations do
not require wallet qualification. Existing beneficiaries remain readable with null
qualification fields; migration V9 adds nullable columns and does not invent
qualification for existing wallets. This change does not qualify existing wallets
at Avenia or add an endpoint to update their qualification.

Example self-custody wallet registration (sandbox test data):

```sh
curl --fail-with-body -sS -X POST \
  "$API/v1/companies/$COMPANY_ID/beneficiaries" \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  --data-binary @- <<'JSON'
{
  "beneficiaryType": "INDIVIDUAL",
  "legalName": "Sandbox Test Recipient",
  "identificationDocument": "52998224725",
  "country": "Brasil",
  "address": "Sandbox Test Street 123",
  "receivingMethod": "CRYPTO_WALLET",
  "nickname": "Sandbox E2E wallet",
  "walletAddress": "0x000000000000000000000000000000000000dEaD",
  "blockchainNetwork": "polygon",
  "currency": "USDC",
  "isSelfCustody": true,
  "counterpartyName": "Sandbox Test Recipient",
  "counterpartyCountry": "BR",
  "confirmed": true
}
JSON
```

For an exchange-held wallet, set `isSelfCustody` to `false` and include the actual
`custodianName`. Custody is supplied by the caller; it is never inferred from the
wallet address. The company must already have its local KYB review approved.

Provider reference: [Avenia wallet registration and qualification](https://integration-guide.avenia.io/docs/Beneficiaries-Wallets/walletsGuide/),
`POST /v2/account/beneficiaries/wallets/`.
