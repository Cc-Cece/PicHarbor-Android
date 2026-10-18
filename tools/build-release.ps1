param([switch]$DiagnosticsOnly, [switch]$DeviceTests, [switch]$Verify, [switch]$Lint, [string]$BuildPython = 'C:/Users/Kanbara/AppData/Local/Programs/Python/Python313/python.exe')
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$keyDirectory = Join-Path $env:LOCALAPPDATA 'PicHarborBuild\signing'
$keystoreFile = Join-Path $keyDirectory 'picharbor-release.jks'
$passwordFile = Join-Path $keyDirectory 'password.dpapi'
if (-not $env:JAVA_HOME) { $env:JAVA_HOME = 'C:\Program Files\Java\jdk-17' }
if (-not $env:ANDROID_HOME) { $env:ANDROID_HOME = Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
New-Item -ItemType Directory -Path $keyDirectory -Force | Out-Null
if ((Test-Path -LiteralPath $keystoreFile) -xor (Test-Path -LiteralPath $passwordFile)) {
    throw 'Signing key/password pair is incomplete. Restore it; do not replace an existing release identity.'
}
$created = $false
try {
    if (-not (Test-Path -LiteralPath $keystoreFile)) {
        $random = New-Object byte[] 48
        [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($random)
        $keyPassword = [Convert]::ToBase64String($random)
        $env:PICHARBOR_STORE_PASSWORD = $keyPassword
        $env:PICHARBOR_KEY_PASSWORD = $keyPassword
        $ErrorActionPreference='Continue'
        & (Join-Path $env:JAVA_HOME 'bin\keytool.exe') -genkeypair -keystore $keystoreFile -storetype JKS -alias picharbor -keyalg RSA -keysize 3072 -sigalg SHA256withRSA -validity 10000 -dname 'CN=cc_cece,O=PicHarbor' -storepass:env PICHARBOR_STORE_PASSWORD -keypass:env PICHARBOR_KEY_PASSWORD -noprompt
        $ErrorActionPreference='Stop'
        if ($LASTEXITCODE -ne 0) { throw 'Could not create the release signing identity.' }
        $keyPassword | ConvertTo-SecureString -AsPlainText -Force | ConvertFrom-SecureString | Set-Content -LiteralPath $passwordFile -Encoding ascii
        $created = $true
    } else {
        $secure = (Get-Content -LiteralPath $passwordFile -Raw).Trim() | ConvertTo-SecureString
        $keyPassword = [Net.NetworkCredential]::new('', $secure).Password
        $env:PICHARBOR_STORE_PASSWORD = $keyPassword
        $env:PICHARBOR_KEY_PASSWORD = $keyPassword
    }
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
    Write-Output 'Release build complete. Signing identity is stored outside the project.'
} finally {
    Remove-Item Env:PICHARBOR_STORE_PASSWORD,Env:PICHARBOR_KEY_PASSWORD,Env:PICHARBOR_KEYSTORE -ErrorAction SilentlyContinue
    $keyPassword = $null
}
