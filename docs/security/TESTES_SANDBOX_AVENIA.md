# Testes controlados do sandbox Avenia — cicd#85

## Resultado e limites

Em **13/11/2026**, foram executadas **40 chamadas HTTP reais** à Avenia sandbox, dentro do teto de 50. Houve **dois tickets de teste pagos pelo simulador**: um na conta principal, com saída INTERNAL para a própria conta, e outro na subconta sintética, pela aplicação local, com saída USDC/POLYGON. Nenhum PIX foi pago em aplicativo bancário, nem houve envio de ativos reais.

A consolidação contém **26 IDs: 22 PASS, 3 FAIL e 1 NÃO EXECUTADO**. Isso corresponde a 25 subcasos avaliados e um grupo de webhooks não executado; não significa cobertura integral da issue. Estados financeiros de falha/cancelamento, recuperação de resultado incerto e outros limites abaixo não estão incluídos como testes aprovados.

Os três FAIL finais são:

- **TC-SBX-AV-05.2 / SEC85-01:** a confirmação HTTP da oferta criou um ticket real de sandbox sem TOTP da transferência, com usuário sintético cujo TOTP estava ativo e que havia feito login com senha e segundo fator. A rota direta, em contraste, recusou a ausência do fator com HTTP 422 e zero chamadas externas.
- **TC-SBX-AV-03.4 / SEC85-07:** a execução preservou **1,123456 USDC**, mas o histórico armazenou **1,12 USDC**. Ambos os valores são massa de teste, originados no fluxo com provedor real.
- **TC-SBX-AV-06.3 / apontamento anterior VULN-11:** o ticket chegou a `PAID`, enquanto o GET local permaneceu em `SUBMITTED`. É uma observação operacional de acompanhamento, não uma exploração de segurança demonstrada. Os agendamentos estavam desativados para controlar o orçamento; além disso, a inspeção do HEAD mostra que o reconciliador existente filtra `BLINDPAY`, e não existe receptor Avenia. Portanto, não se atribui o resultado apenas à configuração do teste, nem se afirma ter validado um reconciliador Avenia habilitado.

Nenhuma vulnerabilidade foi corrigida nesta entrega. Os achados locais anteriores não são apresentados como novos achados independentes.

## Versão, ambiente e autorização

| Item | Condição avaliada |
|---|---|
| Repositório | AGES-V-Stable/backend |
| Branch | `test/85-seguranca-avenia-sandbox` |
| HEAD base | `1a3add10ab0032b9834ff552e9af74a14cd859d8` |
| Código executado | HEAD acima mais instrumentação de teste não commitada; hashes por tentativa em `runner.json` |
| Aplicação | Spring Boot local, servidor HTTP em loopback e porta aleatória |
| Banco | PostgreSQL `16.4` efêmero, criado e descartado por Testcontainers |
| Ferramentas | Java Temurin `25.0.3`, Gradle Wrapper `9.7.1`, Docker `29.8.0` |
| Único destino de negócio externo | `https://api.sandbox.avenia.io:10952` |
| Credenciais | Novas chaves em `.env.avenia-security/`, ignoradas pelo Git; conta esperada confirmada |
| Conta do provedor | INDIVIDUAL; não equivale a KYB institucional/produção |
| Outras integrações | BlindPay e assistente simulados; verificadas sem interações; nenhum acesso AWS |
| Execução | Sequencial, sem fuzzing, carga, concorrência ou falhas de transporte induzidas |

O prompt do responsável autorizou especificamente essas chamadas funcionais como exceção às instruções gerais de testes simulados do `AGENTS.md`. Aplicam-se o planejamento da #83 e seus critérios de parada. Não houve alteração de chaves, permissões, destinos de webhook, configuração compartilhada, código de produção ou migrations.

O transporte de teste injeta timeout, bloqueio de redirects e allowlist de origem/método/path. Isso protege a execução e **não comprova que o builder HTTP padrão da aplicação tenha esses mesmos controles**; o achado anterior sobre redirects não foi marcado como corrigido.

## Evidências e tentativas preservadas

Diretório: [evidencias-85-avenia-sandbox/2026-11-13](evidencias-85-avenia-sandbox/2026-11-13/).

