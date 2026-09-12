# Upsert KEY=VALUE in a dotenv file (used by the Windows install/restore scripts).
param(
    [Parameter(Mandatory = $true)][string]$File,
    [Parameter(Mandatory = $true)][string]$Key,
    [Parameter(Mandatory = $true)][string]$Value
)

$path = (Resolve-Path -LiteralPath $File).Path
$lines = @(Get-Content -LiteralPath $path -Encoding UTF8)
$found = $false
$out = foreach ($line in $lines) {
    if ($line -match ("^\s*" + [regex]::Escape($Key) + "=")) {
        $found = $true
        "$Key=$Value"
    }
    else {
        $line
    }
}
if (-not $found) { $out = @($out) + "$Key=$Value" }
# WriteAllLines: UTF-8 without BOM (docker compose's dotenv parser must not
# see a BOM before the first key).
[System.IO.File]::WriteAllLines($path, [string[]]$out)
