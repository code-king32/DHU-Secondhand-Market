[CmdletBinding()]
param(
    [Parameter(Mandatory)][ValidateSet('Init', 'Reset', 'Clean')][string]$Action,
    [string]$DatabaseUser = 'root',
    [string]$DatabaseHost = 'localhost',
    [ValidateRange(1, 65535)][int]$DatabasePort = 3306,
    [string]$MySql = 'mysql'
)
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$parts = [System.Collections.Generic.List[string]]::new()
if ($Action -eq 'Init') {
    # 已存在的库直接报错；不覆盖已有库，也不接受自定义库名。
    $parts.Add('CREATE DATABASE dhu_secondhand_acceptance CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;')
    foreach ($name in @('01_user.sql', '02_registration_email_code.sql', '03_user_role.sql',
            '04_marketplace.sql', '05_community.sql', '06_marketplace_visibility.sql', '07_session_version.sql')) {
        $sql = [IO.File]::ReadAllText((Join-Path $repo "sql/$name"))
        if (-not $sql.Contains('USE dhu_secondhand;')) { throw "Unexpected schema script: $name" }
        $parts.Add($sql.Replace('USE dhu_secondhand;', 'USE dhu_secondhand_acceptance;'))
    }
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
$start = [Diagnostics.ProcessStartInfo]::new()
$start.FileName = (Get-Command $MySql -CommandType Application -ErrorAction Stop).Source
$start.UseShellExecute = $false
$start.RedirectStandardInput = $true
$start.StandardInputEncoding = [Text.UTF8Encoding]::new($false)
foreach ($argument in @("--host=$DatabaseHost", "--port=$DatabasePort", "--user=$DatabaseUser",
        '-p', '--default-character-set=utf8mb4', '--batch', '--skip-reconnect')) {
    $start.ArgumentList.Add($argument)
}
$process = [Diagnostics.Process]::new()
$process.StartInfo = $start
try {
    # 密码由 mysql 自己从控制台交互读取；SQL 走标准输入，批处理遇错即停。
    if (-not $process.Start()) { throw 'Could not start mysql.' }
    $process.StandardInput.Write(($parts -join "`n"))
    $process.StandardInput.Close()
    $process.WaitForExit()
    if ($process.ExitCode -ne 0) { throw 'Database operation failed. Stop here and inspect the MySQL error.' }
    Write-Host "$Action completed: dhu_secondhand_acceptance"
} finally {
    $process.Dispose()
}
