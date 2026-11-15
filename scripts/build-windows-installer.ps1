param([string]$Compiler)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
if (-not $Compiler) {
    $Compiler = Join-Path ${env:ProgramFiles(x86)} 'Inno Setup 6/ISCC.exe'
}
if (-not (Test-Path -LiteralPath $Compiler)) { throw '请安装 Inno Setup 6，或通过 -Compiler 指定 ISCC.exe。' }
$binary = Join-Path $projectRoot 'dist/0.6.1/windows/hyperaudio.exe'
if (-not (Test-Path -LiteralPath $binary)) { throw '请先运行 scripts/build.ps1 生成 Windows 程序。' }
& $Compiler (Join-Path $PSScriptRoot 'windows-installer.iss')
if ($LASTEXITCODE -ne 0) { throw '安装程序编译失败' }
$installer = Join-Path $projectRoot 'dist/0.6.1/HyperAudioBridge-0.6.1-windows-x64-setup.exe'
$hash = Get-FileHash -LiteralPath $installer -Algorithm SHA256
'{0}  {1}' -f $hash.Hash.ToLowerInvariant(), [IO.Path]::GetFileName($installer) | Set-Content -LiteralPath "$installer.sha256" -Encoding utf8
Write-Host "已生成 $installer"
