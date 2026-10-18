param([Parameter(Mandatory=$true)][string]$Destination)
$ErrorActionPreference='Stop'
$signing=Join-Path $env:LOCALAPPDATA 'PicHarborBuild\signing'
$sourceKey=Join-Path $signing 'picharbor-release.jks'
$sourcePassword=Join-Path $signing 'password.dpapi'
if(Test-Path -LiteralPath $Destination){throw 'Destination already exists. Choose a new .p12 file.'}
if(-not $env:JAVA_HOME){$env:JAVA_HOME='C:\Program Files\Java\jdk-17'}
$secure=(Get-Content -LiteralPath $sourcePassword -Raw).Trim() | ConvertTo-SecureString
$backupPassword=Read-Host 'Choose a strong password for the portable signing backup (minimum 12 characters)' -AsSecureString
try {
    $env:PICHARBOR_SOURCE_PASSWORD=[Net.NetworkCredential]::new('', $secure).Password
    $env:PICHARBOR_BACKUP_PASSWORD=[Net.NetworkCredential]::new('', $backupPassword).Password
    if($env:PICHARBOR_BACKUP_PASSWORD.Length -lt 12){throw 'Use at least 12 characters.'}
    $ErrorActionPreference='Continue'
    & (Join-Path $env:JAVA_HOME 'bin\keytool.exe') -importkeystore -srckeystore $sourceKey -srcstoretype JKS -srcalias picharbor -srcstorepass:env PICHARBOR_SOURCE_PASSWORD -srckeypass:env PICHARBOR_SOURCE_PASSWORD -destkeystore $Destination -deststoretype PKCS12 -destalias picharbor -deststorepass:env PICHARBOR_BACKUP_PASSWORD -destkeypass:env PICHARBOR_BACKUP_PASSWORD -noprompt
    $ErrorActionPreference='Stop'
    if($LASTEXITCODE -ne 0){throw 'Backup export failed.'}
    Write-Output 'Portable signing backup created. Keep its password separately; never include this file in source archives.'
} finally {
    Remove-Item Env:PICHARBOR_SOURCE_PASSWORD,Env:PICHARBOR_BACKUP_PASSWORD -ErrorAction SilentlyContinue
}
