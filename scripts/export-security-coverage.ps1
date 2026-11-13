[CmdletBinding()]
param([Parameter(Mandatory=$true)][string]$EvidenceDirectory)

$ErrorActionPreference='Stop'
$directory=[IO.Path]::GetFullPath($EvidenceDirectory)
$assessment=Get-Content -LiteralPath (Join-Path $directory 'assessment.json') -Raw -Encoding UTF8 | ConvertFrom-Json
$regression=Get-Content -LiteralPath (Join-Path $directory 'integration-retest.json') -Raw -Encoding UTF8 | ConvertFrom-Json
$recorded=@($assessment.cases)+@($regression.cases)
$all=@($recorded | Where-Object status -in @('PASS','FAIL'))
$routes=@(Import-Csv -LiteralPath (Join-Path $directory 'endpoints.csv'))
$conditionalRoutes=@(Import-Csv -LiteralPath (Join-Path $directory 'endpoints-perfis.csv'))

# References point only to executed, sanitized cases. A rejected control is still evaluated.
function References([string]$Pattern) {
    @($all | Where-Object { ($_.suite+' '+$_.case) -match $Pattern } | ForEach-Object evidence_id)
}
$business=@{
    AdminTransactionController='TransactionQueryServiceTest|TransactionQueryIntegrationTest'
    AdminDashboardController='DashboardServiceTest|DashboardIntegrationTest'
    AuthController='AuthControllerTest|TwoFactorAuthServiceTest|LC-AUTH-01'
    BalanceController='BalanceServiceTest|BalanceControllerSecurityTest'
    BeneficiaryController='BeneficiaryControllerTest|BeneficiaryServiceTest|BeneficiaryValidatorTest'
    BlindPayComplianceController='BlindPayComplianceControllerSecurityTest|BlindPayComplianceServiceTest|BlindPayOnboardingFlowTest'
    BlindPayWebhookController='BlindPayWebhookServiceTest|BlindPayWebhookVerifierTest|LC-INT-'
    CompanyController='CompanyControllerTest|CompanyServiceTest'
    ComplianceDocumentController='ComplianceDocumentControllerTest|ComplianceDocumentServiceTest|LC-AUTHZ-02'
    ComplianceKycController='ComplianceKycControllerTest|ComplianceKycServiceTest|LC-AUTHZ-02'
    ComplianceLivenessController='ComplianceLivenessControllerTest|ComplianceLivenessServiceTest|LC-AUTHZ-02'
    DashboardController='DashboardControllerSecurityTest|DashboardIntegrationTest'
    OnboardingController='OnboardingControllerTest|OnboardingServiceTest|LC-AUTHZ-03'
    QuoteController='QuoteControllerSecurityTest|QuoteOrchestratorServiceTest|LC-AUTHZ-04'
    RepresentativeController='RepresentativeControllerTest|UserServiceTest'
    TransactionController='TransactionQueryServiceTest|TransactionControllerSecurityTest'
    TransactionVolumeController='TransactionVolumeControllerSecurityTest|TransactionVolumeServiceTest'
    TransferController='TransferControllerTest|TransferCreationControllerTest|TransferServiceTest'
    TransferDetailsController='TransferDetailsControllerTest|TransferDetailsServiceTest|TransferReceiptPdfGeneratorTest'
    TransferExecutionController='TransferExecutionControllerSecurityTest|TransferExecutionServiceTest|BlindPayExecutionProcessorTest|LC-FIN-|LC-CONC-'
    UserController='UserControllerTest|TC-RBAC-07|TC-RBAC-09'
}
$endpointRows=@(foreach($route in $routes) {
    $key=$route.method+' '+$route.path
    if(-not $business.ContainsKey($route.controller)) { throw "Controller sem mapeamento de negocio: $($route.controller)" }
    $contract=@($assessment.cases | Where-Object case -eq ('LC-API-01 '+$key))
    if($contract.Count -gt 1) { throw "Contrato de entrada/erro duplicado: $key" }
    $access=@($assessment.cases | Where-Object {
        $_.case.EndsWith($key) -and ($_.case -match 'TC-API-01|TC-AUTH-05|LC-AUTHZ-01')
    })
    $domain=@(References $business[$route.controller])
    $objectScope=switch -Regex ($route.controller) {
        '^Compliance' {'KYC por usuario: LC-AUTHZ-02 (todas as seis operacoes) e testes de servico';break}
        'TransferExecution' {'Empresa/oferta/beneficiario e recotacao: TC-AUTHZ-02/05 e LC-FIN-01/02/03';break}
        'QuoteController|TransferController' {'Empresa e beneficiario: LC-AUTHZ-04 e testes de servico';break}
        'Beneficiary|Company' {'Empresa no caminho, id de recurso e propriedades: TC-RBAC-04/08, TC-AUTHZ-04, LC-AUTHZ-03';break}
        'Transaction|Dashboard|Balance' {'Empresa pelo vinculo atual; TC-AUTHZ-05 e testes de integracao/servico';break}
        'BlindPayCompliance' {'Empresa obtida do principal; OWNER para envios; baseline CompanyAuthorizationIntegrationTest';break}
        'BlindPayWebhook' {'Assinatura, timestamp, instancia e correlacao externa; LC-INT e baseline';break}
        default {'Identidade da conta, papel global ou cadastro publico; baseline e LC-AUTH/RBAC'}
    }
    [ordered]@{
        method=$route.method; path=$route.path; controller=$route.controller
        access_evidence=($access.evidence_id -join ';')
        input_error_evidence=if($contract.Count) {$contract[0].evidence_id} else {$null}
        input_error_result=if($contract.Count) {$contract[0].status} else {'NAO_EXECUTADO'}
        business_component_evidence=($domain -join ';')
        object_property_scope=$objectScope
        inventory_scope='controllers-ativos-perfil-padrao'
        note='Referencia de componente nao comprova execucao de todos os contratos por endpoint. Rotas condicionais em cobertura-endpoints-perfis.csv.'
    }
})
$endpointRows | ForEach-Object { [pscustomobject]$_ } | Export-Csv -LiteralPath (Join-Path $directory 'cobertura-endpoints.csv') -NoTypeInformation -Encoding UTF8