- [`summary.json`](evidencias-85-avenia-sandbox/2026-11-13/summary.json): última observação de cada ID, origem da captura e totais; não soma os retestes como novos casos independentes.
- `assessment.json` em cada tentativa: esperado, observado, camada, detalhes permitidos, status, chamadas e horários UTC.
- `runner.json`: branch, HEAD, hashes dos arquivos e encerramento efetivo do Gradle. As revisões da instrumentação têm hashes diferentes; não são uma única captura imutável.
- `validation.json`: resultado da regressão, sanitização e verificação final, quando concluídas.

| Tentativa | HTTP reais | Estado da avaliação | Saída Gradle | Interpretação |
|---|---:|---|---:|---|
| `av85-preflight-01-preflight` | 5 | FINISHED | 0 | Conta, metadados, limites e ausência de callbacks |
| `av85-assessment-01-assessment` | 13 | FINISHED | 1 | Contratos e primeiro ticket PAID; caso local com payload incompleto refeito depois |
| `av85-business-01-business` | 4 | INTERRUPTED | 1 | Preparação do beneficiário rejeitada com HTTP 400; nenhum ticket empresarial enviado |
| `av85-business-02-business` | 5 | FINISHED | 0 | Beneficiário GNOSIS criado; cotação externa HTTP 500, oferta local HTTP 422; cenários dependentes bloqueados |
| `av85-business-03-business` | 13 | FINISHED | 1 | Alternativa POLYGON funcional, segundo ticket PAID, três asserções finais FAIL |
| **Total desta avaliação** | **40** | **Processos encerrados** | — | **Sem `ignoreFailures`** |

As duas consultas GET anteriores de validação das novas chaves são históricas e **não estão somadas às 40 chamadas**. Incluindo-as, são 42 chamadas conhecidas. Não houve 429 ou resultado financeiro desconhecido. Um 500 foi observado, sem persistência na rota alternativa; a parada global estava configurada para o segundo 5xx.

O primeiro `TC-SBX-AV-02.1` retornou 400, não o 404 esperado. O corpo de teste foi ajustado para fornecer os campos conforme o DTO, inclusive valor numérico e `coverFees`; o reteste retornou 404. O FAIL original foi preservado como falha de instrumentação e não contado como vulnerabilidade.

O primeiro payload de carteira incluía `isSameEconomicGroup=false` para país BR. A documentação orienta omitir o campo nessa condição; o payload ajustado foi aceito. A mensagem sanitizada da primeira rejeição não foi capturada, portanto a causa exata daquele 400 não foi demonstrada. Não se classifica essa tentativa como defeito confirmado do produto.

Na primeira captura de `01.2`, apenas o sucesso HTTP ficou evidenciado; as flags não foram extraídas pelo formato inicialmente esperado. `01.6` complementou a leitura por allowlist e comprovou as permissões operacionais. Não se inferem permissões a partir do HTTP 200 isolado.

## Matriz de subcasos

As pré-condições comuns são a conta esperada, chaves novas, orçamento disponível e transporte restrito. Os casos HTTP locais usam empresas e representantes OWNER sintéticos A/B com login real e TOTP ativo. Não reavaliam integralmente OPERATOR/VIEWER/Admin ou convites. Quando os registros financeiros iniciais são fixtures locais, isso está declarado no JSON; não são apresentados como operações remotas.

