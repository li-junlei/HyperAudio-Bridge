# 变更记录

## 首次公开预发布：Android 0.5.3 / Windows 0.6.1

### Android 0.5.3
- 补充投送期间 Binder 防冻结处理与按 UID/PID 校验的服务解冻。
- App 直接发送停止服务指令，控制中心缓存停止 PendingIntent。
- 修正首页状态卡片宽度。
- 新增隐藏桌面图标开关，保留控制中心与 LSPosed 打开入口。
- 系统模块协议升级，安装后需要重启。

### Windows 0.6.1
- 网络读取与 Opus 解码分离，限制待解码及 PCM 队列积压。
- 新增队列、解码耗时和丢弃计数诊断。
- 自启动与托盘显示选项改为胶囊开关。

### 已有功能
- Miuix Android 界面、HyperOS 原生融合设备详情面板。
- Root/shell scrcpy 音频采集、Opus、TLS 和扫码配对。
- 多网卡局域网发现、配对设备管理。
- Rust + GPUI Windows 接收端、登录自启动及托盘隐藏。

验证范围和已知限制见 [TESTING.md](TESTING.md)。
