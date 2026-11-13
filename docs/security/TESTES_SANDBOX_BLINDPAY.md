# Avaliação controlada do sandbox BlindPay — cicd#85

Esta etapa complementa a avaliação local, sem corrigir código de produção. A aplicação roda em loopback e o PostgreSQL é efêmero, via Testcontainers. Somente a integração BlindPay usa a API real, na instância cujo `type=development` foi confirmado por uma consulta autenticada. Avenia está substituída por mock. Não se acessa AWS, produção ou dinheiro real.

Branch: `test/85-integracoes-sandbox`. Commit-base: `01af9fcd48431ecb2d059c710c80d23d69318f92`. Os hashes dos testes, do executor e do contrato OpenAPI usados em cada execução ficam em `execution.json`; alterações locais integram a instrumentação avaliada.

## Resultado da execução completa

`attempt-03` terminou com `execution_state=FINISHED`: **77 subcasos, 60 PASS, 14 FAIL, um BLOQUEADO e dois NÃO EXECUTADO**. Os 14 FAIL são sete reproduções de SEC85-01 e sete de SEC85-11, não 14 vulnerabilidades diferentes. O bloqueio corresponde ao cadastro de cliente externo novo; as duas exclusões são Avenia real e webhook real. A tarefa diagnóstica retornou 1 por esses resultados, sem erro de compilação ou queda do processo nessa tentativa.

| Cenário funcional | Resultado observado | Controle de estado/idempotência |
|---|---|---|
| Sucesso | COMPLETED | PASS |
| Falha de payin | FAILED, sem payout | PASS |
| Reembolso de payin | REFUNDED, sem payout | PASS |
| Falha de payout | FAILED após financiamento | PASS |
| Reembolso de payout | REFUNDED | PASS |
| Oferta expirada localmente | HTTP 422, sem chamada adicional | PASS |
| Expiração durante financiamento | Uma nova cotação de payout; COMPLETED | PASS |
| Confirmação/idempotência repetida | Mesma execução e mesmo payin; COMPLETED | PASS |

No cenário de recotação, a nova oferta permitiu seguir automaticamente (`requote_attempts=1`, `requote_accepted=false`). Portanto, **não foi exercitada a aceitação explícita de uma oferta pior pelo usuário no sandbox**; sua cobertura local permanece separada. Não se provocou manipulação artificial de preços do provedor para forçar esse ramo.

Foram **80 chamadas reais nesta tentativa**, todas HTTP 200 e autenticadas. A campanha tem **123 chamadas confirmadas**, incluindo preparação/diagnóstico/recuperação anteriores, e limite conservador de **139 posições consumidas**, contando a reserva de 16 pela interrupção; teto 180. Nenhum `429`, erro de servidor ou parada por orçamento foi registrado. Isso não é um teste do rate limit do provedor.

`summary.json` vincula os quatro grupos TC-SBX-BP aos IDs efetivamente executados, mesmo quando a verificação de cotação está dentro de um cenário TC-SBX-BP-04. `database-consistency.json` registra consulta adicional de leitura em sete execuções: vínculos de empresa/beneficiário e valores de origem/destino consistentes entre cotação, execução e histórico; a tarifa em BRL continua como achado. A consulta ocorreu durante o último cenário, de modo que seus estados intermediários não substituem a evidência terminal do JUnit.

Nesta tentativa foram criadas uma conta bancária e uma carteira gerenciada de teste, oito cotações compostas iniciais, uma recotação de payout e sete execuções; as repetições idempotentes não representam novas execuções. Recursos e saldo USDB das tentativas anteriores permanecem identificados como massa de teste, sem alteração ou exclusão de recursos da equipe.

## Verificação local após a execução

- Compilação e três testes do limitador/origem/sanitização: aprovados, sem chamadas externas.
- Regressão padrão: **1.522 casos, 1.516 PASS, zero FAIL, seis desabilitados na origem**. As tarefas `test`, `check`, `build` e `jacocoTestReport` concluíram; nenhum teste real de sandbox entrou nessa suíte.
- O comando conjunto `build securityAssessmentTest --continue` retornou 1 pelo diagnóstico separado. Não se apresenta esse comando conjunto como `BUILD SUCCESSFUL`.
- O primeiro diagnóstico no HEAD atualizado produziu 421 casos/23 FAIL. Dois eram uma classificação desatualizada: `/v1/onboarding/account` passou a ser uma rota pública de cadastro em `SecurityConfiguration`, mas a lista do teste ainda exigia autenticação. A lista de teste foi sincronizada com esse contrato; nenhuma regra de produção foi alterada.
- Diagnóstico local reexecutado: **419 casos, 398 PASS, 21 FAIL, zero ignorados**, preservando os 21 resultados conhecidos da avaliação local. O inventário atual tem 71 operações, cinco públicas, 66 protegidas e 18 administrativas. Sua presença no inventário/regressão não equivale a avaliação de segurança completa do fluxo de convites.
- Os resultados individuais estão em `local-regression/integration-retest.json` e `local-regression/assessment.json`; a captura anterior à correção de classificação foi preservada em `before-inventory-update/`.

