param([Parameter(Mandatory)][ValidatePattern('^[A-Fa-f0-9]{64}$')][string]$CertificateSha256,
    [ValidatePattern('^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$')][string]$Tag='v0.3.0-beta')
. "$PSScriptRoot/Env.ps1"
$taskApk=(Get-Item -LiteralPath (Join-Path $taskRoot "dist/$Tag/QuietScreen.apk")).FullName
$taskHash=(Get-FileHash -LiteralPath $taskApk).Hash
$taskTools=Get-ChildItem "$env:ANDROID_HOME/build-tools" -Directory | Sort-Object Name -Descending | Select-Object -First 1
$taskSignature=& "$taskTools/apksigner.bat" verify --verbose --print-certs $taskApk
if ($LASTEXITCODE -ne 0 -or ($taskSignature -join "`n") -notmatch $CertificateSha256) { throw 'Release signature mismatch' }
$taskManifest=& "$env:JAVA_HOME/bin/java.exe" -Xmx256m "-Dcom.android.sdklib.toolsdir=$env:ANDROID_HOME/cmdline-tools/latest" -cp "$env:ANDROID_HOME/cmdline-tools/latest/lib/apkanalyzer-classpath.jar" com.android.tools.apk.analyzer.ApkAnalyzerCli manifest print $taskApk
if ($LASTEXITCODE -ne 0) { throw 'Manifest inspection failed' }
$taskManifest | Set-Content -LiteralPath (Join-Path $taskRoot 'docs/evidence/beta-manifest.xml')
[xml]$taskXml=$taskManifest -join "`n"
$taskAndroid='http://schemas.android.com/apk/res/android'
if ($taskXml.manifest.package -ne 'org.privateview.quietscreen' -or
    $taskXml.manifest.application.GetAttribute('debuggable',$taskAndroid) -eq 'true' -or
    $taskXml.manifest.application.GetAttribute('testOnly',$taskAndroid) -eq 'true' -or
    $taskXml.manifest.application.GetAttribute('allowBackup',$taskAndroid) -ne 'false' -or
    $taskXml.manifest.application.GetAttribute('usesCleartextTraffic',$taskAndroid) -ne 'false') { throw 'Unexpected release manifest' }
$taskAllowed=@('android.permission.INTERNET','android.permission.REQUEST_INSTALL_PACKAGES','android.permission.UPDATE_PACKAGES_WITHOUT_USER_ACTION','org.privateview.quietscreen.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION')
foreach($taskPermission in $taskXml.manifest.'uses-permission') {
    if ($taskPermission.GetAttribute('name',$taskAndroid) -notin $taskAllowed) { throw 'Unexpected permission' }
}
foreach($taskComponent in @($taskXml.manifest.application.activity)+@($taskXml.manifest.application.receiver)+@($taskXml.manifest.application.service)+@($taskXml.manifest.application.provider)) {
    if ($null -ne $taskComponent -and $taskComponent.GetAttribute('exported',$taskAndroid) -eq 'true' -and $taskComponent.GetAttribute('name',$taskAndroid) -ne 'org.privateview.quietscreen.MainActivity') { throw 'Unexpected exported component' }
}
$taskZip=[IO.Compression.ZipFile]::OpenRead($taskApk)
$taskInventory=@()
try {
    foreach($taskEntry in $taskZip.Entries) {
        if ($taskEntry.FullName -match '\.(jks|keystore|p12|pfx|pem|key|apk|jar)$|^META-INF/services/javax.script.ScriptEngineFactory$') { throw 'Unexpected APK asset' }
        $taskStream=$taskEntry.Open(); $taskMemory=[IO.MemoryStream]::new()
        try {
            $taskStream.CopyTo($taskMemory); $taskBytes=$taskMemory.ToArray()
            if ([Text.Encoding]::ASCII.GetString($taskBytes) -match 'C:\\Users\\|-----BEGIN (RSA |EC |OPENSSH )?PRIVATE KEY-----') { throw 'Private path/key in APK payload' }
            $taskInventory += [pscustomobject]@{Path=$taskEntry.FullName;Bytes=$taskEntry.Length;Sha256=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($taskBytes))}
        } finally { $taskMemory.Dispose(); $taskStream.Dispose() }
    }
} finally { $taskZip.Dispose() }
$taskPriorNative=Get-Content (Join-Path $taskRoot 'docs/evidence/phase4-6/trial-native.json') | ConvertFrom-Json
foreach($taskLib in $taskPriorNative) { if (($taskInventory | Where-Object Path -eq $taskLib.Path).Sha256 -ne $taskLib.Sha256) { throw 'Native library changed' } }
if (@($taskInventory | Where-Object Path -like 'lib/*').Count -ne $taskPriorNative.Count) { throw 'Unexpected native libraries' }
$taskInventory | ConvertTo-Json -Depth 4 | Set-Content (Join-Path $taskRoot 'docs/evidence/beta-apk-inventory.json')
$taskScanner=Join-Path ((Get-ChildItem 'C:/ProgramData/Microsoft/Windows Defender/Platform' -Directory | Sort-Object Name -Descending | Select-Object -First 1).FullName) 'MpCmdRun.exe'
$taskScan=& $taskScanner -Scan -ScanType 3 -File $taskApk 2>&1
$taskScanExit=$LASTEXITCODE
$taskScan | Set-Content (Join-Path $taskRoot 'docs/evidence/beta-defender.txt')
if ($taskScanExit -ne 0 -or ($taskScan -join "`n") -notmatch 'Scan finished' -or ($taskScan -join "`n") -notmatch 'found no threats' -or (Get-FileHash -LiteralPath $taskApk).Hash -ne $taskHash) { throw 'APK scan incomplete or artifact changed' }
[pscustomobject]@{Sha256=$taskHash;CertificateSha256=$CertificateSha256.ToLowerInvariant();SignatureVerified=$true;ManifestChecked=$true;NativeLibrariesUnchanged=$true;PrivatePathKeyPatternsAbsent=$true;DefenderCompletedNoThreats=$true} |
    ConvertTo-Json | Tee-Object -FilePath (Join-Path $taskRoot 'docs/evidence/beta-apk-audit.json')
