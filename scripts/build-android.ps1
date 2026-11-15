param([switch]$SkipChecks)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$config = Get-Content -LiteralPath "$projectRoot/android/app/build.gradle.kts" -Raw
if ($config -notmatch 'versionName\s*=\s*"(\d+\.\d+\.\d+)"') { throw '无法读取 Android 版本' }
$version = $Matches[1]
$gradle = if ($env:GRADLE_HOME) { Join-Path $env:GRADLE_HOME 'bin/gradle.bat' } else { "$projectRoot/android/gradlew.bat" }
$tasks = @('assembleDebug')
if (-not $SkipChecks) { $tasks += @('testDebugUnitTest', 'lintDebug') }
& $gradle -p "$projectRoot/android" @tasks --console=plain
if ($LASTEXITCODE -ne 0) { throw 'Android 构建失败' }
$destination = Join-Path $projectRoot "dist/$version"
New-Item -ItemType Directory -Force $destination | Out-Null
$apk = Join-Path $destination "HyperAudio-$version-debug.apk"
Copy-Item -LiteralPath "$projectRoot/android/app/build/outputs/apk/debug/app-debug.apk" -Destination $apk
Copy-Item -LiteralPath "$projectRoot/README.md", "$projectRoot/LICENSE", "$projectRoot/NOTICE", "$projectRoot/THIRD_PARTY_NOTICES.md", "$projectRoot/docs/CHANGELOG.md" -Destination $destination
$hash = Get-FileHash -LiteralPath $apk -Algorithm SHA256
'{0}  {1}' -f $hash.Hash.ToLowerInvariant(), [IO.Path]::GetFileName($apk) | Set-Content -LiteralPath "$apk.sha256" -Encoding utf8
Write-Host "已生成 $apk"
