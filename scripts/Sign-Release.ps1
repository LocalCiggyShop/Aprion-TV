param([ValidatePattern('^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$')][string]$Tag = 'v0.3.0-beta')
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot/Release-Key.ps1"
$taskBuildTools = Get-ChildItem -LiteralPath "$env:ANDROID_HOME/build-tools" -Directory | Sort-Object Name -Descending | Select-Object -First 1
if (!$taskBuildTools) { throw 'Build tools missing' }
$taskUnsigned = Join-Path $taskRoot 'app/build/outputs/apk/release/app-release-unsigned.apk'
$taskMetadata = Get-Content -Raw -LiteralPath (Join-Path $taskRoot 'app/build/outputs/apk/release/output-metadata.json') | ConvertFrom-Json
if ($taskMetadata.applicationId -ne 'org.privateview.quietscreen' -or $taskMetadata.elements.Count -ne 1) { throw 'Unexpected release metadata' }
$taskConfig = ConvertFrom-StringData (Get-Content -Raw -LiteralPath (Join-Path $taskRoot 'release/public-updates.properties'))
if ($taskConfig.owner -notmatch '^[A-Za-z0-9][A-Za-z0-9-]{0,38}$' -or $taskConfig.repo -notmatch '^[A-Za-z0-9][A-Za-z0-9._-]{0,99}$') { throw 'Real update repository required' }
$taskDestination = Join-Path $taskRoot "dist/$Tag"
New-Item -ItemType Directory -Path $taskDestination -Force | Out-Null
$taskSigned = Join-Path $taskDestination 'QuietScreen.apk'
if (Test-Path -LiteralPath $taskSigned) { throw 'Signed artifact exists; use a fresh tag or preserve and review it before replacement' }
$taskSecurePassword = (Get-Content -Raw -LiteralPath $taskPasswordPath).Trim() | ConvertTo-SecureString
$taskPasswordPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($taskSecurePassword)
try {
    $env:QUIETSCREEN_RELEASE_PASSWORD = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($taskPasswordPointer)
    & "$taskBuildTools/apksigner.bat" sign --ks $taskKeyPath --ks-key-alias quietscreen --ks-pass env:QUIETSCREEN_RELEASE_PASSWORD --key-pass env:QUIETSCREEN_RELEASE_PASSWORD --out $taskSigned $taskUnsigned
    if ($LASTEXITCODE -ne 0) { throw 'APK signing failed' }
    & "$taskBuildTools/apksigner.bat" verify --verbose --print-certs $taskSigned *> (Join-Path $taskDestination 'signature.txt')
    if ($LASTEXITCODE -ne 0) { throw 'Signed APK verification failed' }
    $taskHash = (Get-FileHash -LiteralPath $taskSigned).Hash.ToLowerInvariant()
    "$taskHash  QuietScreen.apk" | Set-Content -LiteralPath (Join-Path $taskDestination 'SHA256SUMS.txt') -Encoding ascii
    $taskPayload = @('QUIETSCREEN_UPDATE_V1','org.privateview.quietscreen', [string]$taskMetadata.elements[0].versionCode,
        [string]$taskMetadata.elements[0].versionName,'26',[string](Get-Item -LiteralPath $taskSigned).Length,$taskHash,
        "https://github.com/$($taskConfig.owner)/$($taskConfig.repo)/releases/download/$Tag/QuietScreen.apk") -join "`n"
    $taskPayloadPath = Join-Path $taskKeyDirectory 'update-payload.txt'
    [IO.File]::WriteAllText($taskPayloadPath,$taskPayload,[Text.Encoding]::ASCII)
    $taskCompileDirectory = Join-Path $taskKeyDirectory 'signer'
    New-Item -ItemType Directory -Path $taskCompileDirectory -Force | Out-Null
    # The source filename uses repository naming; javac requires the public-class name.
    Copy-Item -LiteralPath "$PSScriptRoot/Sign-UpdateManifest.java" -Destination (Join-Path $taskCompileDirectory 'SignUpdateManifest.java')
    & "$env:JAVA_HOME/bin/javac.exe" -d $taskCompileDirectory (Join-Path $taskCompileDirectory 'SignUpdateManifest.java')
    if ($LASTEXITCODE -ne 0) { throw 'Metadata signer compile failed' }
    & "$env:JAVA_HOME/bin/java.exe" -cp $taskCompileDirectory SignUpdateManifest $taskKeyPath $taskPayloadPath (Join-Path $taskDestination 'org.privateview.quietscreen.qsu')
    if ($LASTEXITCODE -ne 0) { throw 'Metadata signing failed' }
} finally {
    Remove-Item Env:QUIETSCREEN_RELEASE_PASSWORD -ErrorAction SilentlyContinue
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($taskPasswordPointer)
    $taskSecurePassword.Dispose()
}
Write-Output 'APK and update metadata signed and verified locally. Nothing uploaded; publish the APK before the update feed.'
