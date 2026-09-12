# build.ps1 - Build projectx_patcher.dll + projectx_injector.exe and deploy them
#
# Windows counterpart to the Linux patcher's build.sh. Same source-of-truth
# pattern: a 32-char "current rev" marker is checked against the SOURCE file
# (to fail fast if the source is stale) and against EVERY deployed copy of
# the DLL (to detect a stale copy shadowing a fresh build).
#
# Usage:  client/launcher/patcher-win/build.ps1
#         (from anywhere - all paths are derived from the script's location)

$ErrorActionPreference = 'Stop'

$DLL_NAME               = 'projectx_patcher.dll'
$INJECTOR_NAME          = 'projectx_injector.exe'
$PROJECTX_ENGINE_INJECTOR_NAME = 'projectx_engine_injector.exe'

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$CrateDir  = $ScriptDir
$TargetDir = Join-Path $CrateDir 'target\release'
$BuiltDll  = Join-Path $TargetDir $DLL_NAME
$BuiltExe  = Join-Path $TargetDir $INJECTOR_NAME

# --- Source-of-truth markers (must match lib.rs) ----------------------------
$CURRENT_PREFIX_FULL = 'b2c61c4772bf8882cab71c2b07ccac16'   # 950 login modulus prefix
$CURRENT_MARK        = 'b2c61c47'                          # first 8 chars - contiguous in compiled .dll
$OLD_MARK            = '83fec000'                          # rev 949 prefix - must NOT appear

# The key prefixes live in the shared patcher-common crate, which all three
# platform patchers compile in.
$SrcLib = Join-Path $CrateDir '..\patcher-common\src\lib.rs'

function Fail($msg) { Write-Host "ERROR: $msg" -ForegroundColor Red; exit 1 }
function Ok($msg)   { Write-Host $msg -ForegroundColor Green }
function Bold($msg) { Write-Host $msg -ForegroundColor Cyan }

# --- Pre-flight: confirm source is on the expected revision ------------------
if (-not (Test-Path $SrcLib)) { Fail "patcher source not found at $SrcLib" }
$srcText = Get-Content -Raw $SrcLib
if ($srcText -notmatch [regex]::Escape($CURRENT_PREFIX_FULL)) {
    Fail "$SrcLib does not contain the current login prefix ($CURRENT_PREFIX_FULL). The source is not on the expected revision - refusing to build/deploy a stale patcher."
}
if ($srcText -match [regex]::Escape($OLD_MARK)) {
    Fail "$SrcLib still references the OLD login prefix ($OLD_MARK). Update lib.rs before deploying."
}

# --- Build -------------------------------------------------------------------
Bold "==> Building $DLL_NAME + $INJECTOR_NAME (cargo build --release)"
Write-Host "    crate: $CrateDir"
# cargo writes progress lines to stderr; PowerShell 5.1 with
# $ErrorActionPreference='Stop' wraps each stderr line in a NativeCommandError
# record and aborts the script even when cargo's exit code is 0. Locally
# downgrade ErrorAction to Continue across the cargo invocation, then key off
# $LASTEXITCODE for the real success/failure signal.
$prevErrAction = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
try {
    & cargo build --release --manifest-path (Join-Path $CrateDir 'Cargo.toml')
} finally {
    $ErrorActionPreference = $prevErrAction
}
if ($LASTEXITCODE -ne 0) { Fail "cargo build failed (exit=$LASTEXITCODE)" }

if (-not (Test-Path $BuiltDll)) { Fail "expected build artifact missing: $BuiltDll" }
if (-not (Test-Path $BuiltExe)) { Fail "expected build artifact missing: $BuiltExe" }
$BuiltProjectXExe = Join-Path $TargetDir $PROJECTX_ENGINE_INJECTOR_NAME
if (-not (Test-Path $BuiltProjectXExe)) { Fail "expected build artifact missing: $BuiltProjectXExe" }

