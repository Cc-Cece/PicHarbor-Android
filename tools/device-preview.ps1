param([string]$Serial='691a42fe')
$ErrorActionPreference='Stop'
$taskRoot=Split-Path -Parent $PSScriptRoot
$taskState=Join-Path $env:LOCALAPPDATA 'PicHarborMobileBuild\device-preview'
New-Item -ItemType Directory -Path $taskState -Force | Out-Null
$taskMutex=[Threading.Mutex]::new($false,'Local\PicHarborMobileDevicePreview')
if(-not $taskMutex.WaitOne(0)){exit 0}
$taskStarted=[DateTimeOffset]::Now
$taskLog=Join-Path $taskState ($taskStarted.ToString('yyyyMMdd-HHmmss')+'.log')
$taskStatus=[ordered]@{started=$taskStarted.ToString('o');device=$Serial;status='building';applicationId='com.cccece.picharbor.mobile';log=$taskLog}
function Save-TaskStatus {
    $taskStatus | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $taskState 'latest.json') -Encoding UTF8
}
try {
    Save-TaskStatus
    $taskAdbCommand=Get-Command adb.exe -ErrorAction SilentlyContinue
    $taskAdb=if($taskAdbCommand){$taskAdbCommand.Source}else{Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'}
    if(-not (Test-Path -LiteralPath $taskAdb)){throw 'Android platform-tools adb.exe was not found; installation skipped.'}
    $taskDeviceState=(& $taskAdb -s $Serial get-state 2>$null)
    if($LASTEXITCODE -ne 0 -or ($taskDeviceState -join '').Trim() -ne 'device') {throw 'The selected device is disconnected or unauthorized; no installation attempted.'}
    $taskBuildProcess=Start-Process -FilePath (Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe') -ArgumentList @('-NoProfile','-NonInteractive','-ExecutionPolicy','Bypass','-WindowStyle','Hidden','-File',('"'+(Join-Path $PSScriptRoot 'build-release.ps1')+'"')) -PassThru -WindowStyle Hidden -RedirectStandardOutput $taskLog -RedirectStandardError ($taskLog+'.stderr')
    # Wait for the build script only; Gradle's reusable daemon must not hold the installation until it exits.
    if(-not $taskBuildProcess.WaitForExit(25*60*1000)){Stop-Process -Id $taskBuildProcess.Id -Force;throw 'Build exceeded 25 minutes; installation skipped.'}
    if($taskBuildProcess.ExitCode -ne 0){throw 'Build or verification failed; the installed application was preserved.'}
    $taskApk=Join-Path $taskRoot 'app\build\outputs\apk\release\app-release.apk'
    if(-not (Test-Path -LiteralPath $taskApk)){throw 'Expected APK is missing.'}
    $env:JAVA_HOME='C:\Program Files\Java\jdk-17'
    $env:ANDROID_HOME=Join-Path $env:LOCALAPPDATA 'Android\Sdk'
    & python (Join-Path $PSScriptRoot 'verify_signing.py') --apk $taskApk >> $taskLog 2>&1
    if($LASTEXITCODE -ne 0){throw 'APK signature verification failed; no installation attempted.'}
    $taskStatus.status='installing';Save-TaskStatus
    $taskInstall=& $taskAdb -s $Serial install -r $taskApk 2>&1
    $taskInstall | Add-Content -LiteralPath $taskLog
    if($LASTEXITCODE -ne 0 -or ($taskInstall -join ' ') -notmatch '\bSuccess\b'){throw 'ADB installation did not succeed. No uninstall or data clearing was attempted.'}
    $taskStatus.status='installed'
    $taskStatus.apk=$taskApk
    $taskStatus.sha256=(Get-FileHash -LiteralPath $taskApk -Algorithm SHA256).Hash.ToLowerInvariant()
} catch {
    $taskStatus.status='failed'
    $taskStatus.error=$_.Exception.Message
    $_.Exception.Message | Add-Content -LiteralPath $taskLog
} finally {
    $taskStatus.finished=[DateTimeOffset]::Now.ToString('o');Save-TaskStatus
    $taskMutex.ReleaseMutex();$taskMutex.Dispose()
}
if($taskStatus.status -ne 'installed'){exit 1}
