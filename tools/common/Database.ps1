#requires -Version 7.0

function Get-DhuSchemaSql {
    param(
        [Parameter(Mandatory)]
        [ValidateSet('dhu_secondhand', 'dhu_secondhand_acceptance')][string]$DatabaseName
    )
    $repository = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
    $manifest = Get-Content -LiteralPath (Join-Path $repository 'sql/schema-manifest.json') -Raw |
        ConvertFrom-Json
    $parts = [System.Collections.Generic.List[string]]::new()
    # 不使用 IF NOT EXISTS：已有库必须停止，不把初始化变成隐式升级。
    $parts.Add("CREATE DATABASE $DatabaseName CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;")
    foreach ($relativePath in $manifest.businessScripts) {
        $sql = [IO.File]::ReadAllText((Join-Path $repository $relativePath))
        if (-not $sql.Contains('USE dhu_secondhand;')) { throw "Unexpected schema script: $relativePath" }
        $parts.Add("-- Source: $relativePath`n" + $sql.Replace('USE dhu_secondhand;', "USE $DatabaseName;"))
    }
    $parts.Add("USE $DatabaseName;")
    $parts.Add("-- Source: $($manifest.runtimeScript)`n" +
        [IO.File]::ReadAllText((Join-Path $repository $manifest.runtimeScript)))
    return $parts -join "`n"
}

function Invoke-DhuMySql {
    param(
        [Parameter(Mandatory)][string]$Sql,
        [string]$DatabaseUser = 'root',
        [string]$DatabaseHost = 'localhost',
        [ValidateRange(1, 65535)][int]$DatabasePort = 3306,
        [string]$MySql = 'mysql'
    )
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
        # 密码仅由 mysql 从控制台读取；不接收密码参数，也不打印连接凭据。
        if (-not $process.Start()) { throw 'Could not start mysql.' }
        $process.StandardInput.Write($Sql)
        $process.StandardInput.Close()
        $process.WaitForExit()
        if ($process.ExitCode -ne 0) { throw 'Database operation failed. Stop here and inspect the MySQL error.' }
    } finally {
        $process.Dispose()
    }
}
