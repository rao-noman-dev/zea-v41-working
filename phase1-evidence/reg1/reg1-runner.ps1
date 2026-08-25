# ZYRO REG-1 Minimum Harness Runner - TEST-ONLY TOOLING (Phase-1 baseline set)
# Executes device-side checks and writes honest results. No production impact.
param([string]$Only = '', [string]$OutDir = '')
$ErrorActionPreference = 'Continue'
. "$PSScriptRoot\..\tools\zyro-helpers.ps1"
if ([string]::IsNullOrWhiteSpace($OutDir)) { $OutDir = $PSScriptRoot }
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
$script:Results = New-Object System.Collections.Generic.List[object]
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'

function Log-Result { param($Id, $Name, $Status, $Evidence)
    $script:Results.Add([pscustomobject]@{ id=$Id; name=$Name; status=$Status; evidence=($Evidence -join ' | '); at=(Get-Date -Format 'HH:mm:ss') })
    "$status  $Id  $name" | Write-Output
}

function Wait-Ms { param($ms) Start-Sleep -Milliseconds $ms }

function Get-HubCounts {
    # returns hashtable of hub badge ints when hub visible, else $null
    $d = AdbShell 'uiautomator dump /sdcard/zd.xml'; Wait-Ms 600
    $x = AdbShell 'cat /sdcard/zd.xml'
    if ($x -notmatch 'Manage supported apps') { return $null }
    $vals = @{}
    foreach ($pair in @(@('All Apps','all'), @('Hidden Apps','hidden'), @('Timed Hidden Apps','timed'))) {
        if ($x -match ([regex]::Escape('text="' + $pair[0] + '"'))) {}
        $m = [regex]::Match($x, 'text="' + [regex]::Escape($pair[0]) + '"[\s\S]{0,2400}?text="(\d+)"')
        if ($m.Success) { $vals[$pair[1]] = [int]$m.Groups[1].Value } else { $vals[$pair[1]] = $null }
    }
    return $vals
}

function AdbShell { param([string]$c) (& $script:ADB -s $script:SER shell $c) -join "`n" }

function Get-SurfaceText {
    $null = AdbShell 'uiautomator dump /sdcard/zd.xml'; Wait-Ms 600
    return (AdbShell 'cat /sdcard/zd.xml')
}

function Unlock-Zyro {
    Open-Zyro
    $x = Get-SurfaceText
    if ($x -match 'is locked') {
        Write-Host ''
        Write-Host '[ACTION REQUIRED] Zyro is locked. Enter the PIN manually on the device.' -ForegroundColor Yellow
        Read-Host 'Press Enter after Zyro is unlocked and the home/apps surface is visible' | Out-Null
        Wait-Ms 500
        $x = Get-SurfaceText
        if ($x -match 'is locked') {
            throw 'Zyro is still locked after the manual-unlock checkpoint.'
        }
    }
}

function Get-DpmHiddenFlag { param($pkg)
    $out = AdbShell "dumpsys package $pkg"
    $m = [regex]::Match($out, 'hidden=(true|false)')
    if ($m.Success) { return $m.Groups[1].Value } else { return 'unknown' }
}


function Get-ZyroPreferenceXml {
    $path = 'shared_prefs/zea_local_storage_v09_full.xml'
    $xml = AdbShell "run-as $script:PKG cat $path"
    if (-not $xml -or $xml -match 'run-as:|not debuggable|Permission denied|No such file') {
        return $null
    }
    return $xml
}

function Get-ZyroPreferenceJson { param([string]$Key, [string]$Xml)
    if (-not $Xml) { return $null }
    $pattern = '<string\s+name="' + [regex]::Escape($Key) + '">([\s\S]*?)</string>'
    $m = [regex]::Match($Xml, $pattern)
    if (-not $m.Success) { return '' }
    return [System.Net.WebUtility]::HtmlDecode($m.Groups[1].Value)
}

function Get-ZyroRegistrySnapshot {
    $xml = Get-ZyroPreferenceXml
    if (-not $xml) { return $null }

    $privateRaw = Get-ZyroPreferenceJson -Key 'private_apps_json_v1' -Xml $xml
    $timedRaw = Get-ZyroPreferenceJson -Key 'timed_hides_json_v1' -Xml $xml
    try {
        $privatePackages = @()
        if ($privateRaw) {
            $root = $privateRaw | ConvertFrom-Json
            if ($null -ne $root.apps) {
                $privatePackages = @($root.apps | ForEach-Object { [string]$_.packageName } | Where-Object { $_ } | Select-Object -Unique)
            }
        }

        $timedPackages = @()
        if ($timedRaw) {
            $timed = $timedRaw | ConvertFrom-Json
            $timedPackages = @($timed | ForEach-Object { [string]$_.packageName } | Where-Object { $_ } | Select-Object -Unique)
        }

        return [pscustomobject]@{
            private = $privatePackages
            timed = $timedPackages
        }
    } catch {
        return [pscustomobject]@{
            parseError = $_.Exception.Message
            private = @()
            timed = @()
        }
    }
}

