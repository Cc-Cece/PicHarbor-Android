param([switch]$DiagnosticsOnly, [switch]$DeviceTests, [switch]$Verify, [switch]$Lint, [string]$BuildPython = 'C:/Users/Kanbara/AppData/Local/Programs/Python/Python313/python.exe')
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$keyDirectory = Join-Path $env:LOCALAPPDATA 'PicHarborBuild\signing'
$keystoreFile = Join-Path $keyDirectory 'picharbor-release.jks'
$passwordFile = Join-Path $keyDirectory 'password.dpapi'
if (-not $env:JAVA_HOME) { $env:JAVA_HOME = 'C:\Program Files\Java\jdk-17' }
if (-not $env:ANDROID_HOME) { $env:ANDROID_HOME = Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
if (-not (Test-Path -LiteralPath $keystoreFile) -or -not (Test-Path -LiteralPath $passwordFile)) {
    throw 'Persistent signing key/password pair is required. Restore the existing identity; never generate a replacement for a published app.'
}
try {
    $secure = (Get-Content -LiteralPath $passwordFile -Raw).Trim() | ConvertTo-SecureString
    $keyPassword = [Net.NetworkCredential]::new('', $secure).Password
    $env:PICHARBOR_STORE_PASSWORD = $keyPassword
    $env:PICHARBOR_KEY_PASSWORD = $keyPassword
    & python (Join-Path $PSScriptRoot 'verify_signing.py') --keystore $keystoreFile
    if ($LASTEXITCODE -ne 0) { throw 'Release signing identity does not match the pinned certificate.' }
    $env:PICHARBOR_KEYSTORE = $keystoreFile
    Push-Location $projectRoot
    try {
        $tasks = @()
        if (-not $DiagnosticsOnly) { $tasks += ':app:assembleRelease' }
        if ($DeviceTests) { $tasks += @(':app:assembleDiagnostic', ':app:assembleDiagnosticAndroidTest') }
        if ($Verify) { $tasks += ':app:testReleaseUnitTest' }
        if ($Verify -or $Lint) { $tasks += ':app:lintRelease' }
        $previousErrorAction=$ErrorActionPreference
        try {
            $ErrorActionPreference='Continue'
            & .\gradlew.bat @tasks "-PbuildPython=$BuildPython" --console=plain
            $gradleResult=$LASTEXITCODE
        } finally { $ErrorActionPreference=$previousErrorAction }
        if ($gradleResult -ne 0) { throw 'Gradle failed. Signing identity has been preserved.' }
    } finally { Pop-Location }
    if (-not $DiagnosticsOnly) {
        & python (Join-Path $PSScriptRoot 'verify_signing.py') --apk (Join-Path $projectRoot 'app/build/outputs/apk/release/app-release.apk')
        if ($LASTEXITCODE -ne 0) { throw 'Signed APK identity verification failed.' }
    }
    Write-Output 'Release build complete. Signing identity is stored outside the project.'
} finally {
    Remove-Item Env:PICHARBOR_STORE_PASSWORD,Env:PICHARBOR_KEY_PASSWORD,Env:PICHARBOR_KEYSTORE -ErrorAction SilentlyContinue
    $keyPassword = $null
}
