# Conta financeira Avenia vinculada à empresa

O onboarding usa KYC individual. Na primeira operação financeira sem associação
Avenia, `CompanyFinancialAccountService` resolve o proprietário com vínculo ativo,
consulta o seu KYC mais recente e exige `APPROVED` e uma subconta não vazia.
A subconta individual é então vinculada à empresa em `provider_accounts`, com
`provider = AVENIA` e `status = ACTIVE`. Não se cria uma nova subconta no provedor.
Não há requisito local de KYB/AML da empresa.

## Conta compartilhada e permanente

- Proprietário, operadores e outros membros com permissão usam a mesma associação.
  O usuário autenticado continua sendo o autor da operação para autorização, 2FA e auditoria.
- A associação existente tem prioridade: trocar o proprietário, remover o proprietário
  original ou realizar um novo KYC não troca a subconta financeira já vinculada.
- Uma associação suspensa, pendente ou sem identificador válido bloqueia a operação.
  O sistema não a substitui nem a reativa automaticamente.
- O KYC aprovado de um operador não substitui o KYC pendente do proprietário na primeira associação.
- O índice único de provedor/subconta impede usar a mesma subconta em duas empresas.
  Não há fallback para a conta principal da API key ou para a conta de outro membro.

## Persistência e concorrência

Na ausência da associação, a linha de `companies` é bloqueada e a associação é
consultada novamente antes de criá-la. Duas requisições da mesma empresa não
criam vínculos diferentes. A resolução executa em uma transação própria; o vínculo
é confirmado antes da chamada financeira e permanece salvo se uma criação de
beneficiário, cotação ou transferência falhar depois.

A persistência usa o schema existente, sem migration adicional. A consulta não
submete KYC nem chama APIs externas: usa o resultado já sincronizado pelo onboarding.

## Beneficiários e transferências

O cadastro de beneficiários, as cotações e os tickets Avenia usam o vínculo
canônico de `provider_accounts`. Beneficiários continuam pertencendo à empresa;
seu `avenia_sub_account_id` precisa corresponder à subconta da operação. Registros
feitos anteriormente em outra subconta não são migrados nem têm os IDs externos
reescritos automaticamente.

Permissões de acesso empresarial, separação entre empresas, 2FA, conta financeira
ativa e condições próprias do provedor continuam sendo verificadas.

## Verificação

Testes unitários, HTTP e de integração PostgreSQL cobrem associação a partir de KYC
aprovado, membros compartilhando a conta, persistência após troca de proprietário,
concorrência na primeira operação, falha do provedor, isolamento entre empresas e
recusa de associações suspensas. Todas as chamadas Avenia desses testes são simuladas.
