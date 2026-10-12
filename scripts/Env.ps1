$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path -Parent $PSScriptRoot
$taskJdk = Get-ChildItem -LiteralPath (Join-Path $taskRoot '.tools/jdk21') -Directory | Select-Object -First 1
if (-not $taskJdk) { throw 'Verified portable JDK 21 is not installed. See docs/evidence/environment.md.' }
$env:JAVA_HOME = $taskJdk.FullName
$env:ANDROID_HOME = Join-Path $taskRoot '.tools/android-sdk'
$env:ANDROID_USER_HOME = Join-Path $taskRoot '.tools/android-user'
$env:ANDROID_AVD_HOME = Join-Path $taskRoot '.tools/android-user/avd'
$env:GRADLE_USER_HOME = Join-Path $taskRoot '.tools/gradle-home'
$env:PATH = "$env:JAVA_HOME\bin;$env:ANDROID_HOME\platform-tools;$env:ANDROID_HOME\emulator;$env:PATH"
New-Item -ItemType Directory -Path $env:ANDROID_USER_HOME,$env:ANDROID_AVD_HOME,$env:GRADLE_USER_HOME -Force | Out-Null
