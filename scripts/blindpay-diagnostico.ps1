param(
    [string]$ApiBase = "http://localhost:8080",
    [string]$QuoteJson = "",
    [switch]$NonInteractive
)
$ErrorActionPreference = "Stop"
$project = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$desktop = [Environment]::GetFolderPath("Desktop")
$directory = Join-Path $desktop "BlindPay-Diagnostico"
New-Item -Path $directory -ItemType Directory -Force | Out-Null
$stamp = Get-Date -Format "yyyy-MM-dd_HH-mm-ss"
$logFile = Join-Path $directory "blindpay_$stamp.log"
$script:passed = 0
$script:failed = 0
function Safe([string]$value) {
    if ($null -eq $value) { return "" }
    $value = $value -replace '(?i)(bearer\s+)[^\s,;"'']+', '$1[REDACTED]'
    $value = $value -replace '(?i)((?:api[_-]?key|authorization|jwt[_-]?secret|token|password|secret)\s*[:=]\s*)[^\s,;"'']+', '$1[REDACTED]'
    $value = $value -replace '(?i)([\w.%-]+)@([\w.-]+)', '[EMAIL_REDACTED]'
    $value = $value -replace '(?<!\d)\d{11,14}(?!\d)', '[ID_REDACTED]'
    return $value
}
function Log([string]$message) {
    $text = "$(Get-Date -Format s) $(Safe $message)"
    Write-Host $text
    Add-Content -LiteralPath $logFile -Value $text -Encoding UTF8
}
function Step([string]$label,[scriptblock]$action) {
    Log "INICIO: $label"
    $start = Get-Date
    try {
        & $action
        $script:passed++
        Log ("PASSOU: {0} ({1:n1}s)" -f $label, ((Get-Date)-$start).TotalSeconds)
    } catch {
        $script:failed++
        Log ("FALHOU: {0} ({1})" -f $label, $_.Exception.Message)
    }
}
function Gradle([string]$task) {
    Push-Location $project
    try {
        $output = & .\gradlew.bat $task --console=plain 2>&1
        $exitCode = $LASTEXITCODE
        foreach ($line in $output) {
            $line = Safe ([string]$line)
            if ($line -match '(?i)(FAILED|BUILD SUCCESSFUL|BUILD FAILED|error:|\btests? completed\b|\btests? failed\b|> Task .*FAILED|Exception)') {
                Log ("GRADLE: " + $line.Substring(0,[Math]::Min($line.Length,280)))
            }
        }
        if ($exitCode -ne 0) { throw "Gradle $task terminou com codigo $exitCode. Consulte build/test-results no projeto." }
    } finally { Pop-Location }
}
function Api([string]$method,[string]$path,[string]$token,[string]$jsonFile="") {
    $headers = @{ Authorization = "Bearer $token" }
    $uri = $ApiBase.TrimEnd('/') + $path
    $options = @{ Uri = $uri; Method = $method; Headers = $headers; TimeoutSec = 45; ErrorAction = 'Stop' }
    if ($jsonFile) {
        if (-not (Test-Path -LiteralPath $jsonFile)) { throw "Arquivo JSON nao encontrado: $jsonFile" }
        $options.Body = Get-Content -LiteralPath $jsonFile -Raw -Encoding UTF8
        $options.ContentType = 'application/json'
    }
    try {
        $result = Invoke-WebRequest @options
        Log ("HTTP: {0} {1} -> {2}" -f $method,$path,[int]$result.StatusCode)
        if ($path -eq "/v1/blindpay/compliance" -and $result.Content) {
            $data = $result.Content | ConvertFrom-Json
            Log ("COMPLIANCE: status={0}, habilitado={1}, kyb={2}, aml={3}" -f $data.status,$data.enabled,$data.kycStatus,$data.amlStatus)
        }
        if ($path -eq "/v1/quotes" -and $result.Content) {
            $data = $result.Content | ConvertFrom-Json
            Log ("QUOTE: retornou oferta={0}; expira={1}" -f [bool]$data.offer,$data.offer.expiresAt)
        }
    } catch {
        $status = if ($_.Exception.Response) { [int]$_.Exception.Response.StatusCode } else { "SEM_RESPOSTA" }
        Log "HTTP: $method $path -> $status"
        throw "Requisicao HTTP falhou (status=$status). Resposta completa omitida para proteger dados."
    }
}
Log "Diagnostico BlindPay - inicio. Diretorio do projeto: $project"
Log "Seguranca: nao gravamos token JWT, JSON de entrada nem corpo completo das respostas da API."
Step "Java instalado" { $java = & java -version 2>&1; if ($LASTEXITCODE -ne 0) { throw "Java nao disponivel" }; Log ("Java: " + (Safe ([string]$java[0]))) }
Step "Gradle wrapper" { if (-not (Test-Path (Join-Path $project "gradlew.bat"))) { throw "gradlew.bat ausente" } }
if (-not $NonInteractive) {
    Write-Host ""
    Write-Host "1 - Rodar testes Gradle"
    Write-Host "2 - Rodar build Gradle"
    Write-Host "3 - Verificar endpoint de compliance (requer backend aberto e JWT)"
    Write-Host "4 - Testar quote com JSON existente (POST; nao executa pagamento)"
    Write-Host "5 - Rodar testes e build"
    $choice = Read-Host "Escolha"
} else { $choice = "5" }
switch ($choice) {
    "1" { Step "Gradle test" { Gradle "test" } }
    "2" { Step "Gradle build" { Gradle "build" } }
    "3" {
        $token = Read-Host "Cole o JWT de teste (nao sera salvo)"
        Step "GET /v1/blindpay/compliance" { Api "GET" "/v1/blindpay/compliance" $token }
        Remove-Variable token -ErrorAction SilentlyContinue
    }
    "4" {
        $token = Read-Host "Cole o JWT de teste (nao sera salvo)"
        if (-not $QuoteJson) { $QuoteJson = Read-Host "Caminho do JSON de cotacao ja preparado" }
        $confirm = Read-Host "Confirmar POST /v1/quotes no ambiente configurado? Digite SIM"
        if ($confirm -eq "SIM") { Step "POST /v1/quotes" { Api "POST" "/v1/quotes" $token $QuoteJson } }
        else { Log "Cotacao cancelada." }
        Remove-Variable token -ErrorAction SilentlyContinue
    }
    "5" { Step "Gradle test" { Gradle "test" }; Step "Gradle build" { Gradle "build" } }
    default { Log "Opcao invalida: nenhum teste executado." }
}
Log "RESUMO: PASSOU=$script:passed FALHOU=$script:failed"
Log "Arquivo de diagnostico: $logFile"
Log "Antes de compartilhar: revise o log e remova quaisquer dados sensiveis remanescentes."
