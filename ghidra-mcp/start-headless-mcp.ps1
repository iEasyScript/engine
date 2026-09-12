# Windows counterpart of start-headless-mcp.sh. Starts the headless GhidraMCP host in the
# background and waits until it is serving.
#   .\start-headless-mcp.ps1 -Write rs2client.exe.949-5[,rs2client.949-5] [-Read rs2client.949-1]
# Serves each writable target from 8080 upwards, then each read-only program on the ports after.
# bridge_mcp_ghidra.py (scanning 8080-8089) and every mcp__ghidra__* tool then work with no GUI.

[CmdletBinding()]
param(
    [string[]] $Write = @(),
    [string[]] $Read  = @()
)

$ErrorActionPreference = 'Stop'

function Get-Fallback($value, $fallback) {
    if ([string]::IsNullOrWhiteSpace($value)) { return $fallback }
    return $value
}

$Ghidra      = Get-Fallback $env:GHIDRA_INSTALL_DIR "$env:USERPROFILE\ghidra\ghidra_12.1.2_PUBLIC"
$ProjectDir  = Get-Fallback $env:GHIDRA_PROJECT_DIR "$env:USERPROFILE\ghidra-proj"
$ProjectName = Get-Fallback $env:GHIDRA_PROJECT_NAME 'nxt'
$Venv        = Get-Fallback $env:PGVENV "$env:USERPROFILE\ghidra\pgvenv"
$BasePort    = [int](Get-Fallback $env:BASE_PORT '8080')
$RunDir      = Get-Fallback $env:GHIDRA_MCP_RUN_DIR "$env:LOCALAPPDATA\ghidra-mcp-headless"

$Here    = Split-Path -Parent $MyInvocation.MyCommand.Path
$PidFile = Join-Path $RunDir 'host.pid'
$LogFile = Join-Path $RunDir 'host.log'
$ErrFile = Join-Path $RunDir 'host.err.log'

if (-not (Test-Path $Ghidra)) { throw "GHIDRA_INSTALL_DIR not found: $Ghidra" }
if ($Write.Count -eq 0) { throw "usage: .\start-headless-mcp.ps1 -Write rs2client.exe.<new>[,<more>] [-Read rs2client.<old>[,<more>]]" }

New-Item -ItemType Directory -Force -Path $RunDir | Out-Null

if (Test-Path $PidFile) {
    $existing = Get-Process -Id ([int](Get-Content $PidFile)) -ErrorAction SilentlyContinue
    if ($existing) { throw "headless host already running (pid $($existing.Id)); stop it first." }
}

$VenvPython = Join-Path $Venv 'Scripts\python.exe'
if (-not (Test-Path $VenvPython)) {
    Write-Host "== creating pyghidra venv at $Venv =="
    & python -m venv $Venv
    if ($LASTEXITCODE -ne 0) { throw "venv creation failed" }
    & (Join-Path $Venv 'Scripts\pip.exe') install --no-index `
        --find-links (Join-Path $Ghidra 'Ghidra\Features\PyGhidra\pypkg\dist') pyghidra
    if ($LASTEXITCODE -ne 0) { throw "pyghidra install failed" }
}

# Single-process project lock: refuse while a live process holds it; clear a stale one. The GUI,
# analyzeHeadless and this host are mutually exclusive on a local project.
$Lock = Join-Path $ProjectDir "$ProjectName.lock"
if (Test-Path $Lock) {
    # The GUI runs as javaw.exe (launch.bat appends the 'w' in background mode), so filtering on
    # java.exe alone would take an open CodeBrowser's live lock for a stale one and delete it.
    $holders = Get-CimInstance Win32_Process -Filter "Name='java.exe' OR Name='javaw.exe' OR Name='python.exe'" -ErrorAction SilentlyContinue |
        Where-Object { $_.CommandLine -match 'GhidraRun|analyzeHeadless|mcp_headless_host' }
    if ($holders) { throw "project lock held by a live process (GUI / analyzeHeadless / host); stop it first." }
    Write-Host "removing stale project lock: $Lock"
    Remove-Item $Lock, "$Lock~" -Force -ErrorAction SilentlyContinue
}

$hostArgs = @(
    (Join-Path $Here 'mcp_headless_host.py')
    '--project-dir',  $ProjectDir
    '--project-name', $ProjectName
)
foreach ($w in $Write) { $hostArgs += @('--write', $w) }
foreach ($r in $Read)  { $hostArgs += @('--read',  $r) }
$hostArgs += @('--base-port', "$BasePort")

Write-Host "== starting headless MCP host: $($Write -join ', ') (writable from :$BasePort) $($Read -join ', ') =="
$env:GHIDRA_INSTALL_DIR = $Ghidra
# Start-Process does not quote array elements, so a path containing a space (e.g. a repo
# checked out under "Project X") would reach python split in two.
$hostArgs = $hostArgs | ForEach-Object { if ($_ -match '\s') { '"' + $_ + '"' } else { $_ } }
$proc = Start-Process -FilePath $VenvPython -ArgumentList $hostArgs -WindowStyle Hidden -PassThru `
    -RedirectStandardOutput $LogFile -RedirectStandardError $ErrFile
$proc.Id | Out-File -Encoding ascii $PidFile

function Get-Info($port) {
    try { return (Invoke-WebRequest -Uri "http://127.0.0.1:$port/info" -UseBasicParsing -TimeoutSec 2).Content }
    catch { return $null }
}

for ($i = 0; $i -lt 90; $i++) {
    if (-not (Get-Process -Id $proc.Id -ErrorAction SilentlyContinue)) {
        Write-Host "host exited during startup; last log:" -ForegroundColor Red
        Get-Content $LogFile, $ErrFile -Tail 20 -ErrorAction SilentlyContinue
        Remove-Item $PidFile -Force -ErrorAction SilentlyContinue
        exit 1
    }
    $info = Get-Info $BasePort
    if ($info -and $info -match 'status=ok') {
        Write-Host "== headless MCP ready ==" -ForegroundColor Green
        $last = $BasePort + $Write.Count + $Read.Count - 1
        foreach ($p in $BasePort..$last) {
            $pi = Get-Info $p
            if ($pi -match '(?m)^domain_file_name=(.+)$') { Write-Host ("  :{0} -> {1}" -f $p, $Matches[1].Trim()) }
        }
        Write-Host "(log: $LogFile  pid: $($proc.Id))"
        exit 0
    }
    Start-Sleep -Seconds 1
}

Write-Host "timed out waiting for /info; last log:" -ForegroundColor Red
Get-Content $LogFile, $ErrFile -Tail 20 -ErrorAction SilentlyContinue
exit 1
