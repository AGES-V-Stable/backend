# Vínculos empresariais e definição do proprietário

A migration `V11__add_company_memberships_and_invitations.sql` cria um vínculo (`company_memberships`) para cada usuário existente:

| Situação da empresa | Resultado do backfill |
|---|---|
| Um usuário | Ele vira `OWNER` |
| Vários usuários | Todos viram `OPERATOR`; **ninguém é promovido** |
| Nenhum usuário | Nenhum vínculo |

## Por que isso exige uma ação manual

Uma empresa sem `OWNER` continua operando: consulta, cadastro de beneficiários e transferências seguem funcionando para os operadores. O que fica bloqueado são as ações exclusivas do proprietário:

- envio de termos, documentos, KYB e RFI da BlindPay (`/v1/blindpay/compliance/*`), que responde 403 `OWNER_REQUIRED`;
- gestão de membros e convites, nas próximas tasks da backend#64.

Ao rodar, a migration lista essas empresas num `NOTICE` do Postgres ("Empresas com vários usuários ficaram sem proprietário…"). Só defina o proprietário depois de confirmar com a empresa quem responde por ela.

## 1. Listar as empresas sem proprietário

```sql
SELECT c.id AS company_id, c.legal_name, u.id AS user_id, u.email, u.created_at
FROM companies c
JOIN company_memberships m ON m.company_id = c.id AND m.status = 'ACTIVE'
JOIN users u ON u.id = m.user_id
WHERE NOT EXISTS (
    SELECT 1 FROM company_memberships o
    WHERE o.company_id = c.id AND o.role = 'OWNER' AND o.status = 'ACTIVE')
ORDER BY c.legal_name, u.created_at;
```

## 2. Promover o proprietário confirmado

Troque os dois UUIDs pelos valores confirmados com a empresa. O índice `company_memberships_active_owner_key` impede que a empresa fique com dois `OWNER` ativos.

```sql
BEGIN;
UPDATE company_memberships
SET role = 'OWNER', version = version + 1, updated_at = now()
WHERE company_id = '<company_id>'
  AND user_id = '<user_id>'
  AND status = 'ACTIVE'
  AND role = 'OPERATOR';
-- conferir que exatamente 1 linha foi alterada antes de confirmar
COMMIT;
```

A mudança vale na próxima requisição do usuário, sem novo login, porque o vínculo é relido do banco a cada requisição autenticada.
