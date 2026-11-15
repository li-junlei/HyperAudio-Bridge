use crate::{
    audio::Player,
    identity::{self, Identity, Phone, random_hex},
    protocol::{self, PORT},
};
use anyhow::{Context, Result, bail};
use serde_json::json;
use std::{
    fs::{self, OpenOptions},
    io::Write,
    net::IpAddr,
    sync::{
        Arc, Mutex,
        atomic::{AtomicBool, Ordering},
    },
    time::{Duration, Instant},
};
use subtle::ConstantTimeEq;
use tokio::{
    net::{TcpListener, TcpStream, UdpSocket},
    sync::{Semaphore, watch},
    time::timeout,
};
use tokio_rustls::TlsAcceptor;

struct Active {
    id: String,
    name: String,
    key: String,
    stop: watch::Sender<bool>,
}
struct State {
    identity: Identity,
    secret: Option<(String, Instant)>,
    active: Option<Active>,
    status: String,
    frames: u64,
    decoded: u64,
    rendered: u64,
}
#[derive(Clone)]
pub struct Snapshot {
    pub status: String,
    pub active: Option<String>,
    pub phones: Vec<(String, String)>,
    pub frames: u64,
    pub decoded: u64,
    pub rendered: u64,
}
pub struct Backend {
    state: Mutex<State>,
    pin: String,
    config: Arc<rustls::ServerConfig>,
    runtime: Arc<tokio::runtime::Runtime>,
    diagnostics: AtomicBool,
    file_gate: Mutex<()>,
    pub port: u16,
    name: String,
    #[cfg(test)]
    silent: bool,
}
pub fn addresses() -> Vec<String> {
    let mut addresses: Vec<_> = if_addrs::get_if_addrs()
        .unwrap_or_default()
        .into_iter()
        .filter(|a| !a.is_loopback())
        .filter_map(|a| match a.ip() {
            IpAddr::V4(ip) => Some(ip.to_string()),
            _ => None,
        })
        .collect();
    addresses.sort();
    addresses.dedup();
    addresses.truncate(16);
    addresses
}
impl Backend {
    pub fn start() -> Result<Arc<Self>> {
        Self::open(Identity::load()?, PORT, true)
    }
    fn open(identity: Identity, port: u16, advertise: bool) -> Result<Arc<Self>> {
        let (config, pin) = identity.tls()?;
        let runtime = Arc::new(
            tokio::runtime::Builder::new_multi_thread()
                .worker_threads(3)
                .enable_all()
                .build()?,
        );
        let listener = std::net::TcpListener::bind(("0.0.0.0", port))
            .context("接收端口被占用，请退出旧版程序")?;
        listener.set_nonblocking(true)?;
        let port = listener.local_addr()?.port();
        let owner = Arc::new(Self {
            state: Mutex::new(State {
                identity,
                secret: None,
                active: None,
                status: "等待连接".into(),
                frames: 0,
                decoded: 0,
                rendered: 0,
            }),
            pin,
            config,
            runtime: runtime.clone(),
            diagnostics: AtomicBool::new(false),
            file_gate: Mutex::new(()),
            port,
            name: std::env::var("COMPUTERNAME").unwrap_or_else(|_| "Windows".into()),
            #[cfg(test)]
            silent: true,
        });
        let backend = owner.clone();
        runtime.spawn(async move {
            let listener = TcpListener::from_std(listener).unwrap();
            let slots = Arc::new(Semaphore::new(8));
            while let Ok((stream, _)) = listener.accept().await {
                let Ok(slot) = slots.clone().try_acquire_owned() else {
                    continue;
                };
                let backend = backend.clone();
                tokio::spawn(async move {
                    let _slot = slot;
                    if let Err(error) = backend.clone().handle(stream).await {
                        backend.log(&format!("connection ended: {error:#}"));
                    }
                });
            }
        });
        if advertise {
            owner.start_discovery();
        }
        Ok(owner)
    }
    pub fn snapshot(&self) -> Snapshot {
        let s = self.state.lock().unwrap();
        Snapshot {
            status: s.status.clone(),
            active: s.active.as_ref().map(|a| a.name.clone()),
            phones: s
                .identity
                .phones
                .iter()
                .map(|(id, p)| (id.clone(), p.name.clone()))
                .collect(),
            frames: s.frames,
            decoded: s.decoded,
            rendered: s.rendered,
        }
    }
    pub fn pairing_uri(&self) -> Result<String> {
        let hosts = addresses();
        self.pairing_with_hosts(&hosts)
    }
    fn pairing_with_hosts(&self, hosts: &[String]) -> Result<String> {
        if hosts.is_empty() {
            bail!("请先连接局域网");
        }
        let mut s = self.state.lock().unwrap();
        let secret = random_hex(32);
        s.secret = Some((secret.clone(), Instant::now() + Duration::from_secs(300)));
        let mut uri = url::Url::parse("hyperaudio://pair")?;
        uri.query_pairs_mut()
            .append_pair("host", &hosts[0])
            .append_pair("hosts", &hosts.join(","))
            .append_pair("port", &self.port.to_string())
            .append_pair("id", &s.identity.id)
            .append_pair("name", &self.name)
            .append_pair("pin", &self.pin)
            .append_pair("secret", &secret);
        Ok(uri.into())
    }
    pub fn stop(&self) {
        let mut s = self.state.lock().unwrap();
        if let Some(active) = &s.active {
            let _ = active.stop.send(true);
            s.status = "正在停止".into();
        }
    }
    pub fn forget(&self, id: &str) -> Result<()> {
        let mut s = self.state.lock().unwrap();
        let mut next = s.identity.clone();
        next.phones.remove(id);
        next.save()?;
        s.identity = next;
        if let Some(active) = &s.active {
            if active.id == id {
                let _ = active.stop.send(true);
            }
        }
        Ok(())
    }
    pub fn log(&self, message: &str) {
        if !self.diagnostics.load(Ordering::Relaxed) {
            return;
        }
        let _guard = self.file_gate.lock().unwrap();
        let _ = (|| -> Result<()> {
            fs::create_dir_all(identity::directory())?;
            let path = identity::directory().join("receiver.log");
            if fs::metadata(&path)
                .map(|m| m.len() > 524288)
                .unwrap_or(false)
            {
                let previous = identity::directory().join("receiver.log.previous");
                let _ = fs::remove_file(&previous);
                fs::rename(&path, previous)?;
            }
            writeln!(
                OpenOptions::new().create(true).append(true).open(path)?,
                "{} {}",
                chrono::Local::now().to_rfc3339(),
                message
            )?;
            Ok(())
        })();
    }
    async fn handle(self: Arc<Self>, stream: TcpStream) -> Result<()> {
        stream.set_nodelay(true)?;
        let mut tls = timeout(
            Duration::from_secs(10),
            TlsAcceptor::from(self.config.clone()).accept(stream),
        )
        .await??;
        let request = timeout(Duration::from_secs(10), protocol::read_json(&mut tls)).await??;
        if request["version"] != 1 {
            bail!("协议版本不匹配");
        }
        let op = protocol::required(&request, "op", 16)?;
        let id = protocol::required(&request, "deviceId", 64)?;
        if op == "pair" {
            let offered = protocol::required(&request, "secret", 64)?;
            let name = protocol::required(&request, "name", 80)?;
            let token = {
                let mut s = self.state.lock().unwrap();
                let (secret, until) = s.secret.as_ref().context("配对码已使用或失效")?;
                if Instant::now() > *until
                    || !bool::from(secret.as_bytes().ct_eq(offered.as_bytes()))
                {
                    bail!("配对码无效或过期");
                }
                let token = random_hex(32);
                let mut next = s.identity.clone();
                next.phones.insert(
                    id,
                    Phone {
                        name,
                        token: token.clone(),
                    },
                );
                next.save()?;
                s.identity = next;
                s.secret = None;
                token
            };
            return protocol::write_json(&mut tls, &json!({"ok":true,"token":token})).await;
        }
        if op != "stream" {
            bail!("未知请求");
        }
        let token = protocol::required(&request, "token", 64)?;
        let heartbeat = request["heartbeat"].as_bool().unwrap_or(false);
        let key = random_hex(8);
        let mut stop = {
            let mut s = self.state.lock().unwrap();
            let phone = s.identity.phones.get(&id).context("设备未授权")?;
            if !bool::from(phone.token.as_bytes().ct_eq(token.as_bytes())) {
                bail!("设备未授权");
            }
            let name = phone.name.clone();
            if s.active.is_some() {
                bail!("已有投送会话");
            }
            let (sender, receiver) = watch::channel(false);
            s.active = Some(Active {
                id,
                name,
                key: key.clone(),
                stop: sender,
            });
            s.status = "正在接收".into();
            s.frames = 0;
            s.decoded = 0;
            s.rendered = 0;
            self.diagnostics.store(
                request["diagnostics"].as_bool().unwrap_or(false),
                Ordering::Relaxed,
            );
            receiver
        };
        self.log("session connected");
        let result = async {
            #[cfg(test)] let mut player = if self.silent { None } else { Some(Player::new()?) };
            #[cfg(not(test))] let mut player = Some(Player::new()?);
            protocol::write_json(&mut tls, &json!({"ok":true,"codec":"opus","sampleRate":48000,"channels":2,"heartbeat":heartbeat})).await?;
            let mut next_report = Instant::now() + Duration::from_secs(5);
            loop {
                tokio::select! {
                    _ = stop.changed() => {
                        if heartbeat { let _ = timeout(Duration::from_secs(2), protocol::write_json(&mut tls, &json!({"type":"stop"}))).await; }
                        return Ok::<(), anyhow::Error>(());
                    }
                    result = timeout(Duration::from_secs(if heartbeat {30} else {3600}), protocol::read_packet(&mut tls, heartbeat)) => {
                        let packet = result??;
                        if !packet.is_empty() {
                            if let Some(player) = &mut player { player.push(&packet)?; }
                            let mut s = self.state.lock().unwrap(); s.frames += 1;
                            if let Some(player) = &player {
                                s.decoded = player.stats.decoded.load(Ordering::Relaxed); s.rendered = player.stats.rendered.load(Ordering::Relaxed);
                                if let Some(error) = player.stats.error.lock().unwrap().clone() { s.status = format!("输出恢复中：{error}"); } else { s.status = "正在接收".into(); }
                            }
                        }
                        if Instant::now() >= next_report {
                            let s = self.snapshot();
                            if let Some(player) = &player {
                                let stats = &player.stats;
                                self.log(&format!("frames={} decodedSamples={} renderedSamples={} pendingPackets={} pcmBufferMs={} droppedPackets={} droppedSamples={} maxDecodeUs={} maxQueueMs={}",
                                    s.frames, s.decoded, s.rendered,
                                    stats.queued_packets.load(Ordering::Relaxed), stats.buffered_samples.load(Ordering::Relaxed) / 48,
                                    stats.dropped_packets.load(Ordering::Relaxed), stats.dropped_samples.load(Ordering::Relaxed),
                                    stats.max_decode_us.swap(0, Ordering::Relaxed), stats.max_queue_ms.swap(0, Ordering::Relaxed)));
                            }
                            next_report = Instant::now() + Duration::from_secs(5);
                        }
                    }
                }
            }
        }.await;
        self.log(&format!(
            "session ended error={}",
            result
                .as_ref()
                .err()
                .map(|e| e.to_string())
                .unwrap_or_default()
        ));
        let mut s = self.state.lock().unwrap();
        if s.active.as_ref().is_some_and(|a| a.key == key) {
            s.active = None;
            s.status = "等待连接".into();
            self.diagnostics.store(false, Ordering::Relaxed);
        }
        result
    }
    fn start_discovery(self: &Arc<Self>) {
        let backend = self.clone();
        self.runtime.spawn(async move {
            let Ok(udp) = UdpSocket::bind(("0.0.0.0", PORT)).await else { return };
            let mut buffer = [0;128]; let mut count = 0; let mut window = Instant::now();
            loop {
                let (size, peer) = match udp.recv_from(&mut buffer).await { Ok(v) => v, Err(_) => { tokio::time::sleep(Duration::from_secs(1)).await; continue } };
                if window.elapsed() >= Duration::from_secs(1) { count = 0; window = Instant::now(); }
                count += 1; if count > 20 { continue; }
                let Some(nonce) = discovery_nonce(&buffer[..size]) else { continue };
                let id = backend.state.lock().unwrap().identity.id.clone();
                let reply = json!({"version":1,"nonce":nonce,"id":id,"name":backend.name,"port":backend.port});
                let _ = udp.send_to(&serde_json::to_vec(&reply).unwrap(), peer).await;
            }
        });
        let backend = self.clone();
        self.runtime.spawn(async move {
            let Ok(daemon) = mdns_sd::ServiceDaemon::new() else {
                return;
            };
            let id = backend.state.lock().unwrap().identity.id.clone();
            let mut previous = Vec::new();
            let mut fullname = String::new();
            loop {
                let hosts = addresses();
                if hosts != previous || fullname.is_empty() {
                    if !fullname.is_empty() {
                        let _ = daemon.unregister(&fullname);
                    }
                    let service = mdns_sd::ServiceInfo::new(
                        "_hyperaudio._tcp.local.",
                        &format!("HyperAudio-{}", &id[..8]),
                        &format!("hyperaudio-{id}.local."),
                        hosts.join(",").as_str(),
                        backend.port,
                        [
                            ("version", "1"),
                            ("id", id.as_str()),
                            ("name", backend.name.as_str()),
                        ]
                        .as_slice(),
                    );
                    if let Ok(service) = service {
                        fullname = service.get_fullname().into();
                        let _ = daemon.register(service.enable_addr_auto());
                        previous = hosts;
                    }
                }
                tokio::time::sleep(Duration::from_secs(5)).await;
            }
        });
    }
}
pub fn discovery_nonce(bytes: &[u8]) -> Option<&str> {
    let query = std::str::from_utf8(bytes).ok()?;
    let nonce = query.strip_prefix("HYPERAUDIO_DISCOVER_V1 ")?;
    (nonce.len() == 32 && nonce.bytes().all(|b| b.is_ascii_hexdigit())).then_some(nonce)
}

