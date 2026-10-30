#requires -Version 7.0
[CmdletBinding()]
param(
    [string]$DatabaseUser = 'root',
    [string]$DatabaseHost = 'localhost',
    [ValidateRange(1, 65535)][int]$DatabasePort = 3306,
    [string]$MySql = 'mysql',
    [switch]$Preview
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'common/Database.ps1')
$sql = Get-DhuSchemaSql -DatabaseName 'dhu_secondhand'
if ($Preview) {
    Write-Output $sql
    return
}
Invoke-DhuMySql -Sql $sql -DatabaseUser $DatabaseUser -DatabaseHost $DatabaseHost -DatabasePort $DatabasePort -MySql $MySql
Write-Host 'Initialized: dhu_secondhand (11 business tables and 3 runtime tables).'
