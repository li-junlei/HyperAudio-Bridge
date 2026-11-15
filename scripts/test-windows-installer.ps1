$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$installer = Join-Path $projectRoot 'dist/0.6.1/HyperAudioBridge-0.6.1-windows-x64-setup.exe'
$registration = 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall\{44F43465-25C5-4EBB-B187-00B5A3A6A62B}_is1'
if (Test-Path $registration) { throw '检测到已有安装，跳过安装测试以保留现有程序。' }
$testDir = Join-Path $projectRoot ('.tools/installer-smoke-' + [Guid]::NewGuid().ToString('N'))
$logDir = Join-Path $projectRoot '.tools'
New-Item -ItemType Directory -Force $logDir | Out-Null
try {
    $install = Start-Process -FilePath $installer -ArgumentList @('/VERYSILENT','/SUPPRESSMSGBOXES','/NORESTART','/NOICONS','/LANG=chinesesimp',('/DIR="{0}"' -f $testDir),('/LOG="{0}"' -f (Join-Path $logDir 'installer-smoke.log'))) -WindowStyle Hidden -Wait -PassThru
    if ($install.ExitCode -ne 0) { throw "安装失败：$($install.ExitCode)" }
    $original = (Get-FileHash -LiteralPath (Join-Path $projectRoot 'dist/0.6.1/windows/hyperaudio.exe')).Hash
    $installed = (Get-FileHash -LiteralPath (Join-Path $testDir 'hyperaudio.exe')).Hash
    if ($installed -ne $original) { throw '安装后的程序内容与交付程序不一致' }
    if (-not (Test-Path $registration)) { throw '没有创建系统卸载入口' }
    $location = (Get-ItemProperty $registration).InstallLocation.TrimEnd('\')
    if ($location -ne $testDir.Replace('/','\').TrimEnd('\')) { throw '卸载入口指向意外目录' }
    Write-Output 'PASS: Chinese silent installation, payload hash, uninstall registration'
}
finally {
    $uninstaller = Join-Path $testDir 'unins000.exe'
    if (Test-Path -LiteralPath $uninstaller) {
        $uninstall = Start-Process -FilePath $uninstaller -ArgumentList @('/VERYSILENT','/SUPPRESSMSGBOXES','/NORESTART',('/LOG="{0}"' -f (Join-Path $logDir 'uninstaller-smoke.log'))) -WindowStyle Hidden -Wait -PassThru
        if ($uninstall.ExitCode -ne 0) { throw "测试卸载失败：$($uninstall.ExitCode)" }
        if (Test-Path $registration) { throw '卸载后注册项仍然存在' }
        if (Test-Path -LiteralPath (Join-Path $testDir 'hyperaudio.exe')) { throw '卸载后程序仍然存在' }
        Write-Output 'PASS: Uninstall removes program and registration'
    }
}