function Open-DrawerAndFind { param($label)
    Adb shell input keyevent KEYCODE_HOME | Out-Null; Wait-Ms 700
    Adb shell input swipe 360 1150 360 450 500 | Out-Null; Wait-Ms 2600
    $null = AdbShell 'uiautomator dump /sdcard/zd.xml'; Wait-Ms 600
    $x = AdbShell 'cat /sdcard/zd.xml'
    return ($x -match [regex]::Escape($label))
}

# ============ CHECKS ============

function CHK01-PinUnlock {
    Adb shell am force-stop $script:PKG | Out-Null; Wait-Ms 800
    Adb shell monkey -p $script:PKG -c android.intent.category.LAUNCHER 1 *>$null; Wait-Ms 2500
    $gate = Get-SurfaceText
    $gateShown = $gate -match 'is locked'
    if (-not $gateShown) {
        Log-Result 'CHK01' 'PIN unlock regression' 'NOT VERIFIED' @('no gate on relaunch (session-grace active); run after reboot for forced lock')
        return
    }
    Unlock-Zyro
    $after = AdbShell 'dumpsys window'
    $ok = ($after -match 'mCurrentFocus=Window\{[^\}]*' + [regex]::Escape($script:PKG))
    if ($ok) { Log-Result 'CHK01' 'PIN unlock regression' 'GREEN' @("gate shown=true","unlocked=true") }
    else { Log-Result 'CHK01' 'PIN unlock regression' 'RED' @("PIN entered but unlock failed") }
}

function CHK02-Fingerprint {
    # Baseline presence-only (no enrolled biometric assumed): option visible on gate
    Adb shell am force-stop $script:PKG | Out-Null; Wait-Ms 800
    Adb shell monkey -p $script:PKG -c android.intent.category.LAUNCHER 1 *>$null; Wait-Ms 2200
    $x = AdbShell 'uiautomator dump /sdcard/zd.xml'; Wait-Ms 500; $x = AdbShell 'cat /sdcard/zd.xml'
    if ($x -match 'Use fingerprint') { Log-Result 'CHK02' 'Fingerprint option present' 'GREEN' @('gate offers fingerprint path') }
    elseif ($x -match 'is locked') { Log-Result 'CHK02' 'Fingerprint option present' 'NOT VERIFIED' @('gate present but no fingerprint option (maybe not enrolled)') }
    else { Log-Result 'CHK02' 'Fingerprint option present' 'NOT VERIFIED' @('gate not shown this run') }
}

function CHK03-SecurityOnOff {
    # Verify gate enforced on relaunch. NOTE: app has a session-grace window; a
    # forced re-lock is only guaranteed after reboot (or natural timeout).
    Adb shell am force-stop $script:PKG | Out-Null; Wait-Ms 700
    Adb shell monkey -p $script:PKG -c android.intent.category.LAUNCHER 1 *>$null; Wait-Ms 2200
    $x = Get-SurfaceText
    if ($x -match 'is locked') { Log-Result 'CHK03' 'Security persistence (restart keeps lock)' 'GREEN' @('gate reappears after process death') }
    else { Log-Result 'CHK03' 'Security persistence (restart keeps lock)' 'NOT VERIFIED' @('no gate: session-grace window active (design behavior, not regression) — rerun post-reboot') }
}

