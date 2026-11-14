# Revisão da suíte local — cicd#85

> **Escopo e auditabilidade.** Este documento separa a captura histórica de 12/11, cujos artefatos estão versionados, do reteste relatado pelo autor em 13/11. Os JSON detalhados da execução de 13/11 **não foram incluídos** no PR; portanto, os seus totais não foram auditados a partir de registros individuais. Não confundir falha diagnóstica esperada com aprovação do controle.

Revisão dos comentários do PR sobre `7b46aa6` (o commit completo está nos JSON). Executada em 13/11/2026 na branch `test/85-seguranca-aplicacao-apis`, com as mudanças não commitadas de instrumentação identificadas pelos hashes. O código de produção não foi alterado. A etapa sandbox, realizada em outra branch/versão, não foi repetida nem incorporada a estes totais.

## Resultado

- **Reteste local relatado em 13/11 (sem JSON detalhado versionado):** diagnóstico de **371 casos, 344 PASS, 27 FAIL e zero ignorados**; regressão de **1.241 casos, 1.235 PASS, zero FAIL e seis desabilitados na origem**. Esses valores constam da comunicação do autor e **precisam de nova captura verificável**.
- **Captura histórica versionada de 12/11, anterior às correções da instrumentação:** [assessment.json — 385 casos, 364 PASS, 21 FAIL](../complementacao-local-2026-11-12/assessment.json); [integration-retest.json — regressão da versão anterior](../complementacao-local-2026-11-12/integration-retest.json). Não usar esses arquivos como evidência dos 371 casos da revisão de 13/11.
- `gradlew.bat securityAssessmentTest build --continue --no-daemon --console=plain` executou `test/check/build/jacocoTestReport`; o processo retornou **1 pela tarefa diagnóstica**. Não se apresenta o comando conjunto como BUILD SUCCESSFUL.
- PostgreSQL 16.4 efêmero/Testcontainers, JWT real, credenciais sintéticas, provedores mockados ou transporte em loopback. Frontend e APIs externas não foram reexecutados.

O total anterior de 385 inclui 18 repetições de OWNER em rotas administrativas, removidas porque LC-AUTHZ-01 já cobre OWNER/OPERATOR/VIEWER. Foram acrescentados dois casos de TOTP ausente com 2FA ativo e duas contraprovas do mecanismo concorrente: `385 - 18 + 2 + 2 = 371`.

Os 27 FAIL incluem os 21 resultados anteriormente conhecidos, dois cenários adicionais de ausência de TOTP e quatro entradas TC-FIN-17 que antes aceitavam `ArithmeticException`. Não são 27 vulnerabilidades independentes nem seis vulnerabilidades novas. Os quatro últimos registram robustez/validação no adapter, sem demonstrar HTTP 500 ou exploração financeira. Os casos e tipos de falha de **12/11** podem ser auditados no JSON histórico acima; a listagem detalhada de **13/11** não está disponível neste PR.

## Correções da instrumentação

1. **TC-FIN-20:** exige somente a resposta segura, ausência de processamento/payout e estado REQUOTE_REQUIRED. Removidas as exigências de pagamento e COMPLETED que impediam passar após a correção.
2. **TC-CONC-01:** `BadCredentialsException` resulta em operação recusada; todas as tarefas alcançam a barreira, inclusive quando recusadas na validação. Erros de infraestrutura continuam erros. Duas contraprovas LC-HARNESS-01 passaram com consumo atômico simulado na validação ou no consumo; o serviço real continua reprovado.
3. **TC-FIN-01 e demais confirmações:** helper fornece fator válido e distinto por requisição da conta sintética. Somente os cenários negativos omitem o fator. A corrida de idempotência usa cinco OPERATOR da mesma empresa com fatores independentes, sem exigir replay TOTP.
4. **Contrato ainda pendente de correção:** `ConfirmOfferRequest` não declara `totpCode` neste HEAD e ignora o campo da fixture. O helper usa o nome existente na transferência direta; prepara os testes para a remediação, sem provar que a validação financeira existe. Deve acompanhar o contrato final caso ele seja diferente.
5. **TC-AUTHZ-05:** aceita 403/404 ou 200 com filtragem; sempre verifica ausência dos identificadores financeiros da outra empresa.
6. **TC-FIN-17:** exige `IllegalArgumentException` ou `UnprocessableEntityException`, sem gateway. Não aceita exceção aritmética como prova de validação segura.
7. **LC-API-01:** reset e verificação das interações por DynamicTest; só a consulta de termos admite sua interação específica. Um caso não herda stubs/permissão de tráfego de outro.
8. **Inventário:** [endpoints.csv](endpoints.csv) contém **59 operações dos controllers ativos no perfil padrão**. [endpoints-perfis.csv](endpoints-perfis.csv) lista separadamente duas definições condicionais de POST /events. A aplicação permite esse POST sem JWT nos perfis de jobs; a exposição da implantação/AWS/IAM não foi avaliada. A cobertura de seed em slice MVC e de migration em teste unitário não equivale a um teste de implantação acidental.
9. **Matriz:** grupos obrigatórios explícitos e status derivados da execução/lacunas/resultados. Sem casos: NAO_AVALIADO; com grupos ausentes/ignorados ou limitação conhecida: PARCIAL; plano local executado: AVALIADO_COM_FALHAS ou AVALIADO_SEM_FALHAS. Um controle reprovado foi avaliado, mas não aprovado. R15 usa validação explícita de artefatos, sem associar todos os testes por regex.
10. **Preparação duplicada:** helper do processador de recotação e `ProviderClientFixture` compartilhada entre os testes HTTP.

