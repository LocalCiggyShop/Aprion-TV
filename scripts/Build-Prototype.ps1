param([switch]$Offline,[switch]$IncludeInstrumentation,[switch]$MinifiedTrial)
. "$PSScriptRoot/Env.ps1"
. "$PSScriptRoot/ProjectRuntime.ps1"
$taskOperation = Enter-QuietScreenOperation -Operation Build
Push-Location $taskRoot
try {
    $taskBuildArguments = @('--no-daemon','--max-workers=2','--dependency-verification','strict')
    if ($Offline) { $taskBuildArguments += '--offline' }
    $taskBuildArguments += @(':core:test',':extractor:testDebugUnitTest',':playback:testDebugUnitTest')
    if ($MinifiedTrial) {
        $taskBuildArguments += @(':app:lintTrial', ':app:assembleTrial')
        if ($IncludeInstrumentation) { $taskBuildArguments += ':trial-probe:assembleDebug' }
    } else {
        $taskBuildArguments += @(':app:lintDebug', ':app:assembleDebug')
        if ($IncludeInstrumentation) { $taskBuildArguments += ':app:assembleDebugAndroidTest' }
    }
    & "$taskRoot/gradlew.bat" @taskBuildArguments
    if ($LASTEXITCODE -ne 0) { throw 'Prototype verification/build failed' }
} finally { Pop-Location; $taskOperation.Dispose() }
