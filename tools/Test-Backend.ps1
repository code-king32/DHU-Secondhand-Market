#requires -Version 7.0
[CmdletBinding()]
param(
    [ValidateSet('Default', 'Database', 'Reliability', 'Acceptance', 'Schema', 'Mail')]
    [string]$Suite = 'Default',
    [string]$Maven = 'mvn',
    [switch]$Offline,
    [switch]$Preview
)
$ErrorActionPreference = 'Stop'
$backend = (Resolve-Path (Join-Path $PSScriptRoot '../backend')).Path
$flags = [ordered]@{
    'db.test.enabled' = 'false'
    'reliability.mysql.enabled' = 'false'
    'acceptance.kit.test.enabled' = 'false'
    'schema.update.enabled' = 'false'
    'mail.test.enabled' = 'false'
}
$tests = switch ($Suite) {
    'Database' { $flags['db.test.enabled'] = 'true'; 'ManualDatabaseConnectionTests,ManualMarketplaceTests,ManualAcceptanceFlowTests' }
    'Reliability' { $flags['reliability.mysql.enabled'] = 'true'; 'ManualReliabilityMySqlTests' }
    'Acceptance' { $flags['acceptance.kit.test.enabled'] = 'true'; 'ManualAcceptanceKitTests' }
    'Schema' { $flags['schema.update.enabled'] = 'true'; 'ManualSchemaMigrationTests' }
    'Mail' { $flags['mail.test.enabled'] = 'true'; 'ManualMailSendTests' }
}
$mavenArguments = [System.Collections.Generic.List[string]]::new()
if ($Offline) { $mavenArguments.Add('-o') }
if ($tests) { $mavenArguments.Add("-Dtest=$tests") }
foreach ($entry in $flags.GetEnumerator()) { $mavenArguments.Add("-D$($entry.Key)=$($entry.Value)") }
$mavenArguments.Add('test')
if ($Preview) {
    Write-Output "Working directory: $backend"
    Write-Output ($Maven + ' ' + ($mavenArguments -join ' '))
    return
}
Push-Location $backend
try {
    & $Maven @mavenArguments
    if ($LASTEXITCODE -ne 0) { throw "Backend test suite '$Suite' failed (exit $LASTEXITCODE)." }
} finally {
    Pop-Location
}
