# 构建指南

## 环境

开发基线为 Windows x64、PowerShell 7、Git。Android 使用 JDK 17 或构建插件兼容的 JDK、Android SDK 37、Build Tools 36.0.0 和仓库内 Gradle Wrapper（9.3.1）。Windows 使用 Rust stable MSVC 工具链、Visual Studio C++ 构建工具和 Windows SDK。打包安装程序需要 Inno Setup 6。

设置 `ANDROID_HOME`，或在本地 `android/local.properties` 写入 `sdk.dir`。本地 SDK 路径、签名密钥、设备日志和构建缓存不应提交。首次构建需要联网下载依赖。

## Android

```powershell
./android/gradlew.bat -p android assembleDebug testDebugUnitTest lintDebug --console=plain
./scripts/build-android.ps1
```

APK 位于 `android/app/build/outputs/apk/debug/`，脚本复制到 `dist/<Android版本>/`。当前使用开发机器的调试签名；自行构建的 APK 可能无法直接覆盖其他密钥签名的安装。仓库不包含私有签名密钥。

macOS/Linux 可使用 `chmod +x android/gradlew` 后运行 `./android/gradlew -p android ...`；Android 构建尚未在这些平台验证。Windows 接收端及安装包必须在 Windows 构建。

## Windows

```powershell
cargo test --locked --manifest-path windows/HyperAudio.Rust/Cargo.toml
cargo build --release --locked --manifest-path windows/HyperAudio.Rust/Cargo.toml
```

产物为 `windows/HyperAudio.Rust/target/release/hyperaudio.exe`。测试使用合成音频与本机回环 TLS，不投送手机媒体；部分测试会使用 Windows DPAPI 及临时注册表路径。

完整构建与打包：

```powershell
./scripts/build.ps1
./scripts/build-windows-installer.ps1
```

`build.ps1` 构建两端、运行检查、复制 Windows 可执行文件与许可资料；安装包脚本随后生成当前用户安装程序。单独收集 Rust 依赖许可可运行 `./scripts/collect-rust-licenses.ps1`。发行包应同时保留依赖许可清单和文本。

## 资源与上游

`python design/icons/build_icons.py` 需要 Pillow，用于生成 Android 与 Windows 图标。scrcpy 官方 server 固定在 Android assets 中，来源、版本及 SHA-256 见 [上游说明](../third_party/scrcpy/README.md)。更新该二进制时必须同步其校验值和协议兼容检查。

## 发布

更新两端各自版本、构建脚本与变更记录；完成自动检查和必要的实机验证后，再生成安装包、校验文件和 Git 标签。未完成的 ROM、音频设备或锁屏验证必须记录在发布说明中，实验版本使用 GitHub Pre-release 标记。不要将整个 `dist/`、本地诊断目录或签名密钥提交到 Git。
