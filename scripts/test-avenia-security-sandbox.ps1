[CmdletBinding()]
param(
    [ValidateSet('preflight', 'assessment', 'business')][string]$Phase = 'preflight',
    [ValidatePattern('^[a-zA-Z0-9-]+$')][string]$Campaign = 'initial',
    [string]$RunLabel = (Get-Date -Format 'yyyyMMdd-HHmmss')
)
$ErrorActionPreference = 'Stop'
$repoPath = Split-Path -Parent $PSScriptRoot
$credentialPath = Join-Path $repoPath '.env.avenia-security'
$safeGitPath = $repoPath.Replace('\', '/')
Push-Location $repoPath
$oldValues = @{}
$assessmentExit = 1
try {
    $branch = (& git -c "safe.directory=$safeGitPath" branch --show-current).Trim()
    if ($branch -ne 'test/85-seguranca-avenia-sandbox') { throw 'Use a branch test/85-seguranca-avenia-sandbox.' }
    foreach ($relativeKey in @('.env.avenia-security/api_key.txt', '.env.avenia-security/private_key.pem')) {
        & git -c "safe.directory=$safeGitPath" check-ignore --quiet -- $relativeKey
        if ($LASTEXITCODE -ne 0 -or !(Test-Path -LiteralPath $relativeKey)) { throw 'Credencial local ausente ou desprotegida pelo Git.' }
    }
    if ($RunLabel -notmatch '^[a-zA-Z0-9-]+$') { throw 'RunLabel deve conter somente letras, numeros e hifen.' }
    $priorAttempts = 0
    $priorServerErrors = 0
    $evidenceRoot = Join-Path $repoPath 'docs/security/evidencias-85-avenia-sandbox'
    if (Test-Path -LiteralPath $evidenceRoot) {
        foreach ($previousFile in Get-ChildItem -LiteralPath $evidenceRoot -Filter assessment.json -Recurse) {
            $previousRun = Get-Content -LiteralPath $previousFile.FullName -Raw | ConvertFrom-Json
            $previousManifest = Get-Content -LiteralPath (Join-Path $previousFile.DirectoryName 'runner.json') -Raw | ConvertFrom-Json
            $previousCampaign = 'initial'
            if ($previousManifest.PSObject.Properties.Name -contains 'campaign') { $previousCampaign = $previousManifest.campaign }
            if (@($previousRun.calls | Where-Object { $_.method -eq 'POST' -and $_.state -ne 'RESPONSE_RECEIVED' }).Count -gt 0) {
                throw 'Escrita de campanha anterior com resultado desconhecido: reconciliar antes de nova operacao.'
            }
            $possiblyCreatedTickets = @($previousRun.calls | Where-Object {
                $_.method -eq 'POST' -and $_.endpoint -eq '/v2/account/tickets/' -and
                ($_.http_status -lt 400 -or $_.http_status -ge 500)
            }).Count
            $terminalTicketCases = @($previousRun.cases | Where-Object {
                if ($_.id -eq 'TC-SBX-AV-04.1') {
                    @($_.details.states).Count -gt 0 -and @($_.details.states)[-1] -in @('PAID','FAILED','PARTIAL-FAILED','CANCELED')
                } elseif ($_.id -eq 'TC-SBX-AV-04.2') {
                    $_.details.provider_terminal_status -in @('PAID','FAILED','PARTIAL-FAILED','CANCELED')
                } else { $false }
            }).Count
            if ($possiblyCreatedTickets -gt $terminalTicketCases) {
                throw 'Ticket anterior sem estado terminal comprovado: reconciliar antes de iniciar outra campanha.'
            }
            if ($previousCampaign -ne $Campaign) { continue }
            $priorAttempts += @($previousRun.calls).Count
            $priorServerErrors += @($previousRun.calls | Where-Object { $_.http_status -ge 500 }).Count
            $writes = @($previousRun.calls | Where-Object { $_.method -eq 'POST' })
            if ($writes.Count -gt 0) {
                $safeContinuation = $Phase -eq 'business' -and $previousRun.phase -eq 'assessment' -and $previousRun.state -eq 'FINISHED' -and
                    @($previousRun.cases | Where-Object { $_.id -eq 'TC-SBX-AV-06.2' -and $_.status -eq 'PASS' }).Count -eq 1
                $rejectedPreparationOnly = $Phase -eq 'business' -and $previousRun.phase -eq 'business' -and
                    @($writes | Where-Object { $_.endpoint -ne '/v2/account/beneficiaries/wallets/' -or $_.http_status -ne 400 -or $_.state -ne 'RESPONSE_RECEIVED' }).Count -eq 0
                $safeContinuation = $safeContinuation -or $rejectedPreparationOnly
                $knownWalletPreparation = $Phase -eq 'business' -and $previousRun.phase -eq 'business' -and $previousRun.state -eq 'FINISHED' -and
                    @($writes | Where-Object { $_.endpoint -ne '/v2/account/beneficiaries/wallets/' -or $_.http_status -ne 201 -or $_.state -ne 'RESPONSE_RECEIVED' }).Count -eq 0
                $safeContinuation = $safeContinuation -or $knownWalletPreparation
                if (!$safeContinuation) { throw 'Ha escrita anterior sem continuacao segura: revise checkpoint/recursos privados; nao reenvie automaticamente.' }
            }
        }
    }
    if ($priorAttempts -ge 50) { throw 'Orcamento acumulado de 50 chamadas esgotado.' }
    if ($priorServerErrors -ge 2) { throw 'Dois erros do servidor ja registrados: interromper chamadas ate revisar o provedor.' }
    $outputPath = Join-Path $repoPath "docs/security/evidencias-85-avenia-sandbox/$(Get-Date -Format 'yyyy-MM-dd')/$RunLabel-$Phase"
    if (Test-Path -LiteralPath $outputPath) { throw 'Execucao existente: revise o checkpoint antes de reenviar operacoes.' }
    New-Item -ItemType Directory -Path $outputPath -Force | Out-Null
    $executionValues = @{
        AVENIA_SECURITY_SANDBOX_ENABLED = 'true'
        AVENIA_ASSESSMENT_KEY_DIR = $credentialPath
        AVENIA_ASSESSMENT_OUTPUT = $outputPath
        AVENIA_ASSESSMENT_BRANCH = $branch
        AVENIA_ASSESSMENT_PHASE = $Phase
        AVENIA_ASSESSMENT_REMAINING_CALLS = (50 - $priorAttempts).ToString()
        AVENIA_ASSESSMENT_PRIOR_SERVER_ERRORS = $priorServerErrors.ToString()
        AVENIA_ASSESSMENT_COMMIT = (& git -c "safe.directory=$safeGitPath" rev-parse HEAD).Trim()
        AVENIA_API_KEY = ''
        AVENIA_PRIVATE_KEY = ''
        BLINDPAY_API_KEY = ''
        BLINDPAY_INSTANCE_ID = ''
        BLINDPAY_WEBHOOK_SECRET = ''
        APP_CONFIG_SECRET_ARN = ''
        SPRING_PROFILES_ACTIVE = 'test'
        INVITATION_FRONTEND_ORIGIN = 'http://127.0.0.1'
        TRANSFERS_RECONCILIATION_ENABLED = 'false'
        GRADLE_USER_HOME = (Join-Path $env:USERPROFILE '.gradle')
    }
    if ($Phase -eq 'business') {
        $resourceFiles = @(Get-ChildItem -LiteralPath $credentialPath -Filter 'assessment-resources-*.json')
        if ($resourceFiles.Count -ne 1) { throw 'Subconta propria ambigua ou ausente: revisar os registros privados antes de continuar.' }
        $executionValues.AVENIA_ASSESSMENT_OWN_RESOURCE_FILE = $resourceFiles[0].FullName
    }
    foreach ($secretName in @('JWT_SECRET', 'TWO_FACTOR_ENCRYPTION_KEY', 'INVITATION_ENCRYPTION_KEY')) {
        $size = 32
        if ($secretName -eq 'JWT_SECRET') { $size = 64 }
        $bytes = New-Object byte[] $size
        $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
        try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
        $executionValues[$secretName] = [Convert]::ToBase64String($bytes)
    }
    foreach ($key in $executionValues.Keys) {
        $oldValues[$key] = [Environment]::GetEnvironmentVariable($key, 'Process')
        [Environment]::SetEnvironmentVariable($key, $executionValues[$key], 'Process')
    }
    $hashes = @(Get-ChildItem src/test/java/ages/vstable/backend/sandbox/Avenia*.java | ForEach-Object {
        @{ file = $_.Name; sha256 = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant() }
    })
    $hashes += @{ file = 'build.gradle'; sha256 = (Get-FileHash build.gradle -Algorithm SHA256).Hash.ToLowerInvariant() }
    $hashes += @{ file = 'scripts/test-avenia-security-sandbox.ps1'; sha256 = (Get-FileHash -LiteralPath $PSCommandPath -Algorithm SHA256).Hash.ToLowerInvariant() }
    foreach ($productionFile in @('external/avenia/AveniaClient.java', 'external/avenia/AveniaRequestSigner.java', 'external/avenia/AveniaTransferService.java', 'service/TransferService.java', 'service/quote/AveniaQuoteProvider.java', 'service/transfer/TransferExecutionService.java')) {
        $hashes += @{ file = "src/main/java/ages/vstable/backend/$productionFile"; sha256 = (Get-FileHash -LiteralPath "src/main/java/ages/vstable/backend/$productionFile" -Algorithm SHA256).Hash.ToLowerInvariant() }
    }
    $manifest = @{ branch = $branch; commit = $executionValues.AVENIA_ASSESSMENT_COMMIT; working_tree_changes_expected = $true; files = $hashes; phase = $Phase; campaign = $Campaign; prior_assessment_attempts = $priorAttempts; total_call_ceiling = 50; started_utc = [DateTime]::UtcNow.ToString('o'); state = 'RUNNING' }
    $manifest | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $outputPath 'runner.json') -Encoding UTF8
    New-Item -ItemType Directory -Path build/security-avenia -Force | Out-Null
    # Private build log is deliberately not exported as evidence. The test never prints bodies/headers.
    $logPath = Join-Path $repoPath "build/security-avenia/gradle-$RunLabel-$Phase.log"
    $ErrorActionPreference = 'Continue'
    & .\gradlew.bat aveniaSecuritySandboxTest "-PaveniaPhase=$Phase" --no-daemon --max-workers=1 --console=plain *> $logPath
    $assessmentExit = $LASTEXITCODE
    $ErrorActionPreference = 'Stop'
    $manifest.state = 'FINISHED'
    $manifest.finished_utc = [DateTime]::UtcNow.ToString('o')
    $manifest.gradle_exit_code = $assessmentExit
    $manifest | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $outputPath 'runner.json') -Encoding UTF8
    Write-Output "Avenia assessment phase=$Phase exit=$assessmentExit evidence=$outputPath"
} finally {
    foreach ($key in $oldValues.Keys) { [Environment]::SetEnvironmentVariable($key, $oldValues[$key], 'Process') }
    Pop-Location
}
exit $assessmentExit
