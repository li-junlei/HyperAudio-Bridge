# 协议 v1

## 发现与身份

服务 `_hyperaudio._tcp.local.`，TCP 默认 38471。TXT：`version=1`、`id`（电脑随机 32 位十六进制 ID）、`name`。发现仅是地址提示，不授予信任。攻击者即使伪造 ID / 地址，也必须通过已固定的电脑证书验证。首版只监听 IPv4，手机发现 IPv6-only 地址时可能无法连接，可使用配对二维码中的 IPv4 地址。

配对 URI：

```text
hyperaudio://pair?host=<IPv4>&port=38471&id=<id>&name=<name>&pin=<sha256>&secret=<random-256-bit>
```

URI 是可信带外引导。手机必须从用户自己的 Windows 窗口扫码 / 粘贴；证书指纹不通过未经认证的 mDNS 学习。Windows 每次生成新 URI 立即替换旧凭证，5 分钟过期且首次成功配对后失效。手机固定证书 DER 的 SHA-256，并检查有效期。

Windows 私钥和手机令牌整体由 DPAPI CurrentUser 加密存于 `%LOCALAPPDATA%/HyperAudio Bridge/identity.dat`。Android 凭证存于应用私有 SharedPreferences，禁用系统备份；在 Root 设备上这不能防御已取得 Root 权限的其他应用。

## 连接

TLS 1.2 或 1.3，Windows 作为服务器。最多 8 个并发握手，握手 + JSON 最长 10 秒，JSON 最大 4096 字节（包含 LF）。每次请求新建连接。UTF-8 JSON 以单个 LF 结尾，不使用可能预读音频的文本缓冲读取器。

配对：

```json
{"version":1,"op":"pair","deviceId":"<phone uuid>","name":"<phone model>","secret":"<secret>"}
{"ok":true,"token":"<random per-phone 256-bit token>"}
```

投送：

```json
{"version":1,"op":"stream","deviceId":"<phone uuid>","token":"<token>","heartbeat":true,"diagnostics":false}
{"ok":true,"codec":"opus","sampleRate":48000,"channels":2,"heartbeat":true}
```

无效请求关闭连接。令牌使用定时安全比较。电脑一次只接受一个投送会话，第二个连接不抢占已有会话。撤销授权会关闭当前会话。

响应后手机持续发送 `[payload length: uint32 big endian][Opus packet]`，长度 1–65536。一个 Opus 包对应一帧编码输出，解码器按 Opus 包确定时长，最多 120 ms。首版实时播放不使用采集 PTS，不提供视频同步。

协商 `heartbeat=true` 时，长度为零的包表示心跳，不送入解码器；接收端每次读取最长等待 30 秒。未协商时零长度非法，兼容旧连接的等待上限为 3600 秒。

手机在没有近期发送进度时定期发心跳，写入持续 30 秒无进度会关闭连接。scrcpy 采集建立等待最多 20 秒，codec 头读取超时 7 秒；头校验完成后本地音频读取不设置空闲超时，以兼容暂停或静音。网络读取独立监控服务端控制消息。

协商心跳的连接支持服务端向手机发送 LF 结尾的 `{"type":"stop"}` JSON。手机的显式停止或该控制消息结束会话；异常按有限重试策略恢复。系统 Sticky 恢复仅接受最近 90 秒内续期过的会话。

Windows 网络读取只提交压缩帧，专用线程解码。待解码队列最多六帧、超时 120ms；满时丢弃最旧帧，发生丢包后重置解码状态。PCM 缓冲最多 4800 个 48kHz 双声道帧（100ms），超限保留最新采样。这里约束本地排队，不承诺网络、手机采集及音频硬件在内的总时延。

## scrcpy 本地桥

固定 scrcpy 5.0.1：音频流头 `opus` 的四字节 codec ID；每帧 `[flags/PTS: uint64 BE][length: uint32 BE][payload]`。5.x 使用 bit 63 为 session、bit 62 为 config、bit 61 为 keyframe。音频配置帧校验 OpusHead / 双声道后跳过。不能把 3.x 的 bit 63 配置标志套到 5.x。

随机 SCID 对应的 Android 抽象域套接字只存在本机；App 接受连接后检查 peer UID 为 shell / root。Root 创建随机部署目录、拷贝校验过的官方 JAR，再切换 UID 2000 启动。App 主动结束时先关套接字，清理时核对进程命令行后终止保存的 PID。SELinux 仍可能阻止该链路，需要实机验证。