$jobRows=@(foreach($route in $conditionalRoutes) {
    $pattern=if($route.profile -eq 'seed') {
        'InternalJobSecurityTest|InternalJobControllerTest.*seed_reportsCreationOrExistingAdministrator'
    } else {'InternalJobControllerTest.*migration_reportsSuccessOnlyAfterFlywayReturns'}
    $refs=@(References $pattern)
    [pscustomobject][ordered]@{
        method=$route.method;path=$route.path;profile=$route.profile;controller=$route.controller
        application_auth=$route.application_auth;component_evidence=($refs -join ';')
        coverage_status=if($refs.Count) {'PARCIAL'} else {'NAO_AVALIADO'}
        limitation=if($route.profile -eq 'seed') {
            'Perfil seed: HTTP sem JWT exercitado em slice MVC, seeder mockado. Exposicao AWS/IAM e implantacao acidental nao avaliadas.'
        } else {
            'Perfil migration: controller unitario; contexto HTTP do perfil e exposicao AWS/IAM nao avaliados.'
        }
    }
})
$jobRows | Export-Csv -LiteralPath (Join-Path $directory 'cobertura-endpoints-perfis.csv') -NoTypeInformation -Encoding UTF8

# Grupos obrigatorios explicitos. Encontrar um ControllerTest qualquer nao cobre R03.
# FAIL significa controle reprovado; lacuna/skip significa cobertura insuficiente.
$requirements=@(
    @{id='R01';name='Autenticacao/JWT/TOTP/sessao';groups=@('TC-AUTH-','TC-FIN-(01|20)','LC-AUTH-01','JwtTokenUtilsTest','TwoFactorAuthServiceTest','TotpServiceTest');limits=@('Recuperacao/logout/troca de senha nao implementados neste HEAD.')},
    @{id='R02';name='Autorizacao por funcao objeto propriedade e empresa';groups=@('TC-RBAC-','LC-AUTHZ-01','LC-AUTHZ-02','CompanyAuthorizationIntegrationTest');limits=@('Cadastro/convite de operadores fora deste HEAD/plano local.')},
    @{id='R03';name='Entradas exposicao erros e inventario das APIs';groups=@('LC-API-01','TC-API-02','LC-AUTHZ-03','LC-FIN-02','InternalJobSecurityTest');limits=@('Rotas do perfil padrao: casos limitados de entrada/erro e referencias de componente. Perfis seed/migration listados a parte; cobertura de implantacao e HTTP migration ausentes.')},
    @{id='R04';name='Injecao SSRF configuracao e upload';groups=@('LC-API-02','LC-OUT-02','LC-OUT-03','LC-CONFIG-01','UploadContentAssessmentTest','TC-API-13');limits=@()},
    @{id='R05';name='Cadastro empresas representantes compliance beneficiarios cotacoes transferencias';groups=@('OnboardingServiceTest','CompanyServiceTest','UserServiceTest','BeneficiaryServiceTest','BlindPayOnboardingFlowTest','QuoteOrchestratorServiceTest','TransferExecutionServiceTest');limits=@('Cadastro/convite de operadores fora deste HEAD/plano local.')},
    @{id='R06';name='Valores moedas precisao taxas validade e consistencia persistida';groups=@('TC-FIN-13','TC-FIN-14','TC-FIN-17','TC-FIN-19','LC-FIN-01','FinancialBoundaryAssessmentTest');limits=@()},
    @{id='R07';name='Propriedade dos recursos financeiros';groups=@('TC-AUTHZ-02','TC-AUTHZ-03','TC-AUTHZ-05','LC-AUTHZ-04','LC-FIN-01','LC-FIN-02','LC-FIN-03');limits=@()},
    @{id='R08';name='Repeticao idempotencia concorrencia abuso e estados';groups=@('TC-CONC-02','LC-CONC-01','LC-ABUSE-','BlindPayExecutionProcessorTest');limits=@()},
    @{id='R09';name='TOCTOU e sincronizacao entre validacao e execucao';groups=@('LC-CONC-02','LC-CONC-01','TC-CONC-01');limits=@()},
    @{id='R10';name='Estados invalidos duplicacao e imutabilidade terminal';groups=@('TC-FIN-15','BlindPayExecutionProcessorTest','TransferExecutionServiceTest');limits=@()},
    @{id='R11';name='Autenticidade integridade e processamento de webhooks';groups=@('TC-INT-','LC-INT-','BlindPayWebhookVerifierTest','BlindPayWebhookServiceTest');limits=@('Receptor Avenia ausente; esta captura local nao comprova entrega de evento externo.')},
    @{id='R12';name='Falsificacao replay duplicidade e ordem de eventos';groups=@('LC-INT-','TC-INT-03','TC-INT-04','BlindPayWebhookServiceTest');limits=@('Receptor Avenia ausente; apenas eventos locais assinados/simulados.')},
    @{id='R13';name='Contratos recursos rate limiting e inventario';groups=@('LC-API-01','LC-CONFIG-01','LC-ABUSE-','ProviderFailureAssessmentTest');limits=@('Sem limiar de rate limiting aprovado; sem carga, medicao de gateway ou implantacao dos perfis condicionais.')},
    @{id='R14';name='Consumo seguro de APIs externas erros timeouts retries e credenciais';groups=@('LC-OUT-','ProviderFailureAssessmentTest','AveniaClientTest','BlindPayClientTest');limits=@('Clientes exercitados contra mocks/loopback; resultados sandbox de outra captura nao sao incorporados aqui.')}
)
$rows=@(foreach($r in $requirements) {
    $groups=@(foreach($pattern in $r.groups) {
        $matched=@($recorded | Where-Object { ($_.suite+' '+$_.case) -match $pattern })
        $executed=@($matched | Where-Object status -in @('PASS','FAIL'))
        [ordered]@{pattern=$pattern;executed=$executed.Count;failed=@($executed | Where-Object status -eq 'FAIL').Count
            skipped=@($matched | Where-Object status -eq 'SKIPPED').Count;evidence_ids=@($executed | ForEach-Object evidence_id)}
    })
    $refs=@($groups | ForEach-Object evidence_ids | Sort-Object -Unique)
    $cases=@($all | Where-Object evidence_id -in $refs)
    $failed=@($cases | Where-Object status -eq 'FAIL')
    $missing=@($groups | Where-Object { $_.executed -eq 0 -or $_.skipped -gt 0 })
    $partial=$missing.Count -gt 0 -or $r.limits.Count -gt 0
    $status=if(-not $cases.Count) {'NAO_AVALIADO'} elseif($partial) {'PARCIAL'}
        elseif($failed.Count) {'AVALIADO_COM_FALHAS'} else {'AVALIADO_SEM_FALHAS'}
    [ordered]@{id=$r.id;requirement=$r.name;source_type='executed_cases';local_status=$status
        control_status=if(-not $cases.Count) {'NAO_AVALIADO'} elseif($failed.Count) {'FALHAS_DETECTADAS'} else {'SEM_FALHAS_NOS_CASOS_EXECUTADOS'}
        case_count=$cases.Count;failed_cases=$failed.Count;failed_evidence_ids=@($failed | ForEach-Object evidence_id)
        evidence_ids=$refs;required_groups=$groups;missing_or_skipped_groups=@($missing | ForEach-Object pattern);limitations=$r.limits}
})

