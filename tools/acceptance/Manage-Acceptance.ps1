#requires -Version 7.0
[CmdletBinding()]
param(
    [Parameter(Mandatory)][ValidateSet('Init', 'Reset', 'Clean')][string]$Action,
    [string]$DatabaseUser = 'root',
    [string]$DatabaseHost = 'localhost',
    [ValidateRange(1, 65535)][int]$DatabasePort = 3306,
    [string]$MySql = 'mysql',
    [switch]$Preview
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot '../common/Database.ps1')
$parts = [System.Collections.Generic.List[string]]::new()
if ($Action -eq 'Init') {
    $parts.Add((Get-DhuSchemaSql -DatabaseName 'dhu_secondhand_acceptance'))
    $parts.Add(@'
CREATE TABLE dhu_secondhand_acceptance._acceptance_meta (
    id INT PRIMARY KEY, marker VARCHAR(40) NOT NULL, generation INT NOT NULL
);
INSERT INTO dhu_secondhand_acceptance._acceptance_meta VALUES (1,'dhu-acceptance-v1',0);
'@)
}
if ($Action -in @('Reset', 'Clean')) {
    $parts.Add([IO.File]::ReadAllText((Join-Path $PSScriptRoot 'clean.sql')))
}
if ($Action -in @('Init', 'Reset')) {
    $parts.Add([IO.File]::ReadAllText((Join-Path $PSScriptRoot 'seed.sql')))
}
$sql = $parts -join "`n"
if ($Preview) { Write-Output $sql; return }
Invoke-DhuMySql -Sql $sql -DatabaseUser $DatabaseUser -DatabaseHost $DatabaseHost -DatabasePort $DatabasePort -MySql $MySql
Write-Host "$Action completed: dhu_secondhand_acceptance"
