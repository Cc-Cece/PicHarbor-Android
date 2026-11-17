param()
$ErrorActionPreference='Stop'
$taskSigningDirectory=Join-Path $env:LOCALAPPDATA 'PicHarborMobileBuild\signing'
$taskKey=Join-Path $taskSigningDirectory 'picharbor-release.jks'
$taskPassword=Join-Path $taskSigningDirectory 'password.dpapi'
$taskCertificate=Join-Path $PSScriptRoot 'release-signing.sha256'
if(-not $env:JAVA_HOME){$env:JAVA_HOME='C:\Program Files\Java\jdk-17'}
if((Test-Path -LiteralPath $taskKey) -or (Test-Path -LiteralPath $taskPassword)) {
    if(-not ((Test-Path -LiteralPath $taskKey) -and (Test-Path -LiteralPath $taskPassword))) {throw 'Incomplete signing identity. Restore the missing file; do not regenerate.'}
    try {
        $env:PICHARBOR_STORE_PASSWORD=[Net.NetworkCredential]::new('',((Get-Content -LiteralPath $taskPassword -Raw).Trim() | ConvertTo-SecureString)).Password
        & python (Join-Path $PSScriptRoot 'verify_signing.py') --keystore $taskKey
        if($LASTEXITCODE -ne 0){throw 'The retained key does not match the pinned identity.'}
    } finally {Remove-Item Env:PICHARBOR_STORE_PASSWORD -ErrorAction SilentlyContinue}
    exit
}
if(Test-Path -LiteralPath $taskCertificate) {
    throw 'This application already has a pinned identity. Restore its keystore and password; generating a replacement is forbidden.'
}
New-Item -ItemType Directory -Path $taskSigningDirectory -Force | Out-Null
# Restrict private material to this Windows identity and LocalSystem.
$taskIdentity=[Security.Principal.WindowsIdentity]::GetCurrent().Name
& icacls.exe $taskSigningDirectory /inheritance:r /grant:r "${taskIdentity}:(OI)(CI)F" 'SYSTEM:(OI)(CI)F' | Out-Null
if($LASTEXITCODE -ne 0){throw 'Could not restrict signing directory permissions.'}
$taskRandom=New-Object byte[] 48
$taskRng=[Security.Cryptography.RandomNumberGenerator]::Create()
$taskRng.GetBytes($taskRandom)
try {
    $env:PICHARBOR_STORE_PASSWORD=[Convert]::ToBase64String($taskRandom)
    $taskSecure=ConvertTo-SecureString $env:PICHARBOR_STORE_PASSWORD -AsPlainText -Force
    $taskSecure | ConvertFrom-SecureString | Set-Content -LiteralPath $taskPassword -Encoding ASCII
    $ErrorActionPreference='Continue'
    & (Join-Path $env:JAVA_HOME 'bin\keytool.exe') -genkeypair -alias picharbor -keyalg RSA -keysize 4096 -validity 10000 -storetype JKS -keystore $taskKey -storepass:env PICHARBOR_STORE_PASSWORD -keypass:env PICHARBOR_STORE_PASSWORD -dname 'CN=PicHarbor Mobile, O=Cc-Cece, C=CN' -noprompt 2>$null
    if($LASTEXITCODE -ne 0){throw 'Key generation failed. Inspect retained files before recovery.'}
    $taskPublicCertificate=Join-Path $taskSigningDirectory 'release-certificate.der'
    & (Join-Path $env:JAVA_HOME 'bin\keytool.exe') -exportcert -alias picharbor -keystore $taskKey -storepass:env PICHARBOR_STORE_PASSWORD -file $taskPublicCertificate 2>$null
    if($LASTEXITCODE -ne 0){throw 'Certificate export failed.'}
    $ErrorActionPreference='Stop'
    (Get-FileHash -LiteralPath $taskPublicCertificate -Algorithm SHA256).Hash.ToLowerInvariant() | Set-Content -LiteralPath $taskCertificate -Encoding ASCII
    Copy-Item -LiteralPath $taskCertificate -Destination (Join-Path $taskSigningDirectory 'release-signing.sha256')
    & python (Join-Path $PSScriptRoot 'verify_signing.py') --keystore $taskKey
    if($LASTEXITCODE -ne 0){throw 'New identity could not be verified.'}
    Write-Output 'Mobile signing identity created outside the repository. Back up this directory and keep an encrypted portable copy separately.'
} finally {
    $taskRng.Dispose();[Array]::Clear($taskRandom,0,$taskRandom.Length)
    Remove-Item Env:PICHARBOR_STORE_PASSWORD -ErrorAction SilentlyContinue
}
