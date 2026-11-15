param([string]$Version = '0.6.1')
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
if ($Version -notmatch '^\d+\.\d+\.\d+$') { throw 'Invalid version' }
$destination = Join-Path $projectRoot "dist/$Version"
New-Item -ItemType Directory -Force "$destination/licenses" | Out-Null
$raw = cargo metadata --locked --format-version 1 --manifest-path "$projectRoot/windows/HyperAudio.Rust/Cargo.toml"
if ($LASTEXITCODE -ne 0) { throw 'Cannot read Rust dependency metadata' }
$metadata = $raw | ConvertFrom-Json
$lines = [System.Collections.Generic.List[string]]::new()
$lines.Add('# Rust dependencies')
$lines.Add('')
$lines.Add('License texts are included under licenses/. Package metadata includes build and platform dependencies.')
$lines.Add('')
foreach ($package in ($metadata.packages | Sort-Object name, version)) {
    if ($package.name -notmatch '^[a-zA-Z0-9_-]+$' -or $package.version -notmatch '^[a-zA-Z0-9._+-]+$') { throw 'Unexpected package name' }
    $root = Split-Path -Parent $package.manifest_path
    $files = @(Get-ChildItem -LiteralPath $root -File | Where-Object { $_.Name -match '^(LICENSE|LICENCE|COPYING|NOTICE)([._-].*)?$' })
    if ($package.license_file -and (Test-Path -LiteralPath $package.license_file)) { $files += Get-Item -LiteralPath $package.license_file }
    if ($files.Count -gt 0) {
        $dir = Join-Path "$destination/licenses" "$($package.name)-$($package.version)"
        New-Item -ItemType Directory -Force $dir | Out-Null
        foreach ($file in $files) { Copy-Item -LiteralPath $file.FullName -Destination (Join-Path $dir $file.Name) -Force }
    }
    $lines.Add("- $($package.name) $($package.version) — $($package.license) — $($package.repository)")
}
$lines | Set-Content -LiteralPath "$destination/RUST_DEPENDENCIES.md" -Encoding utf8
Write-Output "Collected metadata for $($metadata.packages.Count) Rust packages"