| ID TC-SBX-AV | Requisito / esperado | Observado e camada | Resultado final |
|---|---|---|---|
| 01.1 | Assinatura RSA e conta correta | GET real pelo transporte/signer do cliente; conta confirmada | PASS |
| 01.2 | Consultar acesso sem expor dados | Consulta autenticada; interpretação das flags complementada em 01.6 | PASS limitado |
| 01.3 | Conhecer liberações | BRL/USD liberados; demais flags restritas no JSON; não prova KYB | PASS |
| 01.4 | Verificar bloqueio e limites | Conta não bloqueada; valores da conta omitidos | PASS |
| 01.5 | Evitar callbacks fora do escopo | Lista de destinos vazia, preservada | PASS |
| 01.6 | Confirmar permissões antes da escrita | Flags operacionais conhecidas e conta novamente confirmada | PASS |
| 02.1 | B não cota beneficiário de A | 404 e zero chamadas externas; fixture local | PASS após reteste |
| 02.2 | B não confirma oferta de A | 404 e zero chamadas externas; fixture local | PASS |
| 02.3 | B não consulta execução de A | 404 e zero chamadas externas; fixture local | PASS |
| 02.4 | Criar subconta própria | Cliente real retornou 201; BRL liberado sem submissão KYC | PASS |
| 02.5 | Preservar vínculos após negativas | SQL confirmou empresa proprietária; nenhum efeito remoto | PASS |
| 02.6 | Vincular carteira remota à empresa A | Carteira própria na subconta correta; vínculo local, KYC PENDING | PASS |
| 02.7 | Impedir uso da carteira remota por B | HTTP 404 antes da Avenia | PASS |
| 02.8 | Impedir confirmação por B da oferta real de A | HTTP 404, sem novo efeito remoto | PASS |
| 03.1 | Cotação com origem fixa | 10 BRL preservados; saída USDC, DTO tipado, tarifas exportadas por moeda | PASS |
| 03.2 | Cotação com destino fixo e precisão | 1,123456 USDC preservados; tarifas observadas sem somar moedas diferentes | PASS |
| 03.3 | Oferta real e persistência | HTTP local, agregador e provider_quotes preservaram 1,123456 USDC; origem confirmada 6,71 BRL | PASS após bloqueio GNOSIS |
| 03.4 | Histórico preservar precisão da execução | 1,12345600 USDC versus 1,12 USDC | **FAIL — SEC85-07** |
| 04.1 | Criar e acompanhar ticket de simulação | Conta principal, PIX → USDC INTERNAL; PROCESSING → PAID | PASS |
| 04.2 | Executar oferta na subconta e consultar status | PIX → USDC POLYGON, PAID; sem atualizar banco artificialmente | PASS |
| 05.1 | Rota direta exigir TOTP financeiro | HTTP 422 sem fator e sem chamada externa | PASS |
| 05.2 | Confirmação de oferta também exigir fator | HTTP 200 e ticket criado sem TOTP financeiro | **FAIL — SEC85-01** |
| 06.1 | Repetir confirmação sem novo efeito | Mesma execução, um registro e zero chamadas adicionais | PASS |
| 06.2 | Ticket terminal permanecer estável | Segunda consulta ao primeiro ticket manteve PAID | PASS limitado à leitura |
| 06.3 | Refletir liquidação no estado local | Provedor PAID versus API local SUBMITTED | **FAIL operacional / limitação de acompanhamento** |
| 08.1 | Receber e autenticar eventos reais | Receptor Avenia e destino público próprio ausentes; nenhum túnel | **NÃO EXECUTADO** |

Cada linha tem sua captura individual em `summary.json`. A numeração 08 preserva o grupo de webhooks solicitado; não existem grupos executados 07 ou novos testes inferidos dos números.

## Achados e recomendações

### TOTP financeiro ausente na confirmação — SEC85-01

Reprodução segura: preparar usuário local com TOTP ativo e autenticação real, obter oferta própria pela API e chamar `POST /v1/quotes/offers/{OWN_OFFER}/confirm` apenas com os montantes confirmados. O ticket de sandbox foi criado. Repetir a mesma confirmação não criou outro efeito, mas esse controle não substitui o segundo fator.

Referências do código avaliado: [TransferExecutionController](../../src/main/java/ages/vstable/backend/controller/TransferExecutionController.java), [TransferExecutionService](../../src/main/java/ages/vstable/backend/service/transfer/TransferExecutionService.java) e `TransferService.createConfirmedOffer`, que não valida o fator como `TransferService.create`.

Correção recomendada: exigir e validar o fator financeiro em todos os caminhos que podem submeter a transferência, com consumo/replay e concorrência tratados de forma compartilhada e atômica. Classificação herdada: CWE-287, OWASP API2/API6:2023. A eficácia da correção exige reteste separado.

### Precisão do histórico — SEC85-07

Reprodução segura: confirmar uma oferta de destino sintético 1,123456 USDC e comparar `transfer_executions.target_amount` com `base_transactions.foreign_amount`. A primeira coluna preservou seis casas úteis; a segunda possui escala 2 em [BaseTransactionEntity](../../src/main/java/ages/vstable/backend/entity/BaseTransactionEntity.java).