## Evidências e limites

[cobertura-endpoints.csv](cobertura-endpoints.csv) distingue um caso limitado de entrada/erro por operação das referências de negócio por componente. Essas referências não comprovam todos os payloads/autorização por endpoint. Os jobs têm [matriz própria](cobertura-endpoints-perfis.csv).

A [matriz histórica de cobertura de requisitos](../complementacao-local-2026-11-12/cobertura-requisitos.json) e a [verificação histórica dos metadados](../complementacao-local-2026-11-12/verificacao.json) pertencem à captura de 12/11; **não comprovam** a execução nem as alterações da matriz de 13/11. O arquivo de validação detalhada da revisão (`validacao-evidencias.json`) **não está versionado neste diretório**. Para auditar os requisitos R01–R15 na revisão, é necessário reexportar e registrar os artefatos correspondentes. A checagem de metadados, por si só, não garante ausência de segredos nem aprovação do DoD.

Segundo o relato de 13/11, os exportadores foram exercitados com cópias temporárias, incluindo ausência de casos, skips parciais/totais, controles reprovados, totais adulterados e hash inconsistente. O arquivo citado pelo autor (`validacao-exportador.json`) **não está versionado**: a execução e seus oito checks não podem ser revalidados apenas pelos arquivos deste PR. Esses checks não contam como testes da aplicação. Capturas de 12/11 não foram sobrescritas.

Persistem limitações: recuperação/logout/troca de senha e receptor Avenia ausentes neste HEAD; convites fora desta branch; nenhuma medição de carga/gateway nem validação de implantação dos perfis de jobs. Resultados externos da outra branch devem ser consultados separadamente. Esta revisão corrige os testes, sem remediar as vulnerabilidades da aplicação ou encerrar integralmente a issue.

## Reprodução local

Na pasta backend, com Java 25 e Docker Desktop:

```powershell
.\gradlew.bat securityAssessmentTest --no-daemon --console=plain
.\gradlew.bat build --no-daemon --console=plain
```

Enquanto os controles continuarem vulneráveis, o diagnóstico retorna 1. A tarefa permanece opt-in e fora de test/check/build/PIT; não se usa ignoreFailures. Para uma captura nova, executar as duas tarefas completas e exportar em diretório novo com `export-security-evidence.ps1`, copiar ambos os inventários e executar `export-security-coverage.ps1`, conforme seção 8.1 do relatório da wiki.

## Sincronização com main em 14/11/2026

A branch deste PR incorporou os três commits então pendentes da `main` (até `0706314fcd13790e3193eeed586f5772949c7bfc`) por merge `606b35b8281c676287a5e307b24636db24070659`. As diferenças adicionadas pela sincronização restringem-se a `ExchangeRateService.java` e `ExchangeRateServiceTest.java`, sem sobreposição com os arquivos do diagnóstico. Isso **não substitui** o reteste da suíte diagnóstica nem garante que os totais de 13/11 permaneçam iguais.

**Para concluir a validação de uma nova captura:** executar separadamente `securityAssessmentTest` e `build` no HEAD atualizado; arquivar um `assessment.json` e um `integration-retest.json` sanitizados, matriz de requisitos e verificações do exportador com commit/hashes; atualizar os totais e distinguir explicitamente os achados ainda falhos dos controles efetivamente corrigidos. Enquanto isso não ocorrer, o último diagnóstico detalhado versionado continua sendo a captura de 12/11.
