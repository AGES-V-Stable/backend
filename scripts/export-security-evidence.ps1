[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('baseline-backend', 'baseline-frontend', 'assessment', 'integration-retest')]
    [string]$Phase,
    [string]$OutputDirectory
)

$ErrorActionPreference = 'Stop'
$backendPath = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$workspacePath = Split-Path $backendPath -Parent
$evidencePath = if ([string]::IsNullOrWhiteSpace($OutputDirectory)) {
    Join-Path $backendPath 'docs/security/evidencias-85'
} else {
    [IO.Path]::GetFullPath($OutputDirectory)
}
$reportPath = switch ($Phase) {
    'baseline-backend' { Join-Path $backendPath 'build/test-results/test' }
    'baseline-frontend' { Join-Path $workspacePath 'frontend/coverage/security-baseline.xml' }
    'assessment' { Join-Path $backendPath 'build/test-results/securityAssessmentTest' }
    'integration-retest' { Join-Path $backendPath 'build/test-results/test' }
}

# Exporta somente nomes, duracoes e resultados. Nunca copia system-out,
# system-err, headers, corpos HTTP, mensagens de excecao ou stack traces.
function Protect-CaseName([string]$Value) {
    # JUnit pode colocar DTOs completos e tokens sintéticos em nomes parametrizados.
    if ($Value -match '^(\[\d+\])\s') { return "$($Matches[1]) [parametros sinteticos omitidos]" }
    $Value = $Value -replace '(?i)Bearer\s+\S+', 'Bearer [REDACTED]'
    $Value = $Value -replace '\beyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+', '[REDACTED]'
    $Value = $Value -replace 'whsec_[A-Za-z0-9+/=_-]+', '[REDACTED]'
    return $Value -replace '[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}', '[REDACTED_EMAIL]'
}

$reports = @(Get-ChildItem -LiteralPath $reportPath -Filter '*.xml' -File | Sort-Object Name)
if ($reports.Count -eq 0) { throw "Nenhum resultado JUnit encontrado para $Phase" }
$caseIndex = 0
$cases = @(
    foreach ($report in $reports) {
        [xml]$document = Get-Content -LiteralPath $report.FullName -Raw -Encoding UTF8
        foreach ($caseNode in $document.SelectNodes('//testcase')) {
            $caseIndex++
            $failureNode = $caseNode.SelectSingleNode('failure|error')
            $caseStatus = if ($null -ne $failureNode) { 'FAIL' }
                elseif ($null -ne $caseNode.SelectSingleNode('skipped')) { 'SKIPPED' }
                else { 'PASS' }
            [ordered]@{
                evidence_id = '{0}-{1:D4}' -f $Phase, $caseIndex
                suite = Protect-CaseName ([string]$caseNode.classname)
                case = Protect-CaseName ([string]$caseNode.name)
                status = $caseStatus
                duration_seconds = [double]::Parse([string]$caseNode.time, [Globalization.CultureInfo]::InvariantCulture)
                failure_type = if ($null -ne $failureNode) { [string]$failureNode.type } else { $null }
            }
        }
    }
)

$commits = [ordered]@{}
foreach ($repoName in @('backend', 'frontend', 'cicd', 'wiki')) {
    $repoPath = (Join-Path $workspacePath $repoName).Replace('\', '/')
    $commit = & git -c "safe.directory=$repoPath" -C $repoPath rev-parse HEAD
    if ($LASTEXITCODE -ne 0) { throw "Nao foi possivel identificar o commit de $repoName" }
    $commits[$repoName] = [string]$commit
}
$testChanges = @(
    'backend/build.gradle',
    'backend/src/test/java/ages/vstable/backend/service/TwoFactorStateAssessmentTest.java',
    'backend/src/test/java/ages/vstable/backend/configuration/security/ApplicationSecurityAssessmentTest.java',
    'backend/src/test/java/ages/vstable/backend/service/blindpay/UploadContentAssessmentTest.java',
    'backend/src/test/java/ages/vstable/backend/service/quote/FinancialBoundaryAssessmentTest.java',
    'backend/src/test/java/ages/vstable/backend/external/avenia/ProviderFailureAssessmentTest.java',
    'backend/scripts/export-security-evidence.ps1',
    'backend/scripts/export-security-coverage.ps1'
)
$hashes = [ordered]@{}
foreach ($relativePath in $testChanges) {
    $absolutePath = Join-Path $workspacePath $relativePath
    if (Test-Path -LiteralPath $absolutePath) {
        $hashes[$relativePath] = (Get-FileHash -LiteralPath $absolutePath -Algorithm SHA256).Hash
    }
}

$summary = [ordered]@{
    total = $cases.Count
    passed = @($cases | Where-Object { $_.status -eq 'PASS' }).Count
    failed = @($cases | Where-Object { $_.status -eq 'FAIL' }).Count
    skipped = @($cases | Where-Object { $_.status -eq 'SKIPPED' }).Count
}
$result = [ordered]@{
    phase = $Phase
    captured_at_utc = [DateTimeOffset]::UtcNow.ToString('o')
    environment = 'local; JUnit/MockMvc/Testcontainers ou Vitest; sem chamadas aos provedores'
    commits = $commits
    assessment_files_sha256 = $hashes
    summary = $summary
    cases = $cases
}
New-Item -ItemType Directory -Path $evidencePath -Force | Out-Null
$outputPath = Join-Path $evidencePath "$Phase.json"
$result | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $outputPath -Encoding UTF8
Write-Output ($summary | ConvertTo-Json -Compress)
Write-Output "Evidencia sanitizada: $outputPath"
