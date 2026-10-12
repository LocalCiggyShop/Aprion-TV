# Run interactively in your own PowerShell. Never enter this passphrase in chat.
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot/Env.ps1"
$taskPrivate = Join-Path $taskRoot '.tools/private-release'
$taskKey = Join-Path $taskPrivate 'quietscreen-release.p12'
$taskBackup = Join-Path $taskPrivate 'portable-backup.p12'
if (!(Test-Path -LiteralPath $taskKey) -or (Test-Path -LiteralPath $taskBackup)) { throw 'Key missing or backup already exists; preserve existing keys/backups' }
$taskOld = (Get-Content -Raw -LiteralPath (Join-Path $taskPrivate 'password.dpapi')).Trim() | ConvertTo-SecureString
$taskNew = Read-Host 'New backup passphrase (at least 16 characters; save it privately)' -AsSecureString
$taskConfirm = Read-Host 'Confirm backup passphrase' -AsSecureString
$taskOldPointer=[Runtime.InteropServices.Marshal]::SecureStringToBSTR($taskOld)
$taskNewPointer=[Runtime.InteropServices.Marshal]::SecureStringToBSTR($taskNew)
$taskConfirmPointer=[Runtime.InteropServices.Marshal]::SecureStringToBSTR($taskConfirm)
try {
    $env:QUIETSCREEN_SOURCE_PASSWORD=[Runtime.InteropServices.Marshal]::PtrToStringBSTR($taskOldPointer)
    $env:QUIETSCREEN_BACKUP_PASSWORD=[Runtime.InteropServices.Marshal]::PtrToStringBSTR($taskNewPointer)
    if ($env:QUIETSCREEN_BACKUP_PASSWORD.Length -lt 16 -or $env:QUIETSCREEN_BACKUP_PASSWORD -cne [Runtime.InteropServices.Marshal]::PtrToStringBSTR($taskConfirmPointer)) { throw 'Backup passphrases differ or are too short' }
    & "$env:JAVA_HOME/bin/keytool.exe" -importkeystore -srckeystore $taskKey -srcstoretype PKCS12 -srcalias quietscreen -srcstorepass:env QUIETSCREEN_SOURCE_PASSWORD -srckeypass:env QUIETSCREEN_SOURCE_PASSWORD -destkeystore $taskBackup -deststoretype PKCS12 -destalias quietscreen -deststorepass:env QUIETSCREEN_BACKUP_PASSWORD -destkeypass:env QUIETSCREEN_BACKUP_PASSWORD -noprompt
    if ($LASTEXITCODE -ne 0) { throw 'Backup creation failed' }
    $taskOldCertificate=Join-Path $taskPrivate 'original-public.der'
    $taskBackupCertificate=Join-Path $taskPrivate 'backup-public.der'
    & "$env:JAVA_HOME/bin/keytool.exe" -exportcert -alias quietscreen -keystore $taskKey -storepass:env QUIETSCREEN_SOURCE_PASSWORD -file $taskOldCertificate
    if ($LASTEXITCODE -ne 0) { throw 'Original certificate verification failed' }
    & "$env:JAVA_HOME/bin/keytool.exe" -exportcert -alias quietscreen -keystore $taskBackup -storepass:env QUIETSCREEN_BACKUP_PASSWORD -file $taskBackupCertificate
    if ($LASTEXITCODE -ne 0 -or (Get-FileHash -LiteralPath $taskOldCertificate).Hash -ne (Get-FileHash -LiteralPath $taskBackupCertificate).Hash) { throw 'Backup certificate mismatch' }
    Write-Output 'Encrypted portable-backup.p12 verified in .tools/private-release. Copy it to private offline storage and keep its passphrase in your password manager. Never upload it.'
} finally {
    Remove-Item Env:QUIETSCREEN_SOURCE_PASSWORD,Env:QUIETSCREEN_BACKUP_PASSWORD -ErrorAction SilentlyContinue
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($taskOldPointer)
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($taskNewPointer)
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($taskConfirmPointer)
    $taskOld.Dispose(); $taskNew.Dispose(); $taskConfirm.Dispose()
}
