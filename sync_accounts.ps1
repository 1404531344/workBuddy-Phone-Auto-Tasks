# Sync BuddySwitch accounts -> phone import.json -> adb push
# ASCII only on purpose: PS 5.1 parses this file safely even without BOM.
param(
    [string]$Serial = ""
)

$ErrorActionPreference = 'Continue'

$adb = 'D:\Android\platform-tools\adb.exe'
$src = Join-Path $env:USERPROFILE '.buddy-switch\accounts.json'
$out = 'D:\Tools\autotask\import.json'

if (-not (Test-Path $src)) {
    Write-Host "accounts.json not found: $src"
    exit 1
}

$raw = Get-Content -LiteralPath $src -Raw -Encoding UTF8 | ConvertFrom-Json
$arr = if ($raw -is [array]) { $raw } else { $raw.accounts }
if (-not $arr) { $arr = @() }

$list = @()
foreach ($a in $arr) {
    $uid = $a.uid
    if (-not $uid) { $uid = $a.userId }
    if (-not $uid) { continue }

    # display name: nickname > label > email > enterpriseName
    $lb = $a.nickname
    if (-not $lb) { $lb = $a.label }
    if (-not $lb) { $lb = $a.email }
    if (-not $lb) { $lb = $a.enterpriseName }
    if (-not $lb) { $lb = '' }

    $ent = $a.enterpriseId
    if (-not $ent) { $ent = '' }

    $list += [pscustomobject]@{
        uid             = [string]$uid
        label           = [string]$lb
        refresh_token   = [string]$a.refresh_token
        access_token    = [string]$a.access_token
        enterpriseId    = [string]$ent
        expiresAt       = [int64]0
        refreshExpiresAt= [int64]0
    }
}

# keep existing expiresAt values if present in accounts.json
for ($i = 0; $i -lt $arr.Count; $i++) {
    if ($arr[$i].expiresAt) { $list[$i].expiresAt = [int64]$arr[$i].expiresAt }
    if ($arr[$i].refreshExpiresAt) { $list[$i].refreshExpiresAt = [int64]$arr[$i].refreshExpiresAt }
}

$json = $list | ConvertTo-Json -Depth 5
[IO.File]::WriteAllText($out, $json, [Text.UTF8Encoding]::new($false))

$bytes = (Get-Item $out).Length
Write-Host ("generated {0} ({1} accounts, {2} bytes)" -f $out, $list.Count, $bytes)
foreach ($x in $list) {
    Write-Host ("  {0}  label=[{1}]" -f $x.uid, $x.label)
}

if (-not (Test-Path $adb)) {
    Write-Host "adb not found, skip push"
    exit 0
}

$devices = @()
if ($Serial) {
    $devices = @($Serial)
} else {
    $lines = & $adb devices
    foreach ($ln in $lines) {
        if ($ln -match '^(\S+)\s+device$') { $devices += $Matches[1] }
    }
}

if ($devices.Count -eq 0) {
    Write-Host "no device online, skip push"
    exit 0
}

foreach ($d in $devices) {
    & $adb -s $d shell "mkdir -p /sdcard/Android/data/com.fz.autotask/files" | Out-Null
    & $adb -s $d push $out /sdcard/Android/data/com.fz.autotask/files/import.json 2>&1 | Out-String | Write-Host
    Write-Host ("pushed to {0}" -f $d)
}

Write-Host "done. open the app on the phone; it auto-imports when the file changes."
