#Requires -Version 7.0
[CmdletBinding()]
param([switch]$SkipBuild)
$ErrorActionPreference = 'Stop'
$projectDirectory = Split-Path $PSScriptRoot -Parent
$toolsDirectory = Join-Path $projectDirectory '.tools'
$stateDirectory = Join-Path $projectDirectory '.local'
$dbDirectory = Join-Path $toolsDirectory 'dynamodb-current'
$dataDirectory = Join-Path $stateDirectory 'data'
$javaExecutable = (Get-Command java -ErrorAction Stop).Source
New-Item -ItemType Directory -Force $dbDirectory, $dataDirectory | Out-Null
$pidFile = Join-Path $stateDirectory 'processes.json'
if (Test-Path $pidFile) { throw 'Hay procesos registrados. Ejecuta scripts/stop-local.ps1 antes de arrancar de nuevo.' }
foreach ($port in @(8000, 8080)) {
    $listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, $port)
    try { $listener.Start() } catch { throw "El puerto $port está ocupado." } finally { $listener.Stop() }
}
if (!(Test-Path (Join-Path $dbDirectory 'DynamoDBLocal.jar'))) {
    Write-Host 'Descargando DynamoDB Local desde AWS...'
    $archive = Join-Path $toolsDirectory 'dynamodb-current.zip'
    $checksumFile = Join-Path $toolsDirectory 'dynamodb-current.sha256'
    $downloadUrl = 'https://d1ni2b6xgvw0s0.cloudfront.net/v3.x/dynamodb_local_latest.zip'
    Invoke-WebRequest $downloadUrl -OutFile $archive
    Invoke-WebRequest "$downloadUrl.sha256" -OutFile $checksumFile
    $expectedHash = ((Get-Content $checksumFile -Raw).Trim() -split '\s+')[0]
    if ((Get-FileHash $archive -Algorithm SHA256).Hash.ToLowerInvariant() -ne $expectedHash) {
        throw 'El SHA-256 de DynamoDB Local no coincide con el publicado por AWS.'
    }
    Expand-Archive -LiteralPath $archive -DestinationPath $dbDirectory -Force
}
if (!$SkipBuild) {
    Push-Location $projectDirectory
    try {
        & ./mvnw.cmd -B -ntp "-Dmaven.repo.local=$toolsDirectory/m2" package
        if ($LASTEXITCODE -ne 0) { throw 'La compilación falló.' }
    } finally { Pop-Location }
}
$appJar = Join-Path $projectDirectory 'target/franchises-api-1.0.0.jar'
if (!(Test-Path $appJar)) { throw 'Falta el JAR. Arranca sin -SkipBuild.' }
$startedProcesses = @()
try {
    $dbJar = Join-Path $dbDirectory 'DynamoDBLocal.jar'
    $nativeLibraries = Join-Path $dbDirectory 'DynamoDBLocal_lib'
    $db = Start-Process $javaExecutable -WindowStyle Hidden -PassThru -WorkingDirectory $dbDirectory `
        -ArgumentList "-Djava.library.path=`"$nativeLibraries`"", '-jar', "`"$dbJar`"", '-sharedDb', `
            '-dbPath', "`"$dataDirectory`"", '-port', '8000', '-disableTelemetry' `
        -RedirectStandardOutput (Join-Path $stateDirectory 'dynamodb.out.log') `
        -RedirectStandardError (Join-Path $stateDirectory 'dynamodb.err.log')
    $startedProcesses += $db
    # Dummy signing header is accepted only by DynamoDB Local; never used against AWS.
    $date = (Get-Date).ToUniversalTime().ToString('yyyyMMdd')
    $headers = @{
        'X-Amz-Target' = 'DynamoDB_20120810.ListTables'
        'X-Amz-Date' = "${date}T000000Z"
        'Authorization' = "AWS4-HMAC-SHA256 Credential=local/$date/us-east-1/dynamodb/aws4_request, SignedHeaders=host;x-amz-date, Signature=$('0' * 64)"
    }
    $ready = $false
    for ($attempt = 0; $attempt -lt 30; $attempt++) {
        if ($db.HasExited) { throw 'DynamoDB terminó. Consulta .local/dynamodb.err.log.' }
        try {
            $tables = Invoke-RestMethod http://127.0.0.1:8000 -NoProxy -Method Post -Headers $headers -SkipHeaderValidation `
                -ContentType 'application/x-amz-json-1.0' -Body '{}' -TimeoutSec 5
            $ready = $true
            break
        } catch { Start-Sleep -Seconds 1 }
    }
    if (!$ready) { throw 'DynamoDB no respondió a tiempo.' }
    if ('franchises' -notin $tables.TableNames) {
        $headers['X-Amz-Target'] = 'DynamoDB_20120810.CreateTable'
        $body = @{
            TableName = 'franchises'; BillingMode = 'PAY_PER_REQUEST'
            AttributeDefinitions = @(@{ AttributeName='pk'; AttributeType='S' }, @{ AttributeName='sk'; AttributeType='S' })
            KeySchema = @(@{ AttributeName='pk'; KeyType='HASH' }, @{ AttributeName='sk'; KeyType='RANGE' })
        } | ConvertTo-Json -Depth 5
        Invoke-RestMethod http://127.0.0.1:8000 -NoProxy -Method Post -Headers $headers -SkipHeaderValidation `
            -ContentType 'application/x-amz-json-1.0' -Body $body | Out-Null
    }
    $app = Start-Process $javaExecutable -WindowStyle Hidden -PassThru -WorkingDirectory $projectDirectory `
        -ArgumentList '-jar', "`"$appJar`"", '--spring.profiles.active=local', `
            '--inventory.dynamodb.endpoint=http://localhost:8000', '--inventory.dynamodb.table=franchises', `
            '--inventory.dynamodb.region=us-east-1', '--server.port=8080', '--server.address=127.0.0.1' `
        -RedirectStandardOutput (Join-Path $stateDirectory 'api.out.log') `
        -RedirectStandardError (Join-Path $stateDirectory 'api.err.log')
    $startedProcesses += $app
    $ready = $false
    for ($attempt = 0; $attempt -lt 60; $attempt++) {
        if ($app.HasExited) { throw 'La API terminó. Consulta .local/api.out.log y api.err.log.' }
        try {
            $health = Invoke-RestMethod http://127.0.0.1:8080/actuator/health/readiness -NoProxy -TimeoutSec 5
            if ($health.status -eq 'UP') { $ready = $true; break }
        } catch { Write-Verbose $_.Exception.Message; Start-Sleep -Seconds 1 }
    }
    if (!$ready) { throw 'La API no está lista. Consulta los logs de .local.' }
    @{ database = @{ pid=$db.Id; jar=$dbJar }; api = @{ pid=$app.Id; jar=$appJar } } | ConvertTo-Json -Depth 4 | Set-Content $pidFile
    Write-Host 'API lista: http://localhost:8080 | Documentación: http://localhost:8080/docs.html'
    Write-Host 'Datos persistentes: .local/data | Logs: .local | Detener: ./scripts/stop-local.ps1'
} catch {
    foreach ($process in $startedProcesses) { if (!$process.HasExited) { Stop-Process -Id $process.Id } }
    throw
}
