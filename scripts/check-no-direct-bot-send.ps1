$ErrorActionPreference = "Stop"

$root = Join-Path $PSScriptRoot "..\src\main\kotlin"
$matches = rg -n "BiliBiliBot\.send(GroupMessage|PrivateMessage|Message|AdminMessage)\(" $root

if ($LASTEXITCODE -ne 0 -and $LASTEXITCODE -ne 1) {
    Write-Error "rg failed with exit code: $LASTEXITCODE"
    exit 2
}

if ($matches) {
    Write-Host "Found forbidden direct calls:"
    $matches | ForEach-Object { Write-Host $_ }
    exit 1
}

Write-Host "Check passed: no direct BiliBiliBot.send* calls found."
exit 0
