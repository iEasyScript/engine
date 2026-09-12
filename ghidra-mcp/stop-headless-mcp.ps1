# Windows counterpart of stop-headless-mcp.sh. Stops the headless GhidraMCP host: final-saves the
# writable program via /shutdown, releases the project lock so the GUI can open it, and confirms
# the lock is gone.

[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'

function Get-Fallback($value, $fallback) {
    if ([string]::IsNullOrWhiteSpace($value)) { return $fallback }
    return $value
}

$ProjectDir  = Get-Fallback $env:GHIDRA_PROJECT_DIR "$env:USERPROFILE\ghidra-proj"
$ProjectName = Get-Fallback $env:GHIDRA_PROJECT_NAME 'nxt'
$BasePort    = [int](Get-Fallback $env:BASE_PORT '8080')
$RunDir      = Get-Fallback $env:GHIDRA_MCP_RUN_DIR "$env:LOCALAPPDATA\ghidra-mcp-headless"
$PidFile     = Join-Path $RunDir 'host.pid'

if (-not (Test-Path $PidFile)) {
    Write-Host "no pidfile ($PidFile); host not tracked as running."
} else {
    $hostPid = [int](Get-Content $PidFile)
    Write-Host "== graceful shutdown via /shutdown (final save) =="
    try { Invoke-WebRequest -Uri "http://127.0.0.1:$BasePort/shutdown" -Method Post -UseBasicParsing -TimeoutSec 5 | Out-Null } catch {}

    for ($i = 0; $i -lt 30; $i++) {
        if (-not (Get-Process -Id $hostPid -ErrorAction SilentlyContinue)) { Write-Host "host exited."; break }
        Start-Sleep -Seconds 1
    }
    if (Get-Process -Id $hostPid -ErrorAction SilentlyContinue) {
        # A venv python.exe is a launcher stub that re-execs the base interpreter as a child, so the
        # JVM actually holding the project lock is that child rather than the pid we tracked.
        $tree = @($hostPid) + @(Get-CimInstance Win32_Process -Filter "ParentProcessId=$hostPid" -ErrorAction SilentlyContinue |
            Select-Object -ExpandProperty ProcessId)
        Write-Host "still alive; terminating $($tree -join ', ') (may skip final save)" -ForegroundColor Yellow
        foreach ($id in $tree) { Stop-Process -Id $id -Force -ErrorAction SilentlyContinue }
        for ($i = 0; $i -lt 15; $i++) {
            if (-not ($tree | Where-Object { Get-Process -Id $_ -ErrorAction SilentlyContinue })) { break }
            Start-Sleep -Seconds 1
        }
    }
    Remove-Item $PidFile -Force -ErrorAction SilentlyContinue
}

$Lock = Join-Path $ProjectDir "$ProjectName.lock"
$stillRunning = Get-CimInstance Win32_Process -Filter "Name='python.exe'" -ErrorAction SilentlyContinue |
    Where-Object { $_.CommandLine -match 'mcp_headless_host' }
if ((Test-Path $Lock) -and -not $stillRunning) {
    Write-Host "clearing residual project lock: $Lock"
    Remove-Item $Lock, "$Lock~" -Force -ErrorAction SilentlyContinue
}
if ($stillRunning) {
    Write-Host "a headless host process survived; the project lock is NOT free." -ForegroundColor Red
    exit 1
}
Write-Host "== stopped; project is free for the GUI ==" -ForegroundColor Green