# --- Marker check on built DLL ----------------------------------------------
function Search-BinaryAscii([string]$path, [string]$ascii) {
    $needle = [System.Text.Encoding]::ASCII.GetBytes($ascii)
    $bytes  = [System.IO.File]::ReadAllBytes($path)
    $hits   = 0
    for ($i = 0; $i -le ($bytes.Length - $needle.Length); $i++) {
        $match = $true
        for ($j = 0; $j -lt $needle.Length; $j++) {
            if ($bytes[$i + $j] -ne $needle[$j]) { $match = $false; break }
        }
        if ($match) { $hits++ }
    }
    return $hits
}

$curHits = Search-BinaryAscii $BuiltDll $CURRENT_MARK
$oldHits = Search-BinaryAscii $BuiltDll $OLD_MARK
if ($curHits -lt 1) { Fail "freshly built $BuiltDll does not contain current marker '$CURRENT_MARK' - build is wrong." }
if ($oldHits -ne 0) { Fail "freshly built $BuiltDll still contains OLD marker '$OLD_MARK' - build is stale/wrong." }
Ok ("    built OK: $BuiltDll (cur={0}, old={1})" -f $curHits, $oldHits)
Ok ("    built OK: $BuiltExe")

# --- Deploy slots -----------------------------------------------------------
# The injector defaults to looking next to its own .exe, then in
# %APPDATA%\ProjectX, then %LOCALAPPDATA%\ProjectX. Drop the DLL into all
# three so users can move the injector anywhere and it still finds the DLL.
$AppData      = $env:APPDATA
$LocalAppData = $env:LOCALAPPDATA

# find_deploy_artifact (client/launcher/src/game/process.rs) probes data/client/<os> FIRST, and the
# config server hands that same copy to the launcher's per-mode data dir. A stale build sitting there
# therefore wins over every fresh one and the client launches unpatched -- rs3windows then rejects the
# binary it downloaded and exits without ever opening a window. Derive the repo root from this
# script's own location so the checkout can live anywhere.
$RepoRoot   = (Resolve-Path (Join-Path $ScriptDir '..\..\..')).Path
$ClientSlot = Join-Path $RepoRoot 'data\client\windows'

$Destinations = @(
    @{ Dir = $TargetDir;                          Files = @($DLL_NAME, $INJECTOR_NAME, $PROJECTX_ENGINE_INJECTOR_NAME) }
    @{ Dir = $ClientSlot;                         Files = @($DLL_NAME, $INJECTOR_NAME, $PROJECTX_ENGINE_INJECTOR_NAME) }
    @{ Dir = (Join-Path $AppData 'ProjectX');      Files = @($DLL_NAME) }
    @{ Dir = (Join-Path $LocalAppData 'ProjectX'); Files = @($DLL_NAME) }
)

Bold "==> Deploying"
foreach ($dest in $Destinations) {
    $dir = $dest.Dir
    if (-not (Test-Path $dir)) { New-Item -ItemType Directory -Path $dir -Force | Out-Null }
    foreach ($file in $dest.Files) {
        $src = Join-Path $TargetDir $file
        $dst = Join-Path $dir       $file
        if ($src -ieq $dst) { Write-Host "    skip (build artifact): $dst"; continue }
        Copy-Item -Force $src $dst
        Write-Host "    deployed: $dst"
    }
}

# --- Verify every deployed DLL copy -----------------------------------------
Bold "==> Verifying deployed DLL copies"
$verifyFailed = $false
foreach ($dest in $Destinations) {
    $dst = Join-Path $dest.Dir $DLL_NAME
    if (-not (Test-Path $dst)) { Write-Host "    MISSING $dst" -ForegroundColor Red; $verifyFailed = $true; continue }
    $cur = Search-BinaryAscii $dst $CURRENT_MARK
    $old = Search-BinaryAscii $dst $OLD_MARK
    if ($cur -ge 1 -and $old -eq 0) {
        Write-Host ("    OK      {0}  (cur={1}, old={2})" -f $dst, $cur, $old) -ForegroundColor Green
    } else {
        Write-Host ("    BAD     {0}  (cur={1} expected>=1, old={2} expected 0)" -f $dst, $cur, $old) -ForegroundColor Red
        $verifyFailed = $true
    }
}
if ($verifyFailed) { Fail "one or more deployed patcher copies are missing or stale (see BAD/MISSING above)." }

Ok "==> Done. All copies of $DLL_NAME (marker $CURRENT_MARK) verified current."
