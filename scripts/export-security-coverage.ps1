[CmdletBinding()]
param([Parameter(Mandatory=$true)][string]$EvidenceDirectory)

$ErrorActionPreference='Stop'
$directory=[IO.Path]::GetFullPath($EvidenceDirectory)
$assessment=Get-Content -LiteralPath (Join-Path $directory 'assessment.json') -Raw -Encoding UTF8 | ConvertFrom-Json
$regression=Get-Content -LiteralPath (Join-Path $directory 'integration-retest.json') -Raw -Encoding UTF8 | ConvertFrom-Json
$all=@($assessment.cases)+@($regression.cases | Where-Object status -ne 'SKIPPED')
$routes=@(Import-Csv -LiteralPath (Join-Path $directory 'endpoints.csv'))

# References point only to executed, sanitized cases. A rejected control is still evaluated.
function References([string]$Pattern) {
    @($all | Where-Object { ($_.suite+' '+$_.case) -match $Pattern } | ForEach-Object evidence_id)
}
function Require-References([string]$Pattern) {
    $refs=@(References $Pattern)
    if($refs.Count -eq 0) { throw "Sem evidencia executada para: $Pattern" }
    return $refs
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
    if($contract.Count -ne 1) { throw "Contrato de entrada/erro sem execucao unica: $key" }
    $access=@($assessment.cases | Where-Object {
        $_.case.EndsWith($key) -and ($_.case -match 'TC-API-01|TC-AUTH-05|LC-AUTHZ-01')
    })
    $domain=@(Require-References $business[$route.controller])
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
        input_error_evidence=$contract[0].evidence_id
        input_error_result=$contract[0].status
        business_component_evidence=($domain -join ';')
        object_property_scope=$objectScope
        note='Um caso por rota de entrada/erro, barreiras aplicaveis e casos de negocio do componente; nao afirma todas as combinacoes de payload por rota.'
    }
})
$endpointRows | ForEach-Object { [pscustomobject]$_ } | Export-Csv -LiteralPath (Join-Path $directory 'cobertura-endpoints.csv') -NoTypeInformation -Encoding UTF8

$requirements=@(
    @('R01','Autenticacao/JWT/TOTP/sessao','TC-AUTH-|LC-AUTH-|JwtTokenUtilsTest|TwoFactorAuthServiceTest|TotpServiceTest'),
    @('R02','Autorizacao por funcao objeto propriedade e empresa','TC-RBAC-|TC-AUTHZ-|LC-AUTHZ-|CompanyAuthorizationIntegrationTest|CompanyAccessTest'),
    @('R03','Entradas exposicao erros e inventario das APIs','LC-API-|TC-API-|LC-AUTHZ-03|LC-FIN-02|ControllerTest'),
    @('R04','Injecao SSRF configuracao e upload','LC-API-02|LC-OUT-02|LC-OUT-03|LC-CONFIG-01|UploadContentAssessmentTest|TC-API-13'),
    @('R05','Cadastro empresas representantes compliance beneficiarios cotacoes transferencias','OnboardingServiceTest|CompanyServiceTest|UserServiceTest|BeneficiaryServiceTest|Compliance.*ServiceTest|BlindPayOnboardingFlowTest|QuoteOrchestratorServiceTest|TransferExecutionServiceTest'),
    @('R06','Valores moedas precisao taxas validade e consistencia persistida','TC-FIN-|LC-FIN-|FinancialBoundaryAssessmentTest|QuoteOrchestratorServiceTest'),
    @('R07','Propriedade dos recursos financeiros','TC-AUTHZ-|LC-AUTHZ-04|LC-FIN-01|LC-FIN-02|LC-FIN-03'),
    @('R08','Repeticao idempotencia concorrencia abuso e estados','TC-CONC-|LC-CONC-|LC-ABUSE-|BlindPayExecutionProcessorTest'),
    @('R09','TOCTOU e sincronizacao entre validacao e execucao','LC-CONC-02|LC-CONC-01|TC-CONC-01|TC-CONC-02'),
    @('R10','Estados invalidos duplicacao e imutabilidade terminal','TC-FIN-08|TC-FIN-09|BlindPayExecutionProcessorTest|TransferExecutionServiceTest'),
    @('R11','Autenticidade integridade e processamento de webhooks','TC-INT-|LC-INT-|BlindPayWebhookVerifierTest|BlindPayWebhookServiceTest'),
    @('R12','Falsificacao replay duplicidade e ordem de eventos','LC-INT-|TC-INT-03|TC-INT-04|BlindPayWebhookServiceTest'),
    @('R13','Contratos recursos rate limiting e inventario','LC-API-|LC-CONFIG-|LC-ABUSE-|ProviderFailureAssessmentTest|QuoteOrchestratorServiceTest'),
    @('R14','Consumo seguro de APIs externas erros timeouts retries e credenciais','LC-OUT-|ProviderFailureAssessmentTest|AveniaClientTest|BlindPayClientTest'),
    @('R15','Resultados ambiente evidencias e condicoes de reproducao','.')
)
$rows=@(foreach($r in $requirements) {
    $refs=@(Require-References $r[2]); $cases=@($all | Where-Object evidence_id -in $refs)
    [ordered]@{id=$r[0];requirement=$r[1];local_status='AVALIADO';case_count=$cases.Count
        failed_cases=@($cases | Where-Object status -eq 'FAIL').Count; evidence_ids=$refs}
})
$result=[ordered]@{
    generated_at_utc=[DateTimeOffset]::UtcNow.ToString('o'); backend_commit=$assessment.commits.backend
    endpoint_count=$routes.Count; requirements=$rows
    residual_conditions=@(
        'Sandboxes oficiais Avenia/BlindPay ainda nao executados; dependem de credenciais e massa autorizada.',
        'Cadastro/convite de operadores nao avaliado: fluxo ainda em desenvolvimento, conforme informado pela equipe.',
        'Recuperacao/logout/troca de senha e receptor webhook Avenia ausentes no HEAD; nao implementados por esta avaliacao.',
        'Rate limiting local inspecionado e repeticao limitada a dez logins incorretos; nenhum limiar de negocio aprovado foi inventado; protecao de gateway externo nao medida.',
        'Injecao/SSRF avaliados nos pontos de entrada e clientes identificados; nao representa prova formal de ausencia nem fuzzing/carga de terceiros.'
    )
}
$result | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $directory 'cobertura-requisitos.json') -Encoding UTF8
Write-Output "Matriz validada: $($routes.Count) endpoints; $($rows.Count) requisitos com referencias executadas."