Correção recomendada: definir precisão por ativo e preservar os montantes efetivos em todo o histórico e na execução; revisar schema, DTOs e arredondamento em remediação própria. Não arredondar cripto como fiat. Relação com CWE-197/CWE-682; o teste não demonstrou desvio de dinheiro real.

### Acompanhamento Avenia ausente — VULN-11 da #83

O polling do teste comprovou o estado PAID, mas não conciliou a aplicação. `TransferExecutionReconciler` seleciona apenas BLINDPAY; `TransferService` documenta que persiste somente o snapshot da criação. Não há receptor Avenia nos controllers deste HEAD.

Recomendação: implementar acompanhamento autorizado por consulta ou webhook autenticado, com validação de propriedade, deduplicação, ordenação e transições válidas. Tratar estados incertos consultando a operação original; `externalId` não é uma garantia de idempotência da Avenia. Essa ausência funcional não foi convertida automaticamente em CWE ou exploração comprovada.

## Recursos e recuperação

Foram deixados **uma subconta INDIVIDUAL própria, duas carteiras beneficiárias próprias (GNOSIS/POLYGON) e dois tickets PAID**. As carteiras usam endereços aleatórios não financiados. Nenhuma subconta/carteira preexistente da equipe foi modificada ou excluída. Subcontas são permanentes segundo a documentação do provedor.

IDs e referências para recuperação estão exclusivamente em `.env.avenia-security/assessment-resources-*.json`, `business-resources-*.json`, `assessment-ticket-*.json` e `business-ticket-*.json`, ignorados pelo Git. A versão final também grava intenções privadas antes de POSTs. Não publicar essa pasta. Os JSONs versionados usam aliases e não permitem ao revisor recuperar identificadores sensíveis.

O executor preserva tentativas anteriores, limita chamadas/polling e bloqueia reenvio após escrita cujo resultado não foi recebido. Após queda, revisar `RUNNING`/`INTERRUPTED`, os registros privados e o provedor antes de iniciar outra operação. Não inferir ausência de ticket pela expiração do token. `FINISHED` na avaliação e saída 1 do Gradle são compatíveis com asserções FAIL; não significam processo ainda em andamento.

## Reprodução e validação

Arquivos de instrumentação adicionados:

| Arquivo | Finalidade |
|---|---|
| `AveniaSandboxPreflightTest.java` | Consultas iniciais de conta, acesso, metadados, limites e callbacks |
| `AveniaSecuritySandboxTest.java` | Aplicação HTTP local, banco efêmero, isolamento A/B, cotações e tickets reais |
| `AveniaSandboxSession.java` | Fronteira de rede, orçamento, assinatura pelo cliente existente e checkpoints sanitizados |
| `AveniaSandboxBoundaryTest.java` | Contraprovas locais para destino, extração de flags e classificação sanitizada de erros |
| `scripts/test-avenia-security-sandbox.ps1` | Segredos exclusivos do processo, execução manual, hashes e bloqueio de reenvio |
| `build.gradle` | Tarefa opt-in e exclusão explícita das suítes padrão/PIT |
| Relatório, JSONs e atualização da wiki | Resultados rastreáveis, condições, recomendações e limitações |

Pré-requisitos: Java 25, Docker funcionando, novas chaves locais protegidas pelo Git e acesso autorizado à conta sandbox esperada. Não configurar MFA novamente para chamadas assinadas.

