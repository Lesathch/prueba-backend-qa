#Requires -Version 7.0
$ErrorActionPreference = 'Stop'
$projectDirectory = Split-Path $PSScriptRoot -Parent
$pidFile = Join-Path $projectDirectory '.local/processes.json'
if (!(Test-Path $pidFile)) { Write-Host 'No hay procesos registrados.'; return }
$processes = Get-Content -LiteralPath $pidFile -Raw | ConvertFrom-Json
foreach ($entry in @($processes.api, $processes.database)) {
    $process = Get-CimInstance Win32_Process -Filter "ProcessId=$($entry.pid)" -ErrorAction SilentlyContinue
    if ($null -eq $process) { continue }
    if ($process.Name -ne 'java.exe' -or !$process.CommandLine.Contains($entry.jar)) {
        throw "El PID $($entry.pid) pertenece a otro proceso. No se detendrá."
    }
    Stop-Process -Id $entry.pid
}
Remove-Item -LiteralPath $pidFile
Write-Host 'API y DynamoDB detenidos. Los datos se conservan en .local/data.'
