# Zyro Phase 1 build + local unit-test checkpoint.
# Non-destructive: this script does not install an APK, clear app data,
# reset Device Owner, reboot a device, or mutate device state.
param(
    [switch]$Clean
)

$ErrorActionPreference = 'Stop'
$ProjectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$Gradle = Join-Path $ProjectRoot 'gradlew.bat'
$Apk = Join-Path $ProjectRoot 'app\build\outputs\apk\debug\app-debug.apk'

if (-not (Test-Path $Gradle)) {
    throw "gradlew.bat was not found at $Gradle"
}

Push-Location $ProjectRoot
try {
    $tasks = @()
    if ($Clean) { $tasks += 'clean' }
    $tasks += ':app:testDebugUnitTest'
    $tasks += ':app:assembleDebug'
    $tasks += '--stacktrace'

    Write-Host "[Phase1] Running: gradlew.bat $($tasks -join ' ')"
    & $Gradle @tasks
    if ($LASTEXITCODE -ne 0) {
        throw "Phase 1 build/unit checkpoint failed with exit code $LASTEXITCODE"
    }

    if (-not (Test-Path $Apk)) {
        throw "Gradle completed but expected APK was not found: $Apk"
    }

    $apkInfo = Get-Item $Apk
    Write-Host "[Phase1] GREEN - local unit tests and debug APK build completed."
    Write-Host "[Phase1] APK: $($apkInfo.FullName)"
    Write-Host "[Phase1] APK bytes: $($apkInfo.Length)"
    Write-Host "[Phase1] No device install/reset was performed."
} finally {
    Pop-Location
}