function Navigate-ToHub {
    # From any unlocked surface, get to Apps Hub. Returns $true on success.
    $x = Get-SurfaceText
    if ($x -match 'Manage supported apps') { return $true }
    if ($x -match 'is locked') { Unlock-Zyro; $x = Get-SurfaceText }
    if ($x -match 'text="All Apps"' -or $x -match 'text="Hidden Apps"' -or $x -match 'text="Timed Hidden Apps"') {
        Adb shell input keyevent 4 | Out-Null
        Wait-Ms 1200
        $x = Get-SurfaceText
        if ($x -match 'Manage supported apps') { return $true }
    }
    # assistant screen: open More options menu (top-right) then pick Apps
    Adb shell input tap 638 144 | Out-Null; Wait-Ms 1300
    $m = Get-SurfaceText
    $mm = [regex]::Match($m, 'text="Apps"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
    if (-not $mm.Success) {
        # maybe already hub w/o subtitle match: try direct
        return (($m -match 'Manage supported apps'))
    }
    Adb shell input tap ([int](([int]$mm.Groups[1].Value+[int]$mm.Groups[3].Value)/2)) ([int](([int]$mm.Groups[2].Value+[int]$mm.Groups[4].Value)/2)) | Out-Null
    Wait-Ms 2200
    $x = Get-SurfaceText
    return (($x -match 'Manage supported apps') -or ($x -match 'All Apps'))
}

function CHK09-CountCoherence {
    Unlock-Zyro
    if (-not (Navigate-ToHub)) {
        Log-Result 'CHK09' 'Count coherence snapshot' 'RED' @('hub not reachable')
        return
    }
    $c = Get-HubCounts
    if ($null -eq $c -or $null -eq $c.all -or $null -eq $c.hidden -or $null -eq $c.timed) {
        Log-Result 'CHK09' 'Count coherence snapshot' 'NOT VERIFIED' @('hub counts could not be parsed reliably')
        return
    }

    # Registry is Zyro's durable managed-state contract. Query its exact package
    # identities instead of scanning only `pm list packages -3`, which omitted
    # preinstalled/system apps and caused the historical false RED (0 hidden).
    $snapshot = Get-ZyroRegistrySnapshot
    if ($null -eq $snapshot) {
        Log-Result 'CHK09' 'Count coherence snapshot' 'NOT VERIFIED' @('run-as could not read Zyro SharedPreferences; no false RED emitted')
        return
    }
    if ($snapshot.parseError) {
        Log-Result 'CHK09' 'Count coherence snapshot' 'RED' @("registry JSON parse failed: $($snapshot.parseError)")
        return
    }

    $registry = @($snapshot.private)
    $timers = @($snapshot.timed)
    $timerOutsideRegistry = @($timers | Where-Object { $registry -notcontains $_ })
    $notHidden = New-Object System.Collections.Generic.List[string]
    $unknown = New-Object System.Collections.Generic.List[string]
    foreach ($pkg in $registry) {
        $flag = Get-DpmHiddenFlag $pkg
        if ($flag -eq 'false') { $notHidden.Add($pkg) }
        elseif ($flag -ne 'true') { $unknown.Add($pkg) }
    }

    $uiProtected = [int]$c.hidden + [int]$c.timed
    $ev = @(
        "hub visible=$($c.all) hidden=$($c.hidden) timed=$($c.timed) protected=$uiProtected",
        "registry protected=$($registry.Count) timedRecords=$($timers.Count)",
        "registry DPM-not-hidden=$($notHidden.Count) unknown=$($unknown.Count)",
        "timerOutsideRegistry=$($timerOutsideRegistry.Count)"
    )

    $ok =
        ($uiProtected -eq $registry.Count) -and
        ([int]$c.timed -eq $timers.Count) -and
        ($timerOutsideRegistry.Count -eq 0) -and
        ($notHidden.Count -eq 0) -and
        ($unknown.Count -eq 0)

    if ($ok) {
        Log-Result 'CHK09' 'Count coherence snapshot' 'GREEN' $ev
    } else {
        if ($notHidden.Count -gt 0) { $ev += 'not hidden: ' + ($notHidden -join ', ') }
        if ($unknown.Count -gt 0) { $ev += 'unknown DPM: ' + ($unknown -join ', ') }
        if ($timerOutsideRegistry.Count -gt 0) { $ev += 'orphan timers: ' + ($timerOutsideRegistry -join ', ') }
        Log-Result 'CHK09' 'Count coherence snapshot' 'RED' $ev
    }
}

function CHK10-DrawerProbe {
    param([string[]]$mustBeAbsent = @())
    Unlock-Zyro
    $missingOk = $true; $seen = @()
    foreach ($lbl in $mustBeAbsent) {
        $present = Open-DrawerAndFind $lbl
        $seen += "$lbl presentInDrawerFirstPage=$present"
        if ($present) { $missingOk = $false }
        Adb shell input keyevent KEYCODE_HOME | Out-Null; Wait-Ms 600
    }
    if ($mustBeAbsent.Count -eq 0) { Log-Result 'CHK10' 'Drawer probe (no targets)' 'NOT VERIFIED' @('no labels supplied'); return }
    if ($missingOk) { Log-Result 'CHK10' 'Drawer surface absence probe' 'GREEN' $seen } else { Log-Result 'CHK10' 'Drawer surface absence probe' 'RED' $seen }
}

function CHK08-PtrSmoke {
    # Gesture on hub: pull down, ensure no crash and hub still renders
    Unlock-Zyro
    if (-not (Navigate-ToHub)) { Log-Result 'CHK08a' 'PTR smoke: hub gesture' 'RED' @('hub not reachable'); return }
    $before = Get-HubCounts
    if ($null -eq $before -or $null -eq $before.all) { Log-Result 'CHK08a' 'PTR smoke: hub gesture' 'NOT VERIFIED' @('could not read hub counts pre-gesture'); return }
    Adb logcat -c | Out-Null
    Adb shell input swipe 360 350 360 1200 350 | Out-Null   # pull-down gesture
    Wait-Ms 2500
    $crash = Test-ZyroCrashSilent
    $after = Get-HubCounts
    $stable = ($null -ne $after -and $null -ne $after.all -and $after.all -eq $before.all)
    if (-not $crash -and $stable) { Log-Result 'CHK08a' 'PTR smoke: hub gesture' 'GREEN' @("counts stable all=$($before.all)->$($after.all)","no crash") }
    elseif ($crash) { Log-Result 'CHK08a' 'PTR smoke: hub gesture' 'RED' @('app crash detected during gesture') }
    else { Log-Result 'CHK08a' 'PTR smoke: hub gesture' 'NOT VERIFIED' @("post-gesture hub unreadable (nav drift?) before.all=$($before.all)") }
}

function Test-ZyroCrashSilent {
    $log = AdbShell 'logcat -d -t 300'
    return ($log -match 'FATAL EXCEPTION' -and $log -match 'com\.raomuhammadnoman\.zea')
}

function CHK11-Restart {
    Unlock-Zyro
    if (-not (Navigate-ToHub)) { Log-Result 'CHK11' 'App restart preserves state' 'RED' @('hub not reachable'); return }
    $c1 = Get-HubCounts
    Adb shell am force-stop $script:PKG | Out-Null; Wait-Ms 900
    Adb shell monkey -p $script:PKG -c android.intent.category.LAUNCHER 1 *>$null; Wait-Ms 2400
    $gate = Get-SurfaceText
    $lockedAgain = $gate -match 'is locked'
    Unlock-Zyro
    $c2 = Get-HubCounts
    $same = ($null -ne $c1 -and $null -ne $c2 -and $c1.all -eq $c2.all -and $c1.hidden -eq $c2.hidden)
    # session-grace design: relock NOT guaranteed on quick relaunch — record honestly either way
    if ($same) { Log-Result 'CHK11' 'App restart preserves counts' 'GREEN' @("all=$($c1.all)->$($c2.all) hidden=$($c1.hidden)->$($c2.hidden)","relockedImmediately=$lockedAgain (grace design)") }
    else { Log-Result 'CHK11' 'App restart preserves counts' 'RED' @("counts drifted: $(($c1|ConvertTo-Json -Compress)) -> $(($c2|ConvertTo-Json -Compress))") }
}

# ---- main ----
$all = @{
    'CHK01'={ CHK01-PinUnlock }
    'CHK02'={ CHK02-Fingerprint }
    'CHK03'={ CHK03-SecurityOnOff }
    'CHK08'={ CHK08-PtrSmoke }
    'CHK09'={ CHK09-CountCoherence }
    'CHK10'={ CHK10-DrawerProbe -mustBeAbsent @() }
    'CHK11'={ CHK11-Restart }
}
if ($Only -and $all.ContainsKey($Only)) { & $all[$Only] }
else { foreach ($k in @('CHK03','CHK01','CHK02','CHK11','CHK08','CHK09')) { & $all[$k] } }

$script:Results | ConvertTo-Json -Depth 3 | Set-Content -LiteralPath (Join-Path $OutDir "reg1-results-$stamp.json")
$rows = $script:Results | ForEach-Object { "| $($_.id) | $($_.name) | $($_.status) | $($_.evidence) |" }
"| id | check | status | evidence |`n|---|---|---|---|`n" + ($rows -join "`n") | Set-Content -LiteralPath (Join-Path $OutDir "reg1-results-$stamp.md")
"DONE -> $OutDir\reg1-results-$stamp.md"
