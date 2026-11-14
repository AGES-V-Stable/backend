param(
    [string]$EvidenceDirectory = ('docs/security/evidencias-85-sandbox/' + (Get-Date -Format 'yyyy-MM-dd-HHmmss')),
    [ValidateRange(0,179)][int]$PriorCalls = 0
)

$ErrorActionPreference = 'Stop'
$backendDirectory = Split-Path -Parent $PSScriptRoot
Push-Location $backendDirectory
try {
    foreach ($variableName in @('BLINDPAY_API_KEY', 'BLINDPAY_INSTANCE_ID')) {
        if (-not [Environment]::GetEnvironmentVariable($variableName, 'Process')) {
            throw "$variableName must be loaded in this process; never put credentials in command arguments."
        }
    }
    $evidenceRoot = [IO.Path]::GetFullPath((Join-Path $backendDirectory 'docs/security/evidencias-85-sandbox'))
    $evidencePath = [IO.Path]::GetFullPath((Join-Path $backendDirectory $EvidenceDirectory))
    if (-not $evidencePath.StartsWith($evidenceRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'Evidence directory must be under docs/security/evidencias-85-sandbox/.'
    }
    if (Test-Path -LiteralPath (Join-Path $evidencePath 'assessment.json')) {
        throw 'Existing assessment will not be overwritten. Choose a new evidence directory.'
    }
    New-Item -ItemType Directory -Force -Path $evidencePath | Out-Null
    $specFile = Join-Path $backendDirectory 'build/security-sandbox/blindpay-openapi.json'
    if (-not (Test-Path -LiteralPath $specFile)) {
        New-Item -ItemType Directory -Force -Path (Split-Path -Parent $specFile) | Out-Null
        $specResponse = Invoke-WebRequest -UseBasicParsing -Uri 'https://api.blindpay.com/doc' -TimeoutSec 25
        [IO.File]::WriteAllText($specFile, $specResponse.Content)
    }
    $env:BLINDPAY_SECURITY_SANDBOX_ENABLED = 'true'
    $env:BLINDPAY_SECURITY_PRIOR_CALLS = [string]$PriorCalls
    $env:BLINDPAY_SECURITY_EVIDENCE_DIR = $evidencePath
    $env:BLINDPAY_SECURITY_COMMIT = (& git -c "safe.directory=$($backendDirectory.Replace('\','/'))" rev-parse HEAD)
    $env:BLINDPAY_BASE_URL = 'https://api.blindpay.com'
    $env:AVENIA_BASE_URL = 'http://127.0.0.1:1'
    $env:AVENIA_API_KEY = ''
    $env:AVENIA_PRIVATE_KEY = ''
    $env:APP_CONFIG_SECRET_ARN = ''
    $env:TRANSFERS_RECONCILIATION_ENABLED = 'false'
    # Fresh secrets belong exclusively to this ephemeral database/application.
    function New-SandboxSecret([int]$Length) {
        $bytes = New-Object byte[] $Length
        $generator = [Security.Cryptography.RandomNumberGenerator]::Create()
        try { $generator.GetBytes($bytes); return [Convert]::ToBase64String($bytes) }
        finally { $generator.Dispose() }
    }
    $env:JWT_SECRET = New-SandboxSecret 96
    $env:TWO_FACTOR_ENCRYPTION_KEY = New-SandboxSecret 32
    $env:INVITATION_ENCRYPTION_KEY = New-SandboxSecret 32
    $exitCode = 1
    $metadata = [ordered]@{
        command = '.\scripts\run-blindpay-security-sandbox.ps1'
        gradle_task = 'blindpaySecuritySandboxTest'
        execution_state = 'STARTED'
        started_utc = [DateTime]::UtcNow.ToString('o')
        preceding_budget_consumed = $PriorCalls
        exit_code = $null
        commit = $env:BLINDPAY_SECURITY_COMMIT
        openapi_sha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $specFile).Hash.ToLowerInvariant()
        source_sha256 = @{}
    }
    foreach ($relative in @('build.gradle', 'scripts/run-blindpay-security-sandbox.ps1',
            'src/test/java/ages/vstable/backend/sandbox/BlindPaySecuritySandboxTest.java',
            'src/test/java/ages/vstable/backend/sandbox/BlindPaySandboxBoundary.java')) {
        $metadata.source_sha256[$relative] = (Get-FileHash -Algorithm SHA256 -LiteralPath $relative).Hash.ToLowerInvariant()
    }
    $metadata | ConvertTo-Json -Depth 6 | Set-Content -Encoding UTF8 -LiteralPath (Join-Path $evidencePath 'execution.json')
    # No raw child-process output is persisted or forwarded. Only fixed task/subcase lines are shown.
    $ErrorActionPreference = 'Continue'
    & .\gradlew.bat blindpaySecuritySandboxTest --no-daemon --console=plain --max-workers=2 '-Dorg.gradle.jvmargs=-Xmx256m -XX:ActiveProcessorCount=2' 2>&1 | ForEach-Object {
        $line = [string]$_
        if ($line -match '^SEC85_SANDBOX [A-Za-z0-9_-]+ (PASS|FAIL|BLOQUEADO|NÃO EXECUTADO) calls=\d+$' -or
            $line -match '^> Task :[A-Za-z0-9]+(?: (?:UP-TO-DATE|NO-SOURCE|FAILED))?$' -or
            $line -match '^BUILD (SUCCESSFUL|FAILED) in [A-Za-z0-9 ]+$' -or
            $line -match '^BlindPaySecuritySandboxTest > controlledAssessment\(\) (PASSED|FAILED|SKIPPED)$') {
            Write-Output $line
        }
    }
    $exitCode = $LASTEXITCODE
    $ErrorActionPreference = 'Stop'
    $metadata.exit_code = $exitCode
    $metadata.execution_state = 'FINISHED'
    $metadata.finished_utc = [DateTime]::UtcNow.ToString('o')
    $metadata | ConvertTo-Json -Depth 6 | Set-Content -Encoding UTF8 -LiteralPath (Join-Path $evidencePath 'execution.json')
    Write-Output ('Sandbox process exit code: ' + $exitCode)
} finally {
    @('BLINDPAY_SECURITY_SANDBOX_ENABLED','BLINDPAY_SECURITY_PRIOR_CALLS','BLINDPAY_SECURITY_EVIDENCE_DIR','BLINDPAY_SECURITY_COMMIT',
      'JWT_SECRET','TWO_FACTOR_ENCRYPTION_KEY','INVITATION_ENCRYPTION_KEY') | ForEach-Object {
        [Environment]::SetEnvironmentVariable($_, $null, 'Process')
    }
    Pop-Location
}
exit $exitCode