# R15 valida os artefatos concretos, em vez de associar todos os testes por regex '.'.
$artifactChecks=@(foreach($entry in @(
    @{name='assessment.json';data=$assessment},@{name='integration-retest.json';data=$regression}
)) {
    $data=$entry.data;$cases=@($data.cases)
    $countsCorrect=$data.summary.total -eq $cases.Count -and
        $data.summary.passed -eq @($cases | Where-Object status -eq 'PASS').Count -and
        $data.summary.failed -eq @($cases | Where-Object status -eq 'FAIL').Count -and
        $data.summary.skipped -eq @($cases | Where-Object status -eq 'SKIPPED').Count
    $schemaCorrect=$cases.Count -gt 0 -and @($cases | Where-Object {
        -not $_.evidence_id -or -not $_.suite -or -not $_.case -or
        $_.status -notin @('PASS','FAIL','SKIPPED') -or $null -eq $_.duration_seconds -or
        @($_.PSObject.Properties.Name | Where-Object { $_ -notin @('evidence_id','suite','case','status','duration_seconds','failure_type') }).Count -gt 0
    }).Count -eq 0
    $hashProperties=@($data.assessment_files_sha256.PSObject.Properties)
    $hashesCorrect=$hashProperties.Count -gt 0
    foreach($property in $hashProperties) {
        $source=Join-Path (Split-Path $PSScriptRoot -Parent | Split-Path -Parent) $property.Name
        if(-not (Test-Path -LiteralPath $source) -or (Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash -ne $property.Value) {$hashesCorrect=$false}
    }
    [ordered]@{artifact=$entry.name;summary_consistent=$countsCorrect;sanitized_schema=$schemaCorrect
        unique_case_ids=@($cases.evidence_id | Sort-Object -Unique).Count -eq $cases.Count
        environment_recorded=-not [string]::IsNullOrWhiteSpace($data.environment)
        captured_at_recorded=-not [string]::IsNullOrWhiteSpace($data.captured_at_utc)
        backend_commit_recorded=$data.commits.backend -match '^[0-9a-f]{40}$'
        source_hashes_match=$hashesCorrect}
})
$artifactFailures=@($artifactChecks | Where-Object {
    -not ($_.summary_consistent -and $_.sanitized_schema -and $_.unique_case_ids -and
        $_.environment_recorded -and $_.captured_at_recorded -and $_.backend_commit_recorded -and $_.source_hashes_match)
})
$audit=[ordered]@{id='R15-artifacts';status=if($artifactFailures.Count) {'FAIL'} else {'PASS'};checks=$artifactChecks
    scope='Consistencia de metadados, schema exportado e hashes. Nao e garantia automatica de ausencia de todo segredo.'}
$audit | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $directory 'validacao-evidencias.json') -Encoding UTF8
$rows+=,[ordered]@{id='R15';requirement='Resultados ambiente evidencias e condicoes de reproducao'
    source_type='artifact_validation';local_status=if($artifactFailures.Count) {'AVALIADO_COM_FALHAS'} else {'AVALIADO_SEM_FALHAS'}
    control_status=$audit.status;case_count=0;failed_cases=0;evidence_ids=@()
    artifact_evidence=@('validacao-evidencias.json#R15-artifacts','assessment.json','integration-retest.json')}
