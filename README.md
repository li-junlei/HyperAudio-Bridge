<p align="center">
  <img src="design/icons/hyperaudio-windows-rounded.png" width="112" alt="HyperAudio Bridge icon">
</p>

<h1 align="center">HyperAudio Bridge</h1>

<p align="center">将 HyperOS 手机的媒体音频通过局域网投送到 Windows。</p>

<p align="center">
  <a href="https://github.com/li-junlei/HyperAudio-Bridge/releases">下载预发布版本</a> ·
  <a href="docs/BUILDING.md">构建指南</a> ·
  <a href="docs/TESTING.md">兼容性与验证</a> ·
  <a href="https://github.com/li-junlei/HyperAudio-Bridge/issues">问题反馈</a>
</p>

> **实验性项目。** Android 需要 Root 和 LSPosed，控制中心适配依赖 HyperOS 私有接口。当前提供调试签名 APK 和未签名的 Windows 安装程序；尚未完成所有机型、长时间息屏及端到端延迟验证。安装前请阅读下方兼容性说明。

## 功能

- **音频投送**：手机采集系统媒体音频，以 Opus 编码，通过 TLS 传输到电脑的系统默认音频输出。
- **控制中心入口**：在适配的 HyperOS 融合设备栏显示电脑，使用系统详情面板开始或停止投送。
- **配对与发现**：扫码配对；同一可互通局域网内通过 mDNS 和 UDP 广播发现电脑，换网后更新已配对设备地址。
- **Android 管理界面**：查看模块、Root、运行状态和设备列表；添加、删除配对设备；按需记录诊断日志及隐藏桌面图标。
- **Windows 后台接收**：Rust + GPUI 界面，支持登录自启动和隐藏托盘图标。隐藏后重新打开应用快捷方式即可唤出窗口。

手机与电脑之间不需要蓝牙连接，电脑可继续使用自己的有线音箱或蓝牙耳机。当前一次只接收一台手机的音频。

## 系统要求

| 平台 | 要求与范围 |
| --- | --- |
| Android | Android 11 及以上；Root；兼容的 LSPosed 环境。系统版本下限不代表所有 ROM 均已验证。 |
| HyperOS 控制中心 | 当前适配依据 HyperOS 4 / Android 17 的小米实机接口；其他版本和机型需要单独验证。 |
| Windows | x64，需支持当前 GPUI 和系统音频接口；尚未建立完整的 Windows 版本兼容矩阵。 |
| 网络 | 手机与电脑可直接互通，允许本地发现和应用的入站连接。访客网络、AP 隔离或组播过滤可能阻止发现。 |

当前发行组合：**Android 0.5.3 / Windows 0.6.1**。不支持互联网中继、AirDrop 式附近直连或绕过应用的受保护音频限制。

## 安装与使用

1. 从 [Releases](https://github.com/li-junlei/HyperAudio-Bridge/releases) 下载 Android APK 和 Windows x64 安装程序。可使用随附的 `SHA256SUMS.txt` 核对下载完整性。
2. 在 Windows 安装并启动 HyperAudio Bridge。若防火墙提示，请根据实际网络环境允许应用在可信局域网上通信。
3. 在手机安装 APK，授予 Root 和通知权限；在 LSPosed 中启用模块，并勾选**系统框架（android）、系统界面和系统界面插件**。
4. **重启手机**，确认首页模块与 Root 状态正常。更新包含系统 Hook 的版本后，也需要重启。
5. 在 Windows 接收页生成配对码，手机进入“设备”页扫码配对。配对过程不需要手动选择网段。
6. 从手机设备页或融合控制中心点击“投送声音”。停止投送可使用手机或 Windows 的停止按钮。

日常投送不需要 USB 或 ADB。隐藏手机桌面图标后，可从控制中心“更多设置”或 LSPosed 进入应用并恢复显示。Windows 隐藏托盘后，通过开始菜单或桌面快捷方式打开窗口，在设置中退出应用。

## 连接与隐私

配对码包含电脑证书指纹与一次性凭据，应只扫描自己电脑上显示的配对码，不要公开截图或链接。配对码有效期为五分钟，成功使用后失效。

音频通过 TLS 在手机与电脑之间直接传输，不依赖云服务。手机固定电脑证书指纹；Windows 使用当前用户的 DPAPI 保护本地身份数据。Root 设备上的其他高权限应用仍可能读取应用私有数据。

诊断日志默认按用户开关启用，记录会话计数、队列和错误，不保存音频内容或配对令牌。反馈前仍应检查日志，移除设备名、局域网地址等不希望公开的信息。详见 [安全说明](SECURITY.md) 和 [协议文档](docs/PROTOCOL.md)。

## 故障排查

| 问题 | 检查步骤 |
| --- | --- |
| 找不到电脑 | 确认接收端在运行、网络可互通；检查防火墙、访客网络和 AP 隔离。跨子网发现不保证可用。 |
| 没有控制中心入口 | 检查 LSPosed 作用域、重启是否完成，以及 HyperOS 接口是否兼容。 |
| 模块显示“需重启” | 安装新版 APK 后重启手机，加载新版系统 Hook。 |
| 投送仍在运行但无法停止 | 0.5.3 增加独立 Binder 防冻结处理；确认已重启加载模块，必要时在 Windows 停止接收并收集日志。 |
| 延迟升高或短暂缺音 | Windows 0.6.1 对待解码及播放队列丢弃过期音频；检查网络和电脑负载。缓冲上限不等于端到端延迟保证。 |
| 仅部分应用没有声音 | 系统权限、应用策略或 DRM 可能限制捕获，不保证所有音源都可投送。 |

## 开发与验证

Android 使用 Kotlin、Jetpack Compose 和 Miuix；通过未修改的官方 scrcpy 5.0.1 server 以 shell UID 采集音频。Windows 使用 Rust、GPUI、rustls、纯 Rust Opus 解码和 CPAL / WASAPI，不依赖 .NET。

```text
android/                  Android 应用与 LSPosed 模块
windows/HyperAudio.Rust/   Windows 接收端
scripts/                  构建、依赖许可收集及安装包脚本
design/icons/             图标源文件和生成脚本
docs/                     构建、协议、验证与变更说明
third_party/scrcpy/        上游来源与许可
```

构建步骤见 [BUILDING.md](docs/BUILDING.md)，贡献流程见 [CONTRIBUTING.md](CONTRIBUTING.md)。当前自动测试覆盖 Android 帧解析、会话恢复、防冻结租约，以及 Windows 配对、TLS、解码和队列边界。自动测试不能替代真机音频、锁屏和系统 UI 验收，具体结果见 [TESTING.md](docs/TESTING.md)。

## 许可与致谢

项目代码按 [Apache License 2.0](LICENSE) 发布。第三方组件保留各自许可，详见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。感谢 scrcpy、Miuix、LSPosed / Xposed、GPUI 及相关开源项目。

本项目是独立社区项目，与小米、Microsoft、Genymobile 等组织无隶属关系。
