. "$PSScriptRoot/Env.ps1"
. "$PSScriptRoot/ProjectRuntime.ps1"
$taskOperation = Enter-QuietScreenOperation -Operation Build
Push-Location $taskRoot
try {
    & "$taskRoot/.tools/gradle/gradle-9.6.1/bin/gradle.bat" -p third_party/build-extractor --no-daemon --max-workers=2 --no-configuration-cache :nanojson:publishLocalPublicationToReviewedRepository :extractor:publishLocalPublicationToReviewedRepository :extractor:test
    if ($LASTEXITCODE -ne 0) { throw 'Reviewed source dependency build failed' }
} finally { Pop-Location; $taskOperation.Dispose() }