Na branch de testes, em `backend/`, para **uma nova campanha explicitamente escolhida**:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/test-avenia-security-sandbox.ps1 -Phase preflight -Campaign revisao-20261114
# Conferir o preflight antes de prosseguir.
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/test-avenia-security-sandbox.ps1 -Phase assessment -Campaign revisao-20261114
```

Os comandos criam recursos novos de teste. Não executar novamente para apenas visualizar resultados; abrir os JSONs e este relatório. A campanha `initial` contém esta captura e bloqueia reenvios. `business` é uma continuação controlada usada na investigação, com recuperação explícita da subconta própria; não é necessária no fluxo final normal. O teto é 50 chamadas por campanha e os limites de tempo são 5 minutos para preflight e 15 para cada etapa posterior. Iniciar outra campanha não é autorização para ignorar operações anteriores incertas.

A tarefa `aveniaSecuritySandboxTest` exige opt-in e está excluída de `test`, `build`, CI padrão e PIT. O executor gera segredos próprios para JWT/TOTP/convites no processo, sem usar os compartilhados. Logs brutos e JUnit permanecem em `build/` ignorado e não foram exportados como evidência. As revisões finais do runner foram verificadas localmente; não se repetiram as operações remotas apenas para atualizar hashes.

Validação sem APIs reais: `gradlew.bat build --no-daemon --max-workers=1 --console=plain`, com opt-in falso, chaves vazias e bases de provedores em loopback. **Saída 0: 1.522 casos em 119 suítes, 1.516 PASS, zero falhas/erros e 6 ignorados.** Inclui os três testes locais da fronteira de sandbox/sanitização. Os testes externos são excluídos por tag, não contados como aprovações da regressão. Resultado e hashes em `validation.json`. A sintaxe PowerShell e a recusa de reenvio de campanha já executada também passaram, sem chamadas HTTP.

A conferência posterior das duas cotações sanitizadas também verificou tarifas não negativas, todas em BRL, soma menor que a entrada e resíduo de arredondamento até um centavo de BRL entre entrada líquida e destino × preço. Os detalhes estão em `validation.json`. É análise das respostas capturadas, sem chamada adicional, e não valida todos os cálculos defensivos do backend ou todas as moedas.

## Pendências e conclusão do escopo

- **Webhooks reais:** recebimento, assinatura, falsificação, replay, duplicidade e ordem não executados; polling não os substitui.
- **Falha financeira/cancelamento:** não foram criados cenários financeiros terminais FAILED/CANCELED. O simulador documentado de bloqueio bancário exige payout PIX, fora da rota BRL/PIX → carteira do adapter atual. Não foi forçada uma corrida de cancelamento contra PIX automaticamente pago.
- **Resultado incerto e TOCTOU:** não houve timeout ou resposta perdida, nem falhas/tempo manipulados no provedor. Permanecem testes defensivos locais; esta etapa não prova recuperação real de um ticket incerto.
- **Validade e tarifas:** tokens frescos foram aceitos e a confirmação recotou pelo caminho de produção; não se induziu expiração/reuso externo nem preço pior no sandbox. Tarifas reais foram observadas com suas moedas; não se afirma validação universal de toda tarifa/ativo/combinação.
- **Onboarding:** não houve envio de documentos, KYC/KYB integral ou aprovação artificial. O sandbox liberou BRL na subconta nova; o vínculo local permaneceu PENDING. Isso não demonstra elegibilidade institucional em produção.
- **Fluxos excluídos:** cadastro/convites de operadores, BlindPay real, AWS, chatbot e produção não foram reavaliados.

Assim, a integração de saída Avenia está demonstrada nas rotas acima e dois achados locais ganharam reprodução com provedor real. O DoD integral da #85 continua parcial, em especial quanto a eventos externos e demais requisitos não demonstrados.

## Fontes e metodologia

Consultadas em 13/11/2026: [início e ambientes](https://integration-guide.avenia.io/docs/Getting-Started/gettingStarted/), [primeira operação](https://integration-guide.avenia.io/docs/Getting-Started/firstOperation/), [operações suportadas](https://integration-guide.avenia.io/docs/Operations/combinations/), [metadados e limites](https://integration-guide.avenia.io/docs/Avenia-Account-Management/aboutLogin/), [subcontas](https://integration-guide.avenia.io/docs/Avenia%20Subaccounts/subAccountManagement/), [carteiras](https://integration-guide.avenia.io/docs/Beneficiaries-Wallets/walletsGuide/), [recebimento simulado](https://integration-guide.avenia.io/docs/Sandbox%20Usecases/receiveMockFunds/), [gestão de webhooks](https://integration-guide.avenia.io/docs/Webhooks/webhookManagement/) e [comportamento dos eventos](https://integration-guide.avenia.io/docs/Concepts/webhooksInPractice/).

O MCP documental Avenia não estava configurado nesta sessão; foram usados guias oficiais, sem instalar MCP de negócio ou enviar segredos. OWASP WSTG 4.2, ASVS 5.0.0, API Top 10:2023 e NIST SP 800-115 orientam os requisitos e a rastreabilidade. Não se declara conformidade integral. Nenhum dado de cartão foi processado e não foi demonstrada aplicabilidade PCI DSS nesta etapa.
