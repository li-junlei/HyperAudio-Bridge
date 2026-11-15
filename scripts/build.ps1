param([switch]$SkipTests)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
Push-Location $projectRoot
try {
    $gradleTasks = @('assembleDebug')
    if (-not $SkipTests) { $gradleTasks += @('testDebugUnitTest', 'lintDebug') }
    $gradle = if ($env:GRADLE_HOME) { Join-Path $env:GRADLE_HOME 'bin/gradle.bat' } else { "$projectRoot/android/gradlew.bat" }
    & $gradle -p android @gradleTasks --console=plain
    if ($LASTEXITCODE -ne 0) { throw 'Android 构建失败' }
    if (-not $SkipTests) {
        cargo test --locked --manifest-path windows/HyperAudio.Rust/Cargo.toml
        if ($LASTEXITCODE -ne 0) { throw 'Windows 测试失败' }
    }
    $destination = Join-Path $projectRoot 'dist/0.6.1'
    New-Item -ItemType Directory -Force $destination | Out-Null
    cargo build --release --locked --manifest-path windows/HyperAudio.Rust/Cargo.toml
    if ($LASTEXITCODE -ne 0) { throw 'Windows 打包失败' }
    New-Item -ItemType Directory -Force "$destination/windows" | Out-Null
    Copy-Item -LiteralPath 'windows/HyperAudio.Rust/target/release/hyperaudio.exe' -Destination "$destination/windows/hyperaudio.exe"
    $androidConfig = Get-Content "$projectRoot/android/app/build.gradle.kts" -Raw
    if ($androidConfig -notmatch 'versionName\s*=\s*"(\d+\.\d+\.\d+)"') { throw '无法读取 Android 版本' }
    $androidVersion = $Matches[1]
    $androidDestination = Join-Path $projectRoot "dist/$androidVersion"
    New-Item -ItemType Directory -Force $androidDestination | Out-Null
    $apk = Join-Path $androidDestination "HyperAudio-$androidVersion-debug.apk"
    Copy-Item -LiteralPath 'android/app/build/outputs/apk/debug/app-debug.apk' -Destination $apk
    $apkHash = Get-FileHash $apk -Algorithm SHA256
    '{0}  {1}' -f $apkHash.Hash.ToLowerInvariant(), [IO.Path]::GetFileName($apk) | Set-Content "$apk.sha256" -Encoding utf8
    Copy-Item -LiteralPath 'README.md', 'THIRD_PARTY_NOTICES.md', 'LICENSE', 'NOTICE' -Destination $destination
    foreach ($folder in @('docs')) {
        New-Item -ItemType Directory -Force "$destination/$folder" | Out-Null
        Copy-Item -Path "$projectRoot/$folder/BUILDING.md", "$projectRoot/$folder/TESTING.md", "$projectRoot/$folder/PROTOCOL.md", "$projectRoot/$folder/CHANGELOG.md" -Destination "$destination/$folder" -Force
    }
    & "$PSScriptRoot/collect-rust-licenses.ps1"
    Get-ChildItem -LiteralPath $destination -File -Recurse | Where-Object { $_.Name -ne 'SHA256SUMS.txt' } | ForEach-Object {
        $hash = Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256
        '{0}  {1}' -f $hash.Hash.ToLowerInvariant(), [IO.Path]::GetRelativePath($destination, $_.FullName).Replace('\', '/')
    } | Set-Content -LiteralPath "$destination/SHA256SUMS.txt" -Encoding utf8
    Write-Host "已生成 $destination"
}
finally { Pop-Location }
