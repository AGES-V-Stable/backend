# Crédito simulado de saldo — backend #38

Este endpoint **não movimenta dinheiro**. A implementação reutiliza a mesma operação de
`POST /v1/balance/simulated-deposits` introduzida pela cicd#24 e **não**
cria uma segunda forma de alterar saldos.

## Contrato HTTP

- `GET /v1/balance`: requer `BALANCE_READ` em vínculo empresarial ativo.
  Retorna `{ "availableBalanceBrl": "12.75" }`.
- `POST /v1/balance/mock-top-ups`: body `{ "amountBrl": "10.25" }`.
  Retorna `{ "creditedAmountBrl": "10.25", "availableBalanceBrl": "12.75" }`.

Os valores monetários da resposta são strings com duas casas decimais.
O montante precisa ser positivo, com até duas casas e caber no
`numeric(15,2)` de `companies.available_balance_brl`.
O service usa bloqueio pessimista da linha para não perder créditos simultâneos
e devolve `409 BALANCE_LIMIT_EXCEEDED` quando a soma excede a capacidade técnica.

## Segurança e diferenças em relação ao texto original da #38

Por segurança, o crédito simulado continua **desligado por padrão**; só está
disponível em ambientes configurados para testes e nunca deve ser habilitado
em produção. Herda as regras do service existente: **OWNER ativo** da própria
empresa e **KYC aprovado**. O texto da backend#38 descreve "usuário autenticado"
mais amplamente. **Essa diferença deve ser acordada com o Product Owner antes
de considerar todos os critérios da issue cumpridos.** Não remover o controle
de acesso nem criar um segundo service para contorná-lo.

A empresa vem exclusivamente do vínculo autenticado; não é aceita no body.
Admin da plataforma, membro removido e pessoa sem o perfil requerido não
podem creditar.

Não há chamadas para Avenia, BlindPay, PIX ou outros serviços financeiros.
A UI da frontend#72 deve usar este contrato, mas só apresentar a ação de
crédito a quem o backend permite e em ambientes habilitados.