Frontend não foi modificado nem reexecutado nesta etapa. Capturas antigas de 385 casos/59 operações e 772 testes frontend pertencem aos seus commits e datas originais.

## Execução e evidências

As capturas ficam em [evidencias-85-sandbox/2026-11-13](evidencias-85-sandbox/2026-11-13). A captura completa mais recente deve ser identificada por `execution_state=FINISHED`, não pela simples existência de um JSON. `RUNNING` é apenas checkpoint. Código de saída diferente de zero pode representar achado ou bloqueio; consulte os subcasos.

- `assessment.json` na raiz: primeira preparação, encerrada antes de criar recursos, porque exigia AML `clear` dos dados de demonstração.
- `preparation-diagnostic.json`: duas consultas de leitura que distinguiram campos ausentes na listagem dos campos disponíveis no detalhe do cliente.
- `attempt-02/assessment.json`: checkpoint da execução interrompida pelo desligamento do computador; não é uma suíte concluída.
- `interruption-review.json`: casos concluídos, casos interrompidos, retirada da hipótese incorreta de moedas diferentes nas tarifas e reserva de orçamento.
- `interruption-recovery.json`: consulta de status do payin interrompido; o provedor retornou `completed`, mas o registro local permanecia `AWAITING_FUNDS`, sem payout registrado. A operação não foi reenviada.
- `fee-persistence.json`: consulta somente leitura no PostgreSQL efêmero recuperado, sem IDs ou dados bancários.
- `attempt-03/`: reexecução com fixtures separadas e checkpoint após cada chamada externa; os resultados dessa execução prevalecem sobre hipóteses das capturas anteriores.

Não some PASS/FAIL das tentativas como se fossem casos independentes de uma única suíte. Em particular, os quatro `*-fee-currency` reprovados em `attempt-02` foram retirados por premissa incorreta, conforme `interruption-review.json`. O contrato OpenAPI indica tarifas de payin e payout em centavos do token; a moeda de `sender_amount` não determina a moeda de `flat_fee`.

## Casos e condições

| Grupo | Verificação |
|---|---|
| TC-SBX-BP-01 | Chave/instância Development, contrato do `BlindPayClient`, status HTTP, tipo de conteúdo e metadados permitidos |
| TC-SBX-BP-02 | Cliente de demonstração aprovado, termos aceitos, nova conta bancária sintética e associação; empresa B impedida de cotar/confirmar recursos da empresa A antes de chamada externa |
| TC-SBX-BP-03 | SOURCE/TARGET fixos, centavos, `cover_fees`, precisão da rota BRL → USDB → USD, validade e persistência da cotação; taxas observadas e sua unidade |
| TC-SBX-BP-04 | Sucesso, falha/reembolso de payin, falha/reembolso de payout, oferta expirada, recotação após expiração local, idempotência e reconciliação/imutabilidade terminal |

As rotas da aplicação são acessadas por HTTP de rede em `127.0.0.1`, com JWT e filtros reais. O usuário é OWNER de uma empresa sintética, com TOTP configurado, ativado e utilizado no login. As confirmações financeiras omitem o TOTP de transferência para avaliar SEC85-01. A recotação expirada é preparada alterando exclusivamente a data no banco de teste; não se declara que o provedor espontaneamente expirou essa cotação naquele instante. Estados não capturados pelo polling não são apresentados como observados.

O PostgreSQL recebe CNPJs gerados com dígitos verificadores e usuários `@example.test`. O cliente externo reutilizado pertence à massa de demonstração `@example.com`, tem KYC aprovado e termos aceitos; é `individual`, não prova KYB de empresa. AML nulo é registrado como não demonstrado, sem alegar triagem aprovada. A aprovação local da fixture reflete o KYC observado; não comprova execução do onboarding completo.

Não foi criado cliente externo novo: o contrato atual exige aceite de termos no navegador e documentos de teste adequados. Não se automatizou aceite em nome de uma pessoa nem se inventaram documentos. A nova conta bancária usa exemplos públicos do contrato, exclusivamente em Development. Recursos existentes da equipe foram preservados.

## Orçamento e interrupção

- Teto total: 180 posições de orçamento, chamadas sequenciais com pelo menos 3 segundos entre elas; sem carga, fuzzing, concorrência externa ou teste para atingir rate limit.
- Limite da etapa externa: 25 minutos por execução; no máximo dez ciclos explícitos de acompanhamento por cenário. Reconciliação automática desativada.
- Antes de `attempt-03`: 43 chamadas confirmadas, mais reserva conservadora de 16 para eventual intervalo não salvo antes do desligamento. Portanto, a reexecução começa com 59 posições consumidas; a reserva não é apresentada como 16 chamadas comprovadamente executadas.
- `external_calls` em `attempt-03` conta as chamadas dessa tentativa; `budget_consumed` inclui as 59 posições anteriores. A soma conhecida é 43 mais as chamadas da tentativa; o limite conservador acrescenta a reserva.
- Bloqueio antes de escrita sem confirmação de Development; origem HTTPS/host/instância restritos; redirects desativados na instrumentação; parada em `429`, autenticação negada, erro de servidor ou resultado de transporte desconhecido. Nenhum retry financeiro com chave nova após resultado incerto.

