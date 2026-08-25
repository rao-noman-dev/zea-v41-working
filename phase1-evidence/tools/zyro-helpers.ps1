# ZYRO Phase-1 REG helpers - TEST-ONLY TOOLING (no production impact)
param()
$script:ADB = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$script:SER = '10FE7N04C40001Y'
$script:PKG = 'com.raomuhammadnoman.zea'

function Adb { & $script:ADB -s $script:SER @args }

function Get-GoodDump {
    param([string]$OutFile, [int]$Tries = 4)
    for ($i = 1; $i -le $Tries; $i++) {
        $xml = Adb shell uiautomator dump /dev/tty 2>$null
        if ($xml -and $xml.Trim().StartsWith('<?xml')) { return $xml }
        Start-Sleep -Milliseconds 700
    }
    return $null
}

function Get-Focus { (Adb shell dumpsys window | Select-String 'mCurrentFocus').Line }

function Open-Zyro {
    Adb shell am force-stop $script:PKG
    Start-Sleep -Milliseconds 800
    Adb shell monkey -p $script:PKG -c android.intent.category.LAUNCHER 1 | Out-Null
    Start-Sleep -Milliseconds 2200
}

function Enter-ZyroPin {
    # Security boundary: never persist or auto-type the Zyro PIN from test tooling.
    # The human operator unlocks the physical device/app and explicitly resumes.
    Write-Host ''
    Write-Host '[ACTION REQUIRED] Unlock Zyro manually on the connected device.' -ForegroundColor Yellow
    Read-Host 'Press Enter only after the Zyro home screen is visible' | Out-Null
}

function Test-ZyroCrash {
    $cut = (Get-Date).AddMinutes(-3)
    $log = Adb shell logcat -d -t 500 2>$null
    $hits = $log | Select-String 'FATAL EXCEPTION|Process: com.raomuhammadnoman.zea|AndroidRuntime.*com.raomuhammadnoman.zea'
    return $hits
}
