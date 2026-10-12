# Permanent release key creation; all private files remain under ignored .tools.
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot/Env.ps1"
$taskKeyDirectory = Join-Path $taskRoot '.tools/private-release'
$taskKeyPath = Join-Path $taskKeyDirectory 'quietscreen-release.p12'
$taskPasswordPath = Join-Path $taskKeyDirectory 'password.dpapi'
if (!(Test-Path -LiteralPath $taskKeyDirectory)) {
    New-Item -ItemType Directory -Path $taskKeyDirectory | Out-Null
}
$taskAcl = Get-Acl -LiteralPath $taskKeyDirectory
$taskIdentity = [Security.Principal.WindowsIdentity]::GetCurrent().User
$taskRules = @($taskAcl.GetAccessRules($true,$true,[Security.Principal.SecurityIdentifier]))
if (!$taskAcl.AreAccessRulesProtected -or $taskRules.Count -ne 1 -or $taskRules[0].IdentityReference -ne $taskIdentity -or $taskRules[0].AccessControlType -ne 'Allow' -or $taskRules[0].FileSystemRights -ne 'FullControl') {
    $taskAcl.SetAccessRuleProtection($true, $false)
    foreach($taskExistingRule in $taskRules) { $taskAcl.RemoveAccessRuleSpecific($taskExistingRule) }
    $taskRule = [Security.AccessControl.FileSystemAccessRule]::new($taskIdentity, 'FullControl', 'ContainerInherit,ObjectInherit', 'None', 'Allow')
    $taskAcl.AddAccessRule($taskRule)
    Set-Acl -LiteralPath $taskKeyDirectory -AclObject $taskAcl
}
if (!(Test-Path -LiteralPath $taskKeyPath)) {
    if (Test-Path -LiteralPath $taskPasswordPath) { throw 'Partial key creation exists; review privately before continuing' }
    $taskPassword = [Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(48))
    try {
        ConvertTo-SecureString -String $taskPassword -AsPlainText -Force | ConvertFrom-SecureString |
            Set-Content -LiteralPath $taskPasswordPath -Encoding ascii
        $env:QUIETSCREEN_RELEASE_PASSWORD = $taskPassword
        & "$env:JAVA_HOME/bin/keytool.exe" -genkeypair -alias quietscreen -keyalg RSA -keysize 4096 -sigalg SHA256withRSA -validity 10950 -dname 'CN=QuietScreen Release' -storetype PKCS12 -keystore $taskKeyPath -storepass:env QUIETSCREEN_RELEASE_PASSWORD -keypass:env QUIETSCREEN_RELEASE_PASSWORD *> (Join-Path $taskKeyDirectory 'key-generation.log')
        if ($LASTEXITCODE -ne 0) { throw 'Release key generation failed; inspect private log' }
    } finally { Remove-Item Env:QUIETSCREEN_RELEASE_PASSWORD -ErrorAction SilentlyContinue; $taskPassword = $null }
}
if (!(Test-Path -LiteralPath $taskPasswordPath)) { throw 'Release key password unavailable; do not replace the permanent key' }
Write-Output 'Permanent release key is available locally. Back up the key and password privately before publication.'