O builder HTTP desta instrumentação aplica limites e bloqueio de origem; esse endurecimento do executor não demonstra que toda configuração de produção tenha os mesmos controles. A suíte padrão continua usando integrações simuladas.

## Achados e recomendações

### SEC85-01 — Confirmação financeira sem TOTP

A evidência de sandbox complementa o achado local: uma conta com TOTP ativo e login de dois fatores consegue acionar o payin pela confirmação HTTP da oferta enviando apenas os valores. Repetições do mesmo defeito em cenários diferentes não são vulnerabilidades distintas.

Aplicar autorização forte na confirmação inicial e na recotação, antes dos efeitos externos, com consumo atômico do fator e idempotência que preserve requisições já autorizadas. O teste deve continuar reprovando enquanto o comportamento inseguro existir.

### SEC85-11 — Tarifa em token persistida como BRL sem conversão

`BlindPayQuoteProvider` transforma os centavos das tarifas do provedor em um escalar denominado no token. `TransferExecutionService.prepareBlindPay` copia esse escalar diretamente para `serviceFeeBrl` quando a origem é BRL. A captura recuperada contém, por exemplo, tarifa sintética de **15,10 USDB** e `service_fee_brl=15.10`, sem conversão de moeda. Os valores de origem/destino desse exemplo são 100,00 BRL e 2,49 USD. Não se atribui esse resultado a dinheiro real.

O problema demonstrado é a unidade da tarifa persistida, não a hipótese retirada de soma de duas moedas diferentes no adapter. Não se comprovou cobrança adicional pelo provedor nem prejuízo real. Preservar a moeda de cada componente, separar taxas cobradas por operação das faturadas posteriormente e converter explicitamente antes de preencher campos em BRL. A regra de contabilização/conversão e o arredondamento devem ser definidos pela equipe. Nenhuma correção de produção foi feita nesta entrega.

## Reprodução

Requisitos: Java 25, Docker Desktop e as variáveis `BLINDPAY_API_KEY` e `BLINDPAY_INSTANCE_ID` carregadas **no mesmo processo** que executará o script. Não forneça valores como argumentos de linha de comando nem os versione. O executor cria segredos locais novos para JWT/TOTP/convites, consulta o contrato público e usa PostgreSQL temporário.

```powershell
# A partir de backend/, com as duas variáveis já carregadas com segurança:
.\scripts\run-blindpay-security-sandbox.ps1

# Verificação local dos controles do executor, sem chamadas externas:
.\gradlew.bat test --tests '*BlindPaySandboxBoundaryTest' --no-daemon
```

Cada execução escolhe uma pasta nova. O script recusa sobrescrever uma avaliação existente. `-PriorCalls` serve para descontar consumo anterior ou reserva documentada; não deve ser zerado para contornar o teto de uma campanha em andamento. A tarefa opt-in é `blindpaySecuritySandboxTest`, excluída de `test/build` e da tarefa anterior `blindpaySandboxTest` por tags distintas.

Os arquivos JSON contêm somente campos permitidos: resultados, estados, valores sintéticos, contagens e hashes. Chaves, senhas, tokens, IDs externos, headers de autenticação e corpos HTTP completos não são exportados. JUnit/HTML brutos em `build/` não são artefatos de publicação.

## Limitações mantidas

1. Avenia real não executada nesta etapa.
2. Recebimento de webhook real não executado: não foi aberto túnel nem alterado o dashboard. Polling não comprova assinatura ou entrega de eventos. Os testes locais existentes de Svix permanecem separados.
3. Cadastro de cliente externo novo e KYB completo não demonstrados; usados dados de demonstração e recursos novos associados.
4. Convite/cadastro de operadores não é escopo desta complementação. O HEAD atual já contém código de convites; sua existência não implica que o fluxo foi avaliado aqui, nem autoriza dizer que continua ausente nesse HEAD.
5. Não se provoca timeout, autenticação inválida, tarifa negativa ou esgotamento de recursos no provedor. A cobertura defensiva correspondente permanece local; uma resposta real válida não substitui esses cenários.
6. USDB com centavos não comprova precisão de oito casas para ativos de produção. Não constitui reteste corretivo de SEC85-07/08.
7. A operação interrompida deixou saldo de teste no provedor; nenhum pagamento real foi realizado. Não se excluem ou liquidam recursos para ocultar esse estado.

Referências de contrato: [OpenAPI oficial](https://api.blindpay.com/doc), [instâncias](https://blindpay.com/docs/learn/instances) e [sandbox quickstart](https://blindpay.com/prompts/sandbox-quickstart). As instruções externas não substituem o escopo autorizado: não houve túnel, publicação de IDs ou aceite automático de termos.