#[cfg(test)]
mod tests {
    use super::*;
    use rustls::{
        DigitallySignedStruct, SignatureScheme,
        client::danger::{HandshakeSignatureValid, ServerCertVerified, ServerCertVerifier},
        pki_types::{CertificateDer, ServerName, UnixTime},
    };
    use tokio::io::AsyncWriteExt;
    #[derive(Debug)]
    struct Pinned(String);
    impl ServerCertVerifier for Pinned {
        fn verify_server_cert(
            &self,
            cert: &CertificateDer<'_>,
            _: &[CertificateDer<'_>],
            _: &ServerName<'_>,
            _: &[u8],
            _: UnixTime,
        ) -> std::result::Result<ServerCertVerified, rustls::Error> {
            use sha2::{Digest, Sha256};
            let pin: String = Sha256::digest(cert.as_ref())
                .iter()
                .map(|b| format!("{b:02x}"))
                .collect();
            if pin != self.0 {
                return Err(rustls::Error::General("pin mismatch".into()));
            }
            Ok(ServerCertVerified::assertion())
        }
        fn verify_tls12_signature(
            &self,
            message: &[u8],
            cert: &CertificateDer<'_>,
            dss: &DigitallySignedStruct,
        ) -> std::result::Result<HandshakeSignatureValid, rustls::Error> {
            rustls::crypto::verify_tls12_signature(
                message,
                cert,
                dss,
                &rustls::crypto::ring::default_provider().signature_verification_algorithms,
            )
        }
        fn verify_tls13_signature(
            &self,
            message: &[u8],
            cert: &CertificateDer<'_>,
            dss: &DigitallySignedStruct,
        ) -> std::result::Result<HandshakeSignatureValid, rustls::Error> {
            rustls::crypto::verify_tls13_signature(
                message,
                cert,
                dss,
                &rustls::crypto::ring::default_provider().signature_verification_algorithms,
            )
        }
        fn supported_verify_schemes(&self) -> Vec<SignatureScheme> {
            rustls::crypto::ring::default_provider()
                .signature_verification_algorithms
                .supported_schemes()
        }
    }
    async fn connect(backend: &Backend) -> tokio_rustls::client::TlsStream<TcpStream> {
        let config = rustls::ClientConfig::builder()
            .dangerous()
            .with_custom_certificate_verifier(Arc::new(Pinned(backend.pin.clone())))
            .with_no_client_auth();
        let socket = TcpStream::connect(("127.0.0.1", backend.port))
            .await
            .unwrap();
        tokio_rustls::TlsConnector::from(Arc::new(config))
            .connect(ServerName::try_from("localhost").unwrap(), socket)
            .await
            .unwrap()
    }
    #[test]
    fn pairing_heartbeat_and_stop_over_real_tls() {
        let _ = rustls::crypto::ring::default_provider().install_default();
        let path = std::env::temp_dir().join(format!("hyperaudio-session-{}.dat", random_hex(8)));
        let backend = Backend::open(Identity::load_at(&path).unwrap(), 0, false).unwrap();
        backend.runtime.block_on(async {
            let uri=url::Url::parse(&backend.pairing_with_hosts(&["192.0.2.1".into(),"127.0.0.1".into()]).unwrap()).unwrap();
            let query:std::collections::HashMap<_,_>=uri.query_pairs().into_owned().collect();
            assert_eq!(query["hosts"],"192.0.2.1,127.0.0.1");
            let mut wrong=connect(&backend).await;
            protocol::write_json(&mut wrong,&json!({"version":1,"op":"pair","deviceId":"test","name":"phone","secret":"bad"})).await.unwrap();
            assert!(protocol::read_json(&mut wrong).await.is_err());
            let mut pairing=connect(&backend).await;
            protocol::write_json(&mut pairing,&json!({"version":1,"op":"pair","deviceId":"test","name":"phone","secret":query["secret"]})).await.unwrap();
            let token=protocol::read_json(&mut pairing).await.unwrap()["token"].as_str().unwrap().to_string();
            assert_eq!(Identity::load_at(&path).unwrap().phones["test"].token,token);
            let mut replay=connect(&backend).await;
            protocol::write_json(&mut replay,&json!({"version":1,"op":"pair","deviceId":"other","name":"phone","secret":query["secret"]})).await.unwrap();
            assert!(protocol::read_json(&mut replay).await.is_err());
            let mut unauthorized=connect(&backend).await;
            protocol::write_json(&mut unauthorized,&json!({"version":1,"op":"stream","deviceId":"test","token":"wrong"})).await.unwrap();
            assert!(protocol::read_json(&mut unauthorized).await.is_err());
            let mut stream=connect(&backend).await;
            protocol::write_json(&mut stream,&json!({"version":1,"op":"stream","deviceId":"test","token":token,"heartbeat":true})).await.unwrap();
            assert_eq!(protocol::read_json(&mut stream).await.unwrap()["heartbeat"],true);
            let mut second=connect(&backend).await;
            protocol::write_json(&mut second,&json!({"version":1,"op":"stream","deviceId":"test","token":token})).await.unwrap();
            assert!(protocol::read_json(&mut second).await.is_err());
            for _ in 0..4 { stream.write_u32(0).await.unwrap(); stream.flush().await.unwrap(); tokio::time::sleep(Duration::from_secs(2)).await; }
            let packet=include_bytes!("../tests/fixtures/opus-20ms.bin");
            stream.write_u32(packet.len() as u32).await.unwrap();stream.write_all(packet).await.unwrap();stream.flush().await.unwrap();
            tokio::time::sleep(Duration::from_millis(100)).await;
            assert_eq!(backend.snapshot().frames,1);assert!(backend.snapshot().active.is_some());
            backend.stop();
            let event=timeout(Duration::from_secs(2),protocol::read_json(&mut stream)).await.unwrap().unwrap();assert_eq!(event["type"],"stop");
            tokio::time::sleep(Duration::from_millis(100)).await;assert!(backend.snapshot().active.is_none());
            backend.forget("test").unwrap();assert!(Identity::load_at(&path).unwrap().phones.is_empty());
            let mut revoked=connect(&backend).await;
            protocol::write_json(&mut revoked,&json!({"version":1,"op":"stream","deviceId":"test","token":token})).await.unwrap();
            assert!(protocol::read_json(&mut revoked).await.is_err());
        });
        fs::remove_file(path).unwrap();
    }
    #[test]
    fn discovery_is_bounded_and_nonce_bound() {
        assert!(discovery_nonce(b"invalid").is_none());
        assert!(discovery_nonce(b"HYPERAUDIO_DISCOVER_V1 bad").is_none());
        assert_eq!(
            discovery_nonce(b"HYPERAUDIO_DISCOVER_V1 aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"),
            Some("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
        );
    }
}
