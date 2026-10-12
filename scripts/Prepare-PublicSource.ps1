param([Parameter(Mandatory)][ValidatePattern('^[A-Za-z0-9_-]{1,64}$')][string]$Label)
$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path -Parent $PSScriptRoot
& "$PSScriptRoot/Check-PublicSource.ps1" -Mode Worktree
if ($LASTEXITCODE -ne 0) { throw 'Public source security check rejected export' }
& "$PSScriptRoot/Run-SecretAudit.ps1" -Mode Worktree
if ($LASTEXITCODE -ne 0) { throw 'Pinned secret audit rejected export' }
$taskOutput = Join-Path $taskRoot "dist/$Label"
if (Test-Path -LiteralPath $taskOutput) { throw 'Use a fresh bundle label' }
$taskPaths = @(& git -c core.longpaths=true -C $taskRoot ls-files --cached --others --exclude-standard)
if ($LASTEXITCODE -ne 0 -or !$taskPaths.Count) { throw 'Cannot enumerate reviewed Git source' }
foreach ($taskPath in $taskPaths) {
    if ($taskPath -notmatch '^(app|core|extractor|playback|trial-probe|updater-fixture|gradle|third_party|licenses)/' -and
        $taskPath -notmatch '^(\.gitignore|README\.md|PUBLICATION\.md|BUILDING\.md|PUBLIC_SECURITY\.md|LICENSE|NOTICE\.md|build\.gradle\.kts|settings\.gradle\.kts|gradle\.properties|gradle\.lockfile|settings-gradle\.lockfile|gradlew|gradlew\.bat)$' -and
        $taskPath -notmatch '^scripts/(Env\.ps1|ProjectRuntime\.ps1|Build-Prototype\.ps1|Build-Dependencies\.ps1|Prepare-PublicSource\.ps1|Check-PublicSource\.ps1|Run-SecretAudit\.ps1|Release-Key\.ps1|Backup-ReleaseKey\.ps1|Sign-Release\.ps1|Inspect-Release\.ps1|Sign-UpdateManifest\.java)$' -and
        $taskPath -notmatch '^\.githooks/pre-push$|^\.github/workflows/public-source-security\.yml$|^\.gitleaks\.toml$' -and
        $taskPath -notmatch '^release/public-updates\.properties$|^updates/[A-Za-z0-9._-]+\.qsu$') { throw 'File outside public source allowlist; inspect staged content' }
    if ($taskPath -match '(^|/)(\.git|\.tools|build|dist|docs/evidence)(/|$)|\.(jks|keystore|p12|pfx|pem|key|hprof)$|(^|/)local\.properties$|(^|/)\.env') { throw 'Private or generated file in public selection' }
    $taskFull = [IO.Path]::GetFullPath((Join-Path $taskRoot $taskPath))
    if (!$taskFull.StartsWith($taskRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) { throw 'Source outside workspace' }
    if ((Get-Item -LiteralPath $taskFull).Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'Linked source requires review' }
}
New-Item -ItemType Directory -Path $taskOutput | Out-Null
$taskArchivePath = Join-Path $taskOutput 'corresponding-source.zip'
$taskArchive = [IO.Compression.ZipFile]::Open($taskArchivePath, [IO.Compression.ZipArchiveMode]::Create)
$taskRecords = @()
try {
    foreach ($taskPath in ($taskPaths | Sort-Object -Unique)) {
        $taskFull = Join-Path $taskRoot $taskPath
        $taskRecords += [pscustomobject]@{Path=$taskPath; Bytes=(Get-Item -LiteralPath $taskFull).Length; Sha256=(Get-FileHash -LiteralPath $taskFull).Hash}
        [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($taskArchive, $taskFull, $taskPath, [IO.Compression.CompressionLevel]::Optimal) | Out-Null
    }
    $taskManifest = Join-Path $taskOutput 'source-manifest.json'
    $taskRecords | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $taskManifest -Encoding utf8NoBOM
    [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($taskArchive, $taskManifest, 'source-manifest.json') | Out-Null
} finally { $taskArchive.Dispose() }
$taskArchive = [IO.Compression.ZipFile]::OpenRead($taskArchivePath)
try {
    foreach ($taskRecord in $taskRecords) {
        $taskEntry = $taskArchive.GetEntry($taskRecord.Path)
        if (!$taskEntry -or $taskEntry.Length -ne $taskRecord.Bytes) { throw 'Archive size mismatch' }
        $taskStream = $taskEntry.Open()
        try { $taskHash = [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($taskStream)) } finally { $taskStream.Dispose() }
        if ($taskHash -ne $taskRecord.Sha256) { throw 'Source changed during export' }
    }
} finally { $taskArchive.Dispose() }
Write-Output "Verified $($taskRecords.Count) source entries. Nothing uploaded."
