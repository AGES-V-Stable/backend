# Revisão da suíte local — cicd#85

Revisão dos comentários do PR sobre `7b46aa6` (o commit completo está nos JSON). Executada em 13/11/2026 na branch `test/85-seguranca-aplicacao-apis`, com as mudanças não commitadas de instrumentação identificadas pelos hashes. O código de produção não foi alterado. A etapa sandbox, realizada em outra branch/versão, não foi repetida nem incorporada a estes totais.

## Resultado

- Diagnóstico: **371 casos, 344 PASS, 27 FAIL, zero ignorados** — [assessment.json](assessment.json).
- Regressão padrão: **1.241 casos, 1.235 PASS, zero FAIL, seis desabilitados na origem** — [integration-retest.json](integration-retest.json).
- `gradlew.bat securityAssessmentTest build --continue --no-daemon --console=plain` executou `test/check/build/jacocoTestReport`; o processo retornou **1 pela tarefa diagnóstica**. Não se apresenta o comando conjunto como BUILD SUCCESSFUL.
- PostgreSQL 16.4 efêmero/Testcontainers, JWT real, credenciais sintéticas, provedores mockados ou transporte em loopback. Frontend e APIs externas não foram reexecutados.

O total anterior de 385 inclui 18 repetições de OWNER em rotas administrativas, removidas porque LC-AUTHZ-01 já cobre OWNER/OPERATOR/VIEWER. Foram acrescentados dois casos de TOTP ausente com 2FA ativo e duas contraprovas do mecanismo concorrente: `385 - 18 + 2 + 2 = 371`.

Os 27 FAIL incluem os 21 resultados anteriormente conhecidos, dois cenários adicionais de ausência de TOTP e quatro entradas TC-FIN-17 que antes aceitavam `ArithmeticException`. Não são 27 vulnerabilidades independentes nem seis vulnerabilidades novas. Os quatro últimos registram robustez/validação no adapter, sem demonstrar HTTP 500 ou exploração financeira. Cada caso e tipo de falha estão no JSON sanitizado.

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

[cobertura-requisitos.json](cobertura-requisitos.json) contém R01–R14 com grupos/casos/limitações; R15 referencia [validacao-evidencias.json](validacao-evidencias.json), que verifica consistência dos totais, schema exportado, IDs, ambiente, commit e hashes. Essa checagem de metadados não é garantia automática de ausência de qualquer segredo nem aprovação do DoD.

Os controles dos exportadores foram exercitados com cópias temporárias, incluindo ausência de casos, skips parciais/totais, controles reprovados, totais adulterados e hash inconsistente. Os resultados específicos são separados em `validacao-exportador.json`; não contam como testes da aplicação. Capturas de 12/11 não foram sobrescritas.

Persistem limitações: recuperação/logout/troca de senha e receptor Avenia ausentes neste HEAD; convites fora desta branch; nenhuma medição de carga/gateway nem validação de implantação dos perfis de jobs. Resultados externos da outra branch devem ser consultados separadamente. Esta revisão corrige os testes, sem remediar as vulnerabilidades da aplicação ou encerrar integralmente a issue.

## Reprodução local

Na pasta backend, com Java 25 e Docker Desktop:

```powershell
.\gradlew.bat securityAssessmentTest --no-daemon --console=plain
.\gradlew.bat build --no-daemon --console=plain
```

Enquanto os controles continuarem vulneráveis, o diagnóstico retorna 1. A tarefa permanece opt-in e fora de test/check/build/PIT; não se usa ignoreFailures. Para uma captura nova, executar as duas tarefas completas e exportar em diretório novo com `export-security-evidence.ps1`, copiar ambos os inventários e executar `export-security-coverage.ps1`, conforme seção 8.1 do relatório da wiki.
