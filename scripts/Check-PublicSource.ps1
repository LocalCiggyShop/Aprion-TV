[CmdletBinding()]
param(
    [ValidateSet('Worktree','Commit')][string]$Mode = 'Worktree',
    [string]$Revision
)
$ErrorActionPreference = 'Stop'
if ($Mode -eq 'Commit' -and [string]::IsNullOrWhiteSpace($Revision)) { throw 'Commit mode requires -Revision.' }
if ($Mode -eq 'Worktree' -and $Revision) { throw 'Use -Revision only with -Mode Commit.' }
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
function Invoke-GitText([string[]]$GitArgs) {
    $out = @(& git -C $root @GitArgs 2>$null)
    if ($LASTEXITCODE -ne 0) { throw 'Git could not inspect the requested source.' }
    return $out
}
function Test-PrivatePath([string]$Path) {
    $p = $Path.Replace('\','/')
    return ($p -match '(^|/)(\.git|\.tools|build|dist|docs/evidence|private|handoffs?)(/|$)' -or
        $p -match '^[^/]*handoff[^/]*$' -or
        $p -match '(^|/)local\.properties$|(^|/)\.env($|\.)|\.(jks|keystore|p12|pfx|pem|key|hprof)$')
}
$patterns = @(
    @{Name='Google API key'; Regex='AIza[0-9A-Za-z_-]{35}'; GitRegex='AIza[0-9A-Za-z_-]{35}'},
    @{Name='GitHub token'; Regex='\b(?:gh[pousr]_[A-Za-z0-9_]{30,}|github_pat_[A-Za-z0-9_]{50,})\b'; GitRegex='(^|[^[:alnum:]_])(gh[pousr]_[A-Za-z0-9_]{30,}|github_pat_[A-Za-z0-9_]{50,})([^[:alnum:]_]|$)'},
    @{Name='AWS access key'; Regex='\bAKIA[0-9A-Z]{16}\b'; GitRegex='(^|[^[:alnum:]_])AKIA[0-9A-Z]{16}([^[:alnum:]_]|$)'},
    @{Name='Private key material'; Regex='-----BEGIN (?:RSA |EC |DSA |OPENSSH )?PRIVATE KEY-----'; GitRegex='-----BEGIN (RSA |EC |DSA |OPENSSH )?PRIVATE KEY-----'},
    @{Name='Credential assignment'; Regex='(?i)\b(?:api[_-]?key|access[_-]?token|client[_-]?secret)\b\s*[:=]\s*["''][^"'']{12,}["'']'; GitRegex='(^|[^[:alnum:]_])(api[_-]?key|access[_-]?token|client[_-]?secret)[[:space:]]*[:=][[:space:]]*["''][^"'']{12,}["'']'}
)
$hits = [System.Collections.Generic.List[string]]::new()
if ($Mode -eq 'Commit') {
    $entries = Invoke-GitText @('ls-tree','-r','--full-tree','-z',$Revision)
    # -z output may be surfaced as one string with NUL delimiters.
    $raw = ($entries -join "`n") -split "`0" | Where-Object { $_ }
    $items = foreach ($entry in $raw) {
        if ($entry -match '^\d+ blob ([0-9a-f]+)\t(.+)$') { [pscustomobject]@{Path=$Matches[2]; Oid=$Matches[1]} }
    }
    foreach ($item in $items) { if (Test-PrivatePath $item.Path) { $hits.Add("$($item.Path) [private path]") } }
    foreach ($pattern in $patterns) {
        # Git grep returns filenames only; matched content is never captured or printed.
        $grepArgs = @('grep','-I','-l','-E')
        if ($pattern.Name -eq 'Credential assignment') { $grepArgs += '-i' }
        $found = @(& git -C $root @grepArgs -e $pattern.GitRegex $Revision -- 2>$null)
        $grepExit = $LASTEXITCODE
        if ($grepExit -gt 1) { throw 'Git could not scan the requested source revision.' }
        foreach ($path in $found) { $hits.Add("$($path -replace ('^' + [regex]::Escape($Revision) + ':'), '') [$($pattern.Name)]") }
    }
} else {
    $tracked = Invoke-GitText @('ls-files','--cached','-z')
    $eligible = Invoke-GitText @('ls-files','--others','--exclude-standard','-z')
    $paths = (($tracked -join "`n") + "`n" + ($eligible -join "`n")) -split "`0|`n" | Where-Object { $_ } | Sort-Object -Unique
    $items = foreach ($path in $paths) { [pscustomobject]@{Path=$path; Oid=$null} }
}
foreach ($item in $items) {
    $path = $item.Path
    if ($Mode -eq 'Commit') { continue }
    if (Test-PrivatePath $path) { $hits.Add("$path [private path]"); continue }
    if ($path -match '(?i)\.(zip|7z|rar|gz|tgz|jar|apk|aar)$') { continue }
    try {
        $full = [IO.Path]::GetFullPath((Join-Path $root $path))
        if (!$full.StartsWith($root + [IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase) -or !(Test-Path -LiteralPath $full -PathType Leaf)) { continue }
        $bytes = [IO.File]::ReadAllBytes($full)
        $text = [Text.Encoding]::UTF8.GetString($bytes)
        foreach ($pattern in $patterns) { if ([regex]::IsMatch($text,$pattern.Regex)) { $hits.Add("$path [$($pattern.Name)]"); break } }
    } catch { $hits.Add("$path [unreadable source]") }
}
if ($hits.Count) {
    Write-Error "Public source check rejected $($hits.Count) file(s):`n$($hits -join "`n")`nOnly paths and categories are shown; values and source text are never printed. Archive contents and binary payloads are not inspected reliably." -ErrorAction Continue
    exit 1
}
Write-Output "Public source check passed ($Mode): scanned $(@($items).Count) Git source entries. Archive contents and binary payloads are not inspected reliably; review them separately."
exit 0
