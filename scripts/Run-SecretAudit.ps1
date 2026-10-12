[CmdletBinding()]
param([ValidateSet('Worktree','Commit')][string]$Mode='Worktree', [string]$Revision, [string]$BaseRevision, [switch]$Install)
$ErrorActionPreference='Stop'
$taskRoot=Split-Path -Parent $PSScriptRoot
$taskVersion='8.30.1'
$taskArchiveName="gitleaks_${taskVersion}_windows_x64.zip"
$taskPublisherSha256='d29144deff3a68aa93ced33dddf84b7fdc26070add4aa0f4513094c8332afc4e'
$taskExecutableSha256='17157e2ee8b76fc8b1d8bee607a250e34b8a8023c8bc81822d4b5ee4d78fcb7c'
$taskToolDirectory=Join-Path $taskRoot '.tools/gitleaks'
$taskTool=Join-Path $taskToolDirectory 'run/gitleaks.exe'
if (!(Test-Path -LiteralPath $taskTool)) {
    if (!$Install) { throw 'Pinned Gitleaks is required. Run scripts/Run-SecretAudit.ps1 -Install first.' }
    New-Item -ItemType Directory -Path $taskToolDirectory -Force | Out-Null
    $taskArchive=Join-Path $taskToolDirectory $taskArchiveName
    Invoke-WebRequest -Uri "https://github.com/gitleaks/gitleaks/releases/download/v$taskVersion/$taskArchiveName" -OutFile $taskArchive -TimeoutSec 60
    if ((Get-FileHash -LiteralPath $taskArchive).Hash -ne $taskPublisherSha256) { throw 'Gitleaks publisher checksum mismatch' }
    $taskZip=[IO.Compression.ZipFile]::OpenRead($taskArchive)
    try { foreach($taskEntry in $taskZip.Entries) {
        $taskTarget=[IO.Path]::GetFullPath((Join-Path $taskToolDirectory $taskEntry.FullName))
        if (!$taskTarget.StartsWith($taskToolDirectory+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)) { throw 'Unexpected tool archive path' }
    } } finally { $taskZip.Dispose() }
    Expand-Archive -LiteralPath $taskArchive -DestinationPath (Join-Path $taskToolDirectory 'run') -Force
}
if ((Get-FileHash -LiteralPath $taskTool).Hash -ne $taskExecutableSha256) { throw 'Secret scanner executable integrity mismatch' }
if ((& $taskTool version).Trim() -ne $taskVersion) { throw 'Unexpected secret-scanner version' }
$taskScratch=Join-Path $taskRoot ('.tools/secret-check-'+[Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $taskScratch | Out-Null
$taskReport=Join-Path $taskScratch 'redacted-report.json'
$taskLog=Join-Path $taskScratch 'redacted-log.txt'
$taskConfig=Join-Path $taskRoot '.gitleaks.toml'
if ($Mode -eq 'Commit') {
    if ($Revision -notmatch '^[a-fA-F0-9]{40,64}$') { throw 'Commit mode requires a full Git object ID' }
    $taskRange="$Revision -1"
    if ($BaseRevision) {
        if ($BaseRevision -notmatch '^[a-fA-F0-9]{40,64}$') { throw 'Base revision must be a full Git object ID' }
        & git -C $taskRoot cat-file -e "$BaseRevision`^{commit}" 2>$null
        if ($LASTEXITCODE -ne 0) { throw 'Fetch the remote branch before pushing so every new commit can be scanned' }
        $taskRange="$BaseRevision..$Revision"
    }
    & $taskTool git $taskRoot --config $taskConfig --log-opts=$taskRange --no-banner --redact=100 --report-format json --report-path $taskReport *> $taskLog
} else {
    # Copy only Git-eligible files; private tools/evidence and ignored secrets never enter this scan.
    $taskPublic=Join-Path $taskScratch 'source'
    New-Item -ItemType Directory -Path $taskPublic | Out-Null
    $taskPaths=@(& git -c core.longpaths=true -C $taskRoot ls-files --cached --others --exclude-standard)
    if ($LASTEXITCODE -ne 0) { throw 'Cannot enumerate source for secret audit' }
    foreach($taskPath in $taskPaths) {
        $taskInput=[IO.Path]::GetFullPath((Join-Path $taskRoot $taskPath))
        $taskOutput=[IO.Path]::GetFullPath((Join-Path $taskPublic $taskPath))
        if (!$taskInput.StartsWith($taskRoot+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase) -or !$taskOutput.StartsWith($taskPublic+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)) { throw 'Source path outside audit scope' }
        New-Item -ItemType Directory -Path (Split-Path -Parent $taskOutput) -Force | Out-Null
        Copy-Item -LiteralPath $taskInput -Destination $taskOutput
    }
    & $taskTool dir $taskPublic --config $taskConfig --max-archive-depth 2 --no-banner --redact=100 --report-format json --report-path $taskReport *> $taskLog
}
$taskExit=$LASTEXITCODE
if ($taskExit -notin @(0,1)) { throw 'Secret scanner failed; inspect the local redacted log' }
if ($taskExit -eq 1) {
    $taskFindings=Get-Content -Raw -LiteralPath $taskReport | ConvertFrom-Json
    $taskFindings | Group-Object RuleID | ForEach-Object { Write-Output "Blocked $($_.Count) finding(s) in category $($_.Name). Values are withheld." }
    throw 'Public source secret audit rejected this candidate; inspect local redacted report before publishing'
}
Write-Output "Pinned Gitleaks $taskVersion secret audit passed ($Mode). Pattern scans cannot guarantee absence of all sensitive data."