$result=[ordered]@{
    generated_at_utc=[DateTimeOffset]::UtcNow.ToString('o'); backend_commit=$assessment.commits.backend
    inventory_scope='controllers ativos do perfil padrao; jobs condicionais inventariados separadamente'
    endpoint_count=$routes.Count;conditional_endpoint_count=$conditionalRoutes.Count;requirements=$rows
    residual_conditions=@(
        'Esta captura nao faz chamadas a sandboxes; capturas externas devem ser consultadas separadamente.',
        'Cadastro/convite de operadores nao avaliado: fluxo ainda em desenvolvimento, conforme informado pela equipe.',
        'Recuperacao/logout/troca de senha e receptor webhook Avenia ausentes no HEAD; nao implementados por esta avaliacao.',
        'Rate limiting local inspecionado e repeticao limitada a dez logins incorretos; nenhum limiar de negocio aprovado foi inventado; protecao de gateway externo nao medida.',
        'Injecao/SSRF avaliados nos pontos de entrada e clientes identificados; nao representa prova formal de ausencia nem fuzzing/carga de terceiros.'
    )
}
$result | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $directory 'cobertura-requisitos.json') -Encoding UTF8
Write-Output "Matriz: $($routes.Count) rotas ativas, $($conditionalRoutes.Count) condicionais; R15 artefatos=$($audit.status)."
if($artifactFailures.Count) { throw 'R15: metadados, schema ou hashes inconsistentes; consulte validacao-evidencias.json.' }
