#Requires -Version 7.0
param([string]$BaseUrl = 'http://127.0.0.1:8080')
$ErrorActionPreference = 'Stop'
$requestOptions = @{}
if (([uri]$BaseUrl).IsLoopback) { $requestOptions.NoProxy = $true }
function Send-Json($Method, $Path, $Body) {
    Invoke-RestMethod @requestOptions -Uri "$BaseUrl$Path" -Method $Method -ContentType 'application/json' `
        -Body (ConvertTo-Json $Body -Compress)
}
$franchise = Send-Json Post '/api/v1/franchises' @{ name='Café Andino' }
$root = "/api/v1/franchises/$($franchise.id)"
$branch = Send-Json Post "$root/branches" @{ name='Chapinero' }
$branchPath = "$root/branches/$($branch.id)"
$product = Send-Json Post "$branchPath/products" @{ name='Café'; stock=15 }
$productPath = "$branchPath/products/$($product.id)"
Send-Json Patch "$root/name" @{ name='Café Andino Colombia'; version=0 } | Out-Null
Send-Json Patch "$branchPath/name" @{ name='Chapinero Norte'; version=0 } | Out-Null
Send-Json Patch "$productPath/name" @{ name='Café de origen'; version=0 } | Out-Null
$updated = Send-Json Patch "$productPath/stock" @{ stock=80; version=1 }
if ($updated.stock -ne 80 -or $updated.version -ne 2) { throw 'Falló el cambio de stock.' }
$winners = Invoke-RestMethod @requestOptions "$BaseUrl$root/products/max-stock"
if ($winners.Count -ne 1 -or $winners[0].stock -ne 80 -or $winners[0].branchName -ne 'Chapinero Norte') {
    throw 'Falló el máximo por sucursal.'
}
Invoke-RestMethod @requestOptions -Method Delete "$BaseUrl$productPath" | Out-Null
$remaining = Invoke-RestMethod @requestOptions "$BaseUrl$branchPath/products"
if ($remaining.Count -ne 0) { throw 'El producto no fue eliminado.' }
Write-Host "OK: creación, tres cambios de nombre, stock, máximo y eliminación. Franquicia de demostración: $($franchise.id)"
